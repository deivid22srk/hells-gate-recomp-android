# Android performance investigation — ~1 FPS root cause analysis (2026-09)

Investigation of the ~1 FPS frame rate reported for the Android (arm64-v8a,
Vulkan, Adreno 619) port, based on the `dantes_inferno.log` capture
(38,104 lines, 07:40:01 → 07:49:40, ~9.6 minutes) and a full read of the
ReXGlue SDK v0.10.0 runtime as patched by this repository.

All fixes ship in commit
`perf(Android): fix ~1 FPS - fault-path costs, per-frame full re-upload, lock serialization`
on branch `perf/android-fault-path-and-frame-upload`.

## Measured evidence from the log

| Signal | Value |
|---|---|
| Access violations (SIGSEGV handled by MMIOHandler) | **37,836** in 9.6 min, sustained ~80/s (median gap 2 ms) |
| Faulting thread | t30982 — the guest main thread (100% of faults) |
| Host PCs covering 68% of faults | 0x744E2DDE0C (13,006), 0x744DE272F4 (12,781), 0x744E17A570 (4,794), 0x744F68EA5C (3,536) |
| Non-fault log volume 07:43→07:49 | zero lines (only faults) — the loop is fault-dominated |
| Vulkan pipelines created | 100, all before 07:42:59 → **not** the sustained bottleneck |
| Texture formats | every BC (DXT1/2/3/4/5, DXN, DXT5A) fell back to uncompressed (Adreno 619 driver lacks filterable BC) |
| Device | Adreno 619, Qualcomm driver 0x80212000, 1600x720 swapchain, presentation mode 1 (FIFO) |

Fault-rate math: 37,836 faults / 576 s ≈ 66/s ≈ 80 faults per 1-FPS frame —
the faults are the *visible* symptom; the wall-clock cost sits in the cascade
each fault triggers and in the per-frame re-upload described below.

## Root causes (ordered by measured/estimated impact)

### 1. `clear_memory_page_state=true` — full working-set re-upload every frame (critical)
`SharedMemory::SetSystemPageBlocksValidWithGpuDataWritten()` (called at frame
end under this SDK default) resets the `valid` bit of **every CPU-uploaded
page** (`system_page_flags_valid_ = system_page_flags_valid_and_gpu_written_`),
forcing the next frame to re-copy the whole vertex/index/texture working set
host → staging → the 512 MB GPU buffer, plus re-arming page protection (RO)
across 3 heap aliases per range. On LPDDR4x shared with a weak CPU, this alone
can consume several hundred ms per frame.
**Fix:** app launches with `--clear_memory_page_state=false` (hot-reloadable
cvar). CPU-side coherency is still enforced by the write-watch mechanism:
uploads re-arm protection, CPU writes fault → invalidate → re-upload.

### 2. Fault path under the global critical region (critical)
Every write-watch fault did, all under the process-wide recursive mutex:
1. `QueryProtect()` → **parses the entire `/proc/self/maps`** (seq_file
   regeneration of hundreds of VMAs; 50 µs–1 ms) as a race re-check;
2. **unconditional `REXLOG_ERROR`** → fmt::format + logcat socket + FUSE file
   write with flush-on-info (0.3–1 ms) — 37,836 lines in the captured session;
3. invalidation callbacks (whole-texture invalidation + re-upload scheduling)
   and `mprotect(RW)` runs (up to 4 MB excess unwatching).
**Fix:** cached `/proc/self/maps` snapshot (invalidated by
`Protect`/`AllocFixed`/`DeallocFixed`; binary-search unit-tested) + fault-log
dedup (first occurrence per host PC, aggregates every 1,000 faults).

### 3. Global lock held during guest interrupt callbacks (critical for stalls)
`FunctionDispatcher::ExecuteInterrupt` acquired the global critical region for
the **entire guest vblank callback** (60 Hz on the vsync thread, PM4_INTERRUPT
on the GPU thread). The main thread needs the same lock for every fault
(~80/s) and for kernel-object refcounts; audio submissions take it too.
**Fix:** interrupts serialize against each other via a dedicated
`interrupt_dispatch_mutex_`; guest callback code runs without the global lock
(everything it touches — kernel objects, page watches, memory protection —
acquires the lock itself).

### 4. Conservative unresolved-memexport path (conditional, now measurable)
When a draw uses a memexport-capable shader but stream constants do not
resolve to ranges, the Vulkan command processor requests the **full 512 MB
range**, inserts a whole-buffer barrier, and afterwards
`RangeWrittenByGpu(0, 512MB)` fires global texture invalidation and re-protects
131k pages. Upstream logs this silently. The captured log cannot prove whether
Dante's Inferno hits it, so the path now emits a throttled WARN every 60
occurrences — the next device log will be conclusive.

### 5. BC texture fallback → RGBA8 (high, GPU-side)
All BC formats fall back on this Adreno driver (`kLinearFilterFeatures`
missing), multiplying texture memory and upload bandwidth 4× and adding a
compute decode pass on every invalidation. The fallback reason (exact missing
`VkFormatFeatureFlags` bits) is now logged once per format so a driver-side
fix (or ETC2/ASTC transcode) can be evaluated with data.

### 6. Secondary
- CommandProcessor worker spun `sched_yield` ×500 before sleeping when the
  ring was empty — steals a big core from the guest on 8-core SoCs; lowered
  to 64.
- `LogConfig::flush_level` default `info` → `warn` (stops one synchronous
  FUSE flush per info line on the game thread).
- ThinLTO (`CMAKE_INTERPROCEDURAL_OPTIMIZATION`) enabled for the whole
  Release build; the guest monolith cross-inlines runtime memory helpers.

## Not the bottleneck (verified)
- Build flags: everything compiles `-O3 -DNDEBUG` in both Gradle variants
  (the user-supplied `-DCMAKE_BUILD_TYPE=Release` overrides the AGP-injected
  Debug tag); Tracy/perf counters compiled out; no libc++ hardening.
- Guest dispatch: O(1) function table, per-function TUs ≤1 MB, PCH.
- Fibers: Android swapcontext is the custom aarch64 asm path without
  `sigprocmask` (~40 instructions, no syscalls).
- Guest clock: 50 MHz scalar 1.0, vblank 60 Hz — real time, not slowed.
- VdSwap/resolve path: 100% GPU (compute EDRAM→shared memory + tiled decode);
  no software 720p copies.

## Results & how to verify on device
CI build with all fixes:
`run 33874595791` (branch `perf/android-fault-path-and-frame-upload`), job
green, artifact `dantes-inferno-android-debug`.

On-device checks for the next session:
1. Frame rate (expect the dominant per-frame re-upload and fault stalls gone;
   fault count per frame should drop from ~80 to the game's genuine dynamic
   writes only).
2. New WARN lines: `Draw #N ... full 512 MiB shared memory range` (memexport
   conservative path incidence) and `Texture format k_DXTx falling back ...
   missing format feature bits` (BC availability) — these decide whether
   further GPU-side work (memexport range tracking / BC or ASTC path) pays
   off.
3. `Access violations so far: N total, M distinct faulting PCs` aggregates
   (every 1,000 faults) replace the previous per-fault spam.
