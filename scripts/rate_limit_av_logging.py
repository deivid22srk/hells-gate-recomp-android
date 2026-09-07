#!/usr/bin/env python3
"""Rate-limit the guest access-violation diagnostic logging carried in
patches/sdk/rexglue-sdk-v0.10.0.patch.

Why: the carried upstream patch logs REXLOG_ERROR per handled guest access
violation. Dante's Inferno produces tens of thousands of these per session
(37,836 in the user's 9.6-minute log; sustained 50-100/s, all concentrated on
guest threads). Each log line is a synchronous logcat socket write plus a FUSE
file-sink write executed on the faulting thread inside the signal handling
path, measurably amplifying every fault's cost (inter-fault deltas on the hot
guest thread show 1-3 ms bursts).

Fix: keep the first event and one line per 512 events (running total), keep
the rare null-pointer-fault detail, keep the Windows-only module-base lookup
sampled with the same rate. The replacement block has exactly the same number
of '+' lines as the original so every hunk header in the patch stays valid.

The block being replaced spans patch lines 2917..2946 (1-indexed), inside the
src/system/mmio_handler.cpp hunk of MMIOHandler::ExceptionCallback.
"""

import io
import sys

PATCH = "patches/sdk/rexglue-sdk-v0.10.0.patch"

START_ANCHOR = "+      auto rip = ex->pc();"
END_ANCHOR = "+#endif"

NEW_BLOCK = """\
+      auto rip = ex->pc();
+      // Rate-limited fault diagnostic. Guest code faults routinely on
+      // write-watched pages (tens of thousands of events per session on
+      // Dante's Inferno), and a synchronous log write per fault is very
+      // expensive on Android (logcat socket + FUSE sink on the faulting
+      // thread inside the signal path). Report the first event, then one
+      // line per 512 events with the running total.
+      static uint64_t av_total = 0;
+      ++av_total;
+      bool is_null_ptr_fault = (fault_guest_virtual_address < 0x100000u);
+      if (is_null_ptr_fault) {
+        REXLOG_ERROR("Null-pointer {} at guest 0x{:08X}",
+                    is_write ? "write" : "read", fault_guest_virtual_address);
+      }
+      if (av_total == 1 || (av_total & 0x1FF) == 0) {
+        REXLOG_ERROR("Access violation faulting PC: 0x{:016X} (event #{})",
+                    (uint64_t)rip, av_total);
+      }
+#if REX_PLATFORM_WIN32
+      // Windows-only module/return-address diagnostics, sampled together
+      // with the rate-limited log line above (per-fault capture is too hot).
+      if (av_total == 1 || (av_total & 0x1FF) == 0) {
+      HMODULE hMod = nullptr;
+      if (GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
+                             (LPCWSTR)rip, &hMod) && hMod) {
+        auto base = reinterpret_cast<uintptr_t>(hMod);
+        REXLOG_ERROR("  module base: 0x{:016X} RVA: 0x{:X}", (uint64_t)base, (uint32_t)((uintptr_t)rip - base));
+      }
+      }
+#endif\
"""


def main() -> int:
    with io.open(PATCH, "r", encoding="utf-8", newline="") as f:
        lines = f.read().split("\n")

    # Locate the start anchor (only occurrence is inside the mmio_handler.cpp
    # hunk of ExceptionCallback).
    starts = [i for i, l in enumerate(lines) if l == START_ANCHOR]
    if len(starts) != 1:
        print(f"ERROR: expected exactly one {START_ANCHOR!r} anchor, found {len(starts)}")
        return 1
    start = starts[0]

    # The block ends at the first '+#endif' after the start.
    end = None
    for i in range(start, min(start + 60, len(lines))):
        if lines[i] == END_ANCHOR:
            end = i
            break
    if end is None:
        print("ERROR: '+#endif' terminator not found after the start anchor")
        return 1

    old_len = end - start + 1
    new_block = NEW_BLOCK.split("\n")
    if old_len != len(new_block):
        print(f"ERROR: replacement must keep line count: old={old_len} new={len(new_block)}")
        return 1

    # Sanity: confirm the old block looks like the expected diagnostic block.
    joined = "\n".join(lines[start : end + 1])
    for expected in (
        "Access violation faulting PC",
        "REX_PLATFORM_WIN32",
        "CaptureStackBackTrace",
    ):
        if expected not in joined:
            print(f"ERROR: old block does not look as expected (missing {expected!r})")
            return 1

    lines[start : end + 1] = new_block

    with io.open(PATCH, "w", encoding="utf-8", newline="") as f:
        f.write("\n".join(lines))

    print(f"OK: replaced patch lines {start + 1}..{end + 1} "
          f"({old_len} lines -> {len(new_block)} lines, hunk headers unchanged)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
