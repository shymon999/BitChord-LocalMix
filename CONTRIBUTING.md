# Contributing to LocalMix

Fixes, small UI improvements and documentation corrections are welcome. For a larger feature or redesign, open an issue first so we can agree on the scope.

Start a branch from `main` in this repository. Keep each pull request focused, explain the behavior that changes, and include the checks you actually ran. If a change came from BitChord or another project, link the original commit or pull request and keep its author and license notices.

## Development

Use JDK 21, Android SDK platform `android-37.0`, CMake 3.22.1 and the Android NDK. Install the platform with `sdkmanager "platforms;android-37.0"`. Android 8.0 is the minimum supported version. The app targets Android SDK 36.

Build the development variant:

```bash
./gradlew -PlocalmixCompileSdk=android-37.0 :app:assembleLocalmixDebug
```

Build with release optimizations for a device check:

```bash
./gradlew -PlocalmixCompileSdk=android-37.0 :app:assembleLocalmixBenchmark
```

On Windows, use `.\gradlew.bat` in place of `./gradlew`. Keep Gradle builds sequential on the same checkout.

Run the local-music domain tests:

```bash
./gradlew :localmix:test
```

Run Android unit tests for the development variant:

```bash
./gradlew -PlocalmixCompileSdk=android-37.0 :app:testLocalmixDebugUnitTest
```

You can select a relevant test class with `--tests "com.music.bitchord.OfflineArtistSongsTest"`. Some existing tests contact external services, so report network failures separately from failures in the changed code.

Listen Together's optional server is in `backend/`; follow its existing setup instructions when changing that integration.

## Changes and checks

- Follow the existing Kotlin and Compose structure. Keep path parsing and recording matching in the `localmix` domain module where they can be tested without Android.
- Use English for new interface text and documentation. Keep labels short; explain detailed behavior in the documentation rather than in persistent screen subtitles.
- Test meaningful edge cases for changes to matching, playlist resolution, queue behavior or backups. Use synthetic playlists and metadata rather than a personal music collection.
- Check navigation, permissions and offline behavior on a device when those flows change. A successful build alone does not prove that they work.
- For audio changes, distinguish the stream format, decoder output and Android audio output from behavior measured on actual hardware.
- Preserve the original BitChord features. Changes to an optional integration should keep the integration available unless its removal has been agreed on.

Do not commit accounts, tokens, signing keys, personal backups, absolute paths from your machine or full personal playlists. All contributions must be compatible with the project's [GPL-3.0 license](LICENSE). The existing [Code of Conduct](CODE_OF_CONDUCT.md) applies here too.
