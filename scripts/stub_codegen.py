#!/usr/bin/env python3
"""stub_codegen.py - emit a placeholder generated/default/ tree for framework
builds (no game XEX required).

The framework APK boots the full ReXGlue runtime (SDL3 video, Vulkan, audio,
input) but carries no recompiled game code and never launches a module: the
Android layer checks for the game files first and shows a dialog explaining
what to copy where.

This script renders the SDK's real codegen templates so the stub stays
structurally identical to genuine codegen output. Requires setup-android.sh
to have populated thirdparty/rexglue-sdk (the templates live there).

Usage:  python3 scripts/stub_codegen.py [--project dantes_inferno]
"""

import argparse
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

# Stub constants: only consumed if a module were launched, which the framework
# APK never does. They mirror the shape of real XEX-derived values.
STUB_IMAGE_BASE = 0x82000000
STUB_IMAGE_SIZE = 0x00200000
STUB_CODE_BASE = 0x82A00000
STUB_CODE_SIZE = 0x00100000
STUB_THUNK_RESERVE = 0x00100000


def render_template(tpl: str, project: str, out_dir: Path, templates: Path) -> str:
    """Minimal jinja-subset renderer covering the constructs used by the
    ReXGlue codegen templates (flag blocks, loops, variable substitution)."""
    s = tpl
    # Nested template includes (pch pulls in _indirect_call.inja).
    while True:
        m = re.search(r'\{% include "codegen/([\w.]+)" %\}', s)
        if not m:
            break
        inc = render_template(
            (templates / m.group(1)).read_text(encoding="utf-8"), project, out_dir, templates
        )
        s = s[: m.start()] + inc + s[m.end() :]
    # Flag-gated define blocks (all stub flags off).
    s = re.sub(r"\{% if config_flags\.\w+ %\}(.*?)\{% endif %\}", "", s, flags=re.S)
    # Optional DLL-module blocks.
    s = re.sub(
        r"\{% if has_dll_modules and not is_dll %\}(.*?)\{% endif %\}", "", s, flags=re.S
    )
    # Inline if/else (rexcrt_heap -> 0).
    s = re.sub(
        r"\{% if rexcrt_heap %\}(.*?)\{% else %\}(.*?)\{% endif %\}", r"\2", s, flags=re.S
    )
    s = re.sub(r"\{% if is_dll %\}(.*?)\{% else %\}(.*?)\{% endif %\}", r"\2", s, flags=re.S)
    # Function loops: stub registers nothing.
    s = re.sub(r"\{% for fn in functions %\}(.*?)\{% endfor %\}", "", s, flags=re.S)
    s = re.sub(r"\{% for mod in dll_modules %\}(.*?)\{% endfor %\}", "", s, flags=re.S)

    # Codegen policy flags: all defaults off in the stub (never launched).
    s = re.sub(r"\{\{ config_flags\.\w+ \}\}", "0", s)

    s = s.replace("{{ project }}", project)
    # rexglue bakes the codegen-time CMAKE_CURRENT_LIST_DIR (the output dir)
    # into sources.cmake; render the absolute output dir the same way.
    s = s.replace('{{ cmake_var("CMAKE_CURRENT_LIST_DIR") }}', str(out_dir.as_posix()))
    s = s.replace("{{ image_base }}", f"0x{STUB_IMAGE_BASE:X}")
    s = s.replace("{{ image_size }}", f"0x{STUB_IMAGE_SIZE:X}")
    s = s.replace("{{ code_base }}", f"0x{STUB_CODE_BASE:X}")
    s = s.replace("{{ code_size }}", f"0x{STUB_CODE_SIZE:X}")
    s = s.replace("{{ thunk_reserve_size }}", f"0x{STUB_THUNK_RESERVE:X}")

    # Generic fallbacks for any remaining if/else/endif blocks (keep the else
    # branch when present, drop otherwise). Non-greedy; templates never nest.
    while True:
        new = re.sub(r"\{% if [^%]*? %\}(.*?)\{% else %\}(.*?)\{% endif %\}", r"\2", s, flags=re.S)
        new = re.sub(r"\{% if [^%]*? %\}(.*?)\{% endif %\}", "", new, flags=re.S)
        new = re.sub(r"\{%\s*for [^%]*?%\}(.*?)\{%\s*endfor\s*%\}", "", new, flags=re.S)
        if new == s:
            break
        s = new

    if "{{" in s or "{%" in s:
        # Show the leftovers so failures are diagnosable instead of emitting
        # broken C++.
        leftovers = sorted(set(re.findall(r"\{[{%].*?[%}]\}", s)))[:8]
        raise RuntimeError(f"unhandled template constructs: {leftovers}")
    return s


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", default="dantes_inferno")
    args = parser.parse_args()
    project = args.project

    templates = REPO_ROOT / "thirdparty" / "rexglue-sdk" / "resources" / "templates" / "codegen"
    if not templates.is_dir():
        print(f"error: {templates} not found - run scripts/setup-android.sh first", file=sys.stderr)
        return 1

    out_dir = REPO_ROOT / "generated" / "default"
    out_dir.mkdir(parents=True, exist_ok=True)

    def render(name: str) -> str:
        return render_template(
            (templates / name).read_text(encoding="utf-8"), project, out_dir, templates
        )

    (out_dir / f"{project}_pch.h").write_text(render("pch_h.inja"), encoding="utf-8")
    (out_dir / f"{project}_funcs.h").write_text(render("funcs_h.inja"), encoding="utf-8")
    (out_dir / f"{project}_init.h").write_text(render("init_h.inja"), encoding="utf-8")
    (out_dir / f"{project}_init.cpp").write_text(render("init_cpp.inja"), encoding="utf-8")
    (out_dir / f"{project}_register.cpp").write_text(render("register_cpp.inja"), encoding="utf-8")

    (out_dir / "sources.cmake").write_text(
        render("sources_cmake.inja"), encoding="utf-8"
    )
    # Stamp + depfile so tooling that expects codegen markers sees a complete
    # tree (nothing re-runs codegen behind our backs).
    (out_dir / "codegen.build.stamp").write_text("stub\n", encoding="utf-8")
    (out_dir / "codegen.d").write_text("codegen.build.stamp: dantes_inferno_manifest.toml\n", encoding="utf-8")

    # Guard: sources.cmake must only reference the stub sources we emitted.
    sources = (out_dir / "sources.cmake").read_text(encoding="utf-8")
    for src in (f"{project}_init.cpp", f"{project}_register.cpp"):
        if src not in sources:
            print(f"warning: {src} not listed in rendered sources.cmake", file=sys.stderr)

    print(f"[stub-codegen] placeholder generated/default written ({project})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
