package mas;

import arc.Core;
import arc.files.Fi;
import arc.util.Log;
import arc.util.io.Streams;
import arc.util.serialization.JsonReader;
import arc.util.serialization.JsonValue;
import mindustry.Vars;
import mindustry.mod.Mods;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Updates the mod from the latest GitHub release.
 * The new jar replaces the current one, and is used from the next game start.
 */
public class Updater {
    public static final String REPO = "lo-opix/MindustryAutoSync";
    // Mindustry's GitHub import also picks the jar starting with "dexed", which works on desktop and Android
    private static final String ASSET_PREFIX = "dexed";

    /**
     * Downloads and installs the latest release if it is newer than the running version. Blocking.
     * @return The installed version, or null if the mod is up to date.
     */
    public static String update() throws IOException {
        Mods.LoadedMod mod = Vars.mods.getMod(MindustryAutomaticSync.class);
        if (mod == null || mod.file == null || mod.file.isDirectory()) return null;

        JsonValue release = new JsonReader().parse(new String(
                get("https://api.github.com/repos/" + REPO + "/releases/latest"), StandardCharsets.UTF_8));
        String version = release.getString("tag_name", "");
        if (compareVersions(version, mod.meta.version) <= 0) return null;

        String url = null;
        for (JsonValue asset = release.get("assets").child; asset != null; asset = asset.next) {
            String name = asset.getString("name", "");
            if (name.startsWith(ASSET_PREFIX) && name.endsWith(".jar")) url = asset.getString("browser_download_url");
        }
        if (url == null) throw new IOException("No " + ASSET_PREFIX + "*.jar in release " + version);

        Fi downloaded = Core.settings.getDataDirectory().child("mas_update.jar");
        downloaded.writeBytes(get(url));
        install(downloaded.file(), mod.file.file());
        Log.info("MAS: updated to @", version);
        return version;
    }

    private static void install(File downloaded, File target) {
        // Works everywhere except on Windows, where the running jar is locked
        if (downloaded.renameTo(target)) return;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                ClassLoader loader = Updater.class.getClassLoader();
                if (loader instanceof Closeable) ((Closeable) loader).close();
            } catch (IOException ignored) {
            }
            if (target.delete() || !target.exists()) downloaded.renameTo(target);
        }));
    }

    /** Compares versions like "v0.2" and "0.10.1". */
    static int compareVersions(String a, String b) {
        String[] pa = a.replaceAll("[^0-9.]", "").split("\\.");
        String[] pb = b.replaceAll("[^0-9.]", "").split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int va = i < pa.length && !pa[i].isEmpty() ? Integer.parseInt(pa[i]) : 0;
            int vb = i < pb.length && !pb[i].isEmpty() ? Integer.parseInt(pb[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(60_000);
        if (url.startsWith("https://api.github.com/")) connection.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            if (connection.getResponseCode() >= 400) throw new IOException("HTTP " + connection.getResponseCode() + " for " + url);
            try (InputStream in = connection.getInputStream()) {
                return Streams.copyBytes(in);
            }
        } finally {
            connection.disconnect();
        }
    }
}
