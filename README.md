# MLBB Draft Companion

A two-part Mobile Legends: Bang Bang draft assistant:

| Part | What it does |
|------|--------------|
| `scraper/` | Python pipeline that aggregates hero roles, counters, win-rate edges, item answers and lane tips from four public sources into one typed `matchups.json`, downloads official hero portraits as vision templates, and emits a `manifest.json` for the app's self-updater. |
| `android/` | Kotlin/Compose app. A foreground `MediaProjection` service samples the screen ~1×/s, template-matches the draft slots with OpenCV, scores counter picks for **your** lane against the **whole** enemy team (with a +40 % boost for your mastered heroes), shows a draggable floating card, switches to a lane briefing when the match loads, and fades out by itself. |

No game memory is read, no accessibility service, no input injection: the app only looks at pixels you have explicitly allowed it to capture.

---

## 1 · Scraper

```bash
cd scraper
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
python scraper.py --android-dir ../android/app/src/main     # ≈5 min, ~530 polite requests
```

Outputs in `scraper/out/`:

* `matchups.json` — 133 heroes, ~1 600 directed counter edges, lane tips, item counters
* `templates/*.png` + `index.json` — 96×96 template arts per hero: the website icon (`<id>.png`, matches ban slots) and the in-game **head** painting from Moonton's own site API (`<id>_1.png`, the exact art on draft-pick cards). The weekly job refreshes them, so new and reworked heroes are picked up automatically.
* `templates.zip`, `manifest.json` — what the app downloads when self-updating

Sources and how they are merged:

| Source | Gives | Weight |
|--------|-------|--------|
| Fandom wiki (MediaWiki API) | canonical hero list, class, specialties, recommended lanes, icons | authoritative for roster |
| api.gms.moontontech.com (Moonton site API) | in-game head art per hero (draft-card painting), icon | vision templates |
| mlbbhub.com `/counter/<hero>` | ranked win-rate edge (pp), kit-matchup reasons, official icon URL | numeric → `2.2 + 0.8·pp` |
| mlbb.io `/en/hero/<hero>/counter` | ranked edge (%), one-line reasons, item answers, "how to play against" | numeric → same scale |
| mlcounter.com `/heroes/<hero>/` | ordered weak/strong-against lists, item counters, tips, lane-specific advice | rank → 4.0…3.0 |
| `overrides.json` | your own curated counters / tips | always wins |

Edges from several sources are averaged and get a small agreement bonus; every edge records its `sources` and `confidence`. The graph is symmetrised so the engine can look a pair up from either side. `mlbbcounters.com` was evaluated and skipped: all of its hero pages 404.

Useful flags: `--limit 10` (smoke test), `--offline` (rebuild from `.cache/`), `--skip-source mlcounter`, `--no-portraits`, `--base-url <where you host out/>`.

### Keeping the app's data fresh (new heroes, patch shifts)

1. The repo lives at https://github.com/Tasneem-Qureshi/mlcounterpicks ; the latest sideload APK is on the `data` branch.
2. `.github/workflows/scrape.yml` re-runs the scraper every Monday and force-pushes `manifest.json`, `matchups.json`, `templates.zip` to a `data` branch.
3. Set `DATA_MANIFEST_URL` in `android/app/build.gradle.kts` to  
   `https://raw.githubusercontent.com/Tasneem-Qureshi/mlcounterpicks/data/manifest.json`.
4. The app checks once a day (WorkManager, needs network + battery not low) and on "Check for updates". A newer `data_version` triggers: download → SHA-256 verify → parse/validate → atomic file swap → Room re-seed → live reload of templates in the running service. Mastery flags survive updates. If anything fails, nothing changes and the bundled dataset remains.

Any static host works instead of GitHub (S3, Cloudflare Pages…); just point `--base-url` and `DATA_MANIFEST_URL` at it.

---

## 2 · Android app

### Toolchain
Matches your Android Studio 2026.1.3: Gradle 9.6, AGP 9.3 (built-in Kotlin 2.2.10), Compose compiler 2.2.10, KSP 2.2.10-2.0.2, Room 2.7.2, Compose BOM 2025.06, OpenCV 4.10 from Maven Central (no manual SDK import). Open `android/` in Android Studio or:

```bash
cd android
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

### Key files

| File | Role |
|------|------|
| `ui/MasterySettingsScreen.kt` | Pick mastered heroes per lane (persisted in Room `heroes.is_preferred`), Strict/Flexible switch (DataStore) |
| `ui/HomeScreen.kt` | Permissions, Start/Stop, default lane, overlay tuning, data update status |
| `capture/ScreenCaptureService.kt` | `FOREGROUND_SERVICE_MEDIA_PROJECTION` service, `ImageReader` loop, adaptive sampling, slot cropping, rotation handling |
| `vision/VisionDetector.kt` | per-slot change cache → coarse pass over every art → fine multi-scale `matchTemplate` of the icon/head face inside the card, both orientations (the game mirrors some cards) |
| `vision/DraftStateMachine.kt` | 2-of-3 temporal voting per slot; IDLE → DRAFTING → LOCKED → IN_GAME → COOLDOWN |
| `engine/CounterEngine.kt` | role filter · 40 % direct lane · 60 % team · ×1.4 mastery · reasons & warnings (pure Kotlin, unit-tested) |
| `engine/RoleAssigner.kt` | optimal enemy → lane assignment (exhaustive over ≤120 permutations) |
| `overlay/OverlayManager.kt` | `TYPE_APPLICATION_OVERLAY` window hosting Compose via a custom lifecycle owner; drag, collapse, fade |
| `capture/DraftCoordinator.kt` | state → recommendations → overlay; briefing timer and auto-dismiss |
| `data/update/DataUpdater.kt` | manifest polling, SHA-256 verification, atomic swap, re-seed |

### Scoring

```
direct = norm(edge(candidate, enemy in my lane))        norm(x) = x/10 + 0.5   (x ∈ [-5, 5])
team   = mean over the other enemies of norm(edge(candidate, enemy))
score  = (0.40·direct + 0.60·team) × (1.03 if the lane is the hero's primary lane) [× 1.40 if mastered and "Boost mastered" is on]
```
`edge(a, b)` combines `b.counters ∋ a` / `a.strong_against ∋ b` (positive) against the reverse (negative). If the lane opponent is not picked yet, `direct` mirrors `team` so weights still sum to 1. The overlay lists every favourable pick (composite > 0.5, at least 5, up to 20) best first; mastered heroes carry a ⭐ badge and only move up if "Boost mastered" is switched on. Strict mode restricts the pool to mastered heroes and falls back (flagged in the overlay) when none plays the lane. The 1-line reason is composed from the strongest contributing edges ("Hard-counters Yu Zhong in lane · beats Brody, Tigreal · wins the straight damage trade"), and a "Weak into X" warning appears for a strongly negative team matchup.

### Modes
* **Overlay only (default, no screen sharing).** Press Start once; with Usage access the card appears whenever MLBB comes to the front. Tap *Pick enemies* on the card to enter the enemy picks and your lane; counters update instantly.
* **Screen detection (optional).** Enable from the notification's *Screen detection* action or the app. Android asks for capture consent each time. Detection switches itself off after the draft briefing, or after 10 minutes without a draft.

### Google account sync
Mastered heroes, lane and mode sync to a hidden app-data file in the user's Google Drive (last-write-wins). One-time setup in [Google Cloud Console](https://console.cloud.google.com/):
1. Create a project → *APIs & Services → Library* → enable **Google Drive API**.
2. *OAuth consent screen*: External, app name "MLBB Draft Companion", your email; add scope `.../auth/drive.appdata`; add yourself as a test user (or publish).
3. *Credentials → Create credentials → OAuth client ID → Android*: package `com.dripagency.mlbbdraft`, SHA-1 of the signing keystore (`keytool -list -v -keystore ~/.android/debug.keystore -storepass android`).
No client id goes into the app; Play Services matches package + SHA-1.

### First run on a phone
1. Install the debug APK, open the app, grant **Draw over other apps** and **Notifications**.
2. Choose your lane; add mastered heroes.
3. Press **Start**. Optionally enable screen detection from the notification.
4. Open MLBB → ranked draft. The card appears when the first portrait is recognised and refreshes as picks come in. After the draft, the loading screen triggers the lane briefing which closes itself after 18 s (configurable).

### Calibrating slot positions (do this once per device)
Slot rectangles live in `app/src/main/assets/layouts.json` (normalised 0–1 coordinates, three aspect-ratio profiles). Turn on **Calibration dump** on the Home screen, enter a draft, then pull  
`Android/data/com.dripagency.mlbbdraft.debug/files/calibration/frame_*.png` — the frame is annotated with the crop boxes (cyan allies, red enemies, magenta bans, green lane badges) and the hero id read in each box. Adjust the numbers until the boxes sit on the portraits; a copy at `files/layouts.json` overrides the asset without rebuilding.

Lane auto-detection needs five small badge crops at `assets/lanes/{exp,gold,mid,roam,jungle}.png` (≈28 px, taken from a calibration frame). Without them the app uses your default lane — which is what you want most of the time anyway.

### Battery / performance
* 1 frame per 1.2 s while drafting, 2.5 s+ when idle; portrait frames are skipped instantly.
* One `ImageReader` with a reusable bitmap; one grayscale `Mat` per frame; 20 crops × ≤12 template matches ≈ 5–10 ms on a mid-range SoC.
* The overlay window is detached (not just hidden) when dismissed.

### Policy note
Screen capture + overlay is the same mechanism screen recorders and game bars use, and the app never interacts with the game process. Moonton's terms still prohibit "third-party tools that give an unfair advantage" in broad language; use the companion at your own discretion, keep it to the draft phase (it dismisses itself before gameplay), and do not distribute it on Play without reviewing the Overlay / MediaProjection policies.

---

## Project layout
```
mlbb-draft-companion/
├── scraper/            scraper.py · overrides.json · requirements.txt
├── android/            Gradle project (app module)
│   └── app/src/main/assets/   matchups.json · templates/ · layouts.json
└── .github/workflows/scrape.yml   weekly data publish
```
