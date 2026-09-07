package com.deivid22srk.hellsgate;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import com.deivid22srk.hellsgate.gamepad.PadSettings;
import com.deivid22srk.hellsgate.gamepad.VirtualPadView;

import org.libsdl.app.SDLActivity;

/**
 * SDL3 activity hosting the recompiled game.
 *
 * SDL is linked STATICALLY into libmain.so, so the library list is just
 * "main" (the default {"SDL3", "main"} would fail: libSDL3.so is not
 * packaged). org.libsdl.app.* sources are copied from the SDL submodule by
 * scripts/setup-android.sh.
 */
public class MainActivity extends SDLActivity {

    /** The on-screen virtual gamepad overlay (null when disabled). */
    private VirtualPadView mGamepad;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (!GameFiles.hasValidGameRoot(this)) {
            // Game files disappeared (or were never fully set up): bounce back
            // to onboarding instead of letting the native side start without a
            // game data root.
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        super.onCreate(savedInstanceState);
        // The virtual gamepad rides ON TOP of the SDL surface as a sibling
        // view and consumes the full gesture stream (the game reads a
        // gamepad only). The native side attaches the SDL virtual gamepad
        // after the runtime's input driver is up (see android_gamepad.cpp),
        // so no Java-side attach races are possible.
        if (PadSettings.get(this).enabled()) {
            mGamepad = VirtualPadView.install(this);
        }
    }

    @Override
    protected void onPause() {
        if (mGamepad != null) {
            // Never let a button stick down while the game is backgrounded.
            mGamepad.onHostPause();
        }
        super.onPause();
    }

    @Override
    protected String[] getLibraries() {
        return new String[]{
                "main"
        };
    }

    /**
     * Native bridge resolved by the SDK's JNI glue (rex::ResolveJavaBridges
     * looks up this exact static signature on the activity class). Opens a
     * content:// URI through ContentResolver so native code can read it via
     * the returned fd. Returns null on any failure - callers treat that as
     * "bridge unavailable" and fall back to direct filesystem paths.
     */
    public static ParcelFileDescriptor openContentFd(String uri, String mode) {
        SDLActivity self = mSingleton;
        if (self == null || uri == null) {
            return null;
        }
        try {
            return self.getContentResolver().openFileDescriptor(
                    Uri.parse(uri), mode == null ? "r" : mode);
        } catch (Exception e) {
            return null;
        }
    }
}
