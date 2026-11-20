# Yuno Desktop

Yuno is an ad-free YouTube client built with Kotlin and Compose Desktop. This branch targets Linux and Windows; the Android app lives on its own branch.

## Features

- YouTube video search powered by NewPipeExtractor.
- Video metadata and stream extraction, with playback handed to mpv.
- A desktop-first layout with Home, Search, Library and Settings.
- Saved videos and recent searches stored in the current user's desktop preferences.
- Optional YouTube session import. Session cookies are stored only in the operating system credential vault:
  - Linux: Secret Service through `secret-tool` (GNOME Keyring, KWallet or another compatible provider).
  - Windows: Windows Credential Manager.
- Linux `.deb` and `.rpm` packages, a portable Linux `.tar.gz` build, plus Windows `.msi` and `.exe` installers from GitHub Actions.

## Requirements

- JDK 17 or newer to build from source.
- mpv installed and available in `PATH` for playback.
- On Linux, a Secret Service provider and `secret-tool` (usually provided by `libsecret` tools) to save an account session.

Yuno can search and play public videos without signing in. The optional account session is not written to application preferences or an unencrypted file. If the operating system keyring is unavailable, Yuno will not store the session.

## Build

```sh
./gradlew :desktopApp:run
```

Create native packages and a portable app image:

```sh
./gradlew :desktopApp:createDistributable :desktopApp:packageDeb :desktopApp:packageRpm
```

On Windows, run:

```powershell
./gradlew.bat :desktopApp:packageMsi :desktopApp:packageExe
```

CI builds Linux `.deb`/`.rpm` and portable `.tar.gz` packages, plus Windows `.msi`/`.exe` installers on every push to the `desktop` branch. Download the artifacts from the **Desktop builds** workflow.

## Playback

Yuno extracts stream URLs with NewPipeExtractor and passes the selected video and audio streams to mpv. Install mpv separately on the target machine; it is not bundled into the installers.

## Disclaimer

Yuno is an independent client and is not affiliated with, endorsed by, or connected to YouTube or Google. Use it in accordance with applicable laws and service terms. Google may restrict accounts used with unofficial clients.

## License

GNU General Public License v3.0. See [LICENSE](LICENSE).