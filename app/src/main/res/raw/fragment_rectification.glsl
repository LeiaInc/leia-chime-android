#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require

precision highp float;

in vec2 vTextureCoord;

uniform samplerExternalOES sImageTexture;

out vec4 gl_FragColor;
uniform mat4 uTransform;

// TODO: Documentation for mathematics being done
void main() {
    vec2 coor = (uTransform * vec4(vTextureCoord, 0, 1)).xy;
    gl_FragColor = texture(sImageTexture, coor);
}
