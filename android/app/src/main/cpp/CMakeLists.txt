cmake_minimum_required(VERSION 3.25)
project(dantes_inferno_android LANGUAGES C CXX ASM)

# --- Paths handed over from Gradle (with sane fallbacks for direct CMake use) --
set(REX_PROJECT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." CACHE PATH
    "hells-gate-recomp-android repository root")
set(REXSDK_DIR "${REX_PROJECT_ROOT}/thirdparty/rexglue-sdk" CACHE PATH
    "ReXGlue SDK tree (populated by scripts/setup-android.sh)")
set(REX_APP_NAME "dantes_inferno" CACHE STRING "Recompiled app identifier")

if(NOT EXISTS "${REXSDK_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "ReXGlue SDK not found at ${REXSDK_DIR}\n"
        "Run scripts/setup-android.sh from the repository root first.")
endif()

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# The profiler client ships as a shared library that has no place in an APK.
set(REXGLUE_ENABLE_TRACY OFF CACHE BOOL "" FORCE)
set(REXGLUE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
# Vulkan is the only graphics backend on Android.
set(REXGLUE_USE_VULKAN ON CACHE BOOL "" FORCE)
set(REXGLUE_USE_D3D12 OFF CACHE BOOL "" FORCE)

#--- Link-time optimization (ThinLTO) ------------------------------------------
# The recompiled guest code is a monolith of small functions calling into the
# runtime's memory access helpers on every guest load/store; cross-module
# inlining of those helpers (plus dead-code stripping of the huge SDK surface)
# is worth 5-15% on recompilation workloads. NDK r27 links with lld, which
# consumes ThinLTO bitcode natively. Cost: slower builds and more link RAM on
# CI. Applied to every target defined after this point (SDK, recompiled code,
# app) for Release builds only.
if(CMAKE_BUILD_TYPE STREQUAL "Release")
  set(CMAKE_INTERPROCEDURAL_OPTIMIZATION ON)
endif()

#--- SDK (patched tree) --------------------------------------------------------
add_subdirectory("${REXSDK_DIR}" rexglue-sdk)

#--- Generated recompiled code --------------------------------------------------
set(REX_GEN_DIR "${REX_PROJECT_ROOT}/generated/default")
if(NOT EXISTS "${REX_GEN_DIR}/sources.cmake")
    message(FATAL_ERROR
        "generated/default/sources.cmake is missing.\n"
        "Run ./scripts/host-codegen.sh first (needs game/default.xex - see README)")
endif()
include("${REX_GEN_DIR}/sources.cmake")

# rexglue bakes absolute paths into sources.cmake (codegen-time
# CMAKE_CURRENT_LIST_DIR); normalize in case a relative form ever appears.
set(REX_GEN_SOURCES "")
foreach(src ${GENERATED_SOURCES})
    if(IS_ABSOLUTE "${src}")
        list(APPEND REX_GEN_SOURCES "${src}")
    else()
        list(APPEND REX_GEN_SOURCES "${REX_GEN_DIR}/${src}")
    endif()
endforeach()

add_library(recomp STATIC ${REX_GEN_SOURCES})
target_include_directories(recomp PRIVATE
    "${REX_PROJECT_ROOT}"
    "${REX_PROJECT_ROOT}/src"
    "${REX_GEN_DIR}"
)
# Mirror the SDK's own generated-code settings (see generated/rexglue.cmake on
# desktop): PCH + line-tables-only debug info for incremental rebuild speed.
target_precompile_headers(recomp PRIVATE "${REX_GEN_DIR}/${REX_APP_NAME}_pch.h")
target_compile_options(recomp PRIVATE
    $<$<CXX_COMPILER_ID:Clang,AppleClang>:-gline-tables-only>
    -Wno-unused-variable
)
target_link_libraries(recomp PUBLIC rex::runtime rexui)

#--- Android app library (loaded by SDLActivity via android.app.lib_name) ------
add_library(main SHARED
    android_main.cpp
    "${REX_PROJECT_ROOT}/src/main.cpp"
    "${REXSDK_DIR}/src/ui/rex_app.cpp"
)
target_include_directories(main PRIVATE
    "${REX_PROJECT_ROOT}"
    "${REX_PROJECT_ROOT}/src"
    "${REX_GEN_DIR}"
    "${REXSDK_DIR}/include"
    "${REXSDK_DIR}/src/ui"
)
target_compile_options(main PRIVATE -Wno-unused-variable)
# Same spelling/scope as the SDK root CMakeLists (add_compile_definitions is
# directory-scoped and does NOT reach targets created by this CMakeLists).
# rex_app.cpp expands REXGLUE_BUILD_TITLE -> REXGLUE_BUILD_CONFIG; without
# this the macro expands to a bare identifier and fails to parse.
target_compile_definitions(main PRIVATE REXGLUE_BUILD_CONFIG="$<CONFIG>")
# rexui is linked here on purpose: it carries the imgui include dirs
# (imgui itself is PRIVATE inside rex::runtime, so it does not propagate)
# needed by <rex/ui/style.h> pulled in through rex_app.h.
target_link_libraries(main PRIVATE recomp rex::runtime rexui)
# The runtime resolves PPCImageConfig / PPCFuncMappings and the app creator
# registration from this library.
target_link_options(main PRIVATE -Wl,--export-dynamic)

# The GPU emulation plugin (librexgpu-xenos.so) is runtime-loaded by the SDK's
# rex::system::LoadGpuPlugin via dlopen from the app's nativeLibraryDir - it
# must never be linked into main, only built and packaged. AGP packages every
# shared library target of this CMake project into the APK, and the SDK's
# CMAKE_BUILD_TYPE=Release config leaves the file name unpostfixed, matching
# the loader's REXGLUE_BUILD_CONFIG-based name (useLegacyPackaging=true in
# build.gradle.kts extracts it next to libmain.so on install).
add_dependencies(main rexgpu-xenos)

# Keep the output names stable for the GPU plugin loader (lib prefix already
# added by CMake for SHARED libs).
set_target_properties(main PROPERTIES
    CXX_VISIBILITY_PRESET default
    VISIBILITY_INLINES_HIDDEN OFF
)
