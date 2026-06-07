# AGENTS.md

## Commands

```bash
./gradlew assembleDebug        # focused verification; builds debug APK
./gradlew assembleRelease      # minified + resource-shrunk release APK
./gradlew ktlintCheck          # available, currently uses app/ktlint-baseline.xml
./gradlew detekt               # available, currently uses app/detekt-baseline.xml
```

- Pass release version overrides as `-PCI_VERSION_CODE=<int> -PCI_VERSION_NAME=<semver>`.
- No `test/` or `androidTest/` Kotlin test sources exist; use Gradle compilation as the default verification step unless adding tests.
- CI only runs on pushes to `main`, builds `assembleRelease`, optionally signs with keystore secrets, and creates a GitHub pre-release tag.

## Project Shape

- Single Android app module: `app/`; package/application id: `com.easyhomework.app`.
- Kotlin/JVM target is 17; wrapper uses Gradle 9.0.0; Android config is `compileSdk = 34`, `targetSdk = 34`, `minSdk = 26`.
- Dependencies must go through `gradle/libs.versions.toml`; do not add raw Maven coordinates in build scripts.
- KSP is used for Room and exports schemas to `app/schemas`; keep schema JSON updated when touching Room entities/DAO/database version. Do not add kapt.
- `local.properties` is intentionally not tracked; it is machine-specific SDK configuration.

## Architecture Notes

- UI is hybrid: Compose screens live in `ui/screens/` with `viewmodel/`; overlays are traditional Android Views in `overlay/`. Do not convert one style to the other unless asked.
- Runtime flow: `MainActivity` toggles `FloatingBallService`; the floating ball launches `ScreenCapturePermissionActivity` when MediaProjection is not ready; `ScreenCaptureService` captures one bitmap and signals `FloatingBallService.ACTION_SCREENSHOT_RESULT`; `RegionSelectorOverlay` crops/OCRs or sends direct image; `AnswerPanelOverlay` streams through `LLMRepository`, executes tools, renders Markwon markdown, and persists Room history.
- `ScreenCaptureService` hands screenshots via an in-process static `lastScreenshot`; `getLastScreenshot()` consumes and clears it. Be careful with bitmap lifetime and avoid storing raw bitmaps in Room/Gson data.
- `FloatingBallService` comments mention edge snapping, but current behavior only persists `PreferencesManager.floatingBallX/Y` after drag.
- Mini ball mode uses a 48dp touch target but draws a small semi-transparent gray dot in `FloatingBallView.onDraw`.

## Data And Config

- `PreferencesManager` stores multiple `LLMConfig` providers; non-sensitive fields are plain prefs, API keys are in `EncryptedSharedPreferences`, active provider is `activeProviderId`.
- `PreferencesManager.getLLMConfig()` intentionally falls back to legacy single-provider keys. Do not remove legacy keys without a migration.
- Room history is `QueryHistory`; list screens should use lightweight summary queries and load full `conversations` only when needed.
- Room no longer uses destructive migration; future schema changes need explicit migrations.

## LLM Specifics

- OpenAI-compatible and Anthropic request shapes differ in `LLMRepository`: Anthropic uses top-level `system` and merges tool result messages into user content blocks.
- Vision support comes from `/v1/models` metadata plus `LLMConfig.modelSupportsVision(...)`; when supported, `RegionSelectorOverlay` shows `直接识图` to skip OCR.
- Tools are declared in `ToolRegistry`, parsed from streaming deltas by `SSEStreamParser`, executed by `ToolExecutor`, then recursively handled in `AnswerPanelOverlay.processToolCalls` with a max depth guard.
