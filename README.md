# Dante's Inferno - Xbox 360 to PC Port (ReXGlue)

<p align="center">
  <img src="assets/fan_artwork.png" alt="Dante's Inferno - Fan Artwork" width="256" />
</p>

<p align="center">
  <em>Fan artwork by <a href="https://www.deviantart.com/pooterman">POOTERMAN</a> (<a href="https://github.com/florinp93/hells-gate-recomp/issues/12">#12</a>)</em>
</p>

A static recompilation port of **Dante's Inferno** (Xbox 360) to native PC and
**Android**, built with the [ReXGlue SDK](https://github.com/rexglue/rexglue-sdk).

ReXGlue converts Xbox 360 PowerPC XEX executables into portable C++ that runs
natively on Windows (D3D12), Linux (Vulkan) and Android (Vulkan, arm64-v8a) -
no emulation, no JIT at runtime.

> **Android port** by deivid22srk — see the [Android section](#android-port-arm64-v8a)
> below. Upstream PC port by [florinp93](https://github.com/florinp93/hells-gate-recomp).

<p align="center">
  <a href="https://ko-fi.com/zerkiller">
    <img src="https://img.shields.io/badge/Ko--Fi-Buy%20me%20a%20coffee-FF5E5B?style=for-the-badge&logo=ko-fi&logoColor=white" alt="Ko-fi" />
  </a>
  <a href="https://discord.gg/mjGfv7ysG8">
    <img src="https://img.shields.io/badge/Discord-Join%20the%20server-5865F2?style=for-the-badge&logo=discord&logoColor=white" alt="Discord" />
  </a>
  <a href="https://github.com/florinp93">
    <img src="https://img.shields.io/badge/Other-Projects-0AB4F5?style=for-the-badge&logo=github&logoColor=white" alt="Other Projects" />
  </a>
</p>

## 🤖 AI Usage Disclosure

Transparency and integrity are important to this project. Artificial Intelligence (AI) tools were utilized as part of the development and maintenance workflow, strictly serving as an assistant to handle repetitive, time-consuming, and low-level tasks. 

### How AI Was Used:
* **Documentation:** Generating initial drafts, organizing notes, and structuring documentation to keep project progress up to date.
* **Research & Exploration:** Investigating APIs, syntax references, and conceptual troubleshooting.
* **Git Workflows:** Assisting with routine commit descriptions, repository maintenance tasks, and boilerplate structuring.

### Human Oversight:
While AI accelerated the auxiliary workflow, all core architectural decisions, advanced problem-solving, code implementation, and final reviews were entirely human-driven. The AI served to eliminate friction, allowing focus on high-level logic and feature development.

## Project layout

```
.
├── CMakeLists.txt                 # Build config (SDK-managed, regenerate w/ rexglue init --force)
├── CMakePresets.json              # Platform build presets (SDK-managed)
├── dantes_inferno_manifest.toml   # ReXGlue project manifest (SDK-managed)
├── generated/
│   ├── rexglue.cmake              # SDK boilerplate (auto-generated, DO NOT EDIT)
│   └── default/                   # codegen output (gitignored, built on demand)
├── src/
│   ├── main.cpp                   # App entry point (SDK-managed)
│   └── dantes_inferno_app.h       # App class - override hooks here (user-owned)
├── game/                          # Extracted Xbox 360 game files (gitignored)
│   └── default.xex                #   <- entrypoint XEX goes here
├── metadata/                      # Achievement icons / embedded metadata
├── thirdparty/
│   └── rexglue-sdk/               # ReXGlue SDK (cloned via setup script, gitignored)
├── docs/
│   └── rexglue_notes.md           # ReXGlue workflow & command reference
├── setup.ps1 / setup.sh           # Clone SDK + init submodules
└── .gitignore
```

## Prerequisites

- **Windows 10/11 x64** (this project targets Windows/D3D12)
- **Clang 18+** (LLVM/Clang)
- **CMake 3.25+**
- **Ninja** build system
- **Visual Studio 2022** (for the Windows SDK / D3D12 headers)

## Getting started

### 1. Set up the SDK

```powershell
.\setup.ps1
```

This clones the ReXGlue SDK (pinned to `v0.10.0`) into `thirdparty/rexglue-sdk`
and initializes its submodules.

### 2. Provide the game files

Extract your ripped Xbox 360 ISO into `game/`. The entrypoint executable must be
at `game/default.xex` (the path set in `dantes_inferno_manifest.toml`). Keep the
original directory layout for all other assets.

> **Do not commit anything under `game/`** - it contains copyrighted assets used
> locally for recompilation only.

### 3. Build the SDK CLI (one time)

```powershell
cmake --preset win-amd64-release -DREXSDK_DIR=thirdparty\rexglue-sdk
cmake --build out\build\win-amd64-release --target rexglue
```

Add the built `rexglue.exe` to your PATH (it lives under
`thirdparty\rexglue-sdk\out\win-amd64\Release\`).

### 4. Regenerate SDK-managed files

Once `game/default.xex` exists, regenerate the SDK-managed scaffolding so it
carries the exact version/build stamp:

```powershell
rexglue init --force --project_name dantes_inferno --project_root . --xex_path game\default.xex --game_root game
```

### 5. Configure & build the port

```powershell
cmake --preset win-amd64-release -DREXSDK_DIR=thirdparty\rexglue-sdk
cmake --build out\build\win-amd64-release
```

The build automatically runs `rexglue codegen` (translating the XEX to C++) the
first time and whenever inputs change. Output: `out\win-amd64\Release\dantes_inferno.exe`.

### 6. Run

```powershell
.\out\win-amd64\Release\dantes_inferno.exe
# Useful flags:
#   --log_level=trace     verbose logging
#   --log_file=run.log    write logs to file
```

## Customizing the port

Override virtual hooks in `src/dantes_inferno_app.h` (e.g. `OnPostSetup`,
`OnCreateDialogs`, `OnConfigurePaths`). That file is **user-owned** and
preserved across `rexglue init` / `rexglue migrate`. See
`docs/rexglue_notes.md` for the full hook list and workflow reference.

## Android port (arm64-v8a)

This repository adds a full Android (NDK + Gradle) port on top of the upstream
PC scaffolding: SDL3 windowing/audio/input over the Android video driver, the
Vulkan backend for mobile GPUs, SAF-based game folder provisioning, and the
necessary ReXGlue SDK patches (Android platform branches, ARM64 memory-ordering
fences for the recompiled code, dynamic physical-memory offset, GPU plugin
loading from `nativeLibraryDir`).

### What you need

| Component | Required for | Notes |
|-----------|--------------|-------|
| `default.xex` + extracted ISO content | build **and** runtime | Your own dump. **Never commit, never upload.** |
| Android SDK + NDK 27.2.12479018 | local builds | CI uses the runner-provided SDK |
| JDK 17+ | Gradle | |
| clang ≥ 18, cmake ≥ 3.25, ninja | host codegen step | The `rexglue` CLI runs on the host |

The **`default.xex` is required twice**: at build time (the `rexglue codegen`
host tool translates the PowerPC code into C++ that is compiled into the APK)
and at runtime (the loaded image supplies sections/imports). The rest of the
extracted ISO (archives, media) is only needed at runtime, on the device.
Because the XEX is copyrighted code, GitHub Actions **cannot** build the
playable APK: CI builds a *framework APK* (full runtime, stub game code) that
prompts for the game files when opened.

### Build the playable APK locally

```bash
# 1. SDK + patches (clones ReXGlue v0.10.0 + submodules, applies patches)
./scripts/setup-android.sh

# 2. Place YOUR extracted game files in game/ (gitignored, never commit):
#    game/default.xex  +  game/ (rest of the ISO content)

# 3. Host codegen: builds the rexglue CLI, translates the XEX, patches output
./scripts/host-codegen.sh

# 4. Gradle build (Android SDK/NDK required)
./scripts/build-android.sh debug

adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

### On the device

1. Open the app — the setup screen asks for the game folder (SAF picker). Pick
   the folder that contains `default.xex` and the game archives.
2. If the folder is on primary storage it is read in place; otherwise the app
   copies it to internal storage (needs free space equal to the game size).
3. Connect a gamepad (Bluetooth/USB) — the game is designed for a controller.
   Logs land in `Android/data/com.deivid22srk.hellsgate/files/logs/`.

### Legal

The Android port carries only scaffolding, build configuration and the SDK
patches. `game/` and the resulting APK with recompiled code are yours alone:
nothing copyrighted is committed or distributed by this repository.

## License

This repository contains only port scaffolding and configuration. The ReXGlue
SDK is licensed under the BSD 3-Clause License (see `thirdparty/rexglue-sdk/`).
Dante's Inferno and all game assets are property of their respective rights
holders; nothing under `game/` is distributed here.
