# GeoSurvey GNSS (Android)

A GNSS survey, path recording, distance measurement and path-following app for Android, built for use with external high-accuracy GNSS receivers (such as Quescan) via Mock Location.

## Modes & Features

1. **Path** – record a track
   - Continuous background GNSS logging via an Android `ForegroundService` (keeps recording with the screen locked).
   - Live cumulative distance (m / km), logged point counter, elevation, and ground speed (km/h).
   - Selectable **Accuracy Gate** (`≤0.5m RTK`, `≤2.0m`, or `All`) to reject multipath outliers.
   - **Undo Last Point**, **Pause/Resume**, and direct `.geojson` saving or sharing.

2. **Measure** – straight line distance between two points
   - Capture **Point A** and **Point B**, each averaged over 4 or 10 stationary epochs, with horizontal spread (`±m`) reported.
   - Reports **horizontal distance**, **slope distance**, **height difference (Δh)** and **true bearing** A → B.
   - Live distance from Point A to your current position.
   - Raw measurements only: no design standards, minimum distances or pass/fail checks are applied.

3. **Area** – feature & area survey
   - One-tap point feature logging with preset tags (`Kerb/Edge`, `Boundary`, `Pit/Drain`, `Power/Util`, `Tree`, `Sign/Post`) and custom notes.
   - Real-time polygon **area** (m² / ha) and **perimeter** using a local tangent plane (ENU) projection.
   - Exports tagged `Point` features and the closed `Polygon` boundary in one GeoJSON `FeatureCollection`.

4. **Follow** – navigate along a loaded GeoJSON path
   - Load a `.geojson` (folder button, top bar) containing a `LineString`, `MultiLineString` or polygon.
   - Pick the line and direction (Start → End or End → Start), then **Start Following**.
   - A large **direction arrow** points to a target a few metres further along the path, so it steers you back onto the line if you drift. Instructions: *Straight ahead / Bear / Turn left or right / Turn around*.
   - Shows **distance left/right of path**, **remaining**, **covered** and **total** distance with a progress bar, and announces arrival at the end.
   - Heading comes from the GPS course when moving (≥1.5 m/s) and from the phone compass (corrected for magnetic declination) when walking slowly or standing still.
   - The map recentres on you, highlights the followed line in orange and draws a dashed guide line; the screen stays awake while following.
   - Handles out-and-back and looping routes by keeping track of progress along the path.

5. **Field & Map Utilities**
   - **Mock GNSS Source Verification:** top-bar badge shows `EXT GNSS` (teal) for external mock coordinates or `PHONE GPS` (orange) if the external receiver disconnects.
   - **GeoJSON Overlay Import** for reference in the field.
   - **Floating Map Controls:** centre on GNSS fix, fit to survey bounds, and switch between OpenStreetMap and OpenTopo layers.

## Build

GitHub Actions (`.github/workflows/build.yml`) runs the unit tests and builds a debug APK on every push (or manually via `workflow_dispatch`). Download the `GeoSurvey-Debug-APK` artifact from the workflow run and install `app-debug.apk` on the phone (allow installs from unknown sources).

Locally: `gradle testDebugUnitTest assembleDebug` with JDK 17 and the Android SDK.
