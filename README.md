# Bighorn for Android & Android TV

A Netflix-style client for your Jellyfin server. One app works on Android TV boxes/TVs
(appears on the TV home screen) and on Android phones.

The interface is the Bighorn web page in `app/src/main/assets/web/index.html`.
To update the app's interface, replace that file and rebuild.

## Build the APK

### Option A — GitHub (no computer needed, works from a phone browser)
1. Create a free account at github.com and make a new **private** repository (e.g. `bighorn`).
2. Upload this whole folder's contents to it, keeping the folder structure
   (easiest from a computer: drag the unzipped folder onto "uploading an existing file";
   or use the GitHub Desktop app).
3. Open the repository's **Actions** tab. The "Build Bighorn APK" job runs automatically on every upload
   (or press **Run workflow**). It takes about 3–5 minutes.
4. When it finishes with a green check, open the run and download **Bighorn-apk** under *Artifacts*.
   It's a zip containing `Bighorn.apk`.

### Option B — Android Studio (on a computer)
1. Install Android Studio, then **File → Open** this folder. Let it finish syncing.
2. **Build → Generate App Bundles or APKs → Generate APKs**.
3. The APK is in `app/build/outputs/apk/`.

## Install it

**Phone:** open the APK file and allow installing from that source when Android asks.

**Android TV / Google TV:**
- Easiest: install the free **Downloader** app (by AFTVnews) from the TV's Play Store, put the APK somewhere
  your TV can download it (e.g. a link from Google Drive/Dropbox, or the GitHub artifact link), open it in Downloader,
  and allow Downloader to install unknown apps when asked.
- Or with a computer: turn on Developer options on the TV (Settings → System → About → click *Android TV OS build* 7 times),
  enable *USB debugging / Network debugging*, then run `adb connect <TV-IP>` and `adb install Bighorn.apk`.

## Using it
- Enter your server as `192.168.x.x:8096` (the address you use for Jellyfin in a browser).
- Remote: arrows move, OK selects, Back closes the player/details/page.
- In the player: OK = play/pause, ←/→ = skip 10 s, ↑ = Back / Next Episode button.

## Notes
- Release builds are signed with the standard Android debug key, which is fine for installing yourself but not for the Play Store.
- `hls.js` (Apache 2.0, see `assets/web/hls.LICENSE.txt`) is bundled so streaming works without internet access.
