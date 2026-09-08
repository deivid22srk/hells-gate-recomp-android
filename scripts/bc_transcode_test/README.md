# BCn -> ASTC/ETC2/EAC transcoder validation harness

Empirical, end-to-end correctness validation for the CPU block transcoder
(`patches/sdk/rexglue-sdk-v0.10.0-android-aaa-perf.patch`, files
`bc_transcoder.h/.cpp` in the SDK tree).

The harness generates random and structured guest BCn blocks, runs them
through the transcoder (host build), and decodes BOTH the original BCn
blocks AND the transcoded ASTC/ETC2/EAC blocks with `texture2ddecoder`
(an independent, battle-tested decoder) - comparing the decoded pixels
proves the bit-level encoders against an implementation that shares no code
with the transcoder.

## Usage

```bash
pip install texture2ddecoder
cd scripts/bc_transcode_test

# Build the generator against a patched SDK tree and run it:
clang++ -std=c++23 \
    -I <repo>/thirdparty/rexglue-sdk/include \
    gen_test_vectors.cpp \
    <repo>/thirdparty/rexglue-sdk/src/graphics/vulkan/bc_transcoder.cpp \
    -o gen_test
./gen_test   # writes blocks_*.bin / out_*.bin

# Cross-validate with the independent decoder:
python3 verify_bc_transcoder.py .
```

## Reference results (3000 blocks per format, seed 12345)

| Path | Quality |
|---|---|
| DXT1 clean -> ASTC 4x4 | PSNR 52.7 dB, max err 2/255 (near lossless) |
| DXT1 punchthrough -> ASTC 4x4 dual-plane | alpha exactly binary (0/255), visible RGB 19.5 dB (random data; better on real foliage) |
| DXT4_5 (BC3) -> ETC2 RGBA8 | realistic data: RGB 32.5-45.4 dB, alpha 35-45 dB |
| DXT2_3 (BC2) -> ETC2 RGBA8 | comparable to BC3 |
| DXT5A (BC4) -> EAC R11 | PSNR 40.7 dB, max err 35/255 |
| DXN (BC5) -> EAC RG11 | PSNR 36.2 dB per channel |

Random-noise blocks are the pathological worst case for the lossy ETC2
family (the format's ceiling, not an encoder defect); real texture content
(gradients, two-tone, constant) measures in the 32-45 dB band.
