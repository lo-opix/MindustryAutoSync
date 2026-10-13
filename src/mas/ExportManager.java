package mas;

import arc.Core;
import arc.files.Fi;
import arc.files.ZipFi;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.io.Streams;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static arc.Core.settings;
import static mindustry.Vars.*;

public class ExportManager {
    public static final String lastSyncedFilename = "mas_last_synced.txt";
    public static final String pendingImportFilename = "mas_pending_import.zip";

    /**
     * Returns the date of the last recorded save sync.
     * Returns 0 if we never recorded a save sync.
     * @return Last save sync, or 0 if never recorded
      */
    public long getLastSynced() {
        Fi dataDir = Core.settings.getDataDirectory();
        Fi lastModified = dataDir.child(lastSyncedFilename);
        if (lastModified.exists()) {
            try {
                return Long.parseLong(lastModified.readString());
            } catch (NumberFormatException e) {
                return 0;
            }
        } else {
            return 0;
        }
    }

    /**
     * Sets the date of the last recorded save sync, in a file in the save folder.
     * @param newValue The new last save sync
     */
    public void setLastSynced(long newValue) {
        Fi dataDir = Core.settings.getDataDirectory();
        Fi lastModified = dataDir.child(lastSyncedFilename);
        lastModified.writeString(Long.toString(newValue));
    }

    /**
     * Checks if MAS export is currently enabled.
     */
    public boolean isExportEnabled() {
        // A downloaded save waiting for a restart must not be overwritten by the outdated local one
        return Core.settings.getBool("mas-enable-export") && drive.isConfigured() && !hasPendingImport();
    }

    /**
     * Checks if MAS import is currently enabled.
     */
    public boolean isImportEnabled() {
        return Core.settings.getBool("mas-enable-import") && drive.isConfigured();
    }

    public final DriveClient drive = new DriveClient();

    public ExportManager() {}

    /**
     * Stores an Arc setting, for transient backup.
     */
    public static class Setting {
        private final String key;
        private final Object value;
        public Setting(String key) {
            this.key = key;
            this.value = Core.settings.get(key, Core.settings.getDefault(key));
        }

        public void apply() {
            Core.settings.put(this.key, this.value);
        }
    }

    /**
     * Imports a MAS export.
     * The main difference with Mindustry's implementation of this function is setting backup (avoid changing device
     * settings such as scaling) and last sync check.
     * Also checks that MAS is currently enabled.
     * @param zip The content of the MAS export.
     * @throws IllegalArgumentException When the provided data is not valid.
     */
    public void importData(byte[] zip) throws IllegalArgumentException {
        //filter and save the current settings (graphical, keybinds...) and keep the non-gameplay related ones
        Seq<Setting> settings_backup = new Seq<>();
        for (String setting : Core.settings.keys()) {
            if (!setting.startsWith("req-") && !setting.contains("serpulo") && !setting.contains("erekir")
                    && !setting.contains("unlocked") && !setting.contains("campaign") && !setting.contains("last")) {
                settings_backup.add(new Setting(setting));
            }
        }

        Fi dest = Core.files.local("zipdata.zip");
        if (dest.exists()) dest.delete();
        dest.writeBytes(zip);
        Fi zipped = new ZipFi(dest);

        Fi base = Core.settings.getDataDirectory();
        if(!zipped.child("settings.bin").exists()){
            throw new IllegalArgumentException("Not valid save data.");
        }
        Fi lastSyncedFi = zipped.child(lastSyncedFilename);
        long lastSynced;
        if (!lastSyncedFi.exists()) {
            throw new IllegalArgumentException("Not a valid MAS save data (" + lastSyncedFilename + " missing).");
        }
        try {
            String rawLastModified = lastSyncedFi.readString().trim();
            lastSynced = Long.parseLong(rawLastModified);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a valid MAS save data (cannot read last modified timestamp).");
        }
        if (lastSynced <= getLastSynced()) {
            Log.info("MAS export is up to date, not importing.");
            dest.delete();
            return;
        }

        //delete old saves so they don't interfere
        saveDirectory.deleteDirectory();

        //purge existing tmp data, keep everything else
        tmpDirectory.deleteDirectory();

        List<String> failed = new ArrayList<>();
        zipped.walk(f -> {
            try {
                f.copyTo(base.child(f.path()));
            } catch (Exception e) {
                e.printStackTrace();
                failed.add(f.path());
            }
        });
        dest.delete();
        if (!failed.isEmpty()) {
            ui.showInfo("MAS failed to copy some files:\n" + String.join("\n", failed));
        }

        //clear old data
        settings.clear();
        //load data so it's saved on exit
        settings.load();
        //load previous settings
        for (Setting setting : settings_backup) {
            setting.apply();
        }

        setLastSynced(lastSynced);
        Log.info("MAS export was older, save imported.");
    }

    /**
     * The save downloaded from Google Drive, applied at the next game start.
     */
    public Fi getPendingImport() {
        return Core.settings.getDataDirectory().child(pendingImportFilename);
    }

    public boolean hasPendingImport() {
        return getPendingImport().exists();
    }

    /**
     * Imports the save downloaded at the previous game start, if any.
     * Must be called before the game loads its data.
     * @return True if a save was imported.
     * @throws IllegalArgumentException When the downloaded save is not valid.
     */
    public boolean applyPendingImport() throws IllegalArgumentException {
        Fi pending = getPendingImport();
        if (!pending.exists()) return false;
        try {
            long before = getLastSynced();
            importData(pending.readBytes());
            return getLastSynced() != before;
        } finally {
            pending.delete();
        }
    }

    /**
     * Downloads the save from Google Drive if it is newer than the local one.
     * It is stored to be imported at the next game start, as the game data is already loaded.
     * Does nothing if import is disabled.
     * @return True if a newer save was downloaded.
     * @throws IOException When Google Drive cannot be reached.
     */
    public boolean downloadFromDrive() throws IOException {
        if (!isImportEnabled()) return false;
        // Only check the metadata first, to avoid downloading the whole save when it didn't change
        if (drive.fetch(false).lastSynced <= getLastSynced()) {
            Log.info("MAS: Google Drive save is up to date.");
            return false;
        }
        DriveClient.RemoteSave save = drive.fetch(true);
        if (!save.exists) return false;
        getPendingImport().writeBytes(save.data);
        Log.info("MAS: newer save downloaded from Google Drive, it will be imported at the next start.");
        return true;
    }

    /**
     * Exports Mindustry data to an MAS zip.
     * The main difference with Mindustry implementation of this function is the inclusion of the last synced file.
     * @return The content of the zip.
     * @throws IOException When a file cannot be read.
     */
    public byte[] exportData() throws IOException {
        setLastSynced(System.currentTimeMillis());
        Seq<Fi> files = new Seq<>();
        files.add(Core.settings.getSettingsFile());
        files.addAll(customMapDirectory.list());
        files.addAll(saveDirectory.list());
        // TODO: Implement smarter mod check (for example, blacklist MAS?)
        files.addAll(modDirectory.list());
        files.addAll(schematicDirectory.list());
        files.add(Core.settings.getDataDirectory().child(lastSyncedFilename));

        String base = Core.settings.getDataDirectory().path();

        //add directories
        for(Fi other : files.copy()){
            Fi parent = other.parent();
            while(!files.contains(parent) && !parent.equals(settings.getDataDirectory())){
                files.add(parent);
            }
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try(ZipOutputStream zos = new ZipOutputStream(bytes)){
            for(Fi add : files){
                String path = add.path().substring(base.length());
                if(add.isDirectory()) path += "/";
                //fix trailing / in path
                path = path.startsWith("/") ? path.substring(1) : path;
                zos.putNextEntry(new ZipEntry(path));
                if(!add.isDirectory()){
                    try(var stream = add.read()){
                        Streams.copy(stream, zos);
                    }
                }
                zos.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
