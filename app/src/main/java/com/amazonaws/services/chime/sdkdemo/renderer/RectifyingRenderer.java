package com.amazonaws.services.chime.sdkdemo.renderer;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.GLES32;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.amazonaws.services.chime.sdkdemo.R;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Objects;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import static android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES;

/**
 * Uses a shader to rectify two camera preview streams and composite them into a side-by-side
 * texture.
 */
public class RectifyingRenderer implements GLSurfaceView.Renderer {

    private static final int FLOAT_SIZE_BYTES = 4;
    private static final int TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES;
    private static final int TRIANGLE_VERTICES_DATA_POS_OFFSET = 0;
    private static final int TRIANGLE_VERTICES_DATA_UV_OFFSET = 3;
    private static final float[] mTriangleVerticesData = {
        //  X,     Y, Z,    U,    V,
        -1.0f,
        -1.0f,
        0,
        0.0f,
        1.0f,
        1.0f,
        -1.0f,
        0,
        1.0f,
        1.0f,
        -1.0f,
        1.0f,
        0,
        0.0f,
        0.0f,
        1.0f,
        1.0f,
        0,
        1.0f,
        0.0f,
        1.0f,
        -1.0f,
        0,
        1.0f,
        1.0f,
        -1.0f,
        1.0f,
        0,
        0.0f,
        0.0f,
    };

    private final Context mContext;
    private final FloatBuffer mTriangleVertices;

    private ShaderProgram mShaderProgram;

    private int maPositionHandle;
    private int maTextureHandle;
    private int mTransformHandle;

    private int mSurfaceWidth;
    private int mSurfaceHeight;

    private int mRightTextureId;
    private int mLeftTextureId;
    @NonNull private final SurfaceTexture mLeftSurfaceTexture;
    @Nullable private final SurfaceTexture mRightSurfaceTexture;
    // private final float[] mPreTransform = new float[16];
    private final float[] mTransform = new float[16];

    public RectifyingRenderer(
            @NonNull Context context,
            @NonNull SurfaceTexture leftSurfaceTexture,
            @Nullable SurfaceTexture rightSurfaceTexture,
            boolean flipX) {
        mContext = Objects.requireNonNull(context);
        mTriangleVertices =
                ByteBuffer.allocateDirect(mTriangleVerticesData.length * FLOAT_SIZE_BYTES)
                        .order(ByteOrder.nativeOrder())
                        .asFloatBuffer();
        mTriangleVertices.put(mTriangleVerticesData).position(0);

        mLeftSurfaceTexture = Objects.requireNonNull(leftSurfaceTexture);
        mRightSurfaceTexture = rightSurfaceTexture;
        Matrix.setIdentityM(mTransform, 0);
        Matrix.translateM(mTransform, 0, .5f, .5f, 0);
        // Hack: Correct aspect ratio.
        // TODO: Would be better to use a preview of the correct size, and get rid of this.
        float originalAspectRatio = 4640f / 3488f;
        float targetAspectRatio = 4640f / 2900f;
        float aspectScalar = originalAspectRatio / targetAspectRatio;
        Matrix.scaleM(mTransform, 0, 1.0f, aspectScalar, 1.0f);
        // Matrix.translateM(mTransform, 0, -.5f, -.5f, 0);

        // Matrix.setIdentityM(mTransform, 0);
        // Matrix.translateM(mTransform, 0, .5f, .5f, 0);
        if (flipX) {
            Matrix.scaleM(mTransform, 0, -1, 1, 1);
        }

        Matrix.translateM(mTransform, 0, -.5f, -.5f, 0);
    }

    @Override
    public void onSurfaceChanged(GL10 glUnused, int width, int height) {
        mSurfaceWidth = width;
        mSurfaceHeight = height;
    }

    @Override
    public void onSurfaceCreated(GL10 glUnused, EGLConfig config) {
        mShaderProgram =
                new ShaderProgram(
                        mContext, R.raw.vertex_rectification, R.raw.fragment_rectification);

        maPositionHandle = GLES32.glGetAttribLocation(mShaderProgram.getHandle(), "aPosition");
        checkGlError();

        maTextureHandle = GLES32.glGetAttribLocation(mShaderProgram.getHandle(), "aTextureCoord");
        checkGlError();

        mTransformHandle = GLES32.glGetUniformLocation(mShaderProgram.getHandle(), "uTransform");
        if (mTransformHandle == -1) throw new IllegalStateException();
        checkGlError();

        int[] textures = new int[2];
        GLES20.glGenTextures(2, textures, 0);
        mLeftTextureId = textures[0];
        mRightTextureId = textures[1];
        checkGlError();
    }

    @Override
    public void onDrawFrame(GL10 glUnused) {
        GLES20.glViewport(0, 0, mSurfaceWidth, mSurfaceHeight);
        checkGlError();

        GLES32.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES32.glClear(GLES32.GL_DEPTH_BUFFER_BIT | GLES32.GL_COLOR_BUFFER_BIT);
        checkGlError();

        GLES32.glUseProgram(mShaderProgram.getHandle());
        checkGlError();

        mLeftSurfaceTexture.attachToGLContext(mLeftTextureId);
        mLeftSurfaceTexture.updateTexImage();

        if (mRightSurfaceTexture != null) {
            mRightSurfaceTexture.attachToGLContext(mRightTextureId);
            mRightSurfaceTexture.updateTexImage();
        }

        // If we have both left and right textures, draw a 2x1 output.
        if (mRightSurfaceTexture != null) {
            GLES20.glViewport(0, 0, mSurfaceWidth / 2, mSurfaceHeight);
            checkGlError();
            drawSingleView(mRightTextureId);
            GLES20.glViewport(mSurfaceWidth / 2, 0, mSurfaceWidth / 2, mSurfaceHeight);
            drawSingleView(mLeftTextureId);
        } else { // If only the left texture, draw only the left as output.
            GLES20.glViewport(0, 0, mSurfaceWidth, 1600);
            drawSingleView(mLeftTextureId);
        }
        mLeftSurfaceTexture.detachFromGLContext();

        if (mRightSurfaceTexture != null) {
            mRightSurfaceTexture.detachFromGLContext();
        }
    }

    private void drawSingleView(int textureId) {

        GLES32.glUniformMatrix4fv(mTransformHandle, 1, false, mTransform, 0);
        checkGlError();

        GLES32.glActiveTexture(GLES32.GL_TEXTURE0);
        GLES32.glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureId);
        checkGlError();
        GLES20.glUniform1i(
                GLES20.glGetUniformLocation(mShaderProgram.getHandle(), "sImageTexture"), 0);
        checkGlError();

        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET);
        GLES32.glVertexAttribPointer(
                maPositionHandle,
                3,
                GLES32.GL_FLOAT,
                false,
                TRIANGLE_VERTICES_DATA_STRIDE_BYTES,
                mTriangleVertices);
        checkGlError();
        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET);
        GLES32.glEnableVertexAttribArray(maPositionHandle);
        checkGlError();
        GLES32.glVertexAttribPointer(
                maTextureHandle,
                2,
                GLES32.GL_FLOAT,
                false,
                TRIANGLE_VERTICES_DATA_STRIDE_BYTES,
                mTriangleVertices);
        checkGlError();
        GLES32.glEnableVertexAttribArray(maTextureHandle);
        checkGlError();
        GLES32.glDrawArrays(GLES32.GL_TRIANGLES, 0, mTriangleVerticesData.length / 5);
        checkGlError();

        GLES32.glDisable(GLES32.GL_CULL_FACE);
    }

    public static void checkGlError() {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            throw new IllegalStateException(String.format("GL ERROR 0x%x", error));
        }
    }
}
