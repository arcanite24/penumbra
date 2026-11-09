#version 450
#extension GL_GOOGLE_include_directive : require

// AMD FidelityFX Super Resolution 1.0, RCAS (robust contrast adaptive sharpening).
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
// Nightjar's FP32 GLES port (RamazanKara/nightjar app/src/main/assets/shaders/rcas.frag, GPL-3.0):
// full-precision arithmetic, clamped edge loads, finite divisions on black and white plateaus,
// and a linear strength (pc.params2.z, 0 for none to 1 for FSR's sharpest, 0 stops).
//
// Reads easu.frag's intermediate image and draws the sharpened picture onto the swapchain,
// where it's turned into the output's encoding like any other frame.

#include "video_common.glsl"

layout(set = 0, binding = 0) uniform sampler2D uUpscaled;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

vec3 FsrRcasLoadF(ivec2 p) {
    return texelFetch(uUpscaled, clamp(p, ivec2(0), textureSize(uUpscaled, 0) - 1), 0).rgb;
}

vec3 FsrRcasF(ivec2 p, float sharpness) {
    vec3 b = FsrRcasLoadF(p + ivec2(0, -1));
    vec3 d = FsrRcasLoadF(p + ivec2(-1, 0));
    vec3 e = FsrRcasLoadF(p);
    vec3 f = FsrRcasLoadF(p + ivec2(1, 0));
    vec3 h = FsrRcasLoadF(p + ivec2(0, 1));
    vec3 mn4 = min(min(b, d), min(f, h));
    vec3 mx4 = max(max(b, d), max(f, h));
    vec3 hitMin = min(mn4, e) / max(4.0 * mx4, vec3(1.0e-8));
    vec3 hitMax = (1.0 - max(mx4, e)) / min(4.0 * mn4 - 4.0, vec3(-1.0e-8));
    vec3 lobes = max(-hitMin, hitMax);
    float lobe = max(-(0.25 - 1.0 / 16.0), min(max(max(lobes.r, lobes.g), lobes.b), 0.0)) * sharpness;
    return (lobe * (b + d + f + h) + e) / (4.0 * lobe + 1.0);
}

void main() {
    // The vertex shader turns the picture into the display's orientation; vUv stays in the
    // picture's own, which is the intermediate image's
    ivec2 p = ivec2(vUv * vec2(textureSize(uUpscaled, 0)));
    outColor = outputColor(FsrRcasF(p, pc.params2.z));
}
