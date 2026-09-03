package com.deivid22srk.hellsgate;

import android.content.Context;

import java.io.File;

/** Shared helpers for locating the configured game data root. */
public final class GameFiles {
    private GameFiles() {
    }

    /** Reads game_root.txt (written by SetupActivity) and validates the xex. */
    public static String configuredGameRoot(Context context) {
        try {
            File external = context.getExternalFilesDir(null);
            if (external == null) {
                return null;
            }
            File config = new File(external, "game_root.txt");
            if (!config.isFile()) {
                return null;
            }
            byte[] bytes = new byte[(int) Math.min(config.length(), 8192)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(config)) {
                int n = in.read(bytes);
                if (n <= 0) {
                    return null;
                }
            }
            String line = new String(bytes).split("\n", 2)[0].trim();
            return line.isEmpty() ? null : line;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean hasValidGameRoot(Context context) {
        String root = configuredGameRoot(context);
        return root != null && new File(root, "default.xex").isFile();
    }
}
