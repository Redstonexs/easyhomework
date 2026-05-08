# AGENTS.md

## Build

```bash
./gradlew assembleDebug        # Debug APK
./gradlew assembleRelease      # Release APK (minified + shrunk)
```

Pass CI version overrides: `-PCI_VERSION_CODE=<int> -PCI_VERSION_NAME=<semver>`

No test suite exists. No lint/typecheck commands beyond Gradle compilation.

## Architecture

Single-module Android app (`app/`). Package: `com.easyhomework.app`.

**UI is hybrid**: Jetpack Compose for screens (`ui/screens/`, `viewmodel/`), traditional Android Views for overlays (`overlay/`, `FloatingBallView`). Do not try to convert one to the other — match existing style.

Key execution flow:
1. `MainActivity` → starts `FloatingBallService` (foreground service)
2. User taps floating ball → `ScreenCapturePermissionActivity` → `ScreenCaptureService` (MediaProjection)
3. Screenshot → `RegionSelectorOverlay` (View-based) → OCR via ML Kit → `AnswerPanelOverlay` (View-based)
4. `AnswerPanelOverlay` calls `LLMRepository` with streaming SSE, renders markdown via Markwon

## Key directories

| Path | Purpose |
|------|---------|
| `service/` | Foreground services (floating ball, screen capture) |
| `overlay/` | View-based fullscreen overlays (region selector, answer panel, floating ball) |
| `network/` | LLM API client (OpenAI-compatible + Anthropic), SSE streaming parser |
| `tools/` | Tool definitions + executor for LLM function calling |
| `model/` | Data classes (LLMConfig, ChatMessage, ModelInfo, QueryHistory) |
| `data/` | Room database + DAO |
| `ocr/` | ML Kit text recognition + smart region detection |
| `ui/` | Compose screens (Settings, History) + theme |
| `viewmodel/` | ViewModels for Compose screens |
| `util/` | PreferencesManager (encrypted + plain prefs) |

## Conventions

- **Multi-provider configs**: `PreferencesManager` stores a list of `LLMConfig` with encrypted API keys. Active provider tracked by `activeProviderId`.
- **Tool calling**: Tools defined in `ToolRegistry`, executed by `ToolExecutor`. Tool calls are collected during streaming, then processed after the stream completes (`processToolCalls` in `AnswerPanelOverlay`).
- **Floating ball position**: Persisted in `PreferencesManager.floatingBallX/Y`. No edge snapping — stays where placed.
- **Mini ball**: Size (`BALL_SIZE_MINI`) and alpha defined in `FloatingBallView.onDraw`. Uses gradient, not solid white.
- **Vision models**: Capability auto-detected from API `/v1/models` response (`ModelInfo.supportsVision`). When supported, region selector shows "直接识图" button to skip OCR.

## Build quirks

- Uses Gradle version catalogs (`gradle/libs.versions.toml`) — always use `libs.xxx` aliases, not raw strings.
- KSP (not kapt) for Room annotation processing.
- `compileSdk = 34`, `minSdk = 26`, JDK 17.
- ProGuard enabled for release builds with `proguard-rules.pro`.
- No `local.properties` in git — it's machine-specific (SDK path).

## CI

GitHub Actions (`.github/workflows/build-release.yml`): on push to `main`, builds release APK, auto-increments patch version from latest git tag, creates GitHub pre-release. Signing only if `KEYSTORE_BASE64` secret exists.
