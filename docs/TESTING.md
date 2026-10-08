# Build and test status

The initial public branch was checked locally on 2026-10-08:

- 341 LocalMix domain tests passed, covering playlist parsing, path resolution, recording matching, radio links and related library logic.
- 109 selected Android regression tests passed, including local backups, offline artist lists, browse routing, queue building, title normalization, transition settings, loudness handling and resource translations.
- The optimized `localmixBenchmark` APK built successfully with R8.

The Android check is a selected regression suite, not the whole upstream suite. Some upstream tests make live requests to music and lyrics services. The selected classes are listed in [the CI template](../.github/ci-templates/localmix.yml). Automatic CI is not enabled yet; the publishing account needs GitHub workflow permission to install this template under `.github/workflows/`.

This public package has not yet been checked on a phone. Earlier device checks used a separate personal build, so they do not establish that every retained integration works here. Account login, listening together and scrobbling still need checks with their own service configuration. Audio transition quality also needs listening tests.

The build reports Kotlin metadata warnings from lint and R8. They did not prevent compilation or the regression tests from completing.
