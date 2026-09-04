/**
 * hells-gate-recomp-android - Android native entry point.
 *
 * Bridges the ReXGlue runtime (SDL3 windowing, Vulkan presenter, recompiled
 * guest) to the Android activity lifecycle:
 *   - SDL3's SDL_main.h maps main() to SDL_main; org.libsdl.app.SDLActivity
 *     (Java) calls SDL_RunApp on a dedicated thread which lands here.
 *   - The game data root and log destination are resolved from the app's
 *     external files dir (written by SetupActivity after the SAF picker).
 *   - rex::SetAndroidApplicationContext() wires the JavaVM + nativeLibraryDir
 *     into the SDK's Android glue (thread naming, ASharedMemory, plugin
 *     loading), all resolved without app-side JNI callbacks.
 *
 * @added  deivid22srk, 2026 - Android port
 */

#include <SDL3/SDL_main.h>
#include <SDL3/SDL_system.h>

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <memory>
#include <string>
#include <vector>

#include <fmt/format.h>

#include <rex/cvar.h>
#include <rex/filesystem.h>
#include <rex/logging.h>
#include <rex/main_android.h>
#include <rex/memory.h>
#include <rex/platform.h>
#include <rex/thread.h>
#include <rex/ui/windowed_app.h>
#include <rex/ui/windowed_app_context_sdl.h>

#if REX_PLATFORM_ANDROID

namespace {

constexpr char kAppIdentifier[] = "dantes_inferno";
constexpr char kConfigFileName[] = "game_root.txt";
// User-visible folder on primary shared storage for logs (so they can be
// inspected with any file manager without rooting / adb). Writable only when
// the All-Files-Access grant is in place; falls back to the app's own
// external files dir otherwise.
constexpr char kSharedLogRoot[] = "/storage/emulated/0/DantesInferno/logs";

// Direct logcat output for failures that happen before logging is up.
#define ALOGE(...) \
  __android_log_print(ANDROID_LOG_ERROR, "dantes_inferno", __VA_ARGS__)

std::string ReadTrimmedFile(const std::string& path) {
  std::ifstream in(path, std::ios::binary);
  if (!in) {
    return {};
  }
  std::string line;
  std::getline(in, line);
  while (!line.empty() && (line.back() == '\r' || line.back() == ' ')) {
    line.pop_back();
  }
  return line;
}

// ApplicationInfo.nativeLibraryDir without JNI: the loader path of this very
// library IS the native library dir (libmain.so is packaged there).
std::string QueryNativeLibraryDir() {
  Dl_info info{};
  if (dladdr(reinterpret_cast<void*>(&QueryNativeLibraryDir), &info) &&
      info.dli_fname) {
    std::string path(info.dli_fname);
    const size_t slash = path.find_last_of('/');
    if (slash != std::string::npos) {
      return path.substr(0, slash);
    }
  }
  return {};
}

JavaVM* QueryJavaVm() {
  // Pull the JavaVM from SDL3's process JNIEnv. (Calling JNI_GetCreatedJavaVMs
  // directly would require linking against libart.so, which is not part of
  // the NDK's public surface - the standard workaround is exactly what SDL
  // already does for us here.)
  auto* env = static_cast<JNIEnv*>(SDL_GetAndroidJNIEnv());
  if (env) {
    JavaVM* vm = nullptr;
    if (env->GetJavaVM(&vm) == JNI_OK && vm) {
      return vm;
    }
  }
  return nullptr;
}

void PrepareStorageDirs(const std::string& external_dir, const std::string& game_root) {
  std::error_code ec;
  std::filesystem::create_directories(external_dir + "/logs", ec);
  std::filesystem::create_directories(external_dir + "/data", ec);
  if (!game_root.empty()) {
    std::filesystem::create_directories(game_root, ec);
  }
}

// Picks the log directory: shared storage first (user-visible), the app's
// external files dir as fallback (always writable). Probes writability so a
// directory we cannot actually write never wins.
std::string ResolveLogDir(const std::string& external_dir) {
  const std::string candidates[] = {
      kSharedLogRoot,
      external_dir + "/logs",
  };
  for (const auto& dir : candidates) {
    std::error_code ec;
    std::filesystem::create_directories(dir, ec);
    if (ec || !std::filesystem::is_directory(dir, ec)) {
      continue;
    }
    const std::string probe = dir + "/.write_test";
    {
      std::ofstream out(probe, std::ios::binary);
      if (!out) {
        continue;
      }
    }
    std::filesystem::remove(probe, ec);
    return dir;
  }
  return external_dir + "/logs";
}

// Startup sequence mirrored from the SDK's windowed_app_main_sdl.cpp
// (RunWindowedApp), resolving the app through the library-mode creator
// registry (XE_UI_WINDOWED_APPS_IN_LIBRARY) instead of a link-time hook.
int RunAndroidApp(int argc, char** argv) {
  // --- Android glue setup (before any runtime subsystem spins threads). ---
  const std::string lib_dir = QueryNativeLibraryDir();
  JavaVM* java_vm = QueryJavaVm();
  if (lib_dir.empty()) {
    ALOGE("nativeLibraryDir unresolved - GPU plugin staging will fail");
  } else {
    ALOGE("nativeLibraryDir: %s", lib_dir.c_str());
  }

  // SDL video init is required before SDL_GetAndroidExternalStoragePath can
  // resolve the Java-side storage paths. SDLWindowedAppContext::Initialize()
  // below re-inits the (refcounted) subsystem.
  if (!SDL_InitSubSystem(SDL_INIT_VIDEO)) {
    REXLOG_ERROR("SDL_InitSubSystem(SDL_INIT_VIDEO) failed: {}", SDL_GetError());
    ALOGE("SDL_InitSubSystem(SDL_INIT_VIDEO) failed: %s", SDL_GetError());
    return EXIT_FAILURE;
  }
  const char* external_c = SDL_GetAndroidExternalStoragePath();
  std::string external_dir = external_c ? external_c : "";
  if (external_dir.empty()) {
    // Deterministic fallback for the primary external storage device.
    external_dir = std::string("/storage/emulated/0/Android/data/") + kAppIdentifier + "/files";
  }

  const std::string config_path = external_dir + "/" + kConfigFileName;
  const std::string game_root = ReadTrimmedFile(config_path);

  PrepareStorageDirs(external_dir, game_root);
  const std::string log_dir = ResolveLogDir(external_dir);

  // The activity object enables the SDK's Java bridges (content:// fd
  // opening). It is optional in the SDK glue - the native library dir is
  // wired independently - but SDL has the live MainActivity here, so hand
  // it over (it must be used in this same native frame: local JNI ref).
  rex::SetAndroidApplicationContext(java_vm, SDL_GetAndroidActivity(),
                                    lib_dir.c_str());
  rex::thread::AndroidInitialize();
  rex::memory::AndroidInitialize();
  rex::filesystem::AndroidInitialize();

  // --- Launch arguments (mirror the desktop launcher's cvar wiring). ---
  std::vector<std::string> args;
  args.emplace_back(kAppIdentifier);
  if (!game_root.empty()) {
    args.emplace_back(fmt::format("--game_data_root={}", game_root));
  } else {
    REXLOG_ERROR("android_main: no game_root.txt under {} - the game data root "
                 "is unset (re-run setup)",
                 external_dir);
    ALOGE("no game_root.txt under %s - re-run setup", external_dir.c_str());
  }
  args.emplace_back(fmt::format("--user_data_root={}", external_dir + "/data"));
  args.emplace_back(
      fmt::format("--log_file={}", log_dir + "/dantes_inferno.log"));

  // Performance (mobile memory bandwidth): the SDK's conservative default
  // (clear_memory_page_state=true) invalidates every CPU-uploaded page at
  // frame end, forcing the full vertex/index/texture working set through
  // memcpy + vkCmdCopyBuffer every single frame. On a phone that alone can
  // collapse the frame rate to ~1 FPS. CPU-side coherency is still enforced
  // by the write-watch mechanism (uploads re-arm page protection; CPU writes
  // fault, invalidate and re-upload), so this only removes the redundant
  // per-frame re-upload. Hot-reloadable: pass
  // --clear_memory_page_state=true to restore upstream behavior when
  // debugging GPU/CPU memory coherency issues.
  args.emplace_back("--clear_memory_page_state=false");

  // --- GPU renderer selection (SetupActivity toggle) -----------------------
  // The Java UI writes "native" or "xenos" into renderer.txt (next to
  // game_root.txt). "native" loads librexgpu-native.so (ARM renderer:
  // persistent driver pipeline cache + BCn-preserving texture policy);
  // anything else keeps the stock librexgpu-xenos.so. The selection is
  // startup-only: the game must be restarted after changing the toggle.
  const std::string renderer = ReadTrimmedFile(external_dir + "/renderer.txt");
  if (renderer == "native") {
    args.emplace_back("--gpu_plugin=native");
  }
  REXLOG_INFO("android_main: gpu renderer = {}",
              renderer == "native" ? "native (rexgpu-native)"
                                   : "xenos (stock, default)");

  std::vector<char*> argv_ptrs;
  argv_ptrs.reserve(args.size());
  for (auto& arg : args) {
    argv_ptrs.push_back(arg.data());
  }

  auto remaining = rex::cvar::Init(static_cast<int>(argv_ptrs.size()), argv_ptrs.data());
  (void)remaining;  // No positional args on Android (paths wired via cvars).
  rex::cvar::ApplyEnvironment();
  rex::InitLoggingEarly();

  // --- Diagnostic state (REXLOG now also reaches logcat on Android). ---
  REXLOG_INFO("android_main: app={}", kAppIdentifier);
  REXLOG_INFO("android_main: external_dir={}", external_dir);
  REXLOG_INFO("android_main: log_dir={}", log_dir);
  if (game_root.empty()) {
    REXLOG_ERROR("android_main: game_data_root is unset (no game_root.txt)");
  } else {
    std::error_code xex_ec;
    const bool xex_found =
        std::filesystem::exists(game_root + "/default.xex", xex_ec);
    REXLOG_INFO("android_main: game_data_root={} (default.xex {})", game_root,
                xex_found ? std::string("found") : std::string("NOT FOUND"));
  }

  int result;
  {
    rex::ui::SDLWindowedAppContext app_context;
    if (!app_context.Initialize()) {
      REXLOG_ERROR("android_main: SDLWindowedAppContext::Initialize failed: {}",
                   SDL_GetError());
      result = EXIT_FAILURE;
      return result;
    }

    const auto creator = rex::ui::WindowedApp::GetCreator(kAppIdentifier);
    if (!creator) {
      REXLOG_ERROR("android_main: app '{}' is not registered - the recompiled "
                   "code was built from a different project name",
                   kAppIdentifier);
      result = EXIT_FAILURE;
      return result;
    }
    REXLOG_INFO("android_main: app registered, running OnInitialize...");
    std::unique_ptr<rex::ui::WindowedApp> app = creator(app_context);

    // No positional args on Android (paths are wired through cvars above).
    if (app->OnInitialize()) {
      result = app_context.RunMainMessageLoop();
    } else {
      REXLOG_ERROR("android_main: OnInitialize failed - see earlier errors "
                   "from the app/runtime");
      result = EXIT_FAILURE;
    }

    app->InvokeOnDestroy();
  }

  REXLOG_INFO("android_main: exiting with code {}", result);
  return result;
}

}  // namespace

int main(int argc, char* argv[]) {
  (void)argc;
  (void)argv;
  return RunAndroidApp(0, nullptr);
}

#endif  // REX_PLATFORM_ANDROID
