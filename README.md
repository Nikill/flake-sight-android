# Flake Sight for Android

Live webcams from French ski resorts, five-day snow forecasts and Météo-France avalanche bulletins. Android port of the iOS app ([Nikill/ski-patrol](https://github.com/Nikill/ski-patrol)), which remains the reference.

## Status

Early port. The catalogue (115 resorts, 351 webcams) loads and its tests pass; the app shows the resort list. See `IOS_SYNC.md` for what is ported.

## Requirements

- Android Studio (current stable), which brings the Android SDK and a JDK.
- JDK 17 or later for command-line builds.
- Android 10 (API 29) or later on the device or emulator. The app targets API 36.

## Layout

| Module | Role |
|---|---|
| `core/` | Kotlin port of SkiPatrolKit: models, catalogue, and later providers, caching and forecasts. Plain Kotlin/JVM with tests. |
| `app/` | Phone and tablet app in Jetpack Compose. |

## Building

Open the folder in Android Studio and run the `app` configuration on an emulator, or from a terminal:

```sh
./gradlew test :app:assembleDebug
```

On Windows use `gradlew.bat`.
