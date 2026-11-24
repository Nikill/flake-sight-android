# Wear OS app (parked)

**Status: parked until further notice.** Nothing here is scheduled. This file keeps what the port needs so it can start without re-reading the iOS Watch app. Reference: `SkiPatrolWatch/` and `SkiPatrolWatchWidgets/` in `Nikill/ski-patrol` at the commit in `IOS_SYNC.md`.

## What the iOS Watch app does

- **Resort list** (`SkiPatrolWatch/Features/ResortListView.swift`): a "Favourites" section with the favourites the iPhone sent (or "Add favourites in Flake Sight on your iPhone." when there are none), then every other resort, alphabetical, or nearest first under "Nearest" once located. Each row: the name with a yellow star for the default resort, the webcam count, the road distance when located, and the bulletin's snow depth ("▲ 105 ▼ 60 cm").
- **Resort webcams** (`ResortWebcamsView.swift`): the resort's webcams.
- **Webcam pages** (`WebcamView.swift`): one page per webcam, paged vertically with the Digital Crown. Each page: the image 120 pt high, a panorama panning sideways from its centre, the webcam name, the capture time (never an old image passed off as current), the provider; a Refresh button and an "Around 16:00" button to the afternoon images of previous days.
- **Complication** (`SkiPatrolWatchWidgets/`): the default resort's first webcam, through the same resolver and last-image store as the phone widget.
- **Deep link**: the complication opens `skipatrol://webcam/<id>` with the resort underneath for Back.
- **Sync**: the iPhone sends the favourites, the default resort and the snow depth report (`depthOnly`) over WatchConnectivity (`WatchSyncPayload`); the Watch keeps the last ones so it works without the phone at launch. The Watch downloads images itself.

## Android port

- **Module**: `wear`, Compose for Wear OS (Material 3 for Wear), depending on `core` only. Same `applicationId` as the phone app so the Data Layer pairs them.
- **Screens**: `ScalingLazyColumn` for the list (Favourites, then All Resorts or Nearest), `VerticalPager` driven by the rotary input for the webcam pages, the panorama in a horizontal scroll centred on start.
- **Sync**: Wearable Data Layer API (`DataClient`) instead of WatchConnectivity. The phone puts one `DataItem` (`/flake-sight/state`) holding the favourite IDs, the default resort ID and the snow depth report, written whenever one of them changes; the watch reads it at launch and listens for changes. Port `WatchSyncPayload` and `watchPayloadRoundTrips` / `watchPayloadRejectsOtherDictionaries` from `WidgetSupportTests` into `core` with that payload.
- **Complication**: a `ComplicationDataSourceService` giving `SMALL_IMAGE` / `PHOTO_IMAGE` of the default resort's first webcam (`WidgetWebcamResolver`, `LastSnapshotStore`), tapping into the webcam. A Tile may suit the image better than a complication: decide when the work starts.
- **Location**: the approximate position as on the phone (`LocationProvider`), for the Nearest section and the distances.
- **Network**: the watch fetches images itself (Wi-Fi or LTE, or through the phone's Bluetooth proxy). Keep the thumbnail size and decode straight to the page size: watches have little memory.
- **Strings**: the Watch's strings, in English and French, in the `wear` module's own `strings.xml`.

## Depends on

- Afternoon images (`AfternoonSnapshotService`, the "Around 16:00" button), not yet ported on the phone either.
- `resortsByFavorites` in `ResortRepository` (the Watch and CarPlay grouping), not yet ported.

## Open questions

- Complication or Tile for the image, or both.
- Whether a standalone watch (no phone app) should be supported: then favourites would be edited on the watch.
- Play Store listing for Wear OS (screenshots, the watch's own privacy section).
