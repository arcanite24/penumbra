#version 450
#extension GL_GOOGLE_include_directive : require

// Snapdragon Game Super Resolution 1 (SGSR 1), mobile RGBA variant.
// Copyright (c) 2023, 2025, Qualcomm Innovation Center, Inc. All rights reserved.
// SPDX-License-Identifier: BSD-3-Clause
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//
// 1. Redistributions of source code must retain the above copyright notice,
//    this list of conditions and the following disclaimer.
//
// 2. Redistributions in binary form must reproduce the above copyright notice,
//    this list of conditions and the following disclaimer in the documentation
//    and/or other materials provided with the distribution.
//
// 3. Neither the name of the copyright holder nor the names of its contributors
//    may be used to endorse or promote products derived from this software
//    without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
// ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
// LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
// CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
// SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
// INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
// CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
// ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
// POSSIBILITY OF SUCH DAMAGE.
//
// From Qualcomm's sgsr/v1/include/glsl/sgsr1_shader_mobile.frag
// (SnapdragonGameStudios/snapdragon-gsr d926f07), by way of Nightjar's GLES port
// (RamazanKara/nightjar app/src/main/assets/shaders/sgsr.frag, GPL-3.0): explicit taps in place
// of texture gathers, full-precision arithmetic with finite divisors, the mobile edge threshold
// and 12-tap filter. Here it reads the decoded video through video_source.glsl and draws
// straight onto the swapchain in one pass. The filter's weights only depend on distances, so it
// runs the same in Vulkan's top-down image space as in GL's bottom-up one.

#include "video_common.glsl"
#include "video_source.glsl"

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

float loadGreen(vec2 pixel, vec2 rcpIn) {
    return loadSource((pixel + 0.5) * rcpIn).g;
}

float fastLanczos2(float x) {
    float wA = x - 4.0;
    float wB = x * wA - wA;
    wA *= wA;
    return wB * wA;
}

vec2 weightY(float dx, float dy, float c, float std) {
    float x = (dx * dx + dy * dy) * 0.55 + clamp(abs(c) * std, 0.0, 1.0);
    float w = fastLanczos2(x);
    return vec2(w, w * c);
}

void main() {
    vec2 inSize = pc.scale.xy;
    vec2 rcpIn = 1.0 / inSize;
    float edgeSharpness = pc.params2.z;

    vec2 uv = cropPosition(vUv);
    vec3 rgb = loadSource(uv);
    vec2 imgCoord = uv * inSize + vec2(-0.5, 0.5);
    vec2 pixel = floor(imgCoord);
    vec2 pl = imgCoord - pixel;

    vec4 left = vec4(loadGreen(pixel + vec2(-1.0, 0.0), rcpIn), loadGreen(pixel, rcpIn),
                     loadGreen(pixel + vec2(0.0, -1.0), rcpIn), loadGreen(pixel + vec2(-1.0, -1.0), rcpIn));
    float edgeVote = abs(left.z - left.y) + abs(rgb.g - left.y) + abs(rgb.g - left.z);
    if (edgeVote > 8.0 / 255.0) {
        vec4 right = vec4(loadGreen(pixel + vec2(1.0, 0.0), rcpIn), loadGreen(pixel + vec2(2.0, 0.0), rcpIn),
                          loadGreen(pixel + vec2(2.0, -1.0), rcpIn), loadGreen(pixel + vec2(1.0, -1.0), rcpIn));
        vec4 upDown = vec4(loadGreen(pixel + vec2(0.0, -2.0), rcpIn), loadGreen(pixel + vec2(1.0, -2.0), rcpIn),
                           loadGreen(pixel + vec2(1.0, 1.0), rcpIn), loadGreen(pixel + vec2(0.0, 1.0), rcpIn));
        float mean = (left.y + left.z + right.x + right.w) * 0.25;
        left -= mean;
        right -= mean;
        upDown -= mean;
        float center = rgb.g - mean;
        float sum = dot(abs(left) + abs(right) + abs(upDown), vec4(1.0));
        float std = 2.181818 / max(sum, 1.0e-8);

        vec2 aWY = weightY(pl.x, pl.y + 1.0, upDown.x, std);
        aWY += weightY(pl.x - 1.0, pl.y + 1.0, upDown.y, std);
        aWY += weightY(pl.x - 1.0, pl.y - 2.0, upDown.z, std);
        aWY += weightY(pl.x, pl.y - 2.0, upDown.w, std);
        aWY += weightY(pl.x + 1.0, pl.y - 1.0, left.x, std);
        aWY += weightY(pl.x, pl.y - 1.0, left.y, std);
        aWY += weightY(pl.x, pl.y, left.z, std);
        aWY += weightY(pl.x + 1.0, pl.y, left.w, std);
        aWY += weightY(pl.x - 1.0, pl.y - 1.0, right.x, std);
        aWY += weightY(pl.x - 2.0, pl.y - 1.0, right.y, std);
        aWY += weightY(pl.x - 2.0, pl.y, right.z, std);
        aWY += weightY(pl.x - 1.0, pl.y, right.w, std);

        float finalY = aWY.y / max(aWY.x, 1.0e-8);
        float maxY = max(max(left.y, left.z), max(right.x, right.w));
        float minY = min(min(left.y, left.z), min(right.x, right.w));
        finalY = clamp(edgeSharpness * finalY, minY, maxY);
        float deltaY = clamp(finalY - center, -23.0 / 255.0, 23.0 / 255.0);
        rgb = clamp(rgb + deltaY, 0.0, 1.0);
    }
    outColor = outputColor(rgb);
}
