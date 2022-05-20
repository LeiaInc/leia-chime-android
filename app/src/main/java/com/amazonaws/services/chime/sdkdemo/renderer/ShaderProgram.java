package com.amazonaws.services.chime.sdkdemo.renderer;

import android.content.Context;
import android.opengl.GLES20;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class ShaderProgram {

    private final int mHandle;
    private final int mVertexShader;
    private final int mFragmentShader;

    public ShaderProgram(
            Context context, final int vertexShaderResource, final int fragmentShaderResource) {

        String fragmentShaderText = readTextFileFromRawResource(context, fragmentShaderResource);

        mVertexShader =
                compileShader(
                        GLES20.GL_VERTEX_SHADER,
                        readTextFileFromRawResource(context, vertexShaderResource));
        mFragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderText);

        mHandle = GLES20.glCreateProgram();
        if (mHandle == 0) {
            throw new RuntimeException(
                    "Error creating GLSL program: GLES20.glCreateProgram() failed.");
        }

        // Bind the vertex shader to the program.
        GLES20.glAttachShader(mHandle, mVertexShader);

        // Bind the fragment shader to the program.
        GLES20.glAttachShader(mHandle, mFragmentShader);

        // Link the two shaders together into a program.
        GLES20.glLinkProgram(mHandle);

        // Get the link status.
        final int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(mHandle, GLES20.GL_LINK_STATUS, linkStatus, 0);

        if (linkStatus[0] == 0) {
            throw new RuntimeException(
                    "Error linking GLSL program: " + GLES20.glGetProgramInfoLog(mHandle));
        }
    }

    public int getHandle() {
        return mHandle;
    }

    /**
     * Helper function to compile a shader.
     *
     * @param shaderType The shader type.
     * @param shaderSource The shader source code.
     * @return An OpenGL handle to the shader.
     */
    private static int compileShader(final int shaderType, final String shaderSource) {

        int shaderHandle = GLES20.glCreateShader(shaderType);
        if (shaderHandle == 0) {
            throw new RuntimeException(
                    "Error creating GLSL shader: GLES20.glCreateShader() failed.");
        }

        // Pass in the shader source.
        GLES20.glShaderSource(shaderHandle, shaderSource);

        // Compile the shader.
        GLES20.glCompileShader(shaderHandle);

        // Get the compilation status.
        final int[] compileStatus = new int[1];
        GLES20.glGetShaderiv(shaderHandle, GLES20.GL_COMPILE_STATUS, compileStatus, 0);

        // If the compilation failed, delete the shader.
        if (compileStatus[0] == 0) {
            GLES20.glDeleteShader(shaderHandle);
            shaderHandle = 0;
        }

        return shaderHandle;
    }

    private static String readTextFileFromRawResource(final Context context, final int resourceId) {
        final InputStream inputStream = context.getResources().openRawResource(resourceId);
        final InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
        final BufferedReader bufferedReader = new BufferedReader(inputStreamReader);

        String nextLine;
        final StringBuilder body = new StringBuilder();

        try {
            while ((nextLine = bufferedReader.readLine()) != null) {
                body.append(nextLine);
                body.append('\n');
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return body.toString();
    }

    public void release() {
        GLES20.glDeleteProgram(mHandle);
        GLES20.glDeleteShader(mVertexShader);
        GLES20.glDeleteShader(mFragmentShader);
    }
}
