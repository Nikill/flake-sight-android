# Android Auto (parked)

**Status: parked until further notice.** Nothing here is scheduled. This file keeps what the port needs so it can start without re-reading the CarPlay scene. Reference: `SkiPatrol/CarPlay/`, `SkiPatrolKit/Sources/SkiPatrolKit/Models/CarPlay*.swift`, `docs/CARPLAY.md` and the README's CarPlay sections in `Nikill/ski-patrol` at the commit in `IOS_SYNC.md`.

## What CarPlay does

- **Category**: driving task (iOS backlog decisions D2 and D8). Its rules shape everything: no video, two templates deep, a refresh never faster than every ten seconds, no past images or settings.
- **Favourites** (first list template): the favourite resorts in the order they were added (`CarPlayCatalog.favorites`, `resortsByFavorites`), capped at the car's list limit. Each row: the name, then `CarPlayResortLine`: the bulletin's snow depth first, then the snow on the way ("▲ 120 ▼ 40 cm · Snow expected: 17 cm at the summit by Wed", from `ResortForecast.snowSummary`), or "Beaufortain · 10 webcams" when no snow is coming.
- **Resort** (second list template): one card per webcam with its image (`CarPlayCardShape`), subtitle "Updated 14:30 · Image" or "Last image 11 Apr · Image" (`WebcamSummary`), an "OLD IMAGE" badge on stale images; selecting a card reloads it. Directions buttons in the top bar lead to the car park, or the resort centre, in Apple Maps or Waze.
- **Data**: the same services as the app; the snow depth report is downloaded when CarPlay connects.
- **Preview**: debug builds of the iPhone app have a "CarPlay Preview" screen simulating both templates.

## Android port

- **Library**: Android for Cars App Library (`androidx.car.app`), a `CarAppService` in the phone app with a `Session` and two `Screen`s: `ListTemplate` for the favourites, `GridTemplate` (or `ListTemplate` with images) for a resort's webcams, an `ActionStrip` for Directions (a navigation intent to `Resort.drivingDestination`; the car's navigation app handles it).
- **Shared logic in `core`**: port `CarPlayResortLine` and its `CarPlaySupportTests`, `ResortForecast.snowSummary` with the snow summary tests left out of `ForecastDisplayTests`, `resortsByFavorites`, and the wording of `WebcamSummary` (labels in `strings.xml`).
- **Refresh**: no faster than the host allows (`invalidate()` budget); reload one card on selection, as CarPlay does.
- **Testing**: the Desktop Head Unit (DHU) from the SDK, as the iOS preview stands in for a car.

## Main risk: the app category

Android Auto only accepts apps in its published categories (media, messaging, navigation, points of interest such as parking and charging, IoT, weather, and the categories Google adds later). A webcam viewer fits none of them exactly. Before writing any code:

1. Check the current categories and the Android for Cars app quality guidelines on developer.android.com.
2. Decide whether the app fits "weather" (snow forecast and snow depth per resort, the webcam image as the weather's picture) or "point of interest" (resorts and their car parks), and what that category allows for images.
3. If none fits, Android Auto stays out; Android Automotive OS (parked cars) may be the alternative.

The CarPlay entitlement took a request to Apple (`docs/CARPLAY.md`); expect the Play review of a car app to be the equivalent gate.

## Open questions

- The category, above.
- Waze as a second Directions choice, as on iOS, or only the default navigation app.
- Whether a debug preview screen in the phone app is worth it when the DHU exists.
