// Test vector generator + transcoder driver for the BCn->ASTC/ETC2/EAC
// empirical validation harness. Standalone (host) build.
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <random>

#include <rex/graphics/vulkan/bc_transcoder.h>

using namespace rex::graphics::vulkan::bc_transcode;

static uint64_t MakeDXT1Block(std::mt19937& rng, bool punchthrough) {
  uint16_t c0 = uint16_t(rng() & 0xFFFF);
  uint16_t c1 = uint16_t(rng() & 0xFFFF);
  if (punchthrough) {
    if (c0 > c1) std::swap(c0, c1);
  } else {
    if (c0 <= c1) {  // force 4-color mode
      if (c0 == c1) c1++;
      else std::swap(c0, c1);
    }
  }
  uint32_t indices = rng() & 0xFFFFFFFF;
  return uint64_t(c0) | (uint64_t(c1) << 16) | (uint64_t(indices) << 32);
}

static uint64_t MakeBC3AlphaBlock(std::mt19937& rng) {
  uint32_t a0 = rng() & 0xFF, a1 = rng() & 0xFF;
  uint64_t indices = 0;
  for (int i = 0; i < 16; ++i) indices |= uint64_t(rng() & 7) << (3 * i);
  return uint64_t(a0) | (uint64_t(a1) << 8) | (indices << 16);
}

static uint64_t MakeBC2AlphaBlock(std::mt19937& rng) {
  uint64_t v = 0;
  for (int i = 0; i < 16; ++i) v |= uint64_t(rng() & 0xF) << (4 * i);
  return v;
}

int main() {
  std::mt19937 rng(12345);
  const uint32_t kCount = 3000;

  // DXT1 -> ASTC 4x4
  {
    FILE* fin = fopen("blocks_dxt1.bin", "wb");
    FILE* fout = fopen("out_astc.bin", "wb");
    std::vector<uint8_t> out(16);
    for (uint32_t i = 0; i < kCount; ++i) {
      const bool punch = (i % 2) == 0;  // half punchthrough blocks
      const uint64_t block = MakeDXT1Block(rng, punch);
      uint8_t in_bytes[8];
      std::memcpy(in_bytes, &block, 8);
      fwrite(in_bytes, 8, 1, fin);
      TranscodeDXT1BlockToASTC(block, out.data());
      fwrite(out.data(), 16, 1, fout);
    }
    fclose(fin); fclose(fout);
  }
  // BC3 -> ETC2 RGBA8
  {
    FILE* fin = fopen("blocks_bc3.bin", "wb");
    FILE* fout = fopen("out_etc2a8.bin", "wb");
    std::vector<uint8_t> out(16);
    for (uint32_t i = 0; i < kCount; ++i) {
      const uint64_t rgb = MakeDXT1Block(rng, false);
      const uint64_t alpha = MakeBC3AlphaBlock(rng);
      uint8_t in_bytes[16];
      std::memcpy(in_bytes, &rgb, 8);
      std::memcpy(in_bytes + 8, &alpha, 8);
      fwrite(in_bytes, 16, 1, fin);
      TranscodeDXT45BlockToETC2RGBA(rgb, alpha, out.data());
      fwrite(out.data(), 16, 1, fout);
    }
    fclose(fin); fclose(fout);
  }
  // BC2 -> ETC2 RGBA8
  {
    FILE* fin = fopen("blocks_bc2.bin", "wb");
    FILE* fout = fopen("out_etc2a8_bc2.bin", "wb");
    std::vector<uint8_t> out(16);
    for (uint32_t i = 0; i < kCount; ++i) {
      const uint64_t rgb = MakeDXT1Block(rng, false);
      const uint64_t alpha = MakeBC2AlphaBlock(rng);
      uint8_t in_bytes[16];
      std::memcpy(in_bytes, &rgb, 8);
      std::memcpy(in_bytes + 8, &alpha, 8);
      fwrite(in_bytes, 16, 1, fin);
      TranscodeDXT23BlockToETC2RGBA(rgb, alpha, out.data());
      fwrite(out.data(), 16, 1, fout);
    }
    fclose(fin); fclose(fout);
  }
  // BC4 -> EAC R11
  {
    FILE* fin = fopen("blocks_bc4.bin", "wb");
    FILE* fout = fopen("out_eacr11.bin", "wb");
    std::vector<uint8_t> out(8);
    for (uint32_t i = 0; i < kCount; ++i) {
      const uint64_t block = MakeBC3AlphaBlock(rng);
      uint8_t in_bytes[8];
      std::memcpy(in_bytes, &block, 8);
      fwrite(in_bytes, 8, 1, fin);
      TranscodeDXT5ABlockToEACR11(block, out.data());
      fwrite(out.data(), 8, 1, fout);
    }
    fclose(fin); fclose(fout);
  }
  // BC5 -> EAC RG11
  {
    FILE* fin = fopen("blocks_bc5.bin", "wb");
    FILE* fout = fopen("out_eacrg11.bin", "wb");
    std::vector<uint8_t> out(16);
    for (uint32_t i = 0; i < kCount; ++i) {
      const uint64_t red = MakeBC3AlphaBlock(rng);
      const uint64_t green = MakeBC3AlphaBlock(rng);
      uint8_t in_bytes[16];
      std::memcpy(in_bytes, &red, 8);
      std::memcpy(in_bytes + 8, &green, 8);
      fwrite(in_bytes, 16, 1, fin);
      TranscodeDXNBlockToEACRG11(red, green, out.data());
      fwrite(out.data(), 16, 1, fout);
    }
    fclose(fin); fclose(fout);
  }
  printf("OK: test vectors generated\n");
  return 0;
}
