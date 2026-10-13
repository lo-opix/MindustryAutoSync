package mas;

import arc.Core;
import arc.Events;
import arc.func.Prov;
import arc.util.Log;
import arc.util.Threads;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.mod.Mod;
import mindustry.ui.dialogs.SettingsMenuDialog;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicBoolean;

import static mindustry.Vars.ui;

public class MindustryAutomaticSync extends Mod {
    private final ExportManager em;
    private final AtomicBoolean uploading = new AtomicBoolean(false);
    // Avoids showing the same warning on every autosave
    private boolean uploadWarningShown;

    // Message shown when game has finalized starting. Built lazily, as the mod bundle isn't loaded yet.
    private Prov<String> message;

    public MindustryAutomaticSync() {
        Core.settings.defaults("mas-script-url", "");
        Core.settings.defaults("mas-folder", "");
        Core.settings.defaults("mas-secret", "");
        Core.settings.defaults("mas-enable-export", false);
        Core.settings.defaults("mas-enable-import", false);

        em = new ExportManager();
        // The save must be imported before the game loads it, so this blocks the startup until Drive answers
        try {
            if (em.importFromDrive()) message = () -> Core.bundle.get("mas-successful-import");
        } catch (IOException e) {
            Log.err("MAS: unable to download the save from Google Drive", e);
            message = () -> describe(e) + "\n" + Core.bundle.get("mas-offline-import");
        } catch (IllegalArgumentException e) {
            Log.err("MAS: invalid save on Google Drive", e);
            message = () -> Core.bundle.get("mas-invalid-import") + "\n" + e.getMessage();
        }

        Events.on(EventType.SaveLoadEvent.class, e -> {
            // TODO: This is very ugly, but it's in place until I find a better way...
            // This will probably involve mixins, I need to figure this out
            Timer.schedule(this::uploadInBackground, 0.25F);
        });
        Events.on(EventType.DisposeEvent.class, e -> {
            // The game is closing: upload synchronously, there is no UI left to show errors
            try {
                em.exportToDrive();
            } catch (IOException exc) {
                Log.err("MAS: unable to upload the save to Google Drive", exc);
            }
        });
        Events.on(EventType.ClientLoadEvent.class, e -> {
            if (message != null) {
                ui.showInfo(message.get());
            }
        });
    }

    /** Uploads the save without freezing the game. Skipped if an upload is already running. */
    private void uploadInBackground() {
        if (!em.isExportEnabled() || !uploading.compareAndSet(false, true)) return;
        byte[] zip;
        try {
            // Zipping reads the save files, so it stays on the main thread
            zip = em.exportData();
        } catch (IOException exc) {
            uploading.set(false);
            Log.err(exc);
            ui.showException(exc);
            return;
        }
        long lastSynced = em.getLastSynced();
        Threads.daemon("MAS upload", () -> {
            try {
                em.drive.upload(zip, lastSynced);
                uploadWarningShown = false;
                Log.info("MAS: save uploaded to Google Drive (@ bytes).", zip.length);
            } catch (IOException exc) {
                Log.err("MAS: unable to upload the save to Google Drive", exc);
                if (!uploadWarningShown) {
                    uploadWarningShown = true;
                    String text = describe(exc) + "\n" + Core.bundle.get("mas-offline-export");
                    Core.app.post(() -> ui.showInfo(text));
                }
            } finally {
                uploading.set(false);
            }
        });
    }

    /** Turns a network error into a readable message. */
    private static String describe(IOException e) {
        if (e instanceof UnknownHostException || e instanceof ConnectException
                || e instanceof NoRouteToHostException || e instanceof SocketTimeoutException) {
            return Core.bundle.get("mas-offline");
        }
        if (e instanceof DriveClient.DriveException) {
            String detail = e.getMessage().startsWith("@") ? Core.bundle.get(e.getMessage().substring(1)) : e.getMessage();
            return Core.bundle.get("mas-drive-error") + "\n" + detail;
        }
        return Core.bundle.get("mas-drive-error") + "\n" + e;
    }

    @Override
    public void init() {
        Vars.ui.settings.addCategory(
                Core.bundle.get("mas-settings"),
                "mas-setting-category",
                this::buildSettings
        );
    }

    public void buildSettings(SettingsMenuDialog.SettingsTable table) {
        table.clearChildren();
        table.defaults().size(400f, 60f).left();
        table.areaTextPref("mas-script-url", "");
        table.areaTextPref("mas-folder", "");
        table.textPref("mas-secret", "");
        table.checkPref("mas-enable-export", false);
        table.checkPref("mas-enable-import", false);
        table.button("@mas-test-connection", this::testConnection).growX().padTop(3f).row();
        table.button(
                Core.bundle.get("settings.reset", "Reset to Defaults"),
                () -> {
                    Core.settings.remove("mas-script-url");
                    Core.settings.remove("mas-folder");
                    Core.settings.remove("mas-secret");
                    Core.settings.remove("mas-enable-export");
                    Core.settings.remove("mas-enable-import");
                    buildSettings(table);
                }
        ).growX().row();
    }

    private void testConnection() {
        if (!em.drive.isConfigured()) {
            ui.showInfo("@mas-not-configured");
            return;
        }
        ui.loadfrag.show();
        Threads.daemon("MAS connection test", () -> {
            String result;
            try {
                DriveClient.RemoteSave save = em.drive.fetch(false);
                result = Core.bundle.get(save.exists ? "mas-connection-ok" : "mas-connection-ok-empty");
            } catch (IOException e) {
                result = describe(e);
            }
            String text = result;
            Core.app.post(() -> {
                ui.loadfrag.hide();
                ui.showInfo(text);
            });
        });
    }
}
