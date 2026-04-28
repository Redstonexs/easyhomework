# EasyHomework 📚✨

AI 搜题助手 — 一款安卓悬浮球搜题应用。

## 功能特性

- 🔵 **悬浮球** — 可拖拽、边缘吸附的悬浮球，在任何应用上层显示
- 📸 **智能截屏** — 点击悬浮球截取屏幕，自动隐藏悬浮球避免截入
- 🎯 **智能选区** — ML Kit 自动检测题目区域，支持手动调整（8个拖拽锚点）
- 🔍 **OCR 识别** — Google ML Kit 中英文双语文字识别
- 🤖 **AI 解答** — 支持 OpenAI 兼容 API（DeepSeek、通义千问等），流式逐字显示
- 💬 **追问对话** — 支持多轮追问，在答案面板直接输入追问内容
- 📝 **历史记录** — 自动保存所有搜题记录，支持展开查看完整对话
- 🎨 **精美 UI** — 深色主题、毛玻璃效果、流畅动画

## 技术栈

| 技术 | 用途 |
|------|------|
| Kotlin | 开发语言 |
| Jetpack Compose | 设置页 & 历史记录 UI |
| MediaProjection API | 屏幕截图 |
| Google ML Kit | OCR 文字识别 |
| OkHttp | 网络请求 & SSE 流式传输 |
| Room | 本地数据库（历史记录） |
| Markwon | Markdown 渲染 |
| EncryptedSharedPreferences | 安全存储 API 密钥 |

## 构建要求

- Android Studio Iguana (2024.1+)
- JDK 17
- Android SDK 34
- Gradle 8.4+

## 构建方法

```bash
# Debug 构建
./gradlew assembleDebug

# Release 构建
./gradlew assembleRelease
```

## 使用方法

1. 安装并打开应用
2. 配置 API 端点和密钥（支持 OpenAI 兼容格式）
3. 点击保存设置
4. 开启悬浮球开关（需要授予悬浮窗权限）
5. 在任意应用中点击悬浮球截屏
6. 调整选区后确认，等待 AI 解答
7. 在答案面板可以追问或复制答案

## 权限说明

| 权限 | 用途 |
|------|------|
| `SYSTEM_ALERT_WINDOW` | 显示悬浮球和答案面板 |
| `FOREGROUND_SERVICE` | 保持悬浮球和截屏服务运行 |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | 截屏功能 |
| `INTERNET` | 调用大模型 API |
| `POST_NOTIFICATIONS` | 显示前台服务通知 |

## License

AGPLv3 License
