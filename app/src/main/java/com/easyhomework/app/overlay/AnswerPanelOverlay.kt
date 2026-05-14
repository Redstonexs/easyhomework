package com.easyhomework.app.overlay

import android.annotation.SuppressLint
import android.animation.ValueAnimator
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
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import kotlin.math.abs
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
 * - Material 3 inspired styling with consistent corner radii
 * - Streaming answer display with typing effect
 * - Multi-turn follow-up questions with tool calling
 * - Copy and regenerate functionality
 * - Markdown rendering
 * - Frosted glass background effect
 * - Vision model support (direct image input)
 * - Collapsible thinking bubble
 * - Swipe-to-dismiss gesture on drag handle
 */
@SuppressLint("ViewConstructor")
class AnswerPanelOverlay(
    private val serviceContext: Context,
    private val screenshotBitmap: Bitmap,
    private val recognizedText: String,
    private val sendDirectImage: Boolean = false,
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

    // M3 Dark Theme Color Palette
    private val bgColor = Color.parseColor("#E6000000") // scrim 90%
    private val surfaceColor = Color.parseColor("#1A1A2E") // surface
    private val surfaceContainerColor = Color.parseColor("#1E1E32") // surfaceContainer
    private val surfaceContainerHighColor = Color.parseColor("#252540") // surfaceContainerHigh
    private val surfaceContainerHighestColor = Color.parseColor("#2A2A48") // surfaceContainerHighest
    private val onSurfaceColor = Color.parseColor("#E8E8F0") // onSurface
    private val onSurfaceVariantColor = Color.parseColor("#A0A0B8") // onSurfaceVariant
    private val outlineColor = Color.parseColor("#6B6B80") // outline
    private val outlineVariantColor = Color.parseColor("#333350") // outlineVariant
    private val primaryColor = Color.parseColor("#6C63FF") // primary
    private val onPrimaryColor = Color.WHITE
    private val tertiaryColor = Color.parseColor("#00BCD4") // tertiary
    private val tertiaryContainerColor = Color.parseColor("#1A00BCD4") // tertiaryContainer
    private val errorColor = Color.parseColor("#EF5350") // error
    private val errorContainerColor = Color.parseColor("#26EF5350") // errorContainer

    private val density = serviceContext.resources.displayMetrics.density

    // Drag state
    private var touchStartY = 0f
    private var touchLastY = 0f
    private var isDragging = false
    private var panelStartHeight = 0
    private val screenHeight = serviceContext.resources.displayMetrics.heightPixels
    private val minHeight = (screenHeight * 0.25f).toInt()
    private val maxHeight = (screenHeight * 0.92f).toInt()
    private val dismissHeightThreshold = (screenHeight * 0.28f).toInt() // panel must be shrunk to ~28% before dismiss
    private val resizeThreshold = dp(8f)
    private val snapRatios = floatArrayOf(0.35f, 0.50f, 0.65f, 0.80f)

    // Velocity tracking for fling detection
    private var velocityTracker: VelocityTracker? = null
    private val flingVelocityThreshold = 1200f // pixels per second
    private var lastMoveTime = 0L
    private var lastMoveY = 0f

    // Height indicator
    private var heightIndicator: TextView? = null
    private var heightIndicatorHandler = Handler(Looper.getMainLooper())
    private var hideHeightIndicatorRunnable: Runnable? = null

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
        setBackgroundColor(bgColor)

        panelContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(surfaceColor)
                cornerRadii = floatArrayOf(
                    dp(28f), dp(28f), dp(28f), dp(28f),
                    0f, 0f, 0f, 0f,
                )
            }
            background = bg
            elevation = dp(24f)
            clipToOutline = true
        }

        val panelHeight = (screenHeight * preferencesManager.answerPanelHeightRatio).toInt()
        val panelParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            panelHeight,
        ).apply {
            gravity = Gravity.BOTTOM
        }

        addView(panelContainer, panelParams)

        // Height indicator (shown during drag)
        heightIndicator = TextView(context).apply {
            setTextColor(onPrimaryColor)
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#CC000000"))
                cornerRadius = dp(20f)
            }
            background = bg
            setPadding(dp(16f).toInt(), dp(8f).toInt(), dp(16f).toInt(), dp(8f).toInt())
            alpha = 0f
            visibility = GONE
        }
        val indicatorParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER
        }
        addView(heightIndicator, indicatorParams)

        // Drag handle container with improved resize and fling-to-dismiss gesture
        val handleContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(16f).toInt(), 0, dp(8f).toInt())
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        touchStartY = event.rawY
                        touchLastY = event.rawY
                        panelStartHeight = panelContainer.height
                        isDragging = false
                        lastMoveTime = System.currentTimeMillis()
                        lastMoveY = event.rawY

                        // Initialize velocity tracker
                        velocityTracker?.recycle()
                        velocityTracker = VelocityTracker.obtain()
                        velocityTracker?.addMovement(event)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        velocityTracker?.addMovement(event)
                        val deltaY = event.rawY - touchStartY
                        val currentTime = System.currentTimeMillis()

                        if (!isDragging && abs(deltaY) > resizeThreshold) {
                            isDragging = true
                            showHeightIndicator()
                        }

                        if (isDragging) {
                            // Continuous resize in both directions
                            val newHeight = (panelStartHeight - deltaY)
                                .coerceIn(minHeight.toFloat(), maxHeight.toFloat())
                            val params = panelContainer.layoutParams as LayoutParams
                            params.height = newHeight.toInt()
                            panelContainer.layoutParams = params

                            // Update height indicator
                            updateHeightIndicator(newHeight.toInt())

                            touchLastY = event.rawY
                            lastMoveTime = currentTime
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        velocityTracker?.computeCurrentVelocity(1000)
                        val velocityY = velocityTracker?.yVelocity ?: 0f

                        if (isDragging) {
                            val deltaY = event.rawY - touchStartY
                            val currentHeight = panelContainer.height
                            val isNearMin = currentHeight <= dismissHeightThreshold

                            // Only dismiss if panel is shrunk near minimum AND user drags/flings down
                            val isFlingDown = velocityY > flingVelocityThreshold && isNearMin
                            val isDragPastMin = deltaY > 0 && isNearMin

                            if (isFlingDown || isDragPastMin) {
                                animateOut()
                            } else {
                                snapToNearestHeight()
                            }

                            hideHeightIndicator()
                        }

                        isDragging = false
                        velocityTracker?.recycle()
                        velocityTracker = null
                        true
                    }
                    else -> false
                }
            }
        }

        dragHandle = View(context).apply {
            val handleBg = GradientDrawable().apply {
                setColor(onSurfaceVariantColor)
                cornerRadius = dp(3f)
            }
            background = handleBg
        }
        handleContainer.addView(dragHandle, LinearLayout.LayoutParams(dp(48f).toInt(), dp(4f).toInt()))
        panelContainer.addView(handleContainer)

        // Header
        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24f).toInt(), dp(4f).toInt(), dp(16f).toInt(), dp(16f).toInt())
        }

        val titleText = TextView(context).apply {
            text = if (sendDirectImage) "AI 识图助手" else "AI 解题助手"
            setTextColor(onSurfaceColor)
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        headerLayout.addView(titleText, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        val closeBtn = TextView(context).apply {
            text = "\u2715"
            setTextColor(onSurfaceVariantColor)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(dp(14f).toInt(), dp(10f).toInt(), dp(14f).toInt(), dp(10f).toInt())
            setOnClickListener { animateOut() }
        }
        headerLayout.addView(closeBtn)

        panelContainer.addView(headerLayout)

        // Divider
        val divider = View(context).apply {
            setBackgroundColor(outlineVariantColor)
        }
        panelContainer.addView(divider, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 1))

        // Messages scroll area
        scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            isFillViewport = true
        }

        messagesContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f).toInt(), dp(16f).toInt(), dp(20f).toInt(), dp(16f).toInt())
        }
        scrollView.addView(messagesContainer)

        panelContainer.addView(
            scrollView,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
        )

        // Action buttons row
        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20f).toInt(), 0, dp(20f).toInt(), dp(8f).toInt())
        }

        val copyBtn = createActionButton("📋 复制") { copyLastAnswer() }
        val regenBtn = createActionButton("🔄 重新生成") { regenerateAnswer() }
        actionsRow.addView(copyBtn)
        actionsRow.addView(regenBtn)
        panelContainer.addView(actionsRow)

        // Input area
        val inputContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f).toInt(), dp(8f).toInt(), dp(16f).toInt(), dp(20f).toInt())
            val bg = GradientDrawable().apply {
                setColor(surfaceContainerColor)
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            }
            background = bg
        }

        inputField = EditText(context).apply {
            hint = "追问..."
            setHintTextColor(outlineColor)
            setTextColor(onSurfaceColor)
            textSize = 15f
            maxLines = 3
            imeOptions = EditorInfo.IME_ACTION_SEND
            val inputBg = GradientDrawable().apply {
                setColor(surfaceContainerHighColor)
                cornerRadius = dp(24f)
            }
            background = inputBg
            setPadding(dp(20f).toInt(), dp(14f).toInt(), dp(16f).toInt(), dp(14f).toInt())
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendFollowUp()
                    true
                } else false
            }
        }
        inputContainer.addView(
            inputField,
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )

        sendButton = ImageView(context).apply {
            val sendBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(primaryColor)
            }
            background = sendBg
            setPadding(dp(12f).toInt(), dp(12f).toInt(), dp(12f).toInt(), dp(12f).toInt())
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(onPrimaryColor)
            setOnClickListener { sendFollowUp() }
        }
        sendButton.setImageBitmap(createSendIcon())

        val sendParams = LinearLayout.LayoutParams(dp(48f).toInt(), dp(48f).toInt()).apply {
            marginStart = dp(10f).toInt()
        }
        inputContainer.addView(sendButton, sendParams)

        panelContainer.addView(inputContainer)
    }

    private fun createActionButton(text: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(onSurfaceVariantColor)
            textSize = 13f
            val bg = GradientDrawable().apply {
                setColor(surfaceContainerHighColor)
                cornerRadius = dp(20f)
            }
            background = bg
            setPadding(dp(18f).toInt(), dp(10f).toInt(), dp(18f).toInt(), dp(10f).toInt())
            val params = LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = dp(10f).toInt()
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
            color = onPrimaryColor
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
    private var thinkingContainer: LinearLayout? = null
    private var isThinkingPhase = false
    private var thinkingExpanded = true

    private var toolCallDepth = 0
    private companion object {
        const val MAX_TOOL_CALL_DEPTH = 5
    }

    private fun sendToLLM(loadingView: TextView) {
        val config = preferencesManager.getLLMConfig()

        if (config.apiKey.isBlank()) {
            updateBubbleText(loadingView, "请先在设置中配置 API 密钥", isLoading = false, isError = true)
            toolCallDepth = 0
            return
        }

        currentStreamingText.clear()
        currentThinkingText.clear()
        isThinkingPhase = false
        thinkingView = null
        thinkingContainer = null
        thinkingExpanded = true

        val tools = ToolRegistry.getToolDefinitions()

        scope.launch {
            if (config.stream) {
                val pendingToolCalls = mutableListOf<ToolCall>()
                var contentReceived = false
                val parentJob = coroutineContext[Job]!!

                val timeoutJob = launch {
                    try {
                        kotlinx.coroutines.delay(60_000)
                        if (!contentReceived) {
                            updateBubbleText(loadingView, "请求超时，请检查网络或 API 配置", isLoading = false, isError = true)
                            scrollToBottom()
                            parentJob.cancel()
                        }
                    } catch (_: CancellationException) {}
                }

                try {
                    llmRepository.streamChatCompletion(config, messages, tools).collect { event ->
                        when (event) {
                            is LLMRepository.StreamEvent.Started -> {
                                updateBubbleText(loadingView, "思考中...", isLoading = true)
                            }
                            is LLMRepository.StreamEvent.Thinking -> {
                                contentReceived = true
                                if (!isThinkingPhase) {
                                    isThinkingPhase = true
                                    updateBubbleText(loadingView, "正在深度思考...", isLoading = true)
                                    val (container, tv) = addThinkingBubble()
                                    thinkingContainer = container
                                    thinkingView = tv
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
                                contentReceived = true
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
                                    isLoading = true,
                                )
                                scrollToBottom()
                            }
                            is LLMRepository.StreamEvent.ToolCall -> {
                                contentReceived = true
                                pendingToolCalls.add(event.toolCall)
                            }
                            is LLMRepository.StreamEvent.Completed -> {
                                timeoutJob.cancel()
                                if (pendingToolCalls.isNotEmpty()) {
                                    val fullText = currentStreamingText.toString()
                                    if (fullText.isNotBlank()) {
                                        updateBubbleText(loadingView, fullText, isLoading = false)
                                    } else {
                                        handler.post {
                                            try {
                                                (loadingView.parent as? View)?.let { container ->
                                                    (container.parent as? ViewGroup)?.removeView(container)
                                                }
                                            } catch (_: Exception) {}
                                        }
                                    }

                                    if (toolCallDepth >= MAX_TOOL_CALL_DEPTH) {
                                        toolCallDepth = 0
                                        updateBubbleText(loadingView, "工具调用次数过多，已停止", isLoading = false, isError = true)
                                        scrollToBottom()
                                    } else {
                                        try {
                                            processToolCalls(fullText, pendingToolCalls.toList())
                                        } catch (e: Exception) {
                                            toolCallDepth = 0
                                            handler.post {
                                                val errView = addAssistantBubble("", isLoading = false)
                                                updateBubbleText(errView, "工具执行失败: ${e.message}", isLoading = false, isError = true)
                                                scrollToBottom()
                                            }
                                        }
                                    }
                                } else {
                                    toolCallDepth = 0
                                    val fullText = currentStreamingText.toString()
                                    if (fullText.isNotBlank()) {
                                        messages.add(ChatMessage.assistant(fullText))
                                        updateBubbleText(loadingView, fullText, isLoading = false)
                                    } else if (!contentReceived) {
                                        updateBubbleText(loadingView, "未收到有效响应，请重试", isLoading = false, isError = true)
                                    } else {
                                        handler.post {
                                            try {
                                                (loadingView.parent as? View)?.let { container ->
                                                    (container.parent as? ViewGroup)?.removeView(container)
                                                }
                                            } catch (_: Exception) {}
                                        }
                                    }
                                    scrollToBottom()
                                    saveToHistory()
                                }
                            }
                            is LLMRepository.StreamEvent.Error -> {
                                timeoutJob.cancel()
                                if (pendingToolCalls.isNotEmpty()) {
                                    val fullText = currentStreamingText.toString()
                                    try {
                                        processToolCalls(fullText, pendingToolCalls.toList())
                                    } catch (e: Exception) {
                                        toolCallDepth = 0
                                        updateBubbleText(loadingView, "工具执行失败: ${e.message}\n原始错误: ${event.message}", isLoading = false, isError = true)
                                        scrollToBottom()
                                    }
                                } else {
                                    toolCallDepth = 0
                                    updateBubbleText(loadingView, event.message, isLoading = false, isError = true)
                                    scrollToBottom()
                                }
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    timeoutJob.cancel()
                    toolCallDepth = 0
                } catch (e: Exception) {
                    timeoutJob.cancel()
                    toolCallDepth = 0
                    updateBubbleText(loadingView, "请求失败: ${e.message}", isLoading = false, isError = true)
                    scrollToBottom()
                }
            } else {
                updateBubbleText(loadingView, "正在思考...", isLoading = true)
                val result = llmRepository.chatCompletion(config, messages, tools)
                result.fold(
                    onSuccess = { response ->
                        if (response.toolCalls != null && response.toolCalls.isNotEmpty()) {
                            handleToolCalls(response.content, response.toolCalls, loadingView)
                        } else {
                            toolCallDepth = 0
                            val text = response.content ?: ""
                            messages.add(ChatMessage.assistant(text))
                            updateBubbleText(loadingView, text, isLoading = false)
                            scrollToBottom()
                            saveToHistory()
                        }
                    },
                    onFailure = { error ->
                        toolCallDepth = 0
                        updateBubbleText(loadingView, error.message ?: "未知错误", isLoading = false, isError = true)
                        scrollToBottom()
                    },
                )
            }
        }
    }

    private suspend fun processToolCalls(fullText: String?, toolCalls: List<ToolCall>) {
        toolCallDepth++
        val correctedToolCalls = toolCalls.map { tc ->
            val correctedName = if (tc.name == "get_current_datatime" || tc.name == "get_datetime") {
                "get_current_datetime"
            } else {
                tc.name
            }
            val correctedArgs = if (tc.arguments.isBlank()) "{}" else tc.arguments
            tc.copy(name = correctedName, arguments = correctedArgs)
        }

        messages.add(ChatMessage.assistantWithToolCalls(fullText?.ifBlank { null }, correctedToolCalls))

        for (toolCall in correctedToolCalls) {
            val argsDisplay = parseToolCallArgs(toolCall)
            val toolName = getToolDisplayName(toolCall.name)

            addToolCallBubble(toolName, argsDisplay)

            val result = toolExecutor.execute(toolCall)

            messages.add(ChatMessage.toolResult(toolCall.id, result.content))

            addToolResultBubble(result.content, result.isError)
        }

        handler.post {
            val newLoadingView = addAssistantBubble("", isLoading = true)
            sendToLLM(newLoadingView)
        }
    }

    private suspend fun handleToolCalls(fullText: String?, toolCalls: List<ToolCall>, @Suppress("UNUSED_PARAMETER") loadingView: TextView) {
        handler.post {
            try {
                (loadingView.parent as? View)?.let { container ->
                    (container.parent as? ViewGroup)?.removeView(container)
                }
            } catch (_: Exception) {}
        }

        if (toolCallDepth >= MAX_TOOL_CALL_DEPTH) {
            toolCallDepth = 0
            handler.post {
                val errView = addAssistantBubble("", isLoading = false)
                updateBubbleText(errView, "工具调用次数过多，已停止", isLoading = false, isError = true)
                scrollToBottom()
            }
            return
        }

        try {
            processToolCalls(fullText, toolCalls)
        } catch (e: Exception) {
            toolCallDepth = 0
            handler.post {
                val errView = addAssistantBubble("", isLoading = false)
                updateBubbleText(errView, "工具执行失败: ${e.message}", isLoading = false, isError = true)
                scrollToBottom()
            }
        }
    }

    // ---- Bubble Views ----

    private fun addUserBubble(text: String) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(dp(48f).toInt(), dp(4f).toInt(), 0, dp(8f).toInt())
        }

        val bubble = TextView(context).apply {
            this.text = text
            setTextColor(onPrimaryColor)
            textSize = 14f
            val bg = GradientDrawable().apply {
                setColor(primaryColor)
                cornerRadii = floatArrayOf(
                    dp(20f), dp(20f), dp(6f), dp(6f),
                    dp(20f), dp(20f), dp(20f), dp(20f),
                )
            }
            background = bg
            setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(16f).toInt(), dp(12f).toInt())
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
            setPadding(dp(48f).toInt(), dp(4f).toInt(), 0, dp(8f).toInt())
        }

        val imagePreview = ImageView(context).apply {
            val maxSize = dp(160f).toInt()
            val scale = minOf(
                maxSize.toFloat() / screenshotBitmap.width,
                maxSize.toFloat() / screenshotBitmap.height,
                1f,
            )
            val scaledBitmap = Bitmap.createScaledBitmap(
                screenshotBitmap,
                (screenshotBitmap.width * scale).toInt(),
                (screenshotBitmap.height * scale).toInt(),
                true,
            )
            setImageBitmap(scaledBitmap)
            scaleType = ImageView.ScaleType.CENTER_CROP
            val bg = GradientDrawable().apply {
                setColor(primaryColor)
                cornerRadii = floatArrayOf(
                    dp(20f), dp(20f), dp(6f), dp(6f),
                    dp(20f), dp(20f), dp(20f), dp(20f),
                )
            }
            background = bg
            setPadding(dp(4f).toInt(), dp(4f).toInt(), dp(4f).toInt(), dp(4f).toInt())
        }

        val imageParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = dp(4f).toInt()
        }
        container.addView(imagePreview, imageParams)

        if (text.isNotBlank()) {
            val bubble = TextView(context).apply {
                this.text = text
                setTextColor(onPrimaryColor)
                textSize = 14f
                val bg = GradientDrawable().apply {
                    setColor(primaryColor)
                    cornerRadii = floatArrayOf(
                        dp(6f), dp(6f), dp(6f), dp(6f),
                        dp(20f), dp(20f), dp(20f), dp(20f),
                    )
                }
                background = bg
                setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(16f).toInt(), dp(12f).toInt())
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
            setPadding(0, dp(4f).toInt(), dp(48f).toInt(), dp(8f).toInt())
        }

        val label = TextView(context).apply {
            this.text = "AI 助手"
            setTextColor(onSurfaceVariantColor)
            textSize = 11f
            setPadding(dp(4f).toInt(), 0, 0, dp(4f).toInt())
        }
        container.addView(label)

        val bubble = TextView(context).apply {
            setTextColor(onSurfaceColor)
            textSize = 14f
            val bg = GradientDrawable().apply {
                setColor(surfaceContainerHighColor)
                cornerRadii = floatArrayOf(
                    dp(6f), dp(6f), dp(20f), dp(20f),
                    dp(20f), dp(20f), dp(20f), dp(20f),
                )
            }
            background = bg
            setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(16f).toInt(), dp(12f).toInt())
            movementMethod = ScrollingMovementMethod.getInstance()
            setLineSpacing(dp(4f), 1f)

            if (isLoading) {
                this.text = if (text.isEmpty()) createLoadingDots() else text
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

    private fun createLoadingDots(): CharSequence {
        return "●  ●  ●"
    }

    private fun addThinkingBubble(): Pair<LinearLayout, TextView> {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(0, dp(2f).toInt(), dp(48f).toInt(), dp(6f).toInt())
        }

        // Clickable header for expand/collapse
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f).toInt(), dp(6f).toInt(), dp(8f).toInt(), dp(4f).toInt())
            val bg = GradientDrawable().apply {
                setColor(tertiaryContainerColor)
                cornerRadii = floatArrayOf(
                    dp(14f), dp(14f), dp(14f), dp(14f),
                    0f, 0f, 0f, 0f,
                )
            }
            background = bg
            setOnClickListener {
                thinkingExpanded = !thinkingExpanded
                thinkingView?.let { tv ->
                    handler.post {
                        if (thinkingExpanded) {
                            tv.maxLines = Int.MAX_VALUE
                            tv.ellipsize = null
                        } else {
                            tv.maxLines = 3
                            tv.ellipsize = android.text.TextUtils.TruncateAt.END
                        }
                    }
                }
            }
        }

        val label = TextView(context).apply {
            text = "思考过程"
            setTextColor(tertiaryColor)
            textSize = 11f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(4f).toInt(), 0, 0, 0)
        }
        headerRow.addView(label, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        val expandIndicator = TextView(context).apply {
            text = "▲"
            setTextColor(tertiaryColor)
            textSize = 10f
            tag = "expand_indicator"
        }
        headerRow.addView(expandIndicator)

        container.addView(headerRow)

        val bubble = TextView(context).apply {
            setTextColor(onSurfaceVariantColor)
            textSize = 12f
            setTypeface(null, Typeface.ITALIC)
            val bg = GradientDrawable().apply {
                setColor(tertiaryContainerColor.copy(alpha = 128))
                cornerRadii = floatArrayOf(
                    0f, 0f, dp(14f), dp(14f),
                    dp(14f), dp(14f), dp(14f), dp(14f),
                )
            }
            background = bg
            setPadding(dp(14f).toInt(), dp(8f).toInt(), dp(14f).toInt(), dp(10f).toInt())
            setLineSpacing(dp(2f), 1f)
        }

        container.addView(bubble)
        messagesContainer.addView(container)
        scrollToBottom()

        return Pair(container, bubble)
    }

    private fun Int.copy(alpha: Int): Int {
        return Color.argb(
            alpha,
            Color.red(this),
            Color.green(this),
            Color.blue(this),
        )
    }

    private fun getToolDisplayName(toolName: String): String {
        return when (toolName) {
            "get_current_datetime", "get_current_datatime" -> "获取日期时间"
            "calculate" -> "计算表达式"
            "evaluate_js" -> "执行计算"
            "convert_unit" -> "单位转换"
            else -> toolName
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
            setPadding(0, dp(2f).toInt(), dp(48f).toInt(), dp(4f).toInt())
        }

        val header = TextView(context).apply {
            text = "调用工具: $toolName"
            setTextColor(Color.parseColor("#66BB6A"))
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#1A4CAF50"))
                cornerRadii = floatArrayOf(
                    dp(14f), dp(14f), 0f, 0f,
                    0f, 0f, dp(14f), dp(14f),
                )
            }
            background = bg
            setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(16f).toInt(), dp(6f).toInt())
        }
        container.addView(header)

        if (args.isNotBlank()) {
            val argsView = TextView(context).apply {
                text = args
                setTextColor(Color.parseColor("#A5D6A7"))
                textSize = 12f
                val bg = GradientDrawable().apply {
                    setColor(Color.parseColor("#154CAF50"))
                    cornerRadii = floatArrayOf(
                        0f, 0f, dp(14f), dp(14f),
                        dp(14f), dp(14f), 0f, 0f,
                    )
                }
                background = bg
                setPadding(dp(16f).toInt(), dp(4f).toInt(), dp(16f).toInt(), dp(10f).toInt())
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
            setPadding(0, dp(2f).toInt(), dp(48f).toInt(), dp(4f).toInt())
        }

        val headerText = if (isError) "工具执行失败" else "工具返回结果"
        val headerColor = if (isError) errorColor else Color.parseColor("#66BB6A")
        val bgColor = if (isError) errorContainerColor else Color.parseColor("#1A4CAF50")
        val contentBgColor = if (isError) errorContainerColor.copy(alpha = 0x10) else Color.parseColor("#154CAF50")
        val contentColor = if (isError) errorColor else Color.parseColor("#A5D6A7")

        val header = TextView(context).apply {
            text = headerText
            setTextColor(headerColor)
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val bg = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadii = floatArrayOf(
                    dp(14f), dp(14f), 0f, 0f,
                    0f, 0f, dp(14f), dp(14f),
                )
            }
            background = bg
            setPadding(dp(16f).toInt(), dp(10f).toInt(), dp(16f).toInt(), dp(4f).toInt())
        }
        container.addView(header)

        val resultView = TextView(context).apply {
            text = content
            setTextColor(contentColor)
            textSize = 12f
            val bg = GradientDrawable().apply {
                setColor(contentBgColor)
                cornerRadii = floatArrayOf(
                    0f, 0f, dp(14f), dp(14f),
                    dp(14f), dp(14f), 0f, 0f,
                )
            }
            background = bg
            setPadding(dp(16f).toInt(), dp(4f).toInt(), dp(16f).toInt(), dp(10f).toInt())
            setLineSpacing(dp(2f), 1f)
        }
        container.addView(resultView)

        messagesContainer.addView(container)
        scrollToBottom()
    }

    private fun updateBubbleText(bubble: TextView, text: String, isLoading: Boolean, isError: Boolean = false) {
        handler.post {
            if (isLoading && text.isNotEmpty()) {
                bubble.text = "$text ▎"
            } else if (!isLoading && text.isNotEmpty()) {
                if (isError) {
                    bubble.setTextColor(errorColor)
                    bubble.text = text
                } else {
                    markwon.setMarkdown(bubble, text)
                }
            } else {
                bubble.text = createLoadingDots()
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
                    "screenshots/screenshot_${System.currentTimeMillis()}.png",
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
                    previewText = if (sendDirectImage) " $preview" else preview,
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
                .setInterpolator(DecelerateInterpolator(1.5f))
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
            .setInterpolator(DecelerateInterpolator(1.5f))
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

    private fun showHeightIndicator() {
        heightIndicator?.let { indicator ->
            indicator.visibility = VISIBLE
            indicator.animate()
                .alpha(1f)
                .setDuration(150)
                .start()
        }
    }

    private fun updateHeightIndicator(height: Int) {
        val ratio = (height.toFloat() / screenHeight * 100).toInt()
        heightIndicator?.text = "${ratio}%"
    }

    private fun hideHeightIndicator() {
        hideHeightIndicatorRunnable?.let { heightIndicatorHandler.removeCallbacks(it) }
        hideHeightIndicatorRunnable = Runnable {
            heightIndicator?.animate()
                ?.alpha(0f)
                ?.setDuration(200)
                ?.withEndAction { heightIndicator?.visibility = GONE }
                ?.start()
        }
        heightIndicatorHandler.postDelayed(hideHeightIndicatorRunnable!!, 800)
    }

    private fun snapToNearestHeight() {
        val currentRatio = panelContainer.height.toFloat() / screenHeight
        val nearestRatio = snapRatios.minByOrNull { abs(it - currentRatio) } ?: 0.65f
        val targetHeight = (screenHeight * nearestRatio).toInt()
        preferencesManager.answerPanelHeightRatio = nearestRatio

        val params = panelContainer.layoutParams as LayoutParams
        val startHeight = params.height
        val animator = ValueAnimator.ofInt(startHeight, targetHeight).apply {
            duration = 350
            interpolator = OvershootInterpolator(0.8f)
            addUpdateListener { animation ->
                val value = animation.animatedValue as Int
                val p = panelContainer.layoutParams as LayoutParams
                p.height = value
                panelContainer.layoutParams = p
            }
        }
        animator.start()
    }

    fun release() {
        hideHeightIndicatorRunnable?.let { heightIndicatorHandler.removeCallbacks(it) }
        heightIndicator?.animate()?.cancel()
        scope.cancel()
    }
}
