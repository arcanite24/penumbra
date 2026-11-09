# Where this renderer comes from

The Vulkan renderer in this folder, `VulkanRendererBridge.java` and the prebuilt PyroWave decoder in `../pyrowave/`
come from **Moonlight X** by MoreOrLessSoftware (https://github.com/MoreOrLessSoftware/moonlight-android, branch
`mlsoft-x`, commit `6e7d0ba5767d82a4233f7b4e414c865ab35c57ce`), GPL-3.0 like Penumbra.

- PyroWave is Hans-Kristian Arntzen's GPU wavelet codec (https://github.com/Themaister/pyrowave), MIT, built with
  Granite and volk (MIT). `../pyrowave/SOURCE` records the exact commits; `../pyrowave/build_libpyrowave.sh`
  rebuilds the library.
- Penumbra uses this renderer only for PyroWave streams (PEN-9). H.264, HEVC and AV1 still go through MediaCodec.
- Local change: when a stream is HDR but the surface has no HDR10 (PQ) format, the formats it does offer are
  logged, which shows why the stream is tone mapped to SDR.

The protocol side lives in `../moonlight-core/moonlight-common-c` (branch `penumbra/pyrowave` of
https://github.com/arcanite24/moonlight-common-c): upstream moonlight-common-c up to 2026-09-26 merged into
Artemis's fork, plus Moonlight X's PyroWave commits. It speaks the protocol in Vibepollo's
`docs/pyrowave-protocol.md`.
