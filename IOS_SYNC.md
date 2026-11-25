# iOS sync

The iOS app ([Nikill/ski-patrol](https://github.com/Nikill/ski-patrol)) is the reference. This app follows what ships on its `main` branch.

## Reference commit

| Field | Value |
|---|---|
| iOS commit last synced | `e5618e699c65f1f6391f2f711201acb04f29c59d` (main, "Merge pull request #69 from Nikill/dev") |
| Synced on | 2026-11-24 |

Each sync reads the iOS commits after this one, ports what applies to Android, then updates this table in the same pull request.

Ported ahead of `main`: catalogue updates (decision D20, `dev` commits `307e88d` and `3aaefca`), at the developer's request. The bundled catalogue is still the one of the commit above.

## Rules

- `core/src/main/resources/ResortCatalog.json` is a verbatim copy of `SkiPatrolKit/Sources/SkiPatrolKit/Resources/ResortCatalog.json`. Never edit it here: catalogue changes are made in the iOS repository and copied over.
- When copying the catalogue, set `CatalogUpdate.BUNDLED_CATALOG_VERSION` to the iOS `MARKETING_VERSION` of the commit it comes from: `catalog-updates.json` names iOS versions in `appliesBefore`, and Android compares them with that constant, not with its own version.
- A change to a SkiPatrolKit test is mirrored in the Kotlin test of the same name in the same pull request. The Swift tests are the specification of `core`.
- Apple-only changes are skipped: CarPlay, `PrivacyInfo.xcprivacy`, the Xcode project, App Store material under `marketing/`.
- Every new user-visible string is added to `app/src/main/res/values/strings.xml` (British English) and `values-fr/strings.xml` (French).

## Port status

| SkiPatrolKit area | Android | Notes |
|---|---|---|
| Catalogue models (`Resort`, `Webcam`, `Parking`, `ResortSeason`, provider and capability types) | Ported | `core/.../Resort.kt`, `Webcam.kt` |
| `ResortRepository` (bundled, lookup, search, `sortedResorts`) | Partly | `sortedResorts` for every order with "Favourites on Top", the position as a `Coordinate` instead of `CLLocation`; `available()` is the app's `availableRepository` (the bundled catalogue `removing` the stored list); `resortsByFavorites` (CarPlay, Watch) not yet |
| `DisabledContent`, `DisabledContentLoader`, `ResortRepository.removing` | Ported | `core/.../DisabledContent.kt`: `DisabledContentStore` keeps the last list in the app's files instead of the App Group; the app refreshes it each time it comes to the foreground and redraws the widget when it changed. The list, the widget, its settings and the background capture start from it |
| `DisabledContentTests` | Ported | `sharedStateStoresTheList` becomes a file round trip; `watchPayloadCarriesTheList` with Wear OS |
| `CatalogUpdate`, `CatalogUpdateLoader`, `ResortRepository.applying`, `available()` | Ported | `core/.../CatalogUpdate.kt`: `CatalogUpdateStore` keeps the last update in the app's files; refreshed with the withdrawal list each time the app comes to the foreground, the widget redrawn when either changed. `available(update, disabled)` applies the update then the withdrawals. Versions compared with `BUNDLED_CATALOG_VERSION` (see Rules) |
| `CatalogRules` | Partly | `core/.../CatalogRules.kt`, every rule but the live video one, which comes with `liveVideoURL` |
| `CatalogUpdateTests` | Ported | `decodesTheFileAndStoresIt` becomes a file round trip, plus `failedRefreshKeepsTheStoredUpdate`; `travelsToTheWatch` with Wear OS |
| `Resort.distance`, `nearest` | Ported | `distanceFrom`, `Iterable<Resort>.nearest` |
| `DistanceSortingTests` | Ported | read `sortedResorts` instead of `resortsByFavorites` until CarPlay and the Watch are ported |
| `LocationProvider` | Ported | in the app (`LocationProvider.kt`): one fix on demand, approximate position only |
| `SnowTotals`, `ResortSortOrder`, `Resort.snowRankingLevel`, `ForecastService.snowTotals` | Partly | totals, batching, cache and every `ResortSortOrder`. The map's sentences are built in the app |
| `SnowTotalsTests` | Ported | `labelsSkipLessThanACentimetre` checks the centimetres the app formats |
| `CatalogTests` | Partly | the per-resort checks run through `CatalogRules`; the four new search cases (Vaujany, Combloux, Doucy, Hohneck) and the live video check come with the `dev` catalogue |
| `ResortRepository+Credits` (`creditedProviderTypes`, `hasResortWebsiteWebcams`) | Ported | in `ResortRepository.kt`; the platforms' names are `WebcamProviderType.brandName` |
| `ResortSeason` phase and wording | Ported | `phase` in `core`; the summary sentences are the app's strings (`seasonSummary` in `ResortScreen.kt`) |
| `ResortSeasonTests` | Ported | the summary tests check the phase and day each sentence reads |
| Providers (`DirectImage`, Skaping, Roundshot, Viewsurf), registry, `WebcamService`, freshness | Ported | `core/.../WebcamService.kt`, `*Provider.kt`, `SnapshotFreshness.kt`. EXIF read by `Exif.kt` instead of ImageIO. Archive lookup (`ArchiveProbe`, `keepsArchive`, `archivedSnapshotReference`) on Skaping and Roundshot, with HEAD requests through `HttpClient.head`. `label`/`systemImage` wording goes in the app with the first screen showing an image |
| `ProviderTests`, `WebcamServiceCacheTests`, `WebcamServiceCaptureTimeTests`, `FreshnessOrderingTests` | Ported | except `downsamplerLimitsLongestSide` (decoding lives in the app); `ArchiveProviderTests` ported; `LiveSourceTests` ported, off unless `SKIPATROL_LIVE_TESTS=1` as on iOS |
| `SnapshotLoader`, `ImageDownsampler` | Partly | in the app: `loadImage` and `decode` (Android's decoder straight to display size); the widget crops with `ContentScale.Crop` instead of `croppedThumbnail` |
| `LastSnapshotStore` | Ported | under the app's cache directory |
| `LastSnapshotStoreTests` | Ported | |
| `WidgetWebcamResolver`, `DeepLink` | Ported | `core/.../WidgetSupport.kt` |
| `FeaturedWebcam`, `FeaturedWebcamStore`, `Array.featuring` | Ported | `core/.../FeaturedWebcam.kt`: a JSON file of its own beside the favourites instead of SwiftData; `List<Webcam>.featuring`. The widget follows the default resort's choice and is redrawn when it changes |
| `FeaturedWebcamTests` | Ported | the shared-store test checks two files side by side; `theAppGroupKeepsTheChoices` becomes a file round trip; `theWatchReceivesTheChoices` with Wear OS |
| `WidgetSupportTests` | Partly | freshness boundary, resolver and deep link; the crop tests stay with the app's decoder; `SharedWidgetState` and the Watch payload have no Android counterpart (the widget runs in the app process and reads the favourites file) |
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
| Webcam widget (`WebcamWidget`, `WebcamTimelineProvider`) | Partly | Jetpack Glance, any size from 2 x 2: the webcam chosen in its settings (`WebcamWidgetConfigureActivity`, the port of `WebcamWidgetIntent`: the default resort, then every webcam by resort with a search; reopened from the widget's Settings from Android 12), else the default resort's first webcam, else the first catalogue resort's, name, resort and capture time over a gradient, the orange badge when stale, the last good image after a failed refresh; tapping opens the webcam. Refreshed every 30 minutes, 15 minutes after a failed download (`WidgetRetryWorker`, WorkManager, network required) and when the default resort changes. No entry at the moment an image turns stale (Android has no timelines): the badge comes with the next refresh, at most 30 minutes late |
| Afternoon images (`AfternoonDay`, `AfternoonWindow`, `AfternoonSnapshot`, `AfternoonSnapshotStore`, `AfternoonSnapshotService`) | Ported | `core/.../Afternoon.kt`; days are `LocalDate`s; the store's downscaling is the app's decoder (passed in), its directory the no-backup files; wording ("Today", "Yesterday", "Tue 27", "Yesterday, 15:30") in the app |
| `AfternoonWindowTests`, `AfternoonSnapshotStoreTests`, `AfternoonSnapshotServiceTests` | Ported | the two wording tests are the app's strings; the downscaling tests check the store keeps what its downscaler gives |
| `AfternoonCaptureTask` | Ported | `AfternoonCaptureWorker` (WorkManager) at the next 15:45 Paris time, booked at each launch and after each run, network required |
| `AfternoonLoader` | Ported | in `WebcamScreen` |
| Watch → Wear OS, CarPlay → Android Auto | Parked | until further notice; what each needs is in `docs/specs/WEAR_OS.md` and `docs/specs/ANDROID_AUTO.md`. iOS changes to the Watch and CarPlay are not synced meanwhile |

| iOS app screen | Android | Notes |
|---|---|---|
| App icon | Ported | adaptive icon from `AppIcon.png` on its corner colour |
| `ResortBrowserView` | Partly | stack below 600 dp; list beside the resort from 600 dp (unfolded foldable, tablet), split at the hinge in book posture; folding keeps the resort and webcam on screen; the map in the detail pane until a resort is chosen, and pushed from the list on a phone. The sidebar follows the iPad and iPhone Duo one: rows without separators, the selected resort as a rounded card, the map button off while the map shows. No widget deep links |
| `ResortListView` | Ported | alphabetical, favourites first with their star and "Default"; search on names, regions and webcams; Sort menu with Name, Distance, Snow Forecast and Recent Snow and the "Favourites on Top" toggle, both remembered under the iOS keys; distance refused goes back to names with the "Location Access Off" alert; each row's recent snow and bulletin snow depth, and the sorted figure on the right (road distance in km, or miles where the locale uses them). Map button in the top bar. Android only: rows as rounded cards without separators and the favourites on top in a rounded group of their own. About from an info button in the top bar. Swiping a row to the right adds it to the favourites or removes it, on release past the threshold, with the same change as a TalkBack action on the row |
| `AboutView`, `PrivacyPolicyView` | Ported | over both panes; no Licence link (Apple's agreement has no Android counterpart); the policy is the iOS text adapted to Android: the widget is part of the app, the map is Google Maps (a new paragraph), the afternoon images are named; three files from tanukee.com since D20 |
| `ResortMapView` | Partly | Google Maps (Maps Compose) instead of MapKit, its key from `local.properties`; a pin per resort, the snow layer with its legend and kind remembered under the iOS keys, snow pins on the `SnowBand` colours, the card with View Resort and Google Maps directions; the user's position and button once located, the map opening framed on them and the 15 nearest resorts |
| `ResortDetailView`, `WebcamCard` | Partly | large title collapsing on scroll, header, website and directions links, favourite star, Set as Default, snow card, avalanche badge, forecast strip, webcam cards ordered by freshness; save failures shown as on iOS, the season line ("Opening planned on 12 December"); a long press (or a TalkBack action) on a webcam puts it at the top with a star, "Show at the Top" / "Remove from the Top" |
| `SnowyMountainCard`, `AvalancheRiskBadge`, `AvalancheColor` | Ported | both open the advanced weather screen |
| `AdvancedForecastView` | Ported | the mountain profile, the recent snow, the bulletin section, the weather models table and the Open-Meteo credit; Try Again instead of pull to refresh |
| `MountainProfileView` | Ported | drawn in Compose, with each day's range and highest temperature (read by TalkBack), without the mountain outline; the legend wraps between entries, its 0 °C has an "i" opening `FreezingLevelInfoView` in a bottom sheet. The day order fix (8dc0044) does not apply: the columns are laid out in order |
| `WeatherModelsTable`, `WeatherModelsInfoView` | Ported | segmented level picker, each model's amount, the agreement row; an "i" beside the title opens who makes each model in a bottom sheet |
| `WeatherModelInfo` | Ported | `core/.../WeatherModelInfo.kt`, ids, names and providers' proper names; countries and details are the app's strings. `WeatherModelInfoTests` ported, its wording tests are the app's strings |
| `BulletinSection` | Ported | drawn in Compose: danger bands and slope rose, fresh snow (observed bars, forecast boxes from the low to the high hypothesis, axis in cm, TalkBack "forecast: between 10 and 20 cm"; `freshSnowReadsAsObservedOrForecast` is the app's strings), the 0 °C legend's "i", the week's depth lines, rain or snow bands with the freezing level and the wind every six hours |
| `ForecastStrip` | Partly | five days at each level (condition as emoji, snow, maximum), "Forecast Details" (snow soon, good days, recent snow, model snow depth) folded and remembered as on iOS, Open-Meteo credit, one TalkBack sentence per day with the range; the grid and the Advanced Weather button open the advanced screen. Snow bars sharp or fading with the models' agreement (`FuzzySnowBar`) |
| `WebcamDetailView` | Partly | panorama scrolling sideways from its middle, refresh, capture time, altitude, orientation, source link; "Around 16:00" with Now and one chip per day, the afternoon image with its day and time in place of the latest one, no Refresh while it shows. No live stream player, as no catalogued webcam has a stream |
