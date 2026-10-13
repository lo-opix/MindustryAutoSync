# Mindustry Auto Sync

> **Warning**  
> For now, this mod has a known issue when it comes to loading newly explored sectors and schematics from other devices. I'm not sure if it is possible to fix that without restarting the game, which is the main goal of the mod.
> **I recommend not using this mod until this get fixed.**

Mindustry Auto Sync (MAS for short) is a Java mod that synchronizes your save with a Google Drive folder.

When the game starts, the mod checks Google Drive. If a newer save is found, it is downloaded and the game asks you to restart to use it.
When you leave a game, the save is uploaded to Google Drive. A popup in the bottom right corner shows the progress.
If you quit the game during the upload, the game waits for it to finish before closing.
If there is no internet connection, a warning is shown and the local save is used.

The mod updates itself from the latest GitHub release when the game starts (it can be disabled in the settings).

The mod works on desktop and Android.

iOS devices cannot be supported. It's not possible to load Java mods on iOS.

## Installing

In Mindustry, go to **Mods > Import mod > Import from GitHub** and type `lo-opix/MindustryAutoSync`.
You can also download `dexed-MindustryAutoSync.jar` from the [releases](https://github.com/lo-opix/MindustryAutoSync/releases) (it works on desktop and Android).

## Setting up Google Drive

Google Drive doesn't allow uploading files without being logged in, even to a public folder.
The mod goes through a small Google Apps Script that runs on your Google account and writes to your folder.

1. Create a folder in your Google Drive. Copy its link (or its ID, the part after `/folders/`).
2. Go to [script.google.com](https://script.google.com), create a new project and paste the content of [`drive-script/Code.gs`](drive-script/Code.gs).
   Optionally, set a password in `SECRET`.
3. Click **Deploy > New deployment**, choose the type **Web app**, with **Execute as: Me** and **Who has access: Anyone**.
   Authorize the script when asked, then copy the web app URL (it ends with `/exec`).
4. In Mindustry, go to **Settings > Automatic Sync** and fill in the script URL, the folder link and the password (if you set one).
   Click **Test connection**.

Anyone who knows the script URL (and the password) can read and replace your save, so keep them private.

## Using the mod

- **Make a backup of your save - things will go wrong.**
- On device 1, set up the mod as explained above, and enable upload and download.
- Play, go back to the main menu, and check that `mindustry-sync.zip` appears in the Drive folder.
- On device 2, install the mod and set it up the same way, then restart the game: the save gets downloaded, and is used after another restart.

Regularly make offline backup of your save. Issue will happen, and you don't want to lose your progress.

## Limitations

The most important limitation is that you cannot use two devices connected to the same save at the same time.
If you open two games at the same time, the game that saves last will overwrite the save on Google Drive.

The save will only get downloaded when the game starts. If the save gets changed while the game is opened, it will likely get overwritten when the game closes.

iOS devices cannot, and will not, be supported.

## Neat things

Since you can choose to enable only upload, you can use this mod to automate a backup of your save.

Since you can choose to enable only download, you can use the mod to create "read-only" devices, which won't export files and avoid collisions.

## Reporting issues

If you have an issue with the mod, don't hesitate to open an issue!

This will be especially useful for platforms I don't test on, such as Windows.

Feature requests are accepted, but as I feel like the mod is feature complete, they will probably get closed quickly.
