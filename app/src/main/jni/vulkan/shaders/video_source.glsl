// The video as the upscaling shaders read it: non-linear R'G'B' at a point of the crop.
// Included after video_common.glsl. Compiled once as is, for MediaCodec's buffers (a sampler with
// a YCbCr conversion), and once with -DPLANAR for PyroWave's Y, Cb and Cr planes.

#ifdef PLANAR
// Full range YCbCr 4:2:0 in separate planes, converted as video_planar.frag does
layout(set = 0, binding = 0) uniform sampler2D uPlanes[3];

vec3 sourceRgb(vec2 uv) {
    float y = texture(uPlanes[0], uv).r;
    vec2 chromaUv = uv + vec2(pc.ycbcr.w, 0.0);
    float cb = texture(uPlanes[1], chromaUv).r - pc.ycbcr.z;
    float cr = texture(uPlanes[2], chromaUv).r - pc.ycbcr.z;
    float kr = pc.ycbcr.x;
    float kb = pc.ycbcr.y;
    float r = y + 2.0 * (1.0 - kr) * cr;
    float b = y + 2.0 * (1.0 - kb) * cb;
    float g = (y - kr * r - kb * b) / (1.0 - kr - kb);
    return vec3(r, g, b);
}
#else
// The sampler carries an immutable VkSamplerYcbcrConversion, so it returns R'G'B'. Such a
// sampler only takes normalized coordinates, so every tap is a texture() at a texel's center.
layout(set = 0, binding = 0) uniform sampler2D uVideo;

vec3 sourceRgb(vec2 uv) {
    return texture(uVideo, uv).rgb;
}
#endif

// p is a position within the crop, from 0 to 1 across it. Taps stay half a texel inside the
// crop, so the decoder's padding never bleeds in.
vec3 loadSource(vec2 p) {
    vec2 uv = clamp(pc.uvRect.xy + p * pc.uvRect.zw, pc.uvClamp.xy, pc.uvClamp.zw);
    return sourceRgb(uv);
}

// Where this fragment is within the crop, from the vertex shader's texture coordinate
vec2 cropPosition(vec2 uv) {
    return (uv - pc.uvRect.xy) / pc.uvRect.zw;
}
