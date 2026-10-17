# GeoSurvey GNSS & SLA (Android)

A 3-mode GNSS survey, path recording, and driveway sight line analysis application built for Android with external high-accuracy GNSS receivers (such as Quescan) via Mock Location.

## Survey Modes & Features

1. **Path Tracker Mode (`supreme-funicular` parity + enhancements):**
   - Continuous background GNSS logging via an Android `ForegroundService` (keeps recording with the screen locked in your pocket).
   - Live cumulative distance (m / km), logged point counter, elevation, and ground speed (km/h).
   - Selectable **Accuracy Gate** (`≤0.5m RTK`, `≤2.0m`, or `All`) to automatically reject multipath outliers.
   - **Undo Last Point**, **Pause/Resume**, and direct `.geojson` saving or sharing.

2. **Driveway Sight Line Analysis (SLA) Mode:**
   - Configurable **4-epoch or 10-epoch stationary averaging** with horizontal spread (`±m` jitter) reporting.
   - Captures **Station A (Driveway Driver Eye)** and **Stations B, C, D (Approaching Sight Targets)**.
   - Automatically calculates **2D Horizontal Sight Distance**, **3D Slope Distance**, **Elevation Delta ($\Delta h$)**, **Road Grade (%)**, and **True Bearing (°)**.
   - Built-in **AS/NZS 2890.1 / Austroads Sight Distance Checker** for 40, 50, 60, 70, and 80 km/h speed zones with instant `PASS` / `SHORT` badges.

3. **Feature & Area Survey Mode:**
   - One-tap point feature logging with preset tags (`Kerb/Edge`, `Boundary`, `Pit/Drain`, `Power/Util`, `Tree`, `Sign/Post`) and custom notes.
   - Real-time **Geodesic Polygon Area** ($\text{m}^2$ and hectares) and **Perimeter** calculation using local tangent plane (ENU) projection.
   - Exports both tagged `Point` features and the closed `Polygon` boundary into a single GeoJSON `FeatureCollection`.

4. **Field & Map Utilities:**
   - **Mock GNSS Source Verification:** Top-bar badge displays `EXT GNSS` (Teal) when receiving external mock coordinates from your Quescan bridge app versus `PHONE GPS` (Orange) if the external receiver disconnects.
   - **GeoJSON Overlay Import:** Load existing `.geojson` files onto the map for reference in the field.
   - **Floating Map Controls:** Center on GNSS fix, Fit to Survey Bounds, and switch between Standard OpenStreetMap and Topographic (`OpenTopo`) map layers.

---

## Instructions for Cloud Agent / Automated Setup

If an automated cloud agent (Devin, Cursor, Copilot Workspace, Claude, etc.) is setting up this repository:

1. **Initialize Git Repository:**
   ```bash
   git init -b main
   git add .
   git commit -m "Initial commit: GeoSurvey 3-mode GNSS Android app with GitHub Actions"
   ```

2. **Push to Remote GitHub Repository:**
   ```bash
   gh repo create geosurvey-sla --public --source=. --remote=origin --push
   # OR link an existing remote URL:
   git remote add origin <GITHUB_REPO_URL>
   git push -u origin main
   ```

3. **Verify GitHub Actions Build:**
   - `.github/workflows/build.yml` runs automatically on push to `main` (or via manual `workflow_dispatch`).
   - Once completed (~2 minutes), download the `GeoSurvey-Debug-APK` artifact containing `app-debug.apk`.
