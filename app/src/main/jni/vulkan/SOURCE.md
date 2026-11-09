# Where this renderer comes from

The Vulkan renderer in this folder, `VulkanRendererBridge.java` and the prebuilt PyroWave decoder in `../pyrowave/`
come from **Moonlight X** by MoreOrLessSoftware (https://github.com/MoreOrLessSoftware/moonlight-android, branch
`mlsoft-x`, commit `6e7d0ba5767d82a4233f7b4e414c865ab35c57ce`), GPL-3.0 like Penumbra.

- PyroWave is Hans-Kristian Arntzen's GPU wavelet codec (https://github.com/Themaister/pyrowave), MIT, built with
  Granite and volk (MIT). `../pyrowave/SOURCE` records the exact commits; `../pyrowave/build_libpyrowave.sh`
  rebuilds the library.
- PyroWave streams always use this renderer (PEN-9). H.264, HEVC and AV1 use it when Settings → Vulkan renderer →
  "Vulkan renderer for H.264, HEVC and AV1" is on (PEN-10, off by default): MediaCodec decodes into the renderer's
  AImageReader, as in Moonlight X, and the renderer paces and presents. Each frame carries the host's timestamp,
  which the pacer reads back from the image. If the renderer can't start, the stream falls back to MediaCodec
  rendering to the SurfaceView, with a toast.
- Local change, options: front buffer and GPU boost moved to the Vulkan renderer category and apply to every codec
  through the renderer (their preference keys are still `checkbox_pyrowave_front_buffer` and
  `checkbox_pyrowave_gpu_boost`). GPU keep-busy, PyroWave-only upstream, also runs for MediaCodec frames.
- Local change, HDR: some drivers (Adreno on Android 13, as on the Odin 2) offer no HDR10 Vulkan color space even
  though the compositor takes BT.2020 PQ buffers. Then the renderer passes PQ through a 10-bit sRGB-nonlinear
  swapchain and tags the window `ADATASPACE_BT2020_PQ` with `ANativeWindow_setBuffersDataSpace` (API 28+). When
  even that isn't possible, it logs the formats it was offered and tone maps to SDR.
- Local change, options: the late-frames policy comes from Settings → PyroWave, and front-buffer rendering and
  GPU boost from Settings → Vulkan renderer, instead of fixed defaults.

## Upscaling (PEN-10)

Settings → Vulkan renderer → Upscaling (`list_vulkan_upscaler`: `off`, `fsr`, `sgsr`; default `off`) scales a stream
smaller than the screen up to it in the renderer, for PyroWave and for MediaCodec frames through the renderer. With
an upscaler chosen, Game leaves the SurfaceView's buffers at the view's size instead of `setFixedSize()`-ing them
to the stream's, and the renderer upscales whenever the swapchain is larger than the stream; otherwise it draws as
before.

- **FSR 1** (`shaders/easu.frag`, `shaders/rcas.frag`): AMD FidelityFX Super Resolution 1.0, EASU into an
  intermediate image at the output size (A2B10G10R10, else RGBA16F, else RGBA8), then RCAS onto the swapchain at
  strength 0.87 (FSR's default 0.2 stops). MIT, Copyright (c) 2021 Advanced Micro Devices, Inc.: from
  `ffx-fsr/ffx_fsr1.h` v1.20210629 (https://github.com/GPUOpen-Effects/FidelityFX-FSR, `a21ffb8`).
- **SGSR 1** (`shaders/sgsr.frag`): Snapdragon Game Super Resolution 1, mobile RGBA variant, one pass at edge
  sharpness 2.0. BSD-3-Clause, Copyright (c) 2023, 2025 Qualcomm Innovation Center, Inc.: from
  `sgsr/v1/include/glsl/sgsr1_shader_mobile.frag` (https://github.com/SnapdragonGameStudios/snapdragon-gsr,
  `d926f07`).
- Both by way of Nightjar's GLES ports (https://github.com/RamazanKara/nightjar, `app/src/main/assets/shaders`,
  GPL-3.0, at `dd62ebf`): explicit taps at texel centers instead of gathers, full-precision arithmetic, finite
  divisions. Here they read the video through `shaders/video_source.glsl` (MediaCodec's YCbCr sampler, or PyroWave's
  planes with `-DPLANAR`), within the decoder's crop, and the final pass applies the renderer's usual output
  conversion (HDR10 pass-through or tone mapping, dithering) and pre-rotation. The licence texts are in the shader
  headers.
- HDR: upscaling stays on for HDR and 10-bit streams. It runs on the PQ-encoded signal, which is perceptually
  uniform like the gamma-encoded SDR signal both algorithms were tuned on, through a 10-bit (or wider)
  intermediate. SGSR's edge threshold and step limit are in 8-bit code values, so they act on PQ steps of about
  the same visibility. This hasn't been compared on a device; if HDR looks wrong, turn upscaling off.
- `adb shell setprop debug.penumbra.upscale_sharpness <value>` tries another sharpness (0 to 1 for FSR's RCAS, 1 to 2
  for SGSR), read when the renderer starts.
- `shaders/compile_shaders.sh` compiles every shader into `shaders_spv.h` with the NDK's `glslc`, as before.

## PyroWave versions

The PyroWave bitstream has no version field, so the client's decoder and the host's encoder must stay
compatible. The prebuilt `libpyrowave-shared.so` is from PyroWave `21f099d` (2026-10-17); Vibepollo 2.0.1 on the
host reports bitstream `186f0393` (2026-09-23). They interoperate in testing. When either side updates PyroWave,
check a stream still decodes before shipping, and rebuild the library with `../pyrowave/build_libpyrowave.sh` to
match the host if it doesn't.

The protocol side lives in `../moonlight-core/moonlight-common-c` (branch `penumbra/pyrowave` of
https://github.com/arcanite24/moonlight-common-c): upstream moonlight-common-c up to 2026-09-26 merged into
Artemis's fork, plus Moonlight X's PyroWave commits. It speaks the protocol in Vibepollo's
`docs/pyrowave-protocol.md`.
