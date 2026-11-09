# Where this renderer comes from

The Vulkan renderer in this folder, `VulkanRendererBridge.java` and the prebuilt PyroWave decoder in `../pyrowave/`
come from **Moonlight X** by MoreOrLessSoftware (https://github.com/MoreOrLessSoftware/moonlight-android, branch
`mlsoft-x`, commit `6e7d0ba5767d82a4233f7b4e414c865ab35c57ce`), GPL-3.0 like Penumbra.

- PyroWave is Hans-Kristian Arntzen's GPU wavelet codec (https://github.com/Themaister/pyrowave), MIT, built with
  Granite and volk (MIT). `../pyrowave/SOURCE` records the exact commits; `../pyrowave/build_libpyrowave.sh`
  rebuilds the library.
- Penumbra uses this renderer only for PyroWave streams (PEN-9). H.264, HEVC and AV1 still go through MediaCodec.
- Local change, HDR: some drivers (Adreno on Android 13, as on the Odin 2) offer no HDR10 Vulkan color space even
  though the compositor takes BT.2020 PQ buffers. Then the renderer passes PQ through a 10-bit sRGB-nonlinear
  swapchain and tags the window `ADATASPACE_BT2020_PQ` with `ANativeWindow_setBuffersDataSpace` (API 28+). When
  even that isn't possible, it logs the formats it was offered and tone maps to SDR.
- Local change, options: the late-frames policy, front-buffer rendering and GPU boost come from Settings →
  PyroWave instead of fixed defaults.

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
