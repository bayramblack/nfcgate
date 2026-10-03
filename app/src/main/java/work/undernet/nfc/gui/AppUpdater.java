package work.undernet.nfc.gui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;
import work.undernet.nfc.BuildConfig;
import work.undernet.nfc.R;

/** HTTPS distribution channel for directly installed APKs. Android confirms installation. */
public final class AppUpdater {
    private static final String ORIGIN = "https://relay.undernet.work";
    private static final long MAX_APK = 100L * 1024 * 1024;
    private final MainActivity activity;
    private final SharedPreferences preferences;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ActivityResultLauncher<Intent> permission;
    private boolean busy, closed;
    private AlertDialog dialog;
    private File ready;

    public AppUpdater(MainActivity activity) {
        this.activity = activity;
        preferences = activity.getSharedPreferences("app_updates", 0);
        permission = activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (Build.VERSION.SDK_INT < 26 || activity.getPackageManager().canRequestPackageInstalls()) install();
                    else message(R.string.update_permission);
                });
    }

    public void check(boolean manual) {
        if (closed || busy) return;
        long now = System.currentTimeMillis();
        if (!manual && now - preferences.getLong("checked", 0) < 6 * 60 * 60 * 1000L) return;
        busy = true;
        if (manual) message(R.string.update_checking);
        worker.execute(() -> {
            try {
                HttpsURLConnection connection = connect(ORIGIN + "/downloads/latest.json");
                JSONObject manifest;
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096];
                    int n;
                    while ((n = input.read(buffer)) != -1) {
                        if (bytes.size() + n > 32768) throw new IllegalStateException("Manifest too large");
                        bytes.write(buffer, 0, n);
                    }
                    manifest = new JSONObject(bytes.toString("UTF-8"));
                } finally { connection.disconnect(); }
                long version = manifest.getLong("versionCode");
                String name = manifest.getString("versionName");
                String url = manifest.getString("url");
                String hash = manifest.getString("sha256");
                int minSdk = manifest.getInt("minSdk");
                long size = manifest.getLong("size");
                if (!hash.matches("[a-fA-F0-9]{64}") || size <= 0 || size > MAX_APK) throw new IllegalStateException("Invalid manifest");
                validateUrl(url);
                preferences.edit().putLong("checked", now).apply();
                ui(() -> {
                    busy = false;
                    if (version <= BuildConfig.VERSION_CODE) { if (manual) message(R.string.update_current); return; }
                    if (minSdk > Build.VERSION.SDK_INT) { if (manual) message(R.string.update_incompatible); return; }
                    if (!manual && version == preferences.getLong("postponed", 0)
                            && now < preferences.getLong("postponed_until", 0)) return;
                    dialog = new AlertDialog.Builder(activity).setTitle(R.string.update_available)
                            .setMessage(activity.getString(R.string.update_description, name))
                            .setPositiveButton(R.string.update_download, (d, w) -> download(url, hash, size, version))
                            .setNegativeButton(R.string.update_later, (d, w) -> preferences.edit()
                                    .putLong("postponed", version).putLong("postponed_until", now + 86400000L).apply())
                            .show();
                });
            } catch (Exception e) {
                ui(() -> { busy = false; if (manual) message(R.string.update_failed); });
            }
        });
    }

    private void download(String url, String hash, long size, long version) {
        if (busy || closed) return;
        busy = true;
        dialog = new AlertDialog.Builder(activity).setTitle(R.string.update_downloading)
                .setMessage(R.string.update_wait).setCancelable(false).show();
        worker.execute(() -> {
            File folder = new File(activity.getCacheDir(), "updates");
            File partial = new File(folder, "update.part");
            try {
                if (!folder.isDirectory() && !folder.mkdirs()) throw new IllegalStateException("Cannot create cache");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                HttpsURLConnection connection = connect(url);
                long count = 0;
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[65536];
                    int n;
                    while ((n = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        count += n;
                        if (count > size || count > MAX_APK) throw new IllegalStateException("Invalid size");
                        digest.update(buffer, 0, n);
                        output.write(buffer, 0, n);
                    }
                    output.getFD().sync();
                } finally { connection.disconnect(); }
                if (count != size || !hex(digest.digest()).equalsIgnoreCase(hash)) throw new IllegalStateException("Invalid checksum");
                verifyPackage(partial, version);
                File apk = new File(folder, "UnderNet-" + version + ".apk");
                if (apk.exists() && !apk.delete()) throw new IllegalStateException("Cannot replace cache");
                if (!partial.renameTo(apk)) throw new IllegalStateException("Cannot save APK");
                ui(() -> {
                    busy = false;
                    dialog.dismiss();
                    ready = apk;
                    install();
                });
            } catch (Exception e) {
                partial.delete();
                ui(() -> { busy = false; if (dialog != null) dialog.dismiss(); message(R.string.update_failed); });
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void verifyPackage(File apk, long version) throws Exception {
        PackageManager pm = activity.getPackageManager();
        PackageInfo incoming = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
        PackageInfo installed = pm.getPackageInfo(activity.getPackageName(), PackageManager.GET_SIGNATURES);
        if (incoming == null || !activity.getPackageName().equals(incoming.packageName)
                || incoming.versionCode != version || version <= BuildConfig.VERSION_CODE
                || incoming.signatures == null || incoming.signatures.length == 0
                || !Arrays.equals(incoming.signatures, installed.signatures)) {
            throw new IllegalStateException("Package identity mismatch");
        }
    }

    private void install() {
        if (closed || ready == null || !ready.isFile()) return;
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            dialog = new AlertDialog.Builder(activity).setTitle(R.string.update_available)
                    .setMessage(R.string.update_permission)
                    .setPositiveButton(R.string.button_ok, (d, w) -> permission.launch(new Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName()))))
                    .setNegativeButton(R.string.update_later, null).show();
            return;
        }
        Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName(), ready);
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { activity.startActivity(intent); }
        catch (android.content.ActivityNotFoundException e) { message(R.string.update_failed); }
    }

    private static void validateUrl(String value) throws Exception {
        URL url = new URL(value);
        if (!"https".equals(url.getProtocol()) || !"relay.undernet.work".equals(url.getHost())
                || url.getUserInfo() != null || (url.getPort() != -1 && url.getPort() != 443)
                || !url.getPath().startsWith("/downloads/")) throw new IllegalStateException("Invalid update URL");
    }
    private static HttpsURLConnection connect(String url) throws Exception {
        validateUrl(url);
        HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        if (connection.getResponseCode() != 200) { connection.disconnect(); throw new IllegalStateException("HTTP error"); }
        return connection;
    }
    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }
    private void message(int id) { Toast.makeText(activity, id, Toast.LENGTH_LONG).show(); }
    private void ui(Runnable runnable) { activity.runOnUiThread(() -> { if (!closed && !activity.isFinishing()) runnable.run(); }); }
    public void close() {
        closed = true;
        if (dialog != null) dialog.dismiss();
        worker.shutdownNow();
    }
}
