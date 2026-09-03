package com.deivid22srk.hellsgate;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * Onboarding: pick the folder with the extracted Xbox 360 game files
 * (default.xex + bigfile*.viv + ...) via the system folder picker (SAF), then
 * hand a native-accessible path to the SDL activity via game_root.txt.
 *
 * Two strategies, in order of preference:
 *  1. Direct filesystem path reconstructed from the tree document id for
 *     primary storage (no copy; the SAF grant keeps the picker happy and the
 *     files are read in place).
 *  2. Copy the picked folder into the app's private storage (robust on every
 *     device; costs disk space equal to the game size).
 */
public class SetupActivity extends Activity {

    private static final int REQUEST_PICK_TREE = 1001;

    private TextView statusText;
    private ProgressBar progress;
    private Button pickButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.setup_title);
        title.setTextSize(20);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText(R.string.setup_help);
        help.setTextSize(14);
        help.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(help);

        statusText = new TextView(this);
        statusText.setText(R.string.setup_status_idle);
        statusText.setTextSize(14);
        root.addView(statusText);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setVisibility(View.GONE);
        root.addView(progress);

        pickButton = new Button(this);
        pickButton.setText(R.string.setup_pick);
        pickButton.setOnClickListener(v -> launchPicker());
        root.addView(pickButton);

        setContentView(root);

        // Fast path: already configured and files still present.
        if (hasValidConfig()) {
            launchGame();
        }
    }

    private void launchPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_PICK_TREE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_TREE || resultCode != RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        final Uri treeUri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
        setStatus(R.string.setup_status_resolving);
        pickButton.setEnabled(false);

        final String directPath = tryResolvePrimaryStoragePath(treeUri);
        if (directPath != null && new File(directPath, "default.xex").isFile()) {
            finishSetup(directPath);
            return;
        }

        // Fall back to copying the SAF tree into app-private storage.
        toast(R.string.setup_copying);
        runCopyTask(treeUri);
    }

    /** Best-effort reconstruction of the real path for primary storage trees. */
    private String tryResolvePrimaryStoragePath(Uri treeUri) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            if (docId == null) {
                return null;
            }
            String[] parts = docId.split(":");
            if (parts.length == 0 || !"primary".equals(parts[0])) {
                return null; // Secondary storage/USB OTG: use the copy fallback.
            }
            String sub = parts.length > 1 ? parts[1] : "";
            while (sub.startsWith("/")) {
                sub = sub.substring(1);
            }
            if (sub.isEmpty()) {
                return "/storage/emulated/0";
            }
            return "/storage/emulated/0/" + sub;
        } catch (Exception e) {
            return null;
        }
    }

    private void runCopyTask(final Uri treeUri) {
        progress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            File destRoot = new File(getFilesDir(), "game");
            int copied = copyTree(treeUri, destRoot);
            final boolean ok = copied > 0 && new File(destRoot, "default.xex").isFile();
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                pickButton.setEnabled(true);
                if (ok) {
                    finishSetup(destRoot.getAbsolutePath());
                } else {
                    setStatusText(getString(R.string.setup_status_missing));
                }
            });
        }, "game-copy").start();
    }

    /** Recursively copy a document tree; returns number of files copied. */
    private int copyTree(Uri treeUri, File destDir) {
        int count = 0;
        try {
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri,
                    DocumentsContract.getDocumentId(treeUri));
            ArrayList<Uri> files = new ArrayList<>();
            ArrayList<Uri> dirs = new ArrayList<>();
            try (Cursor c = getContentResolver().query(childrenUri,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    String docId = c.getString(0);
                    String mime = c.getString(1);
                    Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        dirs.add(docUri);
                    } else {
                        files.add(docUri);
                    }
                }
            }
            // Ignore (non-fatal) if destDir already exists.
            if (!destDir.exists()) {
                destDir.mkdirs();
            }
            for (Uri file : files) {
                File dest = new File(destDir, queryDisplayName(file));
                if (copyFile(file, dest)) {
                    count++;
                    final int done = count;
                    runOnUiThread(() -> {
                        progress.setIndeterminate(false);
                        progress.post(() -> statusText.setText(getString(
                                R.string.setup_status_copied, done)));
                    });
                }
            }
            for (Uri dir : dirs) {
                String name = queryDisplayName(dir);
                count += copyTree(dir, new File(destDir, name));
            }
        } catch (Exception ignored) {
        }
        return count;
    }

    private String queryDisplayName(Uri docUri) {
        try (Cursor c = getContentResolver().query(docUri,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                return c.getString(0);
            }
        } catch (Exception ignored) {
        }
        return "file_" + System.nanoTime();
    }

    private boolean copyFile(Uri source, File dest) {
        try (InputStream in = getContentResolver().openInputStream(source);
             OutputStream out = new java.io.FileOutputStream(dest)) {
            byte[] buffer = new byte[1 << 20];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void finishSetup(String gameRoot) {
        try {
            File external = getExternalFilesDir(null);
            if (external != null) {
                java.io.PrintWriter w = new java.io.PrintWriter(
                        new java.io.FileWriter(new File(external, "game_root.txt"), false));
                w.println(gameRoot);
                w.close();
            }
            SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
            prefs.edit().putString("game_root", gameRoot).apply();
            toast(R.string.setup_done);
            launchGame();
        } catch (Exception e) {
            setStatusText("Failed to write config: " + e);
        }
    }

    private boolean hasValidConfig() {
        String root = readConfiguredRoot();
        return root != null && new File(root, "default.xex").isFile();
    }

    private String readConfiguredRoot() {
        try {
            File external = getExternalFilesDir(null);
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

    private void launchGame() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private void setStatus(int resId) {
        statusText.setText(resId);
    }

    private void setStatusText(String text) {
        statusText.setText(text);
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }
}
