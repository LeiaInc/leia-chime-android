package com.amazonaws.services.chime.sdkdemo.activity


import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.*
import android.util.Log
import android.util.Range
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import com.amazonaws.services.chime.sdk.meetings.analytics.EventAnalyticsController
import com.amazonaws.services.chime.sdk.meetings.analytics.EventAttributeName
import com.amazonaws.services.chime.sdk.meetings.analytics.EventName
import com.amazonaws.services.chime.sdk.meetings.analytics.MeetingHistoryEventName
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoContentHint
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrame
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoRotation
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoSink
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.buffer.VideoFrameBuffer
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.buffer.VideoFrameTextureBuffer
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.capture.*
import com.amazonaws.services.chime.sdk.meetings.device.MediaDevice
import com.amazonaws.services.chime.sdk.meetings.device.MediaDeviceType
import com.amazonaws.services.chime.sdk.meetings.internal.utils.ConcurrentSet
import com.amazonaws.services.chime.sdk.meetings.internal.utils.ObserverUtils
import com.amazonaws.services.chime.sdk.meetings.utils.logger.Logger
import com.amazonaws.services.chime.sdkdemo.renderer.StereoViewRenderer
import com.leia.headtracking.Engine
import com.leia.headtracking.Engine.InitArgs
import com.leia.headtracking.SharedCameraSink
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.min

/**
 * [DefaultCameraCaptureSource] will configure a reasonably standard capture stream which will
 * use the [Surface] provided by the capture source provided by a [SurfaceTextureCaptureSourceFactory]
 */
class LeiaCameraCaptureSource @JvmOverloads constructor(
    private val activity: Activity,
    private val context: Context,
    private val logger: Logger,
    private val surfaceTextureCaptureSourceFactory: SurfaceTextureCaptureSourceFactory,
    private val cameraManager: CameraManager = context.getSystemService(
        Context.CAMERA_SERVICE
    ) as CameraManager
) : CameraCaptureSource, VideoSink, Engine.FrameListener {
    private val handler: Handler

    // Camera2 system library related state
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var cameraDevice: CameraDevice? = null
    private var cameraCharacteristics: CameraCharacteristics? = null

    private class Intrinsics {
        var width = 0
        var height = 0
        var ppx = 0f
        var ppy = 0f
        var fx = 0f
        var fy = 0f
        var isMirrored = false
    }

    private val intrinsics: Intrinsics = Intrinsics()

    // The following are stored from cameraCharacteristics for reuse without additional query
    // From CameraCharacteristics.SENSOR_ORIENTATION, degrees clockwise rotation
    private var sensorOrientation = 0

    // From CameraCharacteristics.LENS_FACING
    private var isCameraFrontFacing = false

    // This source provides a surface we pass into the system APIs
    // and then starts emitting frames once the system starts drawing to the
    // surface. To speed up restart, since theses sources have to wait on
    // in-flight frames to finish release, we just begin the release and
    // create a new one
    private var surfaceTextureSource: SurfaceTextureCaptureSource? = null

    private val observers = mutableSetOf<CaptureSourceObserver>()

    // Concurrency modification could happen when sink gets
    // added/removed from another thread while sending frames
    private val sinks = ConcurrentSet.createConcurrentSet<VideoSink>()

    override val contentHint = VideoContentHint.Motion

    private val MAX_INTERNAL_SUPPORTED_FPS = 15
    private val DESIRED_CAPTURE_FORMAT = VideoCaptureFormat(640, 480, MAX_INTERNAL_SUPPORTED_FPS)
    private val ROTATION_360_DEGREES = 360

    private var engine: Engine? = null
    private var engineCameraSink: SharedCameraSink? = null


    var leftSurfaceTexture: SurfaceTexture =
        SurfaceTexture(false).apply { setDefaultBufferSize(640, 480) }
    var leftSurface = Surface(leftSurfaceTexture)
    var rightSurfaceTexture: SurfaceTexture =
        SurfaceTexture(false).apply { setDefaultBufferSize(640, 480) }
    var rightSurface = Surface(rightSurfaceTexture)

    var cpuImageReader: ImageReader = ImageReader.newInstance( 640,
        480,
        ImageFormat.YUV_420_888,
        2)

    private val TAG = "DefaultCameraCaptureSource"

    var eventAnalyticsController: EventAnalyticsController? = null
        set(value) {
            field = value
        }

    init {
        // Load library so that some of webrtc definition is linked properly
        System.loadLibrary("amazon_chime_media_client")
        val thread = HandlerThread("DefaultCameraCaptureSource")
        thread.start()
        handler = Handler(thread.looper)

        val initArgs = InitArgs(activity, this, false)
        engineCameraSink = SharedCameraSink()
        initArgs.sharedCameraSink = engineCameraSink
        try {
            engine = Engine(initArgs)
            engine!!.startTracking()
        } catch (e: java.lang.Exception) {
            Log.e(TAG, ": wwwwwwww", e)
            e.printStackTrace()
        }
    }

    override var device: MediaDevice? = MediaDevice.listVideoDevices(cameraManager)
        .firstOrNull { it.type == MediaDeviceType.VIDEO_FRONT_CAMERA } ?: MediaDevice.listVideoDevices(cameraManager)
        .firstOrNull { it.type == MediaDeviceType.VIDEO_BACK_CAMERA }
        set(value) {
            logger.info(TAG, "Setting capture device: $value")
            if (field == value) {
                logger.info(TAG, "Already using device: $value; ignoring")
                return
            }

            field = value

            // Restart capture if already running (i.e. we have a valid surface texture source)
            surfaceTextureSource?.let {
                stop()
                start()
            }
        }

    override fun switchCamera() {
        val desiredDeviceType = if (device?.type == MediaDeviceType.VIDEO_FRONT_CAMERA) {
            MediaDeviceType.VIDEO_BACK_CAMERA
        } else {
            MediaDeviceType.VIDEO_FRONT_CAMERA
        }
        device =
            MediaDevice.listVideoDevices(cameraManager).firstOrNull { it.type == desiredDeviceType } ?: MediaDevice.listVideoDevices(cameraManager)
                .firstOrNull { it.type == MediaDeviceType.VIDEO_BACK_CAMERA }

        if (device != null) {
            eventAnalyticsController?.pushHistory(MeetingHistoryEventName.videoInputSelected)
        }
    }

    override var torchEnabled: Boolean = false
        @RequiresApi(Build.VERSION_CODES.M)
        set(value) {
            if (cameraCharacteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == false) {
                logger.warn(
                    TAG,
                    "Torch not supported on current camera, setting value and returning"
                )
                return
            }

            field = value
            if (cameraDevice == null) {
                // If not in a session, use the CameraManager API
                device?.id?.let { cameraManager.setTorchMode(it, field) }
            } else {
                // Otherwise trigger a new request which will pick up the new value
                createCaptureRequest()
            }
        }

    override var format: VideoCaptureFormat = DESIRED_CAPTURE_FORMAT
        set(value) {
//            logger.info(TAG, "Setting capture format: $value")
//            if (field == value) {
//                logger.info(TAG, "Already using format: $value; ignoring")
//                return
//            }
//
//            if (value.maxFps > MAX_INTERNAL_SUPPORTED_FPS) {
//                logger.info(TAG, "Limiting capture to 15 FPS to avoid frame drops")
//            }
//            field = VideoCaptureFormat(
//                value.width, value.height, min(
//                    value.maxFps,
//                    MAX_INTERNAL_SUPPORTED_FPS
//                )
//            )
//
//            // Restart capture if already running (i.e. we have a valid surface texture source)
//            surfaceTextureSource?.let {
//                stop()
//                start()
//            }
        }

    override fun start() {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            handleCameraCaptureFail(CaptureSourceError.PermissionError)
            throw SecurityException("Missing necessary camera permissions")
        }

        logger.info(TAG, "Camera capture start requested with device: $device")
        val device = device ?: run {
            logger.info(TAG, "Cannot start camera capture with null device")
            return
        }
        val id = device.id ?: run {
            logger.info(TAG, "Cannot start camera capture with null device id")
            return
        }

        cameraCharacteristics = cameraManager.getCameraCharacteristics(id).also {
            // Store these immediately for convenience
            sensorOrientation = it.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            isCameraFrontFacing =
                it.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
        }

        val chosenCaptureFormat: VideoCaptureFormat? =
            MediaDevice.listSupportedVideoCaptureFormats(cameraManager, device).minBy { format ->
                abs(format.width - this.format.width) + abs(format.height - this.format.height)
            }
        val surfaceTextureFormat: VideoCaptureFormat = chosenCaptureFormat ?: run {
            handleCameraCaptureFail(CaptureSourceError.ConfigurationFailure)
            return
        }
        surfaceTextureSource =
            surfaceTextureCaptureSourceFactory.createSurfaceTextureCaptureSource(
                surfaceTextureFormat.width,
                surfaceTextureFormat.height,
                contentHint
            )
        surfaceTextureSource?.addVideoSink(this)
        surfaceTextureSource?.start()
        val stereoViewRenderer = StereoViewRenderer(context, surfaceTextureSource!!.surface, leftSurfaceTexture, rightSurfaceTexture, false)
        leftSurfaceTexture.setOnFrameAvailableListener { stereoViewRenderer.drawFrame() }
        dualCamera = findCameraId(CameraCharacteristics.LENS_FACING_FRONT).first()
        cameraManager.openCamera(dualCamera.logicalId, cameraDeviceStateCallback, handler)
    }

    lateinit var dualCamera: DualCamera

    override fun stop() {
        logger.info(TAG, "Stopping camera capture source")
        val sink: VideoSink = this
        runBlocking(handler.asCoroutineDispatcher().immediate) {
            // Close camera capture session
            cameraCaptureSession?.close()
            cameraCaptureSession = null

            // Close camera device, this will eventually trigger the stop callback
            cameraDevice?.close()
            cameraDevice = null

            engineCameraSink?.close()
            engine?.stopTracking()

            // Stop surface capture source
            surfaceTextureSource?.removeVideoSink(sink)
            surfaceTextureSource?.stop()
            surfaceTextureSource?.release()
            surfaceTextureSource = null
        }
    }

    override fun onVideoFrameReceived(frame: VideoFrame) {
        val processedBuffer: VideoFrameBuffer = createBufferWithUpdatedTransformMatrix(
            frame.buffer as VideoFrameTextureBuffer,
            isCameraFrontFacing, -sensorOrientation
        )

        val processedFrame =
            VideoFrame(frame.timestampNs, processedBuffer, VideoRotation.Rotation270)
        sinks.forEach { it.onVideoFrameReceived(processedFrame) }
        processedBuffer.release()
    }

    override fun addVideoSink(sink: VideoSink) {
        sinks.add(sink)
    }

    override fun removeVideoSink(sink: VideoSink) {
        sinks.remove(sink)
    }

    override fun addCaptureSourceObserver(observer: CaptureSourceObserver) {
        observers.add(observer)
    }

    override fun removeCaptureSourceObserver(observer: CaptureSourceObserver) {
        observers.remove(observer)
    }

    fun release() {
        runBlocking(handler.asCoroutineDispatcher().immediate) {
            logger.info(TAG, "Stopping handler looper")
            handler.removeCallbacksAndMessages(null)
            handler.looper.quit()
        }
    }

    // Implement and store callbacks as private constants since we can't inherit from all of them
    // due to Kotlin not allowing multiple class inheritance

    private val cameraDeviceStateCallback = object : CameraDevice.StateCallback(),
        ImageReader.OnImageAvailableListener {

        @RequiresApi(Build.VERSION_CODES.P)
        override fun onOpened(device: CameraDevice) {

            val configs = ArrayList<OutputConfiguration>()
            configs += OutputConfiguration(leftSurface).apply {
                setPhysicalCameraId(dualCamera.physicalId1)
            }

            configs += OutputConfiguration(rightSurface).apply {
                setPhysicalCameraId(dualCamera.physicalId2)
            }

            configs += OutputConfiguration(cpuImageReader.surface).apply {
                setPhysicalCameraId(dualCamera.physicalId1)
            }


            val sessionConfiguration = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                configs,
                AsyncTask.SERIAL_EXECUTOR,
                cameraCaptureSessionStateCallback
            )

            cpuImageReader.setOnImageAvailableListener(this, handler)


            logger.info(TAG, "Camera device opened for ID ${device.id}")
            cameraDevice = device
            try {
                cameraDevice?.createCaptureSession(sessionConfiguration)
            } catch (exception: CameraAccessException) {
                logger.info(
                    TAG,
                    "Exception encountered creating capture session: ${exception.reason}"
                )
                handleCameraCaptureFail(CaptureSourceError.SystemFailure)
                return
            }
        }

        override fun onClosed(device: CameraDevice) {
            logger.info(TAG, "Camera device closed for ID ${device.id}")
            ObserverUtils.notifyObserverOnMainThread(observers) { it.onCaptureStopped() }
        }

        override fun onDisconnected(device: CameraDevice) {
            logger.info(TAG, "Camera device disconnected for ID ${device.id}")
            ObserverUtils.notifyObserverOnMainThread(observers) { it.onCaptureStopped() }
        }

        override fun onError(device: CameraDevice, error: Int) {
            logger.info(TAG, "Camera device encountered error: $error for ID ${device.id}")
            handleCameraCaptureFail(CaptureSourceError.SystemFailure)
        }

        override fun onImageAvailable(imageReader: ImageReader?) {
            val image: Image = imageReader!!.acquireLatestImage()
            if (image.planes.size == 3) {
                val yPlane = image.planes[0]
                val rowStride = yPlane.rowStride
                var rotationDegrees: Int = 180
                // XXX: why?
                if (rotationDegrees == 180 || rotationDegrees == 0) {
                    rotationDegrees = if (rotationDegrees == 180) 0 else 180
                }
                try {
                    val characteristics = cameraManager.getCameraCharacteristics(dualCamera.physicalId1)
                    val focalLength =
                        characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    val sensorSize =
                        characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    if (focalLength != null) {
                        intrinsics.width = rowStride
                        intrinsics.height = yPlane.buffer.remaining() / intrinsics.width
                        intrinsics.ppx = intrinsics.width * 0.5f
                        intrinsics.ppy = intrinsics.height * 0.5f
                        intrinsics.fx = focalLength[0] / sensorSize!!.width * intrinsics.width
                        intrinsics.fy = focalLength[0] / sensorSize.height * intrinsics.height
                        val isMirrored = true
                        engineCameraSink?.updateIntrinsics(
                            intrinsics.width,
                            intrinsics.height,
                            intrinsics.ppx,
                            intrinsics.ppy,
                            intrinsics.fx,
                            intrinsics.fy,
                            isMirrored
                        )
                    }
                } catch (e: CameraAccessException) {
                    Log.e(TAG, ": wwwwwwww", e)
                   e.printStackTrace()
                }
                try {
                    Log.d(TAG, "onImageAvailable: wwwwww")
                    engineCameraSink?.onImage(image, rotationDegrees)
                } catch (e: Exception) {
                    Log.e(TAG, ": wwwwwwww", e)
                    e.printStackTrace()
                }
            }

            image.close()
        }
    }

    private val cameraCaptureSessionStateCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            logger.info(
                TAG,
                "Camera capture session configured for session with device ID: ${session.device.id}"
            )
            cameraCaptureSession = session
            createCaptureRequest()
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            logger.error(
                TAG, "Camera session configuration failed with device ID: ${session.device.id}"
            )
            handleCameraCaptureFail(CaptureSourceError.ConfigurationFailure)
            session.close()
        }
    }

    private fun handleCameraCaptureFail(error: CaptureSourceError) {
        val attributes = mutableMapOf<EventAttributeName, Any>(
            EventAttributeName.videoInputError to error
        )
        eventAnalyticsController?.publishEvent(EventName.videoInputFailed, attributes)
        ObserverUtils.notifyObserverOnMainThread(observers) {
            it.onCaptureFailed(error)
        }
    }

    private val cameraCaptureSessionCaptureCallback =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure
            ) {
                logger.error(TAG, "Camera capture session failed: ${failure.reason}")
                handleCameraCaptureFail(CaptureSourceError.SystemFailure)
            }
        }


    private fun createCaptureRequest() {
        val cameraDevice = cameraDevice ?: run {
            // This can occur occasionally if capture is restarted before the previous
            // completes. The next request will complete normally.
            logger.warn(TAG, "createCaptureRequest called without device set, may be mid restart")
            return
        }
        try {
            val captureRequestBuilder =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)

            // Set target FPS
            val fpsRanges: Array<Range<Int>> =
                cameraCharacteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    ?: run {
                        logger.error(TAG, "Could not retrieve camera FPS ranges")
                        handleCameraCaptureFail(CaptureSourceError.ConfigurationFailure)
                        return
                    }
            // Pick range with max closest to but not exceeding the set max framerate
            val bestFpsRange = fpsRanges
                .filter { it.upper <= this.format.maxFps }
                .minBy { this.format.maxFps - it.upper }
                ?: run {
                    logger.warn(TAG, "No FPS ranges below set max FPS")
                    // Just fall back to the closest
                    return@run fpsRanges.minBy { abs(this.format.maxFps - it.upper) }
                } ?: run {
                    logger.error(TAG, "No valid FPS ranges")
                    handleCameraCaptureFail(CaptureSourceError.ConfigurationFailure)
                    return
                }

            logger.info(TAG, "Setting target FPS range to $bestFpsRange")
            captureRequestBuilder.set(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(bestFpsRange.lower, bestFpsRange.upper)
            )

            // Set target auto exposure mode
            captureRequestBuilder.set(
                CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON
            )
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false)

            // Set current torch status
            if (torchEnabled) {
                captureRequestBuilder.set(
                    CaptureRequest.FLASH_MODE,
                    CaptureRequest.FLASH_MODE_TORCH
                )
            } else {
                captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }

            setStabilizationMode(captureRequestBuilder)
            setFocusMode(captureRequestBuilder)

            captureRequestBuilder.addTarget(
                leftSurface
            )
            captureRequestBuilder.addTarget(
                rightSurface
            )
            captureRequestBuilder.addTarget(
                cpuImageReader.surface
            )
            cameraCaptureSession?.setRepeatingRequest(
                captureRequestBuilder.build(), cameraCaptureSessionCaptureCallback, handler
            )
            logger.info(
                TAG,
                "Capture request completed with device ID: ${cameraCaptureSession?.device?.id}"
            )
            ObserverUtils.notifyObserverOnMainThread(observers) {
                it.onCaptureStarted()
            }
        } catch (exception: CameraAccessException) {
            logger.error(
                TAG,
                "Failed to start capture request with device ID: ${cameraCaptureSession?.device?.id}, exception:$exception"
            )
            handleCameraCaptureFail(CaptureSourceError.SystemFailure)
            return
        }
    }


    private fun findCameraId(facing: Int? = null): List<DualCamera> {
        // Iterate over all the available camera characteristics
        return cameraManager.cameraIdList.map {
            it to cameraManager.getCameraCharacteristics(it)
        }.filter {
            // Filter by cameras facing the requested direction
            facing == null || it.second.get(CameraCharacteristics.LENS_FACING) == facing
        }.filter {
            // Filter by logical cameras
            it.second.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)!!
                .contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
        }.flatMap {
            // All possible pairs from the list of physical cameras are valid results
            it.second.physicalCameraIds.zipWithNext {
                // Note: Unclear to me which camera is right and left; this was experimentally determined.
                    physId1, physId2 ->
                DualCamera(it.first, physId1, physId2)
            }
        }
    }

    private fun setStabilizationMode(captureRequestBuilder: CaptureRequest.Builder) {
        if (cameraCharacteristics?.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION
            )?.any { it == CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON } == true
        ) {
            captureRequestBuilder[CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE] =
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
            captureRequestBuilder[CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE] =
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            logger.info(TAG, "Using optical stabilization.")
            return
        }

        // If no optical mode is available, try software.
        if (cameraCharacteristics?.get(
                CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
            )?.any { it == CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON } == true
        ) {
            captureRequestBuilder[CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE] =
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            captureRequestBuilder[CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE] =
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
            logger.info(TAG, "Using video stabilization.")
            return
        }

        logger.info(TAG, "Stabilization not available.")
    }

    private fun setFocusMode(captureRequestBuilder: CaptureRequest.Builder) {
        if (cameraCharacteristics?.get(
                CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES
            )?.any { it == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO } == true
        ) {
            captureRequestBuilder.set(
                CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            )
            logger.info(TAG, "Using optical stabilization.")
            return
        }

        logger.info(TAG, "Auto-focus is not available.")
    }

    private fun getCaptureFrameRotation(): VideoRotation {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        var rotation = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            Surface.ROTATION_0 -> 0
            else -> 0
        }
        // Account for front cammera mirror
        if (!isCameraFrontFacing) {
            rotation = ROTATION_360_DEGREES - rotation
        }
        // Account for physical camera orientation
        rotation = (sensorOrientation + rotation) % ROTATION_360_DEGREES
        return VideoRotation.from(rotation) ?: VideoRotation.Rotation0
    }

    private fun createBufferWithUpdatedTransformMatrix(
        buffer: VideoFrameTextureBuffer,
        mirror: Boolean,
        rotation: Int
    ): VideoFrameTextureBuffer {
        val transformMatrix = Matrix()
        // Perform mirror and rotation around (0.5, 0.5) since that is the center of the texture.
        transformMatrix.preTranslate(0.5f, 0.5f)
        if (mirror) {
            // This negative scale mirrors across the vertical axis
            transformMatrix.preScale(-1f, 1f)
        }
        transformMatrix.preRotate(rotation.toFloat())
        transformMatrix.preTranslate(-0.5f, -0.5f)

        // The width and height are not affected by rotation
        val newMatrix = Matrix(buffer.transformMatrix)
        newMatrix.preConcat(transformMatrix)
        buffer.retain()
        return VideoFrameTextureBuffer(
            buffer.width,
            buffer.height,
            buffer.textureId,
            newMatrix,
            buffer.type,
            Runnable { buffer.release() })
    }

    override fun onFrame(p0: Engine.FrameData?) {
        p0?.detectedFaces?.eyeScreenCoords
        Log.d(TAG, "onFrame: wwwwww ${p0?.detectedFaces?.eyeScreenCoords?.get(0)}" )
    }
}

data class DualCamera(
    val logicalId: String,
    val physicalId1: String,
    val physicalId2: String
)
