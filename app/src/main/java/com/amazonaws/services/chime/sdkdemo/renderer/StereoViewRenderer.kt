package com.amazonaws.services.chime.sdkdemo.renderer

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import com.leiainc.androidsdk.video.glutils.InputSurface

class StereoViewRenderer(
    context: Context,
    private val surface: Surface,
    leftSurfaceTexture: SurfaceTexture,
    rightSurfaceTexture: SurfaceTexture,
    flipX: Boolean
) {

    private val inputSurface = InputSurface(surface)
    private var rectifier: RectifyingRenderer? = null
    private var isReleased: Boolean = false

    init {
        inputSurface.makeCurrent()
        rectifier = RectifyingRenderer(
            context,
            rightSurfaceTexture,
            leftSurfaceTexture,
            flipX
        )
        rectifier?.onSurfaceCreated(null, null)
        rectifier?.onSurfaceChanged(null, inputSurface.width, inputSurface.height)

        inputSurface.makeUnCurrent()
    }

    fun drawFrame() {
        if (isReleased) return
        inputSurface.makeCurrent()

        rectifier?.onDrawFrame(null)

        inputSurface.swapBuffers()
        inputSurface.makeUnCurrent()
    }

    fun release() {
        isReleased = true
        inputSurface.release()
        surface.release()
    }
}
