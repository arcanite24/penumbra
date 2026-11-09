#version 450
#extension GL_GOOGLE_include_directive : require

// AMD FidelityFX Super Resolution 1.0, EASU (edge adaptive spatial upsampling).
// Copyright (c) 2021 Advanced Micro Devices, Inc. All rights reserved.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
// THE SOFTWARE.
//
// From ffx-fsr/ffx_fsr1.h v1.20210629 (GPUOpen-Effects/FidelityFX-FSR a21ffb8), by way of
// Nightjar's FP32 GLES port (RamazanKara/nightjar app/src/main/assets/shaders/easu.frag, GPL-3.0):
// explicit taps at texel centers in place of gather4, full-precision arithmetic, and divisions
// kept finite on flat areas. Here it reads the decoded video through video_source.glsl, and
// works out FsrEasuCon()'s constants from the push constants.
//
// Writes the upscaled picture, still in the video's own encoding, to an intermediate image the
// size of the output; rcas.frag sharpens it onto the swapchain.

#include "video_common.glsl"
#include "video_source.glsl"

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

float luma(vec3 c) {
    return c.b * 0.5 + (c.r * 0.5 + c.g);
}

void FsrEasuTapF(inout vec3 aC, inout float aW, vec2 off, vec2 dir,
                 vec2 len, float lob, float clp, vec3 c) {
    vec2 v = vec2(dot(off, dir), dot(off, vec2(-dir.y, dir.x))) * len;
    float d2 = min(dot(v, v), clp);
    float wB = (2.0 / 5.0) * d2 - 1.0;
    float wA = lob * d2 - 1.0;
    wB *= wB;
    wA *= wA;
    wB = (25.0 / 16.0) * wB - (25.0 / 16.0 - 1.0);
    float w = wB * wA;
    aC += c * w;
    aW += w;
}

void FsrEasuSetF(inout vec2 dir, inout float len, float w,
                 float lA, float lB, float lC, float lD, float lE) {
    vec2 gradient = vec2(lD - lB, lE - lA);
    vec2 reversal = max(abs(vec2(lD, lE) - lC), abs(lC - vec2(lB, lA)));
    vec2 lengthXY = clamp(abs(gradient) / max(reversal, vec2(1.0e-8)), 0.0, 1.0);
    dir += gradient * w;
    len += dot(lengthXY, lengthXY) * w;
}

// ip: the output pixel, inSize: the crop in texels, outSize: the output in pixels
vec3 FsrEasuF(vec2 ip, vec2 inSize, vec2 outSize) {
    // FsrEasuCon()
    vec2 rcpIn = 1.0 / inSize;
    vec4 con0 = vec4(inSize / outSize, 0.5 * inSize / outSize - 0.5);
    vec4 con1 = vec4(rcpIn, rcpIn.x, -rcpIn.y);
    vec4 con2 = vec4(-rcpIn.x, 2.0 * rcpIn.y, rcpIn.x, 2.0 * rcpIn.y);
    vec2 con3 = vec2(0.0, 4.0 * rcpIn.y);

    vec2 pp = ip * con0.xy + con0.zw;
    vec2 fp = floor(pp);
    pp -= fp;
    vec2 p0 = fp * con1.xy + con1.zw;
    vec2 p1 = p0 + con2.xy;
    vec2 p2 = p0 + con2.zw;
    vec2 p3 = p0 + con3;
    vec2 halfTexel = 0.5 * rcpIn;
    vec3 b = loadSource(p0 + halfTexel * vec2(-1.0, 1.0));
    vec3 c = loadSource(p0 + halfTexel);
    vec3 i = loadSource(p1 + halfTexel * vec2(-1.0, 1.0));
    vec3 j = loadSource(p1 + halfTexel);
    vec3 f = loadSource(p1 + halfTexel * vec2(1.0, -1.0));
    vec3 e = loadSource(p1 - halfTexel);
    vec3 k = loadSource(p2 + halfTexel * vec2(-1.0, 1.0));
    vec3 l = loadSource(p2 + halfTexel);
    vec3 h = loadSource(p2 + halfTexel * vec2(1.0, -1.0));
    vec3 g = loadSource(p2 - halfTexel);
    vec3 o = loadSource(p3 + halfTexel * vec2(1.0, -1.0));
    vec3 n = loadSource(p3 - halfTexel);

    vec2 dir = vec2(0.0);
    float len = 0.0;
    FsrEasuSetF(dir, len, (1.0 - pp.x) * (1.0 - pp.y), luma(b), luma(e), luma(f), luma(g), luma(j));
    FsrEasuSetF(dir, len, pp.x * (1.0 - pp.y), luma(c), luma(f), luma(g), luma(h), luma(k));
    FsrEasuSetF(dir, len, (1.0 - pp.x) * pp.y, luma(f), luma(i), luma(j), luma(k), luma(n));
    FsrEasuSetF(dir, len, pp.x * pp.y, luma(g), luma(j), luma(k), luma(l), luma(o));
    float dirR = dot(dir, dir);
    dir = dirR < (1.0 / 32768.0) ? vec2(1.0, 0.0) : dir * inversesqrt(dirR);
    len *= 0.5;
    len *= len;
    float stretch = dot(dir, dir) / max(abs(dir.x), abs(dir.y));
    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
    float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;
    float clp = 1.0 / lob;

    // Deringing: no brighter or darker than the nearest four texels
    vec3 min4 = min(min(f, g), min(j, k));
    vec3 max4 = max(max(f, g), max(j, k));
    vec3 aC = vec3(0.0);
    float aW = 0.0;
    FsrEasuTapF(aC, aW, vec2( 0.0, -1.0) - pp, dir, len2, lob, clp, b);
    FsrEasuTapF(aC, aW, vec2( 1.0, -1.0) - pp, dir, len2, lob, clp, c);
    FsrEasuTapF(aC, aW, vec2(-1.0,  1.0) - pp, dir, len2, lob, clp, i);
    FsrEasuTapF(aC, aW, vec2( 0.0,  1.0) - pp, dir, len2, lob, clp, j);
    FsrEasuTapF(aC, aW, vec2( 0.0,  0.0) - pp, dir, len2, lob, clp, f);
    FsrEasuTapF(aC, aW, vec2(-1.0,  0.0) - pp, dir, len2, lob, clp, e);
    FsrEasuTapF(aC, aW, vec2( 1.0,  1.0) - pp, dir, len2, lob, clp, k);
    FsrEasuTapF(aC, aW, vec2( 2.0,  1.0) - pp, dir, len2, lob, clp, l);
    FsrEasuTapF(aC, aW, vec2( 2.0,  0.0) - pp, dir, len2, lob, clp, h);
    FsrEasuTapF(aC, aW, vec2( 1.0,  0.0) - pp, dir, len2, lob, clp, g);
    FsrEasuTapF(aC, aW, vec2( 1.0,  2.0) - pp, dir, len2, lob, clp, o);
    FsrEasuTapF(aC, aW, vec2( 0.0,  2.0) - pp, dir, len2, lob, clp, n);
    return clamp(aC / aW, min4, max4);
}

void main() {
    // Drawn unrotated into the intermediate image, so the crop position is the output's
    vec2 outSize = pc.scale.zw;
    vec2 ip = floor(cropPosition(vUv) * outSize);
    outColor = vec4(FsrEasuF(ip, pc.scale.xy, outSize), 1.0);
}
