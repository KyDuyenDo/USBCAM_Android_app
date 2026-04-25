#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uTextureSampler;
uniform float uBrightness;
varying vec2 vTextureCoord;
void main()
{
    vec4 color = texture2D(uTextureSampler, vTextureCoord);
    gl_FragColor = vec4(color.rgb + vec3(uBrightness), color.a);
}
