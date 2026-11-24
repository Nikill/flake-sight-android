# Flake Sight for Android: notes for Claude Code

Android port of Flake Sight, the ski resort webcam app. The iOS app in `Nikill/ski-patrol` is the reference: read its `README.md` for the product and architecture, and `IOS_SYNC.md` here for how the two repositories stay in step.

## Workflow

- `main` is the integration branch. Work on a topic branch and open a pull request into `main` with `gh pr create --base main`.
- One concern per commit, imperative subject line, body says why.
- The developer works on Windows and the repository is checked out on macOS too: keep `.gitattributes` (LF line endings) and never commit IDE folders or `local.properties`.

## Commands

```sh
# Core tests (plain JVM, no Android SDK needed)
./gradlew :core:test

# Everything CI runs
./gradlew test :app:assembleDebug
```

## Project rules

- Modules: `core` is the Kotlin port of SkiPatrolKit, plain Kotlin/JVM; `app` is the phone app in Jetpack Compose. Wear OS and Android Auto modules come later.
- `core` never depends on Android. Logic that decides what to show (wording, order, freshness, caching, parsing) goes in `core` with tests; drawing goes in `app`.
- Port behaviour from the Swift sources and their tests rather than reinventing it. A Kotlin test keeps the name of the Swift test it ports.
- Kotlin warnings are errors in `core`.
- Every user-visible string goes in `strings.xml` (British English, "Favourites") with its French translation in `values-fr/strings.xml`. Identifiers use American spelling (`favorites`).
- Freshness is the camera's capture time, never the download time. A webcam is video only when `Webcam.hasLiveStream` is true.
- `ResortCatalog.json` is copied from the iOS repository and never edited here (see `IOS_SYNC.md`).
- No analytics, no account, no server of our own: same privacy promise as the iOS app.

## Verification before reporting done

Run `./gradlew test :app:assembleDebug` and say exactly what was run and what was not (emulator or device checks, widget behaviour). When a change ports an iOS area, update the port status table in `IOS_SYNC.md`.
