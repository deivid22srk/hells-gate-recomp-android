package com.deivid22srk.hellsgate;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.Settings;
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
 * (default.xex + bigfile*.viv + ...) and hand a native-accessible path to the
 * SDL activity via game_root.txt.
 *
 * The game files are READ IN PLACE — nothing is ever copied into app storage
 * (unless path resolution is impossible on an exotic device, see the very last
 * fallback). To read arbitrary user folders directly, Android 11+ requires the
 * special "All files access" permission (MANAGE_EXTERNAL_STORAGE), which is
 * requested interactively from the system settings page. On Android 9/10 the
 * classic READ_EXTERNAL_STORAGE runtime permission is enough (Android 10 may
 * still need the copy fallback, since scoped storage cannot be bypassed there
 * when targeting API 30+).
 *
 * Strategies, in order of preference:
 *  1. Real filesystem path resolved from the SAF tree document id via
 *     StorageVolume (primary storage, SD cards and USB OTG) — used directly,
 *     no copy.
 *  2. Copy the picked folder into app-private storage (last resort only, and
 *     only after confirming the folder actually contains default.xex).
 */
public class SetupActivity extends Activity {

    private static final int REQUEST_PICK_TREE = 1001;
    private static final int REQUEST_READ_PERMISSION = 1002;

    /** What to do when we come back from the system settings/permission UI. */
    private static final int RESUME_NONE = 0;
    private static final int RESUME_PICK_FOLDER = 1;

    private TextView statusText;
    private ProgressBar progress;
    private Button pickButton;
    private int resumeAction = RESUME_NONE;

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
        pickButton.setOnClickListener(v -> onPickClicked());
        root.addView(pickButton);

        Button resetButton = new Button(this);
        resetButton.setText(R.string.setup_reset);
        resetButton.setOnClickListener(v -> resetConfig());
        root.addView(resetButton);

        setContentView(root);

        // Fast path: already configured, access still granted and files still present.
        if (hasValidConfig()) {
            launchGame();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from the "All files access" settings page (or the
        // runtime permission dialog): continue where the user left off.
        if (resumeAction == RESUME_PICK_FOLDER) {
            resumeAction = RESUME_NONE;
            if (hasValidConfig()) {
                // Access was restored for a previously configured folder.
                launchGame();
            } else if (hasStorageAccess()) {
                launchPicker();
            }
        }
    }

    private void onPickClicked() {
        if (ensureStorageAccess()) {
            launchPicker();
        }
    }

    /**
     * Makes sure the app can read the picked folder directly.
     * Returns true when access is already granted; otherwise it redirects the
     * user to the system UI that grants it and returns false (onResume
     * continues the flow afterwards).
     */
    private boolean ensureStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                return true;
            }
            resumeAction = RESUME_PICK_FOLDER;
            setStatus(R.string.setup_status_need_permission);
            try {
                startActivity(new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException e) {
                try {
                    startActivity(new Intent(
                            Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception ignored) {
                    toast(R.string.setup_status_no_settings);
                }
            }
            return false;
        }
        // Android 9/10: classic runtime read permission.
        if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        resumeAction = RESUME_PICK_FOLDER;
        requestPermissions(new String[]{
                android.Manifest.permission.READ_EXTERNAL_STORAGE},
                REQUEST_READ_PERMISSION);
        return false;
    }

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
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
        prefs().edit().putString("game_tree_uri", treeUri.toString()).apply();
        setStatus(R.string.setup_status_resolving);
        pickButton.setEnabled(false);

        // Strategy 1: read the folder in place (no copy).
        final String directPath = resolveDirectPath(treeUri);
        if (directPath != null && new File(directPath, "default.xex").isFile()) {
            finishSetup(directPath);
            return;
        }

        // Before copying gigabytes, make sure the folder really holds the game.
        if (!treeHasDefaultXex(treeUri)) {
            pickButton.setEnabled(true);
            setStatus(R.string.setup_status_missing);
            return;
        }

        // Strategy 2 (last resort, exotic devices only): copy into app storage.
        toast(R.string.setup_copying);
        runCopyTask(treeUri);
    }

    /**
     * Resolves the real filesystem path of a SAF tree URI. Works for primary
     * storage, SD cards and USB OTG (API 30+, via StorageVolume). Requires the
     * "All files access" permission on Android 11+ to actually read the result.
     */
    private String resolveDirectPath(Uri treeUri) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            if (docId == null) {
                return null;
            }
            int colon = docId.indexOf(':');
            String volumePart = colon >= 0 ? docId.substring(0, colon) : docId;
            String sub = colon >= 0 ? docId.substring(colon + 1) : "";
            while (sub.startsWith("/")) {
                sub = sub.substring(1);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                StorageManager sm = (StorageManager) getSystemService(STORAGE_SERVICE);
                for (StorageVolume volume : sm.getStorageVolumes()) {
                    String uuid = volume.getUuid();
                    boolean match = "primary".equals(volumePart)
                            ? volume.isPrimary()
                            : (uuid != null && uuid.equals(volumePart));
                    File dir = volume.getDirectory();
                    if (match && dir != null) {
                        return sub.isEmpty()
                                ? dir.getAbsolutePath()
                                : new File(dir, sub).getAbsolutePath();
                    }
                }
            }
            // Legacy fallback for primary storage on older APIs.
            if ("primary".equals(volumePart)) {
                return sub.isEmpty()
                        ? "/storage/emulated/0"
                        : "/storage/emulated/0/" + sub;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Cheap SAF probe: does the ROOT of the picked tree contain default.xex? */
    private boolean treeHasDefaultXex(Uri treeUri) {
        try {
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri,
                    DocumentsContract.getDocumentId(treeUri));
            try (Cursor c = getContentResolver().query(childrenUri,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null)) {
                while (c != null && c.moveToNext()) {
                    String name = c.getString(0);
                    if (name != null && "default.xex".equalsIgnoreCase(name)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
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
                    setStatus(R.string.setup_status_missing);
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
            prefs().edit().putString("game_root", gameRoot).apply();
            toast(R.string.setup_done);
            // If a previous attempt fell back to copying, reclaim that space:
            // the game is now read in place and the private copy is stale.
            purgeStalePrivateCopy();
            launchGame();
        } catch (Exception e) {
            setStatusText("Failed to write config: " + e);
        }
    }

    /** Deletes a leftover in-app game copy (used before in-place access worked). */
    private void purgeStalePrivateCopy() {
        final File stale = new File(getFilesDir(), "game");
        if (!stale.exists()) {
            return;
        }
        new Thread(() -> deleteRecursively(stale), "purge-copy").start();
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private void resetConfig() {
        try {
            File external = getExternalFilesDir(null);
            if (external != null) {
                //noinspection ResultOfMethodCallIgnored
                new File(external, "game_root.txt").delete();
            }
            prefs().edit().remove("game_root").remove("game_tree_uri").apply();
            deleteRecursively(new File(getFilesDir(), "game"));
        } catch (Exception ignored) {
        }
        progress.setVisibility(View.GONE);
        pickButton.setEnabled(true);
        setStatus(R.string.setup_status_reset);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("settings", MODE_PRIVATE);
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
