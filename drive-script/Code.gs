/**
 * Mindustry Auto Sync - Google Drive bridge.
 *
 * Deploy this script as a web app (Execute as: Me, Who has access: Anyone),
 * then paste the deployment URL and your Drive folder link in the mod settings.
 * See the "Setting up Google Drive" section of README.md for the full setup.
 */

// Optional: set a password here and type the same one in the mod settings.
// Anyone who knows the web app URL (and this password) can read and replace your save.
const SECRET = '';

const FILE_NAME = 'mindustry-sync.zip';

function doGet(e) {
  return handle(e, () => {
    const file = findSyncFile(getFolder(e));
    if (!file) return { exists: false, lastSynced: 0 };

    const result = { exists: true, lastSynced: Number(file.getDescription()) || 0 };
    if (e.parameter.action !== 'info') {
      result.data = Utilities.base64Encode(file.getBlob().getBytes());
    }
    return result;
  });
}

function doPost(e) {
  return handle(e, () => {
    const folder = getFolder(e);
    const lastSynced = Number(e.parameter.lastSynced) || 0;
    const bytes = Utilities.base64Decode(e.postData.contents);
    const blob = Utilities.newBlob(bytes, 'application/zip', FILE_NAME);

    // Create the new file first, then remove the old ones, so a failure never leaves the folder empty
    const created = folder.createFile(blob);
    created.setDescription(String(lastSynced));
    const files = folder.getFilesByName(FILE_NAME);
    while (files.hasNext()) {
      const file = files.next();
      if (file.getId() !== created.getId()) file.setTrashed(true);
    }
    return { ok: true, lastSynced: lastSynced };
  });
}

function handle(e, action) {
  let result;
  try {
    if (SECRET && e.parameter.secret !== SECRET) {
      result = { error: 'Invalid password' };
    } else {
      result = action();
    }
  } catch (err) {
    result = { error: String(err) };
  }
  return ContentService.createTextOutput(JSON.stringify(result))
    .setMimeType(ContentService.MimeType.JSON);
}

function getFolder(e) {
  const id = e.parameter.folder;
  if (!id) throw new Error('Missing folder id');
  return DriveApp.getFolderById(id);
}

function findSyncFile(folder) {
  const files = folder.getFilesByName(FILE_NAME);
  let newest = null;
  while (files.hasNext()) {
    const file = files.next();
    if (!newest || file.getLastUpdated() > newest.getLastUpdated()) newest = file;
  }
  return newest;
}
