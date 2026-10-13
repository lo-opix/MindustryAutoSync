package mas;

import arc.Core;
import arc.Events;
import arc.func.Prov;
import arc.util.Log;
import arc.util.Threads;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.core.GameState;
import mindustry.game.EventType;
import mindustry.mod.Mod;
import mindustry.ui.dialogs.SettingsMenuDialog;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static mindustry.Vars.ui;

public class MindustryAutomaticSync extends Mod {
    private static final long MAX_UPLOAD_WAIT = 5 * 60 * 1000;

    private final ExportManager em;
    private SyncPopup popup;
    private volatile Thread uploadThread;
    // True when the save changed since the last successful upload
    private volatile boolean dirty;

    // Message shown when game has finalized starting. Built lazily, as the mod bundle isn't loaded yet.
    private Prov<String> message;

    public MindustryAutomaticSync() {
        Core.settings.defaults("mas-script-url", "");
        Core.settings.defaults("mas-folder", "");
        Core.settings.defaults("mas-secret", "");
        Core.settings.defaults("mas-enable-export", false);
        Core.settings.defaults("mas-enable-import", false);
        Core.settings.defaults("mas-auto-update", true);

        em = new ExportManager();
        // The save downloaded at the previous start must be imported before the game loads its data
        try {
            if (em.applyPendingImport()) message = () -> Core.bundle.get("mas-successful-import");
        } catch (IllegalArgumentException e) {
            Log.err("MAS: invalid save downloaded from Google Drive", e);
            message = () -> Core.bundle.get("mas-invalid-import") + "\n" + e.getMessage();
        }

        Events.on(EventType.StateChangeEvent.class, e -> {
            if (e.to != GameState.State.menu) {
                dirty = true;
            } else if (e.from != GameState.State.menu) {
                // Leaving a game: give the game some time to finish saving
                Timer.schedule(this::uploadInBackground, 0.25F);
            }
        });
        Events.on(EventType.DisposeEvent.class, e -> {
            // The game is closing: finish the running upload, then upload the last changes if needed.
            // There is no UI left to show errors.
            waitForUpload();
            if (dirty && em.isExportEnabled()) {
                try {
                    byte[] zip = em.exportData();
                    em.drive.upload(zip, em.getLastSynced());
                } catch (IOException exc) {
                    Log.err("MAS: unable to upload the save to Google Drive", exc);
                }
            }
        });
        Events.on(EventType.ClientLoadEvent.class, e -> {
            popup = new SyncPopup();
            if (message != null) {
                ui.showInfo(message.get());
            }
            downloadInBackground();
            if (Core.settings.getBool("mas-auto-update")) updateInBackground();
        });
    }

    /** Uploads the save without freezing the game, with a popup showing the progress. */
    private void uploadInBackground() {
        if (!dirty || !em.isExportEnabled() || uploadThread != null) return;
        byte[] zip;
        try {
            // Zipping reads the save files, so it stays on the main thread
            zip = em.exportData();
        } catch (IOException exc) {
            Log.err(exc);
            ui.showException(exc);
            return;
        }
        long lastSynced = em.getLastSynced();
        dirty = false;
        popup.show("mas-uploading", "mas-do-not-close");
        uploadThread = Threads.daemon("MAS upload", () -> {
            IOException error = null;
            try {
                em.drive.upload(zip, lastSynced);
                Log.info("MAS: save uploaded to Google Drive (@ bytes).", zip.length);
            } catch (IOException exc) {
                Log.err("MAS: unable to upload the save to Google Drive", exc);
                error = exc;
                dirty = true;
            }
            IOException finalError = error;
            Core.app.post(() -> {
                uploadThread = null;
                if (finalError == null) {
                    popup.finish("mas-upload-done", true);
                } else {
                    popup.finish("mas-upload-failed", false);
                    ui.showInfo(describe(finalError) + "\n" + Core.bundle.get("mas-offline-export"));
                }
            });
        });
    }

    private void waitForUpload() {
        Thread thread = uploadThread;
        if (thread == null) return;
        Log.info("MAS: waiting for the upload to finish before closing.");
        try {
            thread.join(MAX_UPLOAD_WAIT);
        } catch (InterruptedException ignored) {
        }
    }

    /** Checks Google Drive for a newer save, with a popup showing the progress. */
    private void downloadInBackground() {
        if (!em.isImportEnabled() || em.hasPendingImport()) return;
        popup.show("mas-downloading", null);
        Threads.daemon("MAS download", () -> {
            try {
                boolean downloaded = em.downloadFromDrive();
                Core.app.post(() -> {
                    if (downloaded) {
                        popup.hide();
                        ui.showCustomConfirm("@mas-restart-title", "@mas-restart-text", "@mas-restart-quit", "@mas-restart-later",
                                () -> Core.app.exit(), () -> {});
                    } else {
                        popup.finish("mas-download-up-to-date", true);
                    }
                });
            } catch (IOException exc) {
                Log.err("MAS: unable to download the save from Google Drive", exc);
                Core.app.post(() -> {
                    popup.finish("mas-download-failed", false);
                    ui.showInfo(describe(exc) + "\n" + Core.bundle.get("mas-offline-import"));
                });
            }
        });
    }

    private void updateInBackground() {
        Threads.daemon("MAS update", () -> {
            try {
                String version = Updater.update();
                if (version != null) {
                    Core.app.post(() -> ui.showInfo(Core.bundle.format("mas-updated", version)));
                }
            } catch (IOException | RuntimeException exc) {
                Log.err("MAS: unable to update the mod", exc);
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
        table.checkPref("mas-auto-update", true);
        table.button("@mas-test-connection", this::testConnection).growX().padTop(3f).row();
        table.button(
                Core.bundle.get("settings.reset", "Reset to Defaults"),
                () -> {
                    Core.settings.remove("mas-script-url");
                    Core.settings.remove("mas-folder");
                    Core.settings.remove("mas-secret");
                    Core.settings.remove("mas-enable-export");
                    Core.settings.remove("mas-enable-import");
                    Core.settings.remove("mas-auto-update");
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
