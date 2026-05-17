# AGENTS.md

## Commands

```bash
./gradlew assembleDebug        # Debug APK
./gradlew assembleRelease      # Release APK (minified + shrunk)
```

Pass CI version overrides: `-PCI_VERSION_CODE=<int> -PCI_VERSION_NAME=<semver>`

No test sources exist in this repo. Use Gradle compilation as the focused verification step; there is no separate lint/typecheck task documented here.

## Architecture

Single-module Android app (`app/`). Package: `com.easyhomework.app`.

**UI is hybrid**: Jetpack Compose for app screens (`ui/screens/`, `viewmodel/`), traditional Android Views for overlays (`overlay/`, `FloatingBallView`). Do not convert one style to the other unless explicitly requested.

Key execution flow:
1. `MainActivity` starts/stops `FloatingBallService` after overlay permission.
2. Tapping the floating ball opens `ScreenCapturePermissionActivity` if MediaProjection is not ready, then starts `ScreenCaptureService`.
3. `ScreenCaptureService` captures one bitmap and notifies `FloatingBallService` via `ACTION_SCREENSHOT_RESULT`.
4. `FloatingBallService` shows `RegionSelectorOverlay`; confirm either OCRs via ML Kit or sends the cropped image directly for vision models.
5. `AnswerPanelOverlay` streams through `LLMRepository`, renders markdown with Markwon, executes tool calls, and saves Room history.

## Key directories

| Path | Purpose |
|------|---------|
| `service/` | Foreground services for floating ball and MediaProjection capture |
| `overlay/` | View-based overlays: floating ball, region selector, answer panel |
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
- **Legacy config fallback exists**: `PreferencesManager.getLLMConfig()` first uses multi-provider config, then old single-provider keys. Do not remove legacy keys without a migration reason.
- **Tool calling**: Tools are defined in `ToolRegistry`, executed by `ToolExecutor`, collected during streaming, then processed after stream completion in `AnswerPanelOverlay.processToolCalls`.
- **Floating ball position**: Persisted in `PreferencesManager.floatingBallX/Y` only after drag. There is no edge snapping despite an outdated comment in `FloatingBallService`.
- **Mini ball**: Touch target is `BALL_TOUCH_SIZE_MINI`, but drawing is controlled by `FloatingBallView.onDraw`; current mini mode is semi-transparent gray, not the normal gradient.
- **Vision models**: Capability comes from API `/v1/models` metadata plus `LLMConfig.modelSupportsVision(...)`. When supported, `RegionSelectorOverlay` shows "直接识图" to skip OCR.
- **Anthropic requests differ**: `LLMRepository` puts system prompt at top level and merges tool result messages into user content blocks; preserve this when touching request shaping.

## Build quirks

- Uses Gradle version catalogs (`gradle/libs.versions.toml`); always use `libs.xxx` aliases, not raw strings.
- KSP is configured for Room annotation processing; do not add kapt.
- `compileSdk = 34`, `targetSdk = 34`, `minSdk = 26`, JDK/JVM target 17.
- Release builds enable minify and resource shrink with `app/proguard-rules.pro`.
- No `local.properties` in git — it's machine-specific (SDK path).

## CI

GitHub Actions (`.github/workflows/build-release.yml`) runs only on pushes to `main`: computes the next `v*` patch tag, builds `assembleRelease` with CI version properties, optionally signs if `KEYSTORE_BASE64` exists, and creates a GitHub pre-release.
