package com.amazonaws.services.chime.sdkdemo.activity

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.AsyncTask
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
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
import com.amazonaws.services.chime.sdkdemo.renderer.StereoViewRenderer
import com.leia.headtracking.Engine
import com.leia.headtracking.SharedCameraSink
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executor

class LeiaCameraCaptureSource(
    private val activity: Activity,
    private val context: Context,
    private var leftCameraSurfaces: MutableList<Surface>?,
    private var rightCameraSurfaces: MutableList<Surface>?,
    private val surfaceTextureCaptureSourceFactory: SurfaceTextureCaptureSourceFactory
) : CameraCaptureSource, VideoSink, Engine.FrameListener {
    private val cameraManager = context.getSystemService(CameraManager::class.java)!!
    private lateinit var captureSession: CameraCaptureSession
    private lateinit var leftImageReader: ImageReader
    private lateinit var rightImageReader: ImageReader
    private var dualCamera: DualCamera? = null
    private lateinit var  handler: Handler
    private var engine: Engine? = null
    private var engineCameraSink: SharedCameraSink? = null

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

    private fun openCamera(lensFacing: Int, onCameraOpened: () -> Unit) {
        leftCameraSurfaces?.add(leftSurface)
        rightCameraSurfaces?.add(rightSurface)
        dualCamera = findCameraId(lensFacing).first()
        initCaptureSize()
        initHeadTracking()

        val thread = HandlerThread("LeiaCamCamera")
        thread.start()
        handler = Handler(thread.looper)

        leftCameraSurfaces?.add(leftImageReader.surface)
        createCameraSession(
            dualCamera!!,
            leftCameraSurfaces,
            rightCameraSurfaces
        ) { session ->
            captureSession = session
            onCameraOpened.invoke()
            startPreview()
        }

        leftImageReader.setOnImageAvailableListener({ reader ->
            val image = reader!!.acquireNextImage()
            updateIntrinsics(image)
            engineCameraSink?.onImage(image, 0)
            image.close()
        }, handler)
    }

    private fun updateIntrinsics(image: Image) {
        val yPlane = image.planes[0]
        val rowStride = yPlane.rowStride
        try {
            val characteristics = cameraManager.getCameraCharacteristics(dualCamera!!.physicalId1)
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
            e.printStackTrace()
        }
    }

    private fun initHeadTracking() {

        val initArgs = Engine.InitArgs(activity, this, false)
        engineCameraSink = SharedCameraSink()
        initArgs.sharedCameraSink = engineCameraSink
        try {
            engine = Engine(initArgs)
            engine!!.startTracking()
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }


    }


    fun startPreview() {
        val captureRequest =
            captureSession.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                leftCameraSurfaces?.forEach { addTarget(it) }
                rightCameraSurfaces?.forEach { addTarget(it) }
            }.build()

        captureSession.setRepeatingRequest(captureRequest, null, null)
    }

    // Use the maximum resolution as the image capture size based on the characteristic provided by the hardware.
    private fun initCaptureSize() {
//        if (width != 0) return
        val maximumSize = getMaximumResolutionCameraSupport()
//        width = maximumSize.width
//        height = maximumSize.height
        leftImageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
        rightImageReader = ImageReader.newInstance(maximumSize.width, maximumSize.height, ImageFormat.JPEG, 1)
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

    @SuppressLint("MissingPermission")
    private fun createCameraSession(
        dualCamera: DualCamera,
        leftTarget: List<Surface>?,
        rightTarget: List<Surface>?,
        executor: Executor = AsyncTask.SERIAL_EXECUTOR,
        callback: (CameraCaptureSession) -> Unit
    ) {

        val configs = ArrayList<OutputConfiguration>()
        if (leftTarget != null) {
            configs += leftTarget.map { createOutputConfiguration(it, dualCamera.physicalId1) }
        }
        if (rightTarget != null) {
            configs += rightTarget.map { createOutputConfiguration(it, dualCamera.physicalId2) }
        }

        val sessionConfiguration = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            configs,
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) { callback(session) }
                override fun onConfigureFailed(session: CameraCaptureSession) = session.device.close()
            }
        )

        cameraManager.openCamera(
            dualCamera.logicalId, AsyncTask.SERIAL_EXECUTOR,
            object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    device.createCaptureSession(sessionConfiguration)
                }
                override fun onError(device: CameraDevice, error: Int) {
                    onDisconnected(device)
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                }
            }
        )
    }

    private fun createOutputConfiguration(surface: Surface, physId: String) =
        OutputConfiguration(surface)
            .apply { setPhysicalCameraId(physId) }

    private fun getMaximumResolutionCameraSupport(): Size {
        // Read the supported size
        val characteristics = cameraManager.getCameraCharacteristics(dualCamera!!.logicalId)
        val map = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        )
        // Return true as long as we find a supported size whose width is greater than 4000.
        val sizeList = map!!.getOutputSizes(ImageFormat.JPEG).toList()
        // Return the maximum resolution which is located at the index 0
        return sizeList.get(0)
    }

    fun closeCamera() {
        captureSession.device.close()
        captureSession.close()
    }

    override fun onFrame(p0: Engine.FrameData?) {
        Log.d(TAG, "onFrame: wwwwww ${p0?.detectedFaces?.eyeScreenCoords?.get(0)}" )
    }
    private  val TAG = "Camera2StereoWrapper"
    override fun onVideoFrameReceived(frame: VideoFrame) {
        val processedBuffer: VideoFrameBuffer = createBufferWithUpdatedTransformMatrix(
            frame.buffer as VideoFrameTextureBuffer,
            true, -270
        )

        val processedFrame =
            VideoFrame(frame.timestampNs, processedBuffer, VideoRotation.Rotation270)
        sinks.forEach { it.onVideoFrameReceived(processedFrame) }
        processedBuffer.release()
    }

    private val DESIRED_CAPTURE_FORMAT = VideoCaptureFormat(960, 1440, 30)
    private var surfaceTextureSource: SurfaceTextureCaptureSource? = null

    val width = 1920
    val height = 1440
    var leftSurfaceTexture: SurfaceTexture =
        SurfaceTexture(false).apply { setDefaultBufferSize(width, height) }
    var leftSurface = Surface(leftSurfaceTexture)
    var rightSurfaceTexture: SurfaceTexture =
        SurfaceTexture(false).apply { setDefaultBufferSize(width, height) }
    var rightSurface = Surface(rightSurfaceTexture)

    // Concurrency modification could happen when sink gets
    // added/removed from another thread while sending frames
    private val sinks = ConcurrentSet.createConcurrentSet<VideoSink>()



    override var device: MediaDevice? = MediaDevice.listVideoDevices(cameraManager)
        .firstOrNull { it.type == MediaDeviceType.VIDEO_FRONT_CAMERA } ?: MediaDevice.listVideoDevices(cameraManager)
        .firstOrNull { it.type == MediaDeviceType.VIDEO_BACK_CAMERA }
    override var torchEnabled: Boolean = false
    override var format: VideoCaptureFormat = DESIRED_CAPTURE_FORMAT

    override fun switchCamera() {

    }

    override fun start() {
        val surfaceTextureFormat =  VideoCaptureFormat(960, 1440, 30)
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
        openCamera(0, {})
    }

    override fun stop() {
        val sink: VideoSink = this
        runBlocking(handler.asCoroutineDispatcher().immediate) {
            closeCamera()

            engineCameraSink?.close()
            engine?.stopTracking()

            // Stop surface capture source
            surfaceTextureSource?.removeVideoSink(sink)
            surfaceTextureSource?.stop()
            surfaceTextureSource?.release()
            surfaceTextureSource = null
        }
    }

    override fun addCaptureSourceObserver(observer: CaptureSourceObserver) {
    }

    override fun removeCaptureSourceObserver(observer: CaptureSourceObserver) {
    }

    override fun addVideoSink(sink: VideoSink) {
        sinks.add(sink)
    }

    override fun removeVideoSink(sink: VideoSink) {
        sinks.remove(sink)
    }

    override val contentHint: VideoContentHint  = VideoContentHint.Motion

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
            kotlinx.coroutines.Runnable { buffer.release() })
    }
}

data class DualCamera(
    val logicalId: String,
    val physicalId1: String,
    val physicalId2: String
)
