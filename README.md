# wayopps (Android)

A mapping, activity recording, distance measurement and path-following app for Android, built for use with external high-accuracy GNSS receivers (such as Quescan) via Mock Location.

## Modes & Features

1. **Path** – record runs, rides, walks and hikes
   - Clear recording flow: pick the activity type (and optionally a course), **Start** → **Pause** (and **Lap**) → then **Resume** to keep adding to the same track, **Finish…** to name it and save it to **History**, or **Discard**. Every recording has a name (tap it to rename at any time; defaults to e.g. "Run Sat 18 Oct 07:05"). Recording continues with the screen locked.
   - **Laps**: the **Lap** button records a manual split (time, distance, pace since the last lap).
   - **Courses & PBs**: save any route as a course (*Finish… › Also save the route as a course*, or *History › Save as course…*) — loops are detected automatically, or choose point to point. Pick the course before starting: laps are timed automatically from your closest pass of the start to your closest pass of the finish (back to back on a loop), only count when ≥80% of the course was covered, and show a live **gap to your PB** at the same point on the course (split every 50 m). Each lap plays a sound and notifies "Lap 3: 4:58 · New PB!". *☰ › Courses & PBs* lists courses with PB and a best-times table. Course PBs are kept when old activities are cleared by the retention setting.
   - Live distance, moving time, pace and speed; the path draws on the map as you go. Pace is your average over a trailing window set in **Settings › Pace measurement** (10 s, 20 s, 30 s (default), 1 min or 2 min) and the same window drives the pace alerts.
   - Selectable **Accuracy Gate** (≤0.5 / 2 / 5 / 10 (default) / 25 m) in Settings, with a status line that warns when fixes are being skipped. A point is logged once you move more than ~0.4× the fix accuracy (min 0.5 m), so standing still doesn't add jitter distance.
   - **Alerts** (🔔): any number of **distance alerts** — once (e.g. at 1 km) or repeating (every 1 km, every 500 m…) — each with its own system notification sound; and a **pace alert** with a target pace and separate *too slow* / *too fast* tolerances (5–20 %), each with its own sound, repeating every 30/60/120 s while out of range. Alerts play the sound, vibrate and post a notification.
   - **History** (☰ › History): every finished activity is stored in an on-device SQLite database, written point by point while recording (so a run survives the app being closed). Choose how long to keep them (7 / 30 / 90 days, 1 year, forever); older ones are deleted automatically. Show on the map (and follow it), export **GPX** (Strava/Garmin/QGIS) or GeoJSON, rename, delete, or share everything as one GPX.

2. **Measure** – multi-station straight line measuring (same workflow and maths as the [SLA web app](https://github.com/pucejuice/sla))
   - **＋ Station** opens a full-screen hold: the phone is held still for the hold time (default 30 s) split into epochs (default 4). Every fix better than the gate (default ±15 m) is averaged, weighted 1/accuracy²; the quoted **±spread** is the DRMS of the epoch means, which sees GNSS bias that fix-to-fix scatter does not.
   - The first station becomes the **origin**. Every further station is measured from the current origin, so you can shoot as many lines from one station as you like.
   - **Any station can be the origin at any time**: pick it from the "From ST…" selector, tap its marker on the map, or tap *use* in Records.
   - **Chain** toggle: each new station becomes the origin automatically, for consecutive (traverse-style) measuring. The panel shows the **cumulative chain distance** (e.g. ST1→ST4 · 3 legs · 45.67 m) and, with chain on, the running total including the live distance to you.
   - **Records** sheet groups lines under their origin station: *use*, *link…* (add lines from a station to other stored stations, computed from stored coordinates and flagged `linked`), *rename*, *delete*. **Undo** steps back the last station or link.
   - Distances are horizontal ground distances on the WGS84 ellipsoid (local tangent plane at the line midpoint, checked against Vincenty to < 1 mm over 1 km); bearings are grid bearings; line uncertainty is the two endpoint spreads in quadrature.
   - Optional **Record height** (Settings) adds Δh and slope distance. GNSS height is uncorrected and not AHD, so it is off by default.
   - Records are kept on the phone and survive closing the app. Export writes stations (Point) and `measured_line` features (2-point LineString) with distance, uncertainty, bearing and method.

3. **Area** – feature & area survey
   - One-tap point logging with a feature type and optional note. Types are a generic mapping list (Point of interest, Landmark, Viewpoint, Junction, Gate, Bridge, Water, Tree, Building, Sign, Parking, Hazard) that you can add to, delete from or clear entirely (*Edit feature types…*).
   - Real-time polygon **area** (m² / ha) and **perimeter** using a local tangent plane (ENU) projection; exports points and the closed polygon as GeoJSON.

4. **Follow** – navigate along a loaded GeoJSON path
   - Load a `.geojson` (folder button, top bar) containing a `LineString`, `MultiLineString` or polygon.
   - Pick the line and direction (Start → End or End → Start), then **Start Following**.
   - A large **direction arrow** points to a target a few metres further along the path, so it steers you back onto the line if you drift. Instructions: *Straight ahead / Bear / Turn left or right / Turn around*.
   - Shows **distance left/right of path**, **remaining**, **covered** and **total** distance with a progress bar, and announces arrival at the end.
   - Heading comes from the GPS course when moving (≥1.5 m/s) and from the phone compass (corrected for magnetic declination) when walking slowly or standing still.
   - The map recentres on you, highlights the followed line in orange and draws a dashed guide line; the screen stays awake while following.
   - Handles out-and-back and looping routes by keeping track of progress along the path.

5. **GNSS receiver bridge** (☰ › GNSS receiver, or tap the status badge)
   - Connects to an external receiver by **USB cable** (CDC-ACM, FTDI, CP210x, CH34x, PL2303 — via an OTG cable; plugging it in offers to open wayopps and connects automatically) or **Bluetooth** (paired SPP receivers).
   - Reads NMEA **GGA** (position, fix type, satellites, HDOP, MSL height + geoid separation), **RMC** (speed, course) and **GST** (position standard deviations → accuracy; otherwise estimated from HDOP and fix type). Checksums are verified.
   - Receiver fixes go straight into wayopps (badge shows **RTK / FLOAT / DGPS / GNSS** ±accuracy); phone GPS is ignored while the receiver is feeding.
   - **Share as phone location**: select wayopps in *Developer options › Select mock location app* and the receiver's position is published as the phone's location (GPS test provider + fused provider), so other apps use it too. Only one app can be the mock location app, so this replaces a separate bridge app.
   - While connected, the receiver keeps running in the background (notification with **Stop GPS**).
   - RTK corrections are not handled by wayopps (no NTRIP client yet): the receiver needs its own correction source to reach RTK fixed.

6. **Field & Map Utilities**
   - **GNSS status badge:** `EXT` for external (mock) receiver coordinates or `GPS` for the phone's own GPS, with horizontal accuracy, coloured green (RTK) / blue (sub-metre) / orange / red.
   - **Map-first layout:** the map fills the screen; each mode has a compact two-row control panel above the mode tabs at the bottom. Save / Share / Clear live behind a single save button.
   - **☰ Menu:** load or remove a GeoJSON overlay, and **Settings** (dark/light theme, path accuracy gate, station hold time / epochs / gate, record height).
   - **Look:** SLA colour palette, with the faceted slate Voronoi mesh from the SLA / ingeniir cover art on the top bar, the station hold screen, the Records header and the launcher icon.
   - **Map layers** (layers button on the map): Dark (Esri street map with a dark filter, default), Streets, Satellite and Topographic, all from Esri ArcGIS Online.
   - **Floating Map Controls:** centre on GNSS fix, fit to survey bounds, and layer picker.

## App ID

The Android application ID and code package are `com.wayopps.app` (from v2.1). Earlier builds used `com.example.geosurvey`, so v2.1 installs as a **separate app**: export anything you want to keep from the old one (History › All as GPX, Measure/Area exports) before uninstalling it.

## Build

GitHub Actions (`.github/workflows/build.yml`) runs the unit tests and builds a debug APK **on request**: Actions tab › *Build Android APK* › **Run workflow** (pick the branch), or push a version tag (`v*`). Ordinary pushes do not build. Download the `wayopps-debug-apk` artifact from the workflow run and install `app-debug.apk` on the phone (allow installs from unknown sources).

Locally: `gradle testDebugUnitTest assembleDebug` with JDK 17 and the Android SDK.
