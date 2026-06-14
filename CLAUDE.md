# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

EasyHomework (搜题助手) is a single-module Android app: a draggable floating ball that screenshots whatever is on screen, OCRs the question, and streams an answer from an LLM. Code comments and UI strings are largely in Chinese.

## Commands

```bash
./gradlew compileDebugKotlin    # fast default verification (no test sources exist)
./gradlew assembleDebug         # debug APK
./gradlew assembleRelease       # minified + resource-shrunk release APK (what CI builds)
./gradlew ktlintCheck           # ktlint (uses app/ktlint-baseline.xml)
./gradlew detekt                # detekt (uses app/detekt-baseline.xml)
```

- Release version overrides: `-PCI_VERSION_CODE=<int> -PCI_VERSION_NAME=<semver>` (default to `1` / `1.0.0`).
- **`ktlintCheck`/`detekt` currently FAIL on a clean tree** — the baselines are stale (pre-existing debt) and neither runs in CI. CI only runs `assembleRelease` (push to `main` → optional keystore signing → GitHub pre-release tag). Verify your changes with `compileDebugKotlin`; if you care about lint, grep `app/build/reports/detekt/detekt.txt` for the files you touched rather than expecting the whole task to pass.
- **No `test/` or `androidTest/` Kotlin sources.** If you add JVM tests, run a single one with `./gradlew testDebugUnitTest --tests "com.easyhomework.app.SomeTest.method"`; instrumented tests run via `connectedDebugAndroidTest`.
- Toolchain: JDK 17, Gradle 9.0.0 wrapper, AGP 8.4.2 / Kotlin 1.9.24, `compileSdk`/`targetSdk` 34, `minSdk` 26.

## Architecture

### End-to-end runtime flow (spans services + overlays + network)
This is the core path and requires reading several files to follow:

1. **`SafeLaunchActivity`** is the launcher. On each start it asks `CrashReporter` whether the last run crashed; normally it routes straight to `MainActivity`, otherwise it shows a copyable diagnostics screen.
2. **`MainActivity`** hosts the Compose UI (Settings + History) and toggles **`FloatingBallService`**.
3. **`FloatingBallService`** is the orchestrator. It owns the floating-ball and answer-panel overlays and drives the whole capture→answer sequence. When MediaProjection isn't ready it launches the transparent **`ScreenCapturePermissionActivity`**.
4. **`ScreenCaptureService`** (`foregroundServiceType=mediaProjection`) captures exactly one bitmap, stashes it in an in-process static `lastScreenshot`, and signals `FloatingBallService.ACTION_SCREENSHOT_RESULT`. `getLastScreenshot()` consumes and clears it — **bitmaps are passed by static handoff, not Intent extras; mind bitmap lifetime and never persist raw bitmaps into Room/Gson.**
5. **`RegionSelectorOverlay`** lets the user crop, then either OCRs the region via `TextRecognitionManager` (ML Kit) or, for vision-capable models, offers **直接识图** to send the cropped image directly and skip OCR.
6. **`AnswerPanelOverlay`** streams the response through `LLMRepository`, executes any tool calls, renders Markdown via Markwon, and persists the conversation to Room.

The overlays in `overlay/` are **traditional Android Views**, while `ui/screens/` is **Jetpack Compose** with `viewmodel/`. This hybrid is intentional — do not convert one style to the other unless asked.

### LLM layer (`network/`, `model/`, `tools/`)
- **`LLMRepository`** speaks two protocols behind one interface: OpenAI-compatible and Anthropic. They differ in request shape — Anthropic uses a top-level `system` field and merges `tool`-role results into user content blocks, and image payloads are encoded differently per protocol. Streaming deltas are parsed by `SSEStreamParser`.
- **Model capability detection** (vision / function-calling / thinking) resolves through a priority chain: explicit provider `/v1/models` metadata → the **models.dev catalog** (`ModelCatalog`) → name heuristics (`LLMConfig.modelSupportsVision()` / `modelSupportsFunctionCalling()`). `CapabilitySource` records the winning source (`API`/`API_UNSUPPORTED`, `CATALOG`/`CATALOG_UNSUPPORTED`, `AUTO`, `MANUAL`). `LLMConfig.supportsVisionInput()` resolves the final answer at send time and, for the `AUTO` source, consults `ModelCatalog` then the name heuristic — so the catalog must be loaded and the heuristics must stay correct there too.
- **`ModelCatalog`** (pure, in `model/`) is an in-memory map from model id → capability bitmask, sourced from [models.dev](https://models.dev). A trimmed snapshot ships at `assets/models_dev.json` (~96 KB); `network/ModelCatalogLoader` loads it (preferring a newer network-cached copy in `filesDir`) at app startup, and best-effort refreshes from `https://models.dev/api.json` (TTL-gated, on a background thread) when Settings is opened. Lookups normalize the id (lowercase, strip `:tag`, try the part after `/`) and return `null` when unknown so callers fall back to heuristics. **To regenerate the bundled snapshot**: download `api.json`, reduce each model id to a bitmask (`1`=image in `modalities.input`, `2`=`tool_call`, `4`=`reasoning`) aggregated by **majority vote across providers** (ties → unset, the safe direction for vision), keyed by exact id (`full`) and by vendor-suffix (`suffix`) — the same logic `ModelCatalogLoader.trimApiJson` runs at refresh time.
- **Tools / function-calling**: declared in `ToolRegistry`, parsed from streaming deltas, executed by `ToolExecutor`, then recursively handled in `AnswerPanelOverlay.processToolCalls` with a max-depth guard. Math tools run JavaScript locally via QuickJS (`quickjs-android`).
- `ProviderPresets` are one-tap configs for China-friendly providers (DeepSeek, Kimi, GLM, Qwen, SiliconFlow, OpenRouter, OpenAI, Claude).

### Config & persistence (`util/`, `data/`)
- **`PreferencesManager`** stores multiple `LLMConfig` providers. Non-sensitive fields are plain SharedPreferences; **API keys live in `EncryptedSharedPreferences`**; the active provider is `activeProviderId`. `getLLMConfig()` deliberately falls back to legacy single-provider keys — **don't remove legacy keys without a migration.**
- **Room** (`AppDatabase`, `HistoryDao`, `QueryHistory`) holds search history. Schemas are exported to `app/schemas` via KSP — keep the schema JSON updated when changing entities/DAO/version. **Destructive migration is off; schema changes need explicit migrations.** History list screens use lightweight summary queries and load full `conversations` only when needed.

### Startup & crash handling
- **`CrashInitProvider`** (a no-op `ContentProvider`) installs `CrashReporter` before any third-party startup providers. The ML Kit and jlatexmath init providers are removed from the manifest and initialized lazily on first use (see `ocr/MlKitInitializer`) to avoid startup crashes. `EasyHomeworkApp` (the `Application`) re-installs the reporter, sets lifecycle "stages", and creates notification channels.

## Conventions
- All dependencies go through `gradle/libs.versions.toml` — no raw Maven coordinates in build scripts. **KSP only (Room); do not add kapt.**
- `local.properties` is untracked (machine-specific SDK config); `latest_crash.txt` at the repo root is a captured crash artifact, not source.
- `AGENTS.md` holds additional, finer-grained notes (e.g. floating-ball edge-snap vs. drag-persist behavior, mini-ball hit-target details) — consult it and keep it in sync with CLAUDE.md when conventions change.
