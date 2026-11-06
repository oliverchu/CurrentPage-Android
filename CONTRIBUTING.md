# Contributing

Thanks for taking a look! This project is a small Android developer tool; issues and pull requests are welcome.

## Before you start

- Read the README and the privacy section. The app is intentionally offline: do not add network permissions, analytics or crash reporting SDKs.
- The accessibility service is the core of the app. Changes to event filtering or window handling should explain the scenario they fix.

## Build & test

Requirements: Android Studio with AGP 9.4 support, JDK 21, Android SDK Platform 37.

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

Please make sure all three commands pass before opening a PR. CI runs the same tasks.

## Code style

- Kotlin official style (`kotlin.code.style=official`).
- Keep comments focused on *why*; UI text goes into `values/strings.xml` and `values-zh/strings.xml`.
- Prefer pure functions for logic that can be unit tested (see `IdTagLayout`).

## Translations

The app ships with `values/` (English), `values-zh/` (Simplified Chinese) plus
`values-zh-rTW/`, `values-ja/`, `values-ko/`, `values-es/`, `values-fr/`,
`values-de/`, `values-ru/` and `values-pt/`. When adding or changing a string,
update **every** locale file and keep the `%1$s` placeholders identical; otherwise
Lint reports missing translations. Run `./gradlew :app:lintDebug` before opening
the PR.

## Release checklist (maintainers)

1. Decide the final `applicationId` before the first public release — changing it later breaks upgrades and the accessibility binding.
2. Never commit a keystore, `local.properties`, `*.apk` or IDE state (`.gitignore` already covers these). Release signing reads `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` from the local `local.properties`; v2 + v3 signing are enabled in `app/build.gradle.kts`. On CI, provide them as secrets or expect an unsigned release.
3. Bump `versionCode` / `versionName` in `app/build.gradle.kts`.
4. Run the three Gradle tasks above and smoke test on a real device:
   - enable accessibility, monitoring all apps and target mode;
   - collapse / expand, vertical drag, ID snapshot;
   - screen off / on and service rebind.
5. Tag the release and attach the signed APK to GitHub Releases. Do not publish to Google Play without checking its Accessibility API policy.
