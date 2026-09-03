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

#include <dlfcn.h>
#include <jni.h>

#include <cstdlib>
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
  JavaVM* vm = nullptr;
  jsize count = 0;
  if (JNI_GetCreatedJavaVMs(&vm, 1, &count) == JNI_OK && count > 0 && vm) {
    return vm;
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

// Startup sequence mirrored from the SDK's windowed_app_main_sdl.cpp
// (RunWindowedApp), resolving the app through the library-mode creator
// registry (XE_UI_WINDOWED_APPS_IN_LIBRARY) instead of a link-time hook.
int RunAndroidApp(int argc, char** argv) {
  // --- Android glue setup (before any runtime subsystem spins threads). ---
  const std::string lib_dir = QueryNativeLibraryDir();
  JavaVM* java_vm = QueryJavaVm();

  // SDL video init is required before SDL_GetAndroidExternalStoragePath can
  // resolve the Java-side storage paths. SDLWindowedAppContext::Initialize()
  // below re-inits the (refcounted) subsystem.
  if (!SDL_InitSubSystem(SDL_INIT_VIDEO)) {
    REXLOG_ERROR("SDL_InitSubSystem(SDL_INIT_VIDEO) failed: {}", SDL_GetError());
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

  rex::SetAndroidApplicationContext(java_vm, nullptr, lib_dir.c_str());
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
  }
  args.emplace_back(fmt::format("--user_data_root={}", external_dir + "/data"));
  args.emplace_back(
      fmt::format("--log_file={}", external_dir + "/logs/dantes_inferno.log"));

  std::vector<char*> argv_ptrs;
  argv_ptrs.reserve(args.size());
  for (auto& arg : args) {
    argv_ptrs.push_back(arg.data());
  }

  auto remaining = rex::cvar::Init(static_cast<int>(argv_ptrs.size()), argv_ptrs.data());
  (void)remaining;  // No positional args on Android (paths wired via cvars).
  rex::cvar::ApplyEnvironment();
  rex::InitLoggingEarly();

  int result;
  {
    rex::ui::SDLWindowedAppContext app_context;
    if (!app_context.Initialize()) {
      return EXIT_FAILURE;
    }

    const auto creator = rex::ui::WindowedApp::GetCreator(kAppIdentifier);
    if (!creator) {
      REXLOG_ERROR("android_main: app '{}' is not registered - the recompiled "
                   "code was built from a different project name",
                   kAppIdentifier);
      return EXIT_FAILURE;
    }
    std::unique_ptr<rex::ui::WindowedApp> app = creator(app_context);

    // No positional args on Android (paths are wired through cvars above).
    result = app->OnInitialize() ? app_context.RunMainMessageLoop() : EXIT_FAILURE;

    app->InvokeOnDestroy();
  }

  return result;
}

}  // namespace

int main(int argc, char* argv[]) {
  (void)argc;
  (void)argv;
  return RunAndroidApp(0, nullptr);
}

#endif  // REX_PLATFORM_ANDROID
