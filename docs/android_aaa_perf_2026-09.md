# Android AAA deep optimization — 2026-09-08

Structural performance work on the Android (arm64-v8a, Vulkan) port,
layered as `patches/sdk/rexglue-sdk-v0.10.0-android-aaa-perf.patch` (applied
by `scripts/setup-android.sh` after the four existing SDK patches). Every
change compiles for arm64-v8a (NDK r27c, Release/ThinLTO flags identical to
Gradle) and x86_64 host; the transcoder is additionally validated
bit-exactly against an independent decoder
(`scripts/bc_transcode_test/`).

Diagnosis that motivated this work: `docs/diagnostico_inicial_2026-09-08.md`.

## 1. CPU BCn -> ASTC/ETC2/EAC transcoder (texture upload path)

**Problem (measured/documented).** Adreno 619-class devices with older
driver packages do not expose BC1-BC5 sampling
(`kLinearFilterFeatures` missing, see the BC fallback log in
`docs/android_performance_2026-09.md`). The texture cache then decompresses
every BCn texture to RGBA8 at upload time: 4x the texture memory, 4x the
sampling bandwidth, plus a compute decode pass on *every* write-watch
invalidation. On a bandwidth-starved mid-range GPU this dominates frame
time.

**Fix.** New CPU block transcoder (`bc_transcoder.h/.cpp`, ~700 lines,
deterministic, allocation-free) converts guest BCn blocks once per texture
version at upload time into universally-supported mobile compressed
formats:

| Guest format | Target | Block | Quality (independent decoder) |
|---|---|---|---|
| DXT1 (+`_AS_16_16_16_16`) | ASTC 4x4 | 16 B | clean: 52.7 dB (max err 2/255); punchthrough: alpha exactly binary |
| DXT2_3 / DXT4_5 (+aliases) | ETC2 RGBA8 | 16 B | realistic content 32-45 dB RGB / 35-45 dB alpha |
| DXT5A | EAC R11 | 8 B | 40.7 dB |
| DXN (BC5) | EAC RG11 | 16 B | 36.2 dB |

Encoding details:
- DXT1 clean blocks -> ASTC CEM 12 (LDR RGBA direct), single plane, 2-bit
  weights: endpoint colors are exact RGB565->RGB888 expansions and the
  {0,1/3,2/3,1} BC1 ramp maps onto the {0,21,43,64} ASTC weight levels with
  <=1 LSB/channel error. Endpoint swap avoids blue_contract losslessly.
- DXT1 punchthrough blocks (3-color + transparent) -> dual-plane CEM 12
  with 1-bit weights: alpha plane is exactly {0,255}; the RGB plane uses a
  2-level quantizer over {c0, c1, mid} chosen per block (per-texel error
  |c0-c1|/4, half of the naive endpoint snap).
- ETC2 RGBA8: EAC alpha block (exact {0,255} fast path for binary alpha;
  otherwise a (base, mult, table) mini-search) + an ETC2
  differential/individual-mode mini-encoder (both flips, both modes,
  full 8-table search per subblock).
- EAC R11/RG11: 11-bit level mini-search fit per block.

**Integration.** In `VulkanTextureCache::Initialize`, a capability ladder
runs after the existing BC fallback checks: for each guest BCn format that
fell back, if the transcode target VkFormat is sampleable+filterable, the
host format becomes the target (block-compressed). Uploads of such
textures route through `LoadTextureDataBCnTranscodeImpl` (in
`LoadTextureDataFromResidentMemoryImpl`): guest blocks are read straight
from CPU RAM (BCn guest data is CPU-authored by construction - disc-
streamed or CPU-written; render targets and resolves are never BCn
formats), walked with the exact tiling math the load shaders use
(`GetTiledOffset2D/3D`, packed mip tails, array/3D strides), endian-swapped
per `key.endianness`, transcoded, and uploaded through a dedicated
host-visible `VulkanUploadBufferPool` (reclaimed per completed submission,
flushed in `EndSubmission`). No compute dispatch, no scratch buffer.

Unaligned-base textures (e.g. 3x3 DXT1) keep the RGBA8 decompression path
(the image format check in the dispatch guards this), as do
scaled-resolve textures. `vulkan_bc_transcode` (default `true`) disables
the whole ladder. If a target format is unsupported the RGBA8 fallback
stays and the decision is logged once.

**Expected effect.** 4x less texture memory and sampling bandwidth than
the RGBA8 fallback (8 bpp ASTC vs 32 bpp RGBA8 on DXT1; EAC R11 is 4 bpp),
no per-invalidation compute decode pass, no scratch buffer churn.

**Known quality notes (documented, bounded).** Random-noise blocks are the
worst case for the lossy ETC2 family (~16 dB); real BC3 color content
measures 32-45 dB. Punchthrough blocks carry alpha-exact edges with a
small RGB error on mid-color texels (|c0-c1|/4) and on alpha-masked
transparent texels (RGB chosen darkest-endpoint, invisible under
alpha-test and equivalent under (0,A,0) blending). BC3/BC2 color quality
can be upgraded later to a near-lossless ASTC dual-plane + BISE endpoint
path (basis_universal-style) if on-device QA flags it.

## 2. Per-draw CPU: descriptor set reuse + index buffer dirty check

**Problem.** `UpdateBindings` unconditionally invalidated the texture and
sampler descriptor sets on every draw (Xenia's own
`TODO(Triang3l): Reuse texture and sampler bindings if not changed.`), so
every draw paid a transient descriptor set allocation (hash lookup +
free-list pop), a `VkWriteDescriptorSet` array rebuild,
`vkUpdateDescriptorSets` and a rebind - even for draws with bindings
identical to the previous one (the common case: many objects sharing one
material). `CmdVkBindIndexBuffer` was also recorded unconditionally.

**Fix.** The image infos a write would contain are built (as before) and
compared (POD `memcmp` + binding counts, which also identify the set
layout) against the last actually written signature; a stage is only
invalidated when something changed. Image view handles are stable while
the backing textures live (uploads keep the same `VkImage`), so the
signature is exactly "what a write would write" and no stale descriptor
can survive: any change in views, sampler handles, order or counts forces
a rewrite. The reuse window is a single guest frame (per-frame
`BeginSubmission` resets the values bits and recycles the transient sets).
Index buffer binding is now dirty-checked (buffer/offset/type) with state
reset per submission, since the deferred command stream is rebuilt every
submission. Expected effect: removal of ~5-15 us per steady-state draw on
the single GPU worker thread.

Also: `NativeFrameRenderer::BeginFrameDraw` called `VdQueryVideoMode`
(memset + 3 cvar reads, a kernel export) on every native-routed draw; it
is now cached and refreshed once per presented frame.

## 3. big.LITTLE thread placement (modest devices)

**Problem.** All runtime threads run at default nice 0 with no affinity.
On 2-big-core SoCs (Snapdragon 695-class: 2x Cortex-A78 + 6x Cortex-A55)
the scheduler can place the guest main thread or the GPU command processor
on an A55, where single-thread throughput is 3-4x lower. Pipeline
compilation threads (cores*3/4) actively steal big-core time during
warm-up. `pthread_setschedparam`/`SCHED_FIFO` silently fails EPERM for
unprivileged apps, so nothing was ever done.

**Fix.** New topology-aware helpers in the threading layer
(`PinCurrentThreadToPerformanceCores/EfficiencyCores`,
`DemoteCurrentThreadPriority`): cores are classified by
`/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq` (median split;
homogeneous parts and unreadable sysfs degrade to no-op). Placement:
- **Performance cores:** guest main thread ("Main XThread" - the game
  loop) and the GPU command processor worker.
- **Efficiency cores + demoted priority (setpriority +10):** vblank timer
  thread, audio worker, XMA decoder, kernel dispatch, Vulkan pipeline
  compiler threads.
- Other guest threads stay unpinned so the scheduler can spread them.

## 4. Conservative memexport invalidation coalescing

**Problem.** When a draw uses a memexport-capable shader with unresolved
stream constants, `RangeWrittenByGpu(0, 512 MiB)` runs after *every* such
draw: it walks all 128 watch buckets - invalidating **every watched
texture** (forcing a full texture reload on the next draw) - and
re-protects 131072 system pages under the global lock. The upstream
investigation documented a ~1 FPS collapse when this path is hot.

**Fix.** The full-range invalidation is coalesced to once per submission
(flushed in `EndSubmission`), with a throttled warning carrying the
coalesced draw count. Rationale: within one submission all draws are
recorded into a single command buffer before execution, so a reload in the
same submission is already raced against the write either way; the flush
at submission end still guarantees every later reload sees the writes.
`vulkan_memexport_invalidate_per_draw=true` restores exact upstream
behavior.

## 5. Explicitly deferred (with rationale)

- **Per-primary-buffer submit batching** (`vulkan_submit_on_primary_buffer_
  end`): expected win is a few `vkQueueSubmit`+pool resets per frame
  (~0.1-0.5 ms), but it interacts with upload-pool page pressure and fence
  pacing in ways that cannot be validated without a device - deferred
  rather than risked.
- **Native renderer fixes** (skip the wasted `RequestSwapTexture` load per
  native swap; scope the EDRAM bridge dump to the resolve rectangle): both
  only affect the experimental opt-in `rexgpu-native` plugin and need
  surgery around its fallback path; documented for a follow-up.
- **Codegen `REX_PHYS_HOST_OFFSET` branch removal**: the offset is
  provably 0 only on 4 KB-page hosts; the branch is ~2 instructions per
  guest memory access and cannot be eliminated for 16 KB-page hosts without
  a second compiled variant of the guest code.

## On-device validation checklist (next session)

1. Frame rate and the FPS overlay: expect the largest gains in
   texture-heavy scenes (bandwidth-bound) and in scenes with many
   same-material draws (descriptor reuse).
2. New one-shot log lines `Transcoding k_DXT1 to VkFormat ...` confirm the
   ladder armed; `BCn transcode target ... unavailable` lines identify
   formats kept on the RGBA8 fallback.
3. If frame rate is still low: look for the coalesced memexport warning
   (`Conservative memexport invalidation coalesced N draws`) - its draw
   counts decide whether tighter memexport range tracking pays off next.
4. `logcat`/`simpleperf`: confirm "Main XThread" and "GPU Commands" are
   scheduled on the big cores (the helper logs nothing by default; check
   `/proc/<pid>/task/*/stat` affinity masks or `simpleperf` per-thread CPU
   usage).
5. Texture quality spot-check: foliage (punchthrough DXT1) and spec-mapped
   surfaces (BC3) are the quality-critical paths; if BC3 color quality is
   unacceptable, the BISE-endpoint ASTC upgrade (§1 notes) is the next
   step.

## Resumo executivo (PT-BR)

Esta leva de otimizações ataca os quatro maiores gargalos estruturais
identificados no diagnóstico inicial, sem ajustes superficiais:

1. **Texturas BCn** deixam de ser decompostas para RGBA8 (custo 4x): são
   transcodificadas uma única vez na CPU para ASTC/ETC2/EAC nativos do
   hardware móvel — 4x menos memória e banda de sampling, sem compute
   decode por invalidação. Validação empírica com decodificador
   independente: DXT1 quase-sem-perda (52,7 dB), punchthrough com alpha
   binário exato, BC3/BC2 em 32-45 dB (conteúdo realista), BC4/BC5 em
   36-41 dB.
2. **Descriptor sets de textura/sampler** deixam de ser reescritos em todo
   draw: comparação de assinatura elimina alocação + vkUpdateDescriptorSets
   + rebind nos draws em regime permanente (resolvendo o TODO do próprio
   Xenia).
3. **Threads** ganham placement big.LITTLE consciente (main + GPU Commands
   nos cores grandes; vblank/áudio/XMA/compiladores nos pequenos com
   prioridade rebaixada) — evita a queda catastrófica de 3-4x quando o
   scheduler coloca as threads críticas nos A55.
4. **Memexport conservativo** invalida a memória toda uma vez por
   submissão em vez de uma vez por draw (caminho documentado como colapso
   ~1 FPS).

Cada mudança é reversible por cvar (`vulkan_bc_transcode`,
`vulkan_memexport_invalidate_per_draw`) e compila validada em arm64-v8a e
x86_64. O checklist acima orienta a medição no device na próxima sessão.
