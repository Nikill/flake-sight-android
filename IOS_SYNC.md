# iOS sync

The iOS app ([Nikill/ski-patrol](https://github.com/Nikill/ski-patrol)) is the reference. This app follows what ships on its `main` branch.

## Reference commit

| Field | Value |
|---|---|
| iOS commit last synced | `e5618e699c65f1f6391f2f711201acb04f29c59d` (main, "Merge pull request #69 from Nikill/dev") |
| Synced on | 2026-11-24 |

Each sync reads the iOS commits after this one, ports what applies to Android, then updates this table in the same pull request.

## Rules

- `core/src/main/resources/ResortCatalog.json` is a verbatim copy of `SkiPatrolKit/Sources/SkiPatrolKit/Resources/ResortCatalog.json`. Never edit it here: catalogue changes are made in the iOS repository and copied over.
- A change to a SkiPatrolKit test is mirrored in the Kotlin test of the same name in the same pull request. The Swift tests are the specification of `core`.
- Apple-only changes are skipped: CarPlay, `PrivacyInfo.xcprivacy`, the Xcode project, App Store material under `marketing/`.
- Every new user-visible string is added to `app/src/main/res/values/strings.xml` (British English) and `values-fr/strings.xml` (French).

## Port status

| SkiPatrolKit area | Android | Notes |
|---|---|---|
| Catalogue models (`Resort`, `Webcam`, `Parking`, `ResortSeason`, provider and capability types) | Ported | `core/.../Resort.kt`, `Webcam.kt` |
| `ResortRepository` (bundled, lookup, search) | Partly | `available()` and withdrawn content not yet; credits not yet |
| `CatalogTests` | Ported | except `creditsListPlatformsByWeightAndResortWebsites` |
| `ResortSeason` phase and wording | Not yet | |
| Providers, `WebcamService`, freshness | Not yet | |
| Forecast, snow depth, avalanche bulletin | Not yet | |
| Favourites, widgets, afternoon images | Not yet | |
| Watch → Wear OS, CarPlay → Android Auto | Not yet | after the phone app |
