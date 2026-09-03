package com.deivid22srk.hellsgate;

import android.content.Intent;
import android.os.Bundle;

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
    }

    @Override
    protected String[] getLibraries() {
        return new String[]{
                "main"
        };
    }
}
