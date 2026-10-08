# LocalMix

A BitChord fork for people who keep music on their phone as well as listen through YouTube Music.

I started LocalMix to make my own files and M3U playlists feel like part of the same library. You can play a local playlist, open an artist's available tracks offline, or start a radio from a song you already own.

The name and Android package are different so you can keep **LocalMix and the original BitChord installed side by side**. LocalMix does not replace BitChord, and each app keeps its own settings, accounts and library data.

This public fork keeps BitChord's original features, including its account integrations and social features. The LocalMix changes are added on top.

The app opens in English, including all LocalMix screens. BitChord's other languages are still available in settings.

## What's added

| Area | Changes |
| --- | --- |
| Local playlists | Import `.m3u` or `.m3u8` files individually or from a folder. Keep the original order, repeated entries and missing-file rows. Reimport an edited playlist in place. |
| Local playback | Recognize local copies of catalog tracks and use them when **Use local files instead of streaming** is enabled. Review, confirm or detach a match. |
| Radio | Start YouTube Music radio from a local file. The app finds a matching catalog recording, or asks you to choose when it is unsure. |
| Artist pages | Show downloaded and local tracks offline, including credited guest artists. Load the online artist page when a connection is available. |
| Home screen | Choose sections, reorder them, and switch between grids and carousels. Add your playlists, favorites, recently played, most played, recently added files and artists. Carousels snap to a card. |
| Navigation and queue | Previously played tracks stay visible in the queue. Swipe up from the full player to open it, and swipe between the main tabs. |
| Transitions and autoplay | Song transition settings for Automix and Crossfade, plus filtering that stops autoplay from immediately returning another result with the seed song's title. |
| Backups | Include imported playlist records, local matches and radio choices in the app's backup. Audio files are kept separately. |

The app also keeps BitChord's playback, downloads, lyrics, animated artwork, source modules, equalizer and listening statistics. Google sign-in, Spotify login and playlist import, Spotify Canvas, Discord Rich Presence, Last.fm, ListenBrainz and Listen Together remain in the project.

Some integrations need an account, service configuration or a working external server. Keeping them in the fork does not mean every service has been tested with LocalMix.

## Does my music need special preparation?

Usually, no. Grant access to your music and let Android scan the files. **Title and artist tags make matching much more reliable**, and the track's duration helps distinguish recordings. Keep version information such as “Live” or a remix name in the title.

You do not need YouTube IDs in your tags, a special folder layout, or an M3U playlist to start a radio. A filename is a fallback for a missing title, but it cannot reliably replace a missing artist tag.

Radio needs an internet connection and a corresponding recording on YouTube Music. LocalMix searches using the song's metadata and compares the results; it does not upload the audio or use audio fingerprinting. If the match is unclear, you can search manually or say that none of the results are right. Playing the local file itself does not require a catalog match.

See [Using your local music](docs/LOCAL_MUSIC.md) for playlist paths, moved folders, matching and offline playback.

## Try it

LocalMix is an early Android build. Download the universal preview APK from [Releases](https://github.com/shymon999/BitChord-LocalMix/releases). It supports ARM and x86_64 phones. There is no stable release yet.

Development builds use a debug signing key. An APK from another build machine may have a different key and require a separate installation or an uninstall before it can replace your existing development build. Export your app backup first if you need to uninstall.

Requirements: Android 8.0 or newer. LocalMix's Android package is `io.github.shymon999.localmix`; benchmark builds add `.benchmark`.

The repository still contains BitChord's desktop and backend code. The LocalMix changes and build instructions here focus on Android.

## Build from source

Use JDK 21, Android SDK platform `android-37.0`, and CMake 3.22.1 with the Android NDK. Install the platform with `sdkmanager "platforms;android-37.0"` and set up your SDK location in `local.properties` or through `ANDROID_HOME`, then run:

```bash
./gradlew -PlocalmixCompileSdk=android-37.0 :app:assembleLocalmixBenchmark
```

On Windows:

```powershell
.\gradlew.bat -PlocalmixCompileSdk=android-37.0 :app:assembleLocalmixBenchmark
```

APKs are written to `app/build/outputs/apk/localmix/benchmark/`. This build has release optimizations and debug signing. Use `:app:assembleLocalmixDebug` for ordinary development, or configure your own signing key using `keystore.properties.example` before making a release build.

Last.fm builds can take `LASTFM_API_KEY` and `LASTFM_SECRET` from your local properties or environment. Listen Together can use `LISTEN_TOGETHER_SERVER`, and its server address can also be changed in the app. Keep credentials and signing keys out of Git.

## Bugs and contributions

Open an [issue](https://github.com/shymon999/BitChord-LocalMix/issues) for problems specific to this fork. Include your app version, Android version and the steps that reproduce the problem. For playlist bugs, a small made-up example is more useful than your whole music library.

See [CONTRIBUTING.md](CONTRIBUTING.md) for development and test commands.
The [build and test status](docs/TESTING.md) records what has actually been checked.

## Credits and license

LocalMix is based on [BitChord](https://github.com/kushagrasinghx/BitChord), by [Kushagra Singh](https://github.com/kushagrasinghx) and its [contributors](https://github.com/kushagrasinghx/BitChord/graphs/contributors). The player and its existing features are their work.

The transition work and autoplay filter also draw on BitChord [PR #582](https://github.com/kushagrasinghx/BitChord/pull/582) and [PR #528](https://github.com/kushagrasinghx/BitChord/pull/528).

Licensed under [GPL-3.0](LICENSE). LocalMix is an independent fork and is not affiliated with Google or YouTube.
