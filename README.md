# LEMesh Horizon

**Lake Effect Mesh ([lemesh.us](https://lemesh.us))** — Android Kotlin & Jetpack Compose **915 MHz Viewshed & Relay Planner** (installed launcher label: **MeshHorizon**) for LoRa RF propagation, terrain viewsheds, Fresnel line-of-sight profiling, observed-vs-predicted link diagnostics, and 2-node relay bridge planning built around Meshtastic NodeDB exports and live telemetry feeds.

---

## Core Features

- **NodeDB Map Visualization & Live Telemetry Sync**
  - Opens Meshtastic NodeDB `.json` / `.geojson` exports via file picker or Android **Share / Open With** (`ACTION_VIEW` & `ACTION_SEND`) with an interactive **Merge into Current DB** vs **Replace DB** prompt.
  - Supports optional live MQTT v3.1.1 and local JSON telemetry sync that updates node positions, battery, and neighbor SNR without overwriting manual nodes or custom site notes.
  - Color-codes nodes by RF classification (Self, Favorite, Direct 0-Hop RF, Multi-Hop RF, MQTT) or per-node custom color overrides, plus Observed vs. Predicted RF link diagnostic tiers.
  - Supports **Add Node Mode** (tap map, use map center, or manual coordinate/spec entry), full node spec/notes editing (`CustomNodeStore`), search, RF sightlines, and No-GPS node inspection.
- **AWS Terrarium DEM Viewsheds, Offline Regional Packs & Aggregate RF Heatmaps**
  - Computes terrain-aware radial viewsheds and multi-node aggregate RSSI heatmaps using a shared decoded AWS Mapzen Terrarium DEM tile pool with offline regional DEM packs and crisp high-zoom overlay rendering.
- **915 MHz Fresnel & Knife-Edge Diffraction Line-of-Sight (LoS)**
  - Evaluates 100% and 60% first Fresnel zone (`F1`) clearances, 4/3 effective Earth curvature bulge, knife-edge diffraction loss, global seasonal clutter / storm fade margins (`0 dB` Winter, `+6 dB` Canopy, `+12 dB` Lake-Effect Squall), predicted RSSI, link margin, and exact mast height remediation.
- **Bidirectional Link Budgets, 2-Node Relay Bridge Finder & Antenna Alignment HUD**
  - Models independent TX, RX, and Relay transmit power (default `22 dBm`), antenna gain (default `3.0 dBi`), receiver sensitivity (`-130 dBm`), and mast heights (`AGL`), with two-stage coarse-to-fine relay candidate search and a real-time True-North Antenna Alignment Compass HUD.
- **Field & Outreach Exports**
  - **Host Outreach Spec Card (`.png`)**: Branded `LEMesh Horizon • lemesh.us` property-owner coverage cards that omit personal handheld nodes, mask portable sites (~1 km), and never embed property photos.
  - **KML (`.kml`)**: Color-coded `LEMesh Horizon • lemesh.us` placemarks, node specs, notes, RF sightlines, Fresnel paths, and viewshed boundaries ready for Google My Maps and Google Earth.
  - **NodeDB JSON Backup (`.json`)**: Full Meshtastic-compatible backup preserving manual nodes, edited specs, notes, and custom color overrides.
  - **GeoJSON (`.geojson`)** & **Annotated 2× PNG** map snapshots with watermarked Lake Effect Mesh emblem branding.

---

## Environment Configuration

Basemap tiles from CARTO use `CARTO_API_KEY` via the Secrets Gradle Plugin (`BuildConfig.CARTO_API_KEY`). Copy `.env.example` to `.env` at the repository root and set your key before building locally:

```bash
cp .env.example .env
```

```ini
# .env
CARTO_API_KEY=your_carto_api_key_here
```

> **Note:** `.env` is excluded by `.gitignore`. Never commit real API keys. Keyless basemaps (`OSM Standard`, `OpenTopoMap`) and CARTO public styles remain available if no key is provided.

---

## Building & Testing Locally

### Prerequisites
- JDK 17 (Temurin or equivalent)
- Android SDK (compileSdk 36, minSdk 26, targetSdk 36)

### Run Unit & Robolectric Tests
```bash
./gradlew testDebugUnitTest
```

### Build Debug APK
```bash
./gradlew assembleDebug
```
The output APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### Build Release App Bundle
```bash
./gradlew bundleRelease
```

---

## Continuous Integration

GitHub Actions (`.github/workflows/android-ci.yml`) runs on every `push` and `pull_request` to `main`:
1. Executes `./gradlew testDebugUnitTest` across all unit, RF propagation, KML/NodeDB merge, and Robolectric Compose UI test suites.
2. Builds the debug APK (`./gradlew assembleDebug`).
3. Uploads `app/build/outputs/apk/debug/app-debug.apk` as a workflow artifact.
