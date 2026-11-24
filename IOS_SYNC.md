# iOS sync

The iOS app ([Nikill/ski-patrol](https://github.com/Nikill/ski-patrol)) is the reference. This app follows its `dev` branch, and may also port features from iOS pull requests still open (such as #70 to #73, the 1.3 redesign) when the developer says so: port from the pull request's latest commit, and note in the port status that it follows an open pull request, so a later change there is not missed.

## Reference point

| Field | Value |
|---|---|
| Last iOS changelog entry ported | 1.2, #69 |
| iOS commit at that point | `e5618e699c65f1f6391f2f711201acb04f29c59d` (main, "Merge pull request #69 from Nikill/dev") |
| Synced on | 2026-11-24 |

## How a sync works

1. Read `CHANGELOG.md` on the iOS `dev` branch (or on the open pull request's branch): every entry with a pull request number above the last one ported is to do. Each entry names the surfaces it touches.
2. Skip entries marked **[Apple only]**. **Catalogue** entries mean copying `ResortCatalog.json`. **Data** entries mean porting `SkiPatrolKit` code and its tests to `core`. **App**, **Widgets**, **Watch** and **CarPlay** map to the Android app, Glance widgets, Wear OS and Android Auto.
3. Read the merged pull request of each entry for the detail: the changelog line says what changes, the pull request says how.
4. Port, then update the table above and the port status below in the same pull request.

## Rules

- `core/src/main/resources/ResortCatalog.json` is a verbatim copy of `SkiPatrolKit/Sources/SkiPatrolKit/Resources/ResortCatalog.json`. Never edit it here: catalogue changes are made in the iOS repository and copied over.
- A change to a SkiPatrolKit test is mirrored in the Kotlin test of the same name in the same pull request. The Swift tests are the specification of `core`.
- Apple-only changes are skipped: `PrivacyInfo.xcprivacy`, the Xcode project, signing, App Store material under `marketing/`. The iOS changelog marks them **[Apple only]**.
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
