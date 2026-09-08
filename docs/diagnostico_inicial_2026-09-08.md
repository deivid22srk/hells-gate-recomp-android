# Diagnóstico Inicial — hells-gate-recomp-android (Dante's Inferno / ReXGlue)

Data: 2026-09-08 · Branch base: `main` @ 4cb60f2 · SDK: ReXGlue v0.10.0 (árvore Xenia adaptada)

## 1. Arquitetura

- **Recompilação estática**: XEX (PowerPC BE) → C++ portável via `rexglue codegen` (sem JIT em runtime). Código guest compilado no APK como `libmain.so` + `librecomp.a` via NDK r27c (arm64-v8a, clang, C++23).
- **Runtime derivado do Xenia**: kernel xboxkrnl/xam, `CommandProcessor` (PM4), `SharedMemory` (512MB físicos + EDRAM 10MB), texture cache, pipeline cache, presenter Vulkan.
- **GPU**: dois plugins empacotados — `librexgpu-xenos.so` (emulação Xenos completa; DEFAULT) e `librexgpu-native.so` (renderer ARM "Skate 3 Mobile": superfícies nativas + ponte EDRAM; toggle em renderer.txt, reset p/ xenos em v0.1.5).
- **Android**: SDL3 (windowing/áudio AAudio/input) + Java (SetupActivity SAF/All-Files-Access → game_root.txt; MainActivity=SDLActivity). JNI mínimo (JavaVM via SDL, openContentFd para mmap SAF — não usado no fluxo normal).
- **CI**: workflow `build.yml` — clang-19 host, codegen com XEX de repo privado, `assembleDebug` (nativo Release -O3 + ThinLTO), valida 3 .so no APK, publica APK jogável.

## 2. Dependências externas

ReXGlue SDK (BSD-3, patches locais em `patches/sdk/`), SDL3, FFmpeg (VP6/Bink), glslang/spirv-tools, simde (VMX), fmt, spdlog, imgui, VMA, vulkan-headers/loader, Tracy (OFF no Android).

## 3. Estado de build

- Setup (`scripts/setup-android.sh`) clona SDK v0.10.0 e aplica 4 patches — **validado localmente: aplicam limpo**.
- Host codegen CLI: **compilando localmente** com clang-19.1.7 (user-space) — ambiente replicado.
- `default.xex` obtido do repo privado do usuário (10.7MB, XEX válido EA-2255) — codegen real disponível localmente (nunca commitado).
- CI verde no último commit (run 33874595791 histórico; estado atual: "EDRAM resolve bridge" = jogo renderiza).

## 4. Gargalos identificados (evidência em código + docs de device)

### Já corrigidos pela sessão anterior (docs/android_performance_2026-09.md)
`clear_memory_page_state=false`, cache de /proc/self/maps, rate-limit de logs de fault, lock de interrupts fora do global region, sched_yield 500→64, ThinLTO. Resultado: saiu de ~1 FPS para jogo renderizando.

### SUSPEITOS REMANESCENTES (ordenados por impacto esperado)

| # | Frente | Evidência | Custo esperado |
|---|--------|-----------|----------------|
| G1 | **Fallback BCn→RGBA8** (Adreno 619 sem bits BC) | texture_cache.cpp:2456-2537: TODOS os BC caem para RGBA8/RG8/R8 decode-compute | Memória 4x, banda de sampling 4x, compute decode POR invalidação. "TODO(Triang3l): S3TC → ETC2" no próprio código |
| G2 | **Descriptor sets de textura/sampler reescritos POR DRAW** | command_processor.cpp:6693 `// TODO(Triang3l): Reuse texture and sampler bindings if not changed.` | ~5-15µs/draw: rebuild VkDescriptorImageInfo, alloc de transient set, vkUpdateDescriptorSets, rebind — em TODOS os draws |
| G3 | **Scheduling de threads** (2 big cores em SD695-class) | threading_posix.cpp: pthread_setschedparam SCHED_FIFO falha EPERM; zero setnice/affinity | Guest/GPU threads podem cair em cores A55 (3-4x mais lentos) — catastrófico em 8-core LITTLE |
| G4 | **Memexport conservativo** (se atingido) | command_processor.cpp:4132-4156: RequestRange(0,512MB)+barrier+RangeWrittenByGgpu(0,512MB) POR DRAW → FireWatches global + MakeRangeValid 131072 páginas + re-protect | Colapso ~1 FPS documentado; incidência INDEFINIDA (sem log de device pós-fix) |
| G5 | **Submits por primary-buffer-end** | `vulkan_submit_on_primary_buffer_end=true` default | N submits/frame (fence+pool reset+replay interpretado+queue submit) |
| G6 | **Path de presents (nativo)** | RequestSwapTexture load ANTES do check native (upload 720p desperdiçado/swap); dump EDRAM full-tile-box em vez do resolve rect; 3 cópias full-frame | GPU-side no renderer experimental |
| G7 | **Skew de endereço por acesso de memória do guest** | pch_h.inja: REX_PHYS_HOST_OFFSET = branch + load de global POR load/store (offset=0 em 4KB hosts) | ~2 instr por acesso (~3-5% CPU guest) |
| G8 | **I/O via FUSE** (game_root em /storage/emulated/0) | filesystem_posix.cpp pread em path FUSE; PopulateEntry walk recursivo no boot | Streaming de bigfile ~50-100MB/s vs direto; boot mais lento |

### Não-gargalos verificados
- Flags de build: -O3 -DNDEBUG + ThinLTO no nativo (Gradle força CMAKE_BUILD_TYPE=Release) — OK.
- Dispatch de funções guest: tabela O(1), TUs ≤1MB, PCH — OK.
- `emit_set_flush_mode`: stateful (emite só em transições FPU↔VMX) — OK.
- Fibers: swapcontext asm custom aarch64 (~40 instr, sem syscall) — OK.
- Barriers: já batched por SubmitBarriers (grupos divididos) — OK.
- Zero-copy total no path nativo (nenhum memcpy de pixels na CPU) — OK.

## 5. Plano de otimização (frentes de execução)

1. **Ciclo 1 (CPU por draw)**: descriptor-set reuse (G2) + dirty-check de index buffer + cache VdQueryVideoMode (G6-parcial).
2. **Ciclo 2 (GPU/banda)**: transcoder BCn→ASTC/ETC2/EAC em CPU no upload (G1) com ladder de formatos e testes de correção cruzados host-side.
3. **Ciclo 3 (sistema)**: affinity big-core + nice de threads (G3), submit batching Android (G5), memexport: cache de análise + coalescing de invalidação conservativa com cvar (G4).
4. **Ciclo 4 (polimento)**: renderer nativo (G6), micro-opts codegen (G7), I/O (G8), documentação + relatório final.

Validação: compilação host (clang-19) + alvo Android (NDK r27c local), codegen real com XEX, testes unitários do transcoder (decodificador independente via Python), CI `build.yml` no push.
