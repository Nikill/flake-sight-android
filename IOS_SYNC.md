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
| `ResortRepository` (bundled, lookup, search, `sortedResorts`) | Partly | `sortedResorts` for every order with "Favourites on Top", the position as a `Coordinate` instead of `CLLocation`; `resortsByFavorites` (CarPlay, Watch), `available()`, withdrawn content and credits not yet |
| `Resort.distance`, `nearest` | Ported | `distanceFrom`, `Iterable<Resort>.nearest` |
| `DistanceSortingTests` | Ported | read `sortedResorts` instead of `resortsByFavorites` until CarPlay and the Watch are ported |
| `LocationProvider` | Ported | in the app (`LocationProvider.kt`): one fix on demand, approximate position only |
| `SnowTotals`, `ResortSortOrder`, `Resort.snowRankingLevel`, `ForecastService.snowTotals` | Partly | totals, batching, cache and every `ResortSortOrder`. The map's sentences are built in the app |
| `SnowTotalsTests` | Ported | `labelsSkipLessThanACentimetre` checks the centimetres the app formats |
| `CatalogTests` | Ported | except `creditsListPlatformsByWeightAndResortWebsites` |
| `ResortSeason` phase and wording | Not yet | |
| Providers (`DirectImage`, Skaping, Roundshot, Viewsurf), registry, `WebcamService`, freshness | Ported | `core/.../WebcamService.kt`, `*Provider.kt`, `SnapshotFreshness.kt`. EXIF read by `Exif.kt` instead of ImageIO. Archive lookup (`ArchiveProbe`, `archivedSnapshotReference`) comes with the afternoon images. `label`/`systemImage` wording goes in the app with the first screen showing an image |
| `ProviderTests`, `WebcamServiceCacheTests`, `WebcamServiceCaptureTimeTests`, `FreshnessOrderingTests` | Ported | except `downsamplerLimitsLongestSide` (decoding lives in the app); `ArchiveProviderTests` and `LiveSourceTests` not yet |
| `SnapshotLoader`, `ImageDownsampler`, `LastSnapshotStore` | Not yet | app side and widgets |
| `ForecastService` (daily, hourly, models, snow totals), `ResortForecast`, `DetailedForecast`, `ForecastLevel`, `DailyForecast`, `WeatherCondition` | Partly | every request with its cache; wording moves to the app's strings, `core` gives whole centimetres, degrees and weekdays. `snowSummary` comes with CarPlay's Android Auto line |
| `ForecastServiceTests` | Ported | |
| `ForecastDisplayTests` | Partly | display tests as values; the snow summary and VoiceOver tests with Android Auto |
| `ForecastBriefing` (`ForecastPeriod`, `UpcomingSnow`, `GoodSkiingDay`, `SnowDepth`) | Ported | sentences built in the app from the briefing's values |
| `ForecastBriefingTests` | Ported | wording tests check the values the sentences read; `sentencesReadInFrench` is the app's French strings |
| `SnowDepthReport`, `SnowDepthReading`, `AvalancheRisk`, `SnowDepthLoader`, `FreshSnow`, `MountainWeather`, `SlopeAspect` | Partly | the whole file as the app reads it; the report is kept in the app's files instead of the App Group. `depthOnly` comes with Wear OS |
| `SnowDepthTests`, `AvalancheBulletinTests` | Partly | reading, wording values, slopes, fresh snow, mountain forecast, file and refresh tests; the CarPlay and Watch tests with those surfaces |
| `MountainProfile` | Ported | without the models' ranges, which come with `SnowfallRange` |
| `MountainProfileTests` | Ported | wording tests check the values the app formats |
| `SnowfallRange`, `ForecastConfidence`, `ModelSnowfall`, `SnowGaugeScale` | Ported | labels built in the app |
| `SnowfallRangeTests` | Ported | wording tests check the values the app formats; the VoiceOver sentences are the app's |
| `SnowBand`, `SnowTotals.band` | Ported | colours and titles in the app (`ResortMapScreen.kt`) |
| `SnowBandTests` | Ported | title and sentence tests check the bounds and totals the app formats |
| `FavoriteResort`, `FavoritesStore` | Ported | JSON file in the app's files instead of SwiftData, written atomically; a failed write throws and changes nothing |
| `FavoritesStoreTests` | Ported | plus Android-only persistence and failure tests |
| `ResortSortingTests` | Ported | |
| Widgets, afternoon images | Not yet | |
| Watch → Wear OS, CarPlay → Android Auto | Not yet | after the phone app |

| iOS app screen | Android | Notes |
|---|---|---|
| App icon | Ported | adaptive icon from `AppIcon.png` on its corner colour |
| `ResortBrowserView` | Partly | stack below 600 dp; list beside the resort from 600 dp (unfolded foldable, tablet), split at the hinge in book posture; folding keeps the resort and webcam on screen; the map in the detail pane until a resort is chosen, and pushed from the list on a phone. The sidebar follows the iPad and iPhone Duo one: rows without separators, the selected resort as a rounded card, the map button off while the map shows. No widget deep links |
| `ResortListView` | Partly | alphabetical, favourites first with their star and "Default"; search on names, regions and webcams; Sort menu with Name, Distance, Snow Forecast and Recent Snow and the "Favourites on Top" toggle, both remembered under the iOS keys; distance refused goes back to names with the "Location Access Off" alert; each row's recent snow and bulletin snow depth, and the sorted figure on the right (road distance in km, or miles where the locale uses them). Map button in the top bar. No swipe to favourite or About |
| `ResortMapView` | Partly | Google Maps (Maps Compose) instead of MapKit, its key from `local.properties`; a pin per resort, the snow layer with its legend and kind remembered under the iOS keys, snow pins on the `SnowBand` colours, the card with View Resort and Google Maps directions; the user's position and button once located, the map opening framed on them and the 15 nearest resorts |
| `ResortDetailView`, `WebcamCard` | Partly | large title collapsing on scroll, header, website and directions links, favourite star, Set as Default, snow card, avalanche badge, forecast strip, webcam cards ordered by freshness; save failures shown as on iOS. No season |
| `SnowyMountainCard`, `AvalancheRiskBadge`, `AvalancheColor` | Ported | both open the advanced weather screen |
| `AdvancedForecastView` | Ported | the mountain profile, the recent snow, the bulletin section, the weather models table and the Open-Meteo credit; Try Again instead of pull to refresh |
| `MountainProfileView` | Ported | drawn in Compose, with each day's range |
| `WeatherModelsTable` | Ported | segmented level picker, each model's amount, the agreement row |
| `BulletinSection` | Ported | drawn in Compose: danger bands and slope rose, fresh snow bars, the week's depth lines, rain or snow bands with the freezing level and the wind every six hours |
| `ForecastStrip` | Partly | five days at each level (condition as emoji, snow, maximum), "Forecast Details" (snow soon, good days, recent snow, model snow depth) folded and remembered as on iOS, Open-Meteo credit, one TalkBack sentence per day with the range; the grid and the Advanced Weather button open the advanced screen. Snow bars sharp or fading with the models' agreement (`FuzzySnowBar`) |
| `WebcamDetailView` | Partly | panorama scrolling sideways from its middle, refresh, capture time, altitude, orientation, source link. No afternoon images (with their area); no live stream player, as no catalogued webcam has a stream |
