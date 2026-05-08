package com.easyhomework.app.overlay

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.easyhomework.app.data.AppDatabase
import com.easyhomework.app.model.ChatMessage
import com.easyhomework.app.model.LLMConfig
import com.easyhomework.app.model.QueryHistory
import com.easyhomework.app.network.LLMRepository
import com.easyhomework.app.tools.ToolCall
import com.easyhomework.app.tools.ToolExecutor
import com.easyhomework.app.tools.ToolRegistry
import com.easyhomework.app.util.PreferencesManager
import io.noties.markwon.Markwon
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/**
 * Bottom sheet-style overlay panel that displays LLM answers.
 * Features:
 * - Quark-style bottom slide-up panel
 * - Streaming answer display with typing effect
 * - Multi-turn follow-up questions with tool calling
 * - Copy and regenerate functionality
 * - Markdown rendering
 * - Frosted glass background effect
 * - Vision model support (direct image input)
 */
@SuppressLint("ViewConstructor")
class AnswerPanelOverlay(
    private val serviceContext: Context,
    private val screenshotBitmap: Bitmap,
    private val recognizedText: String,
    private val sendDirectImage: Boolean = false
) : FrameLayout(serviceContext) {

    var onClose: (() -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val llmRepository = LLMRepository()
    private val toolExecutor = ToolExecutor()
    private val preferencesManager = PreferencesManager(serviceContext)
    private val markwon: Markwon = try {
        Markwon.create(serviceContext)
    } catch (e: Exception) {
        try {
            Markwon.builder(serviceContext).build()
        } catch (e2: Exception) {
            Markwon.builder(serviceContext).build()
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val database by lazy { AppDatabase.getDatabase(serviceContext) }

    private val messages = mutableListOf<ChatMessage>()
    private var currentStreamingText = StringBuilder()
    private var historyId: Long = -1
    private var conversationStarted = false

    // Views
    private lateinit var panelContainer: LinearLayout
    private lateinit var messagesContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var inputField: EditText
    private lateinit var sendButton: ImageView
    private lateinit var dragHandle: View

    // Colors
    private val panelBgColor = Color.parseColor("#F01A1A2E")
    private val cardBgColor = Color.parseColor("#252540")
    private val userBubbleColor = Color.parseColor("#6C63FF")
    private val assistantBubbleColor = Color.parseColor("#2A2A3E")
    private val toolBubbleColor = Color.parseColor("#1A3A2A")
    private val textColor = Color.parseColor("#E8E8F0")
    private val secondaryTextColor = Color.parseColor("#A0A0B8")
    private val accentColor = Color.parseColor("#6C63FF")

    private val density = serviceContext.resources.displayMetrics.density

    init {
        buildUI()
        animateIn()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!conversationStarted) {
            conversationStarted = true
            handler.post { startConversation() }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUI() {
        setBackgroundColor(Color.parseColor("#80000000"))

        panelContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(panelBgColor)
                cornerRadii = floatArrayOf(
                    dp(24f), dp(24f), dp(24f), dp(24f),
                    0f, 0f, 0f, 0f
                )
            }
            background = bg
            elevation = dp(16f)
            clipToOutline = true
        }

        val screenHeight = context.resources.displayMetrics.heightPixels
        val panelHeight = (screenHeight * 0.65f).toInt()
        val panelParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            panelHeight
        ).apply {
            gravity = Gravity.BOTTOM
        }

        addView(panelContainer, panelParams)

        val handleContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12f).toInt(), 0, dp(8f).toInt())
        }

        dragHandle = View(context).apply {
            val handleBg = GradientDrawable().apply {
                setColor(Color.parseColor("#555570"))
                cornerRadius = dp(3f)
            }
            background = handleBg
        }
        handleContainer.addView(dragHandle, LinearLayout.LayoutParams(dp(48f).toInt(), dp(5f).toInt()))
        panelContainer.addView(handleContainer)

        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20f).toInt(), dp(4f).toInt(), dp(12f).toInt(), dp(12f).toInt())
        }

        val titleText = TextView(context).apply {
            text = if (sendDirectImage) "✨ AI 识图助手" else "✨ AI 解题助手"
            setTextColor(textColor)
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        headerLayout.addView(titleText, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(secondaryTextColor)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(dp(12f).toInt(), dp(8f).toInt(), dp(12f).toInt(), dp(8f).toInt())
            setOnClickListener { animateOut() }
        }
        headerLayout.addView(closeBtn)

        panelContainer.addView(headerLayout)

        val divider = View(context).apply {
            setBackgroundColor(Color.parseColor("#333350"))
        }
        panelContainer.addView(divider, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 1))

        scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            isFillViewport = true
        }

        messagesContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(16f).toInt(), dp(12f).toInt())
        }
        scrollView.addView(messagesContainer)

        panelContainer.addView(
            scrollView,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val inputContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f).toInt(), dp(8f).toInt(), dp(12f).toInt(), dp(16f).toInt())
            val inputBg = GradientDrawable().apply {
                setColor(Color.parseColor("#15FFFFFF"))
            }
            background = inputBg
        }

        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f).toInt(), 0, dp(16f).toInt(), dp(4f).toInt())
        }

        val copyBtn = createActionButton("📋 复制") { copyLastAnswer() }
        val regenBtn = createActionButton("🔄 重新生成") { regenerateAnswer() }
        actionsRow.addView(copyBtn)
        actionsRow.addView(regenBtn)
        panelContainer.addView(actionsRow)

        inputField = EditText(context).apply {
            hint = "追问..."
            setHintTextColor(Color.parseColor("#666680"))
            setTextColor(textColor)
            textSize = 15f
            maxLines = 3
            imeOptions = EditorInfo.IME_ACTION_SEND
            val inputBg = GradientDrawable().apply {
                setColor(Color.parseColor("#252540"))
                cornerRadius = dp(24f)
            }
            background = inputBg
            setPadding(dp(20f).toInt(), dp(12f).toInt(), dp(12f).toInt(), dp(12f).toInt())
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendFollowUp()
                    true
                } else false
            }
        }
        inputContainer.addView(
            inputField,
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )

        sendButton = ImageView(context).apply {
            val sendBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accentColor)
            }
            background = sendBg
            setPadding(dp(10f).toInt(), dp(10f).toInt(), dp(10f).toInt(), dp(10f).toInt())
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(Color.WHITE)
            setOnClickListener { sendFollowUp() }
        }
        sendButton.setImageBitmap(createSendIcon())

        val sendParams = LinearLayout.LayoutParams(dp(44f).toInt(), dp(44f).toInt()).apply {
            marginStart = dp(8f).toInt()
        }
        inputContainer.addView(sendButton, sendParams)

        panelContainer.addView(inputContainer)
    }

    private fun createActionButton(text: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(secondaryTextColor)
            textSize = 13f
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#1AFFFFFF"))
                cornerRadius = dp(16f)
            }
            background = bg
            setPadding(dp(14f).toInt(), dp(6f).toInt(), dp(14f).toInt(), dp(6f).toInt())
            val params = LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(8f).toInt()
            }
            layoutParams = params
            setOnClickListener { onClick() }
        }
    }

    private fun createSendIcon(): Bitmap {
        val size = dp(24f).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val path = Path().apply {
            moveTo(size * 0.2f, size * 0.5f)
            lineTo(size * 0.8f, size * 0.5f)
            moveTo(size * 0.55f, size * 0.25f)
            lineTo(size * 0.8f, size * 0.5f)
            lineTo(size * 0.55f, size * 0.75f)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.5f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(path, paint)
        return bitmap
    }

    // ---- Conversation Management ----

    private fun startConversation() {
        val config = preferencesManager.getLLMConfig()
        val isVisionMode = sendDirectImage && (config.supportsVision || LLMConfig.modelSupportsVision(config.modelName))

        if (isVisionMode) {
            val promptText = if (recognizedText.isNotBlank()) {
                recognizedText
            } else {
                "请识别并解答图片中的题目，给出详细的解题步骤和最终答案。"
            }
            val userMessage = ChatMessage.userWithImage(promptText, screenshotBitmap)
            messages.add(userMessage)
            addUserBubbleWithImage(promptText)
        } else {
            val userMessage = ChatMessage.user(recognizedText)
            messages.add(userMessage)
            addUserBubble(recognizedText)
        }

        val loadingView = addAssistantBubble("", isLoading = true)
        sendToLLM(loadingView)
    }

    private fun sendFollowUp() {
        val text = inputField.text.toString().trim()
        if (text.isEmpty()) return

        inputField.text.clear()

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(inputField.windowToken, 0)

        val userMessage = ChatMessage.user(text)
        messages.add(userMessage)
        addUserBubble(text)

        val loadingView = addAssistantBubble("", isLoading = true)
        sendToLLM(loadingView)
    }

    private var currentThinkingText = StringBuilder()
    private var thinkingView: TextView? = null
    private var isThinkingPhase = false

    private fun sendToLLM(loadingView: TextView) {
        val config = preferencesManager.getLLMConfig()

        if (config.apiKey.isBlank()) {
            updateBubbleText(loadingView, "⚠️ 请先在设置中配置 API 密钥", isLoading = false)
            return
        }

        currentStreamingText.clear()
        currentThinkingText.clear()
        isThinkingPhase = false
        thinkingView = null

        // Get tool definitions if function calling is supported
        val tools = if (config.supportsVision || LLMConfig.modelSupportsVision(config.modelName)) {
            // Most modern models support tools, so we always provide them
            ToolRegistry.getToolDefinitions()
        } else {
            ToolRegistry.getToolDefinitions()
        }

        scope.launch {
            if (config.stream) {
                llmRepository.streamChatCompletion(config, messages, tools).collect { event ->
                    when (event) {
                        is LLMRepository.StreamEvent.Started -> {
                            updateBubbleText(loadingView, "思考中...", isLoading = true)
                        }
                        is LLMRepository.StreamEvent.Thinking -> {
                            if (!isThinkingPhase) {
                                isThinkingPhase = true
                                updateBubbleText(loadingView, "💭 正在深度思考...", isLoading = true)
                                thinkingView = addThinkingBubble()
                            }
                            currentThinkingText.append(event.text)
                            thinkingView?.let { tv ->
                                handler.post {
                                    tv.text = currentThinkingText.toString()
                                }
                            }
                            scrollToBottom()
                        }
                        is LLMRepository.StreamEvent.Token -> {
                            if (isThinkingPhase) {
                                isThinkingPhase = false
                                thinkingView?.let { tv ->
                                    handler.post {
                                        tv.maxLines = 3
                                        tv.ellipsize = android.text.TextUtils.TruncateAt.END
                                    }
                                }
                                updateBubbleText(loadingView, "", isLoading = true)
                            }
                            currentStreamingText.append(event.text)
                            updateBubbleText(
                                loadingView,
                                currentStreamingText.toString(),
                                isLoading = true
                            )
                            scrollToBottom()
                        }
                        is LLMRepository.StreamEvent.ToolCall -> {
                            // Handle tool call
                            handleToolCall(event.toolCall, loadingView)
                        }
                        is LLMRepository.StreamEvent.Completed -> {
                            val fullText = currentStreamingText.toString()
                            if (fullText.isNotBlank()) {
                                messages.add(ChatMessage.assistant(fullText))
                                updateBubbleText(loadingView, fullText, isLoading = false)
                            } else {
                                // Remove loading bubble if no content
                                handler.post {
                                    try {
                                        messagesContainer.removeView(loadingView.parent as? View)
                                    } catch (_: Exception) {}
                                }
                            }
                            scrollToBottom()
                            saveToHistory()
                        }
                        is LLMRepository.StreamEvent.Error -> {
                            val errorText = "❌ ${event.message}"
                            updateBubbleText(loadingView, errorText, isLoading = false)
                            scrollToBottom()
                        }
                    }
                }
            } else {
                updateBubbleText(loadingView, "正在思考...", isLoading = true)
                val result = llmRepository.chatCompletion(config, messages, tools)
                result.fold(
                    onSuccess = { response ->
                        if (response.toolCalls != null && response.toolCalls.isNotEmpty()) {
                            // Handle tool calls for non-streaming
                            handleToolCalls(response.toolCalls, loadingView)
                        } else {
                            val text = response.content ?: ""
                            messages.add(ChatMessage.assistant(text))
                            updateBubbleText(loadingView, text, isLoading = false)
                            scrollToBottom()
                            saveToHistory()
                        }
                    },
                    onFailure = { error ->
                        updateBubbleText(loadingView, "❌ ${error.message}", isLoading = false)
                        scrollToBottom()
                    }
                )
            }
        }
    }

    private suspend fun handleToolCall(toolCall: ToolCall, @Suppress("UNUSED_PARAMETER") loadingView: TextView) {
        // Add tool call to messages
        messages.add(ChatMessage.assistantWithToolCalls(null, listOf(toolCall)))

        // Parse arguments for display
        val argsDisplay = parseToolCallArgs(toolCall)
        val toolName = getToolDisplayName(toolCall.name)

        // Show tool call with arguments
        addToolCallBubble(toolName, argsDisplay)

        // Execute tool
        val result = toolExecutor.execute(toolCall)

        // Add tool result to messages
        messages.add(ChatMessage.toolResult(toolCall.id, result.content))

        // Show tool result
        addToolResultBubble(result.content, result.isError)

        // Continue conversation with tool result
        val newLoadingView = addAssistantBubble("", isLoading = true)
        sendToLLM(newLoadingView)
    }

    private suspend fun handleToolCalls(toolCalls: List<ToolCall>, @Suppress("UNUSED_PARAMETER") loadingView: TextView) {
        // Add assistant message with tool calls
        messages.add(ChatMessage.assistantWithToolCalls(null, toolCalls))

        for (toolCall in toolCalls) {
            val argsDisplay = parseToolCallArgs(toolCall)
            val toolName = getToolDisplayName(toolCall.name)

            // Show tool call with arguments
            addToolCallBubble(toolName, argsDisplay)

            val result = toolExecutor.execute(toolCall)

            messages.add(ChatMessage.toolResult(toolCall.id, result.content))

            // Show tool result
            addToolResultBubble(result.content, result.isError)
        }

        // Continue conversation with tool results
        val newLoadingView = addAssistantBubble("", isLoading = true)
        sendToLLM(newLoadingView)
    }

    // ---- Bubble Views ----

    private fun addUserBubble(text: String) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(dp(40f).toInt(), dp(4f).toInt(), 0, dp(8f).toInt())
        }

        val bubble = TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            val bg = GradientDrawable().apply {
                setColor(userBubbleColor)
                cornerRadii = floatArrayOf(
                    dp(16f), dp(16f), dp(4f), dp(4f),
                    dp(16f), dp(16f), dp(16f), dp(16f)
                )
            }
            background = bg
            setPadding(dp(14f).toInt(), dp(10f).toInt(), dp(14f).toInt(), dp(10f).toInt())
            maxLines = 8
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        container.addView(bubble)
        messagesContainer.addView(container)
        scrollToBottom()
    }

    private fun addUserBubbleWithImage(text: String) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(dp(40f).toInt(), dp(4f).toInt(), 0, dp(8f).toInt())
        }

        val imagePreview = ImageView(context).apply {
            val maxSize = dp(160f).toInt()
            val scale = minOf(
                maxSize.toFloat() / screenshotBitmap.width,
                maxSize.toFloat() / screenshotBitmap.height,
                1f
            )
            val scaledBitmap = Bitmap.createScaledBitmap(
                screenshotBitmap,
                (screenshotBitmap.width * scale).toInt(),
                (screenshotBitmap.height * scale).toInt(),
                true
            )
            setImageBitmap(scaledBitmap)
            scaleType = ImageView.ScaleType.CENTER_CROP
            val bg = GradientDrawable().apply {
                setColor(userBubbleColor)
                cornerRadii = floatArrayOf(
                    dp(16f), dp(16f), dp(4f), dp(4f),
                    dp(16f), dp(16f), dp(16f), dp(16f)
                )
            }
            background = bg
            setPadding(dp(4f).toInt(), dp(4f).toInt(), dp(4f).toInt(), dp(4f).toInt())
        }

        val imageParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(4f).toInt()
        }
        container.addView(imagePreview, imageParams)

        if (text.isNotBlank()) {
            val bubble = TextView(context).apply {
                this.text = "📸 $text"
                setTextColor(Color.WHITE)
                textSize = 14f
                val bg = GradientDrawable().apply {
                    setColor(userBubbleColor)
                    cornerRadii = floatArrayOf(
                        dp(16f), dp(16f), dp(4f), dp(4f),
                        dp(16f), dp(16f), dp(16f), dp(16f)
                    )
                }
                background = bg
                setPadding(dp(14f).toInt(), dp(10f).toInt(), dp(14f).toInt(), dp(10f).toInt())
                maxLines = 8
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            container.addView(bubble)
        }

        messagesContainer.addView(container)
        scrollToBottom()
    }

    private fun addAssistantBubble(text: String, isLoading: Boolean): TextView {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(0, dp(4f).toInt(), dp(40f).toInt(), dp(8f).toInt())
        }

        val label = TextView(context).apply {
            this.text = "🤖 AI 助手"
            setTextColor(secondaryTextColor)
            textSize = 11f
            setPadding(dp(4f).toInt(), 0, 0, dp(4f).toInt())
        }
        container.addView(label)

        val bubble = TextView(context).apply {
            setTextColor(textColor)
            textSize = 14f
            val bg = GradientDrawable().apply {
                setColor(assistantBubbleColor)
                cornerRadii = floatArrayOf(
                    dp(4f), dp(4f), dp(16f), dp(16f),
                    dp(16f), dp(16f), dp(16f), dp(16f)
                )
            }
            background = bg
            setPadding(dp(14f).toInt(), dp(10f).toInt(), dp(14f).toInt(), dp(10f).toInt())
            movementMethod = ScrollingMovementMethod.getInstance()
            setLineSpacing(dp(3f), 1f)

            if (isLoading) {
                this.text = if (text.isEmpty()) "●●●" else text
            } else {
                markwon.setMarkdown(this, text)
            }

            tag = "assistant_bubble"
        }

        container.addView(bubble)
        messagesContainer.addView(container)
        scrollToBottom()

        return bubble
    }

    private fun addThinkingBubble(): TextView {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(0, dp(2f).toInt(), dp(40f).toInt(), dp(4f).toInt())
        }

        val label = TextView(context).apply {
            this.text = "💭 思考过程"
            setTextColor(Color.parseColor("#8888AA"))
            textSize = 10f
            setPadding(dp(4f).toInt(), 0, 0, dp(2f).toInt())
        }
        container.addView(label)

        val bubble = TextView(context).apply {
            setTextColor(Color.parseColor("#7777AA"))
            textSize = 12f
            setTypeface(null, Typeface.ITALIC)
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#1A6C63FF"))
                cornerRadius = dp(12f)
            }
            background = bg
            setPadding(dp(12f).toInt(), dp(8f).toInt(), dp(12f).toInt(), dp(8f).toInt())
            setLineSpacing(dp(2f), 1f)
        }

        container.addView(bubble)
        messagesContainer.addView(container)
        scrollToBottom()

        return bubble
    }

    private fun getToolDisplayName(toolName: String): String {
        return when (toolName) {
            "get_current_datetime" -> "🕐 获取日期时间"
            "calculate" -> "🔢 计算表达式"
            "evaluate_js" -> "💻 执行计算"
            "convert_unit" -> "📐 单位转换"
            else -> "🔧 $toolName"
        }
    }

    private fun parseToolCallArgs(toolCall: ToolCall): String {
        return try {
            val json = org.json.JSONObject(toolCall.arguments)
            val sb = StringBuilder()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.get(key)
                if (sb.isNotEmpty()) sb.append("\n")
                val displayKey = when (key) {
                    "expression" -> "表达式"
                    "code" -> "代码"
                    "value" -> "数值"
                    "from_unit" -> "从"
                    "to_unit" -> "到"
                    "timezone" -> "时区"
                    else -> key
                }
                sb.append("$displayKey: $value")
            }
            sb.toString()
        } catch (e: Exception) {
            toolCall.arguments
        }
    }

    private fun addToolCallBubble(toolName: String, args: String) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(0, dp(2f).toInt(), dp(40f).toInt(), dp(4f).toInt())
        }

        // Tool call header
        val header = TextView(context).apply {
            this.text = "⚙️ 调用工具: $toolName"
            setTextColor(Color.parseColor("#88CC88"))
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#1A2A4A2A"))
                cornerRadii = floatArrayOf(dp(12f), dp(12f), 0f, 0f, 0f, 0f, dp(12f), dp(12f))
            }
            background = bg
            setPadding(dp(14f).toInt(), dp(10f).toInt(), dp(14f).toInt(), dp(6f).toInt())
        }
        container.addView(header)

        // Tool call arguments
        if (args.isNotBlank()) {
            val argsView = TextView(context).apply {
                this.text = args
                setTextColor(Color.parseColor("#AACCBB"))
                textSize = 12f
                val bg = GradientDrawable().apply {
                    setColor(Color.parseColor("#152A4A2A"))
                    cornerRadii = floatArrayOf(0f, 0f, dp(12f), dp(12f), dp(12f), dp(12f), 0f, 0f)
                }
                background = bg
                setPadding(dp(14f).toInt(), dp(4f).toInt(), dp(14f).toInt(), dp(10f).toInt())
                setLineSpacing(dp(2f), 1f)
            }
            container.addView(argsView)
        }

        messagesContainer.addView(container)
        scrollToBottom()
    }

    private fun addToolResultBubble(content: String, isError: Boolean) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(0, dp(2f).toInt(), dp(40f).toInt(), dp(4f).toInt())
        }

        val headerText = if (isError) "⚠️ 工具执行失败" else "✅ 工具返回结果"
        val headerColor = if (isError) "#CC8888" else "#88CC88"
        val bgColor = if (isError) "#1A4A2A2A" else "#1A2A4A2A"

        val header = TextView(context).apply {
            this.text = headerText
            setTextColor(Color.parseColor(headerColor))
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor(bgColor))
                cornerRadii = floatArrayOf(dp(12f), dp(12f), 0f, 0f, 0f, 0f, dp(12f), dp(12f))
            }
            background = bg
            setPadding(dp(14f).toInt(), dp(10f).toInt(), dp(14f).toInt(), dp(4f).toInt())
        }
        container.addView(header)

        val resultBgColor = if (isError) "#154A2A2A" else "#152A4A2A"
        val resultView = TextView(context).apply {
            this.text = content
            setTextColor(if (isError) Color.parseColor("#DDAAAA") else Color.parseColor("#BBDDCC"))
            textSize = 12f
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor(resultBgColor))
                cornerRadii = floatArrayOf(0f, 0f, dp(12f), dp(12f), dp(12f), dp(12f), 0f, 0f)
            }
            background = bg
            setPadding(dp(14f).toInt(), dp(4f).toInt(), dp(14f).toInt(), dp(10f).toInt())
            setLineSpacing(dp(2f), 1f)
        }
        container.addView(resultView)

        messagesContainer.addView(container)
        scrollToBottom()
    }

    private fun updateBubbleText(bubble: TextView, text: String, isLoading: Boolean) {
        handler.post {
            if (isLoading && text.isNotEmpty()) {
                bubble.text = text + " ▎"
            } else if (!isLoading && text.isNotEmpty()) {
                if (text.startsWith("❌") || text.startsWith("⚠️")) {
                    bubble.text = text
                } else {
                    markwon.setMarkdown(bubble, text)
                }
            } else {
                bubble.text = "●●●"
            }
        }
    }

    private fun scrollToBottom() {
        handler.postDelayed({
            scrollView.fullScroll(View.FOCUS_DOWN)
        }, 50)
    }

    // ---- Actions ----

    private fun copyLastAnswer() {
        val lastAssistant = messages.lastOrNull { it.role == ChatMessage.ROLE_ASSISTANT }
        if (lastAssistant != null) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("answer", lastAssistant.content))
            Toast.makeText(context, "答案已复制", Toast.LENGTH_SHORT).show()
        }
    }

    private fun regenerateAnswer() {
        val lastIdx = messages.indexOfLast { it.role == ChatMessage.ROLE_ASSISTANT }
        if (lastIdx >= 0) {
            messages.removeAt(lastIdx)
        }

        for (i in messagesContainer.childCount - 1 downTo 0) {
            val child = messagesContainer.getChildAt(i)
            if (child is LinearLayout) {
                for (j in 0 until child.childCount) {
                    val inner = child.getChildAt(j)
                    if (inner is TextView && inner.tag == "assistant_bubble") {
                        messagesContainer.removeViewAt(i)
                        break
                    }
                }
                break
            }
        }

        val loadingView = addAssistantBubble("", isLoading = true)
        sendToLLM(loadingView)
    }

    private fun saveToHistory() {
        scope.launch(Dispatchers.IO) {
            try {
                val screenshotFile = java.io.File(
                    context.filesDir,
                    "screenshots/screenshot_${System.currentTimeMillis()}.png"
                )
                screenshotFile.parentFile?.mkdirs()
                java.io.FileOutputStream(screenshotFile).use { fos ->
                    screenshotBitmap.compress(Bitmap.CompressFormat.PNG, 85, fos)
                }

                val preview = if (recognizedText.length > 60) {
                    recognizedText.substring(0, 60) + "..."
                } else recognizedText

                val history = QueryHistory(
                    id = if (historyId > 0) historyId else 0,
                    screenshotPath = screenshotFile.absolutePath,
                    recognizedText = if (sendDirectImage) "[图片] $recognizedText" else recognizedText,
                    conversations = messages.toList(),
                    previewText = if (sendDirectImage) "📸 $preview" else preview
                )

                historyId = database.historyDao().insertHistory(history)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ---- Animation ----

    private fun animateIn() {
        panelContainer.post {
            val parentHeight = getContainerHeight()
            panelContainer.translationY = parentHeight.toFloat()
            panelContainer.animate()
                .translationY(0f)
                .setDuration(400)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        alpha = 0f
        animate().alpha(1f).setDuration(300).start()
    }

    private fun animateOut() {
        val parentHeight = getContainerHeight()
        panelContainer.animate()
            .translationY(parentHeight.toFloat())
            .setDuration(300)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                onClose?.invoke()
            }
            .start()

        animate().alpha(0f).setDuration(250).start()
    }

    private fun getContainerHeight(): Int {
        val parentView = parent as? View
        if (parentView != null && parentView.height > 0) {
            return parentView.height
        }
        if (height > 0) {
            return height
        }
        return context.resources.displayMetrics.heightPixels
    }

    private fun dp(value: Float): Float = value * density

    fun release() {
        scope.cancel()
    }
}
