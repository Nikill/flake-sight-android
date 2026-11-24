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
| `ResortRepository` (bundled, lookup, search, `sortedResorts`) | Partly | `sortedResorts` for the name and snow orders with "Favourites on Top"; distance order, `available()`, withdrawn content and credits not yet |
| `SnowTotals`, `ResortSortOrder`, `Resort.snowRankingLevel`, `ForecastService.snowTotals` | Partly | totals, batching, cache and the snow orders; `ResortSortOrder` has no `distance` yet (a stored `distance` reads as name). `SnowBand` and the sentences for the map not yet |
| `SnowTotalsTests` | Ported | `labelsSkipLessThanACentimetre` checks the centimetres the app formats |
| `CatalogTests` | Ported | except `creditsListPlatformsByWeightAndResortWebsites` |
| `ResortSeason` phase and wording | Not yet | |
| Providers (`DirectImage`, Skaping, Roundshot, Viewsurf), registry, `WebcamService`, freshness | Ported | `core/.../WebcamService.kt`, `*Provider.kt`, `SnapshotFreshness.kt`. EXIF read by `Exif.kt` instead of ImageIO. Archive lookup (`ArchiveProbe`, `archivedSnapshotReference`) comes with the afternoon images. `label`/`systemImage` wording goes in the app with the first screen showing an image |
| `ProviderTests`, `WebcamServiceCacheTests`, `WebcamServiceCaptureTimeTests`, `FreshnessOrderingTests` | Ported | except `downsamplerLimitsLongestSide` (decoding lives in the app); `ArchiveProviderTests` and `LiveSourceTests` not yet |
| `SnapshotLoader`, `ImageDownsampler`, `LastSnapshotStore` | Not yet | app side and widgets |
| `ForecastService` (daily, hourly, snow totals), `ResortForecast`, `DetailedForecast`, `ForecastLevel`, `DailyForecast`, `WeatherCondition` | Partly | daily and hourly forecasts with their caches, snow totals; wording moves to the app's strings, `core` gives whole centimetres, degrees and weekdays. Models' snowfall and `SnowfallRange`, `snowSummary` not yet |
| `ForecastServiceTests`, `ForecastDisplayTests` | Partly | daily, hourly, HTTP and cache tests; display tests as values. Models, snow summary and VoiceOver tests with those features |
| `ForecastBriefing` (`ForecastPeriod`, `UpcomingSnow`, `GoodSkiingDay`, `SnowDepth`) | Ported | sentences built in the app from the briefing's values |
| `ForecastBriefingTests` | Ported | wording tests check the values the sentences read; `sentencesReadInFrench` is the app's French strings |
| `SnowBand`, `MountainProfile`, Météo-France snow depth, avalanche bulletin | Not yet | |
| `FavoriteResort`, `FavoritesStore` | Ported | JSON file in the app's files instead of SwiftData, written atomically; a failed write throws and changes nothing |
| `FavoritesStoreTests` | Ported | plus Android-only persistence and failure tests |
| `ResortSortingTests` | Partly | name, snow and favourites tests; distance tests with that order |
| Widgets, afternoon images | Not yet | |
| Watch → Wear OS, CarPlay → Android Auto | Not yet | after the phone app |

| iOS app screen | Android | Notes |
|---|---|---|
| App icon | Ported | adaptive icon from `AppIcon.png` on its corner colour |
| `ResortBrowserView` | Partly | stack below 600 dp; list beside the resort from 600 dp (unfolded foldable, tablet), split at the hinge in book posture; folding keeps the resort and webcam on screen. No map in the empty detail pane, no widget deep links |
| `ResortListView` | Partly | alphabetical, favourites first with their star and "Default"; search on names, regions and webcams; Sort menu with Name, Snow Forecast and Recent Snow and the "Favourites on Top" toggle, both remembered under the iOS keys; each row's recent snow, and the sorted figure on the right. No distance order, snow depth line, swipe to favourite, map or About |
| `ResortDetailView`, `WebcamCard` | Partly | header, website link, favourite star, Set as Default, forecast strip, webcam cards ordered by freshness; save failures shown as on iOS. No directions, season, snow depth card or avalanche badge |
| `ForecastStrip` | Partly | five days at each level (condition as emoji, snow, maximum), "Forecast Details" (snow soon, good days, recent snow, model snow depth) folded and remembered as on iOS, Open-Meteo credit, one TalkBack sentence per day. No snow bars, models' ranges or advanced weather screen |
| `WebcamDetailView` | Partly | panorama scrolling sideways from its middle, refresh, capture time, altitude, orientation, source link. No afternoon images (with their area); no live stream player, as no catalogued webcam has a stream |
