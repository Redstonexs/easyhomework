package com.easyhomework.app.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.FrameLayout.LayoutParams
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var onReselect: (() -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val llmRepository = LLMRepository()
    private val toolExecutor = ToolExecutor()
    private val preferencesManager = PreferencesManager(serviceContext)
    private val markwon: Markwon by lazy { createMarkwon() }
    private val handler = Handler(Looper.getMainLooper())
    private val database by lazy { AppDatabase.getDatabase(serviceContext) }

    private val messages = mutableListOf<ChatMessage>()
    private var currentStreamingText = StringBuilder()
    private var historyId: Long = -1
    private var conversationStarted = false

    private companion object {
        const val IMAGE_SOLVING_PROMPT = "请识别并解答图片中的题目，给出详细的解题步骤和最终答案。"
        const val IMAGE_USER_PLACEHOLDER = "[图片题目]"
        const val MAX_TOOL_CALL_DEPTH = 5
        const val TIMELINE_DOT_SIZE_DP = 18f
        const val TIMELINE_RAIL_WIDTH_DP = 22f
        const val TAG_ASSISTANT_TIMELINE = "assistant_timeline"
        const val TAG_TIMELINE_ITEM = "timeline_item"
        const val INLINE_MATH_DELIMITER = "\$"
        const val BLOCK_MATH_DELIMITER = "\$\$"
        const val STREAM_CURSOR = "▎"
    }

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

        val reselectBtn = TextView(context).apply {
            text = "重新框选"
            setTextColor(primaryColor)
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(14f).toInt(), dp(8f).toInt(), dp(14f).toInt(), dp(8f).toInt())
            val bg = GradientDrawable().apply {
                setColor(surfaceContainerHighColor)
                setStroke(dp(1f).toInt(), primaryColor)
                cornerRadius = dp(18f)
            }
            background = bg
            setOnClickListener { onReselect?.invoke() }
        }
        val reselectParams = LinearLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
        ).apply {
            marginEnd = dp(8f).toInt()
        }
        headerLayout.addView(reselectBtn, reselectParams)

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
                } else {
                    false
                }
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
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
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
        val isVisionMode = sendDirectImage && config.supportsVisionInput()

        if (isVisionMode) {
            val requestText = if (recognizedText.isNotBlank()) {
                recognizedText
            } else {
                IMAGE_SOLVING_PROMPT
            }
            val displayText = recognizedText.ifBlank { IMAGE_USER_PLACEHOLDER }
            val userMessage = ChatMessage.userWithImage(requestText, screenshotBitmap)
            messages.add(userMessage)
            addUserBubbleWithImage(displayText)
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

    private enum class TimelineTone {
        THINKING,
        TOOL,
        ANSWER,
        ERROR,
    }

    private data class TimelineItem(
        val container: LinearLayout,
        val contentView: TextView,
    )

    private var currentThinkingText = StringBuilder()
    private var thinkingView: TextView? = null
    private var thinkingContainer: LinearLayout? = null
    private var isThinkingPhase = false
    private var currentTimelineBody: LinearLayout? = null
    private var currentAnswerView: TextView? = null

    private var toolCallDepth = 0

    private fun sendToLLM(loadingView: TextView) {
        val config = preferencesManager.getLLMConfig()

        if (config.apiKey.isBlank()) {
            updateTimelineItem(loadingView, "配置错误", "请先在设置中配置 API 密钥", isLoading = false, isError = true)
            toolCallDepth = 0
            return
        }

        currentStreamingText.clear()
        currentThinkingText.clear()
        isThinkingPhase = false
        thinkingView = null
        thinkingContainer = null
        currentAnswerView = null

        val tools = if (config.supportsToolCalling()) {
            ToolRegistry.getToolDefinitions()
        } else {
            emptyList()
        }
        val requestMode = when {
            sendDirectImage -> "直接识图"
            recognizedText.isNotBlank() -> "OCR 识字"
            else -> "文本为空"
        }
        val noResponseDetails = buildNoResponseDetails(config, requestMode, tools.size)

        scope.launch {
            if (tools.isNotEmpty() && shouldPreloadCurrentDatetimeTool()) {
                handleToolCalls(
                    fullText = null,
                    thinkingText = null,
                    toolCalls = listOf(
                        ToolCall(
                            id = "local_datetime_${System.currentTimeMillis()}",
                            name = "get_current_datetime",
                            arguments = "{}",
                        ),
                    ),
                    loadingView = loadingView,
                )
                return@launch
            }

            if (config.stream) {
                val pendingToolCalls = mutableListOf<ToolCall>()
                var contentReceived = false
                val parentJob = coroutineContext[Job]

                val timeoutJob = launch {
                    try {
                        kotlinx.coroutines.delay(60_000)
                        if (!contentReceived) {
                            updateTimelineItem(
                                loadingView,
                                "请求超时",
                                "请求超时，请检查网络或 API 配置",
                                isLoading = false,
                                isError = true,
                            )
                            scrollToBottom()
                            parentJob?.cancel()
                        }
                    } catch (_: CancellationException) {}
                }

                try {
                    llmRepository.streamChatCompletion(config, messages, tools, scope).collect { event ->
                        when (event) {
                            is LLMRepository.StreamEvent.Started -> {
                                updateTimelineItem(loadingView, "思考", "思考中...", isLoading = true)
                            }
                            is LLMRepository.StreamEvent.Thinking -> {
                                contentReceived = true
                                if (!isThinkingPhase) {
                                    isThinkingPhase = true
                                    updateTimelineItem(loadingView, "思考", "正在深度思考...", isLoading = true)
                                    thinkingContainer = findTimelineItemContainer(loadingView)
                                    thinkingView = loadingView
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
                                    collapseTimelineItem(thinkingContainer)
                                }
                                currentStreamingText.append(event.text)
                                val answerView = ensureAnswerTimelineView(loadingView)
                                updateTimelineItem(
                                    answerView,
                                    "回答",
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
                                        val answerView = ensureAnswerTimelineView(loadingView)
                                        updateTimelineItem(answerView, "回答", fullText, isLoading = false)
                                    }

                                    if (toolCallDepth >= MAX_TOOL_CALL_DEPTH) {
                                        toolCallDepth = 0
                                        updateTimelineItem(
                                            loadingView,
                                            "工具调用次数过多",
                                            "工具调用次数过多，已停止",
                                            isLoading = false,
                                            isError = true,
                                        )
                                        scrollToBottom()
                                    } else {
                                        try {
                                            processToolCalls(
                                                fullText,
                                                currentThinkingText.toString(),
                                                pendingToolCalls.toList(),
                                            )
                                        } catch (e: Exception) {
                                            toolCallDepth = 0
                                            handler.post {
                                                val errView = addAssistantBubble("", isLoading = false)
                                                updateTimelineItem(
                                                    errView,
                                                    "工具执行失败",
                                                    "工具执行失败: ${e.message}",
                                                    isLoading = false,
                                                    isError = true,
                                                )
                                                scrollToBottom()
                                            }
                                        }
                                    }
                                } else {
                                    toolCallDepth = 0
                                    val fullText = currentStreamingText.toString()
                                    if (fullText.isNotBlank()) {
                                        messages.add(
                                            ChatMessage.assistant(
                                                fullText,
                                                reasoningContent = currentThinkingText.toString().ifBlank { null },
                                            ),
                                        )
                                        val answerView = ensureAnswerTimelineView(loadingView)
                                        updateTimelineItem(answerView, "回答", fullText, isLoading = false)
                                    } else if (!contentReceived) {
                                        updateTimelineItem(
                                            loadingView,
                                            "请求错误",
                                            noResponseDetails,
                                            isLoading = false,
                                            isError = true,
                                        )
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
                                        processToolCalls(
                                            fullText,
                                            currentThinkingText.toString(),
                                            pendingToolCalls.toList(),
                                        )
                                    } catch (e: Exception) {
                                        toolCallDepth = 0
                                        updateTimelineItem(
                                            loadingView,
                                            "工具执行失败",
                                            "工具执行失败: ${e.message}\n原始错误: ${event.message}",
                                            isLoading = false,
                                            isError = true,
                                        )
                                        scrollToBottom()
                                    }
                                } else {
                                    toolCallDepth = 0
                                    updateTimelineItem(
                                        loadingView,
                                        "请求错误",
                                        event.message,
                                        isLoading = false,
                                        isError = true,
                                    )
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
                    updateTimelineItem(
                        loadingView,
                        "请求失败",
                        "请求失败: ${e.message}",
                        isLoading = false,
                        isError = true,
                    )
                    scrollToBottom()
                }
            } else {
                updateTimelineItem(loadingView, "思考", "正在思考...", isLoading = true)
                val result = llmRepository.chatCompletion(config, messages, tools)
                result.fold(
                    onSuccess = { response ->
                        if (response.toolCalls != null && response.toolCalls.isNotEmpty()) {
                            handleToolCalls(response.content, response.thinking, response.toolCalls, loadingView)
                        } else {
                            toolCallDepth = 0
                            val text = response.content ?: ""
                            messages.add(
                                ChatMessage.assistant(
                                    text,
                                    reasoningContent = response.thinking?.ifBlank { null },
                                ),
                            )
                            response.thinking?.takeIf { it.isNotBlank() }?.let {
                                updateTimelineItem(loadingView, "思考", it, isLoading = false)
                            }
                            val answerView = ensureAnswerTimelineView(loadingView)
                            updateTimelineItem(answerView, "回答", text, isLoading = false)
                            scrollToBottom()
                            saveToHistory()
                        }
                    },
                    onFailure = { error ->
                        toolCallDepth = 0
                        updateTimelineItem(
                            loadingView,
                            "请求错误",
                            error.message ?: "未知错误",
                            isLoading = false,
                            isError = true,
                        )
                        scrollToBottom()
                    },
                )
            }
        }
    }

    private fun shouldPreloadCurrentDatetimeTool(): Boolean {
        val lastUserIndex = messages.indexOfLast { it.role == ChatMessage.ROLE_USER }
        if (lastUserIndex < 0) return false

        val messagesAfterLastUser = messages.drop(lastUserIndex + 1)
        val alreadyUsedDatetimeTool = messagesAfterLastUser.any { message ->
            message.toolCallId?.startsWith("local_datetime_") == true ||
                message.toolCalls.orEmpty().any { toolCall ->
                    toolCall.name == "get_current_datetime" || toolCall.name == "get_current_datatime"
                }
        }
        if (alreadyUsedDatetimeTool) return false

        return isCurrentDatetimeQuestion(messages[lastUserIndex].content)
    }

    private fun isCurrentDatetimeQuestion(text: String): Boolean {
        val compact = text.lowercase().replace(Regex("\\s+"), "")
        val currentMarkers = listOf("今天", "今日", "现在", "当前", "此刻", "本地", "today", "now", "current")
        val datetimeMarkers = listOf(
            "几月几号",
            "几号",
            "日期",
            "年月日",
            "星期几",
            "周几",
            "礼拜几",
            "几点",
            "当前时间",
            "时间戳",
            "date",
            "time",
            "weekday",
            "timestamp",
        )
        return currentMarkers.any { compact.contains(it) } && datetimeMarkers.any { compact.contains(it) }
    }

    private fun buildNoResponseDetails(config: LLMConfig, requestMode: String, toolCount: Int): String {
        val endpoint = "${config.apiEndpoint.trimEnd('/')}/${config.apiPath.trimStart('/')}"
        return """
            未收到有效响应，请重试。
            请求模式: $requestMode
            API 类型: ${config.apiType.displayName}
            模型: ${config.modelName}
            地址: $endpoint
            流式输出: ${if (config.stream) "开启" else "关闭"}
            工具定义: 已发送 $toolCount 个
            视觉能力: ${if (config.supportsVisionInput()) "开启" else "关闭"}
            OCR 文本长度: ${recognizedText.length}
            说明: 连接已完成，但没有解析到回答正文、推理内容或工具调用。请检查模型是否支持当前请求格式，或关闭流式输出后重试。
        """.trimIndent()
    }

    private suspend fun processToolCalls(fullText: String?, thinkingText: String?, toolCalls: List<ToolCall>) {
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

        messages.add(
            ChatMessage.assistantWithToolCalls(
                fullText?.ifBlank { null },
                correctedToolCalls,
                thinkingText?.ifBlank { null },
            ),
        )

        val timelineBody = currentTimelineBody
        for (toolCall in correctedToolCalls) {
            val argsDisplay = parseToolCallArgs(toolCall)
            val toolName = getToolDisplayName(toolCall.name)

            // UI operations must run on main thread
            withContext(Dispatchers.Main) {
                currentTimelineBody = timelineBody
                addToolCallBubble(toolName, argsDisplay)
            }

            val result = toolExecutor.execute(toolCall)

            messages.add(ChatMessage.toolResult(toolCall.id, result.content))

            // UI operations must run on main thread
            withContext(Dispatchers.Main) {
                currentTimelineBody = timelineBody
                addToolResultBubble(result.content, result.isError)
            }
        }

        // Switch to main thread for UI + sendToLLM (which launches its own coroutine)
        withContext(Dispatchers.Main) {
            currentTimelineBody = timelineBody
            currentAnswerView = null
            val newLoadingView = addTimelineStep(
                title = "思考",
                content = createLoadingDots().toString(),
                tone = TimelineTone.THINKING,
                targetBody = timelineBody,
            ).contentView
            sendToLLM(newLoadingView)
        }
    }

    private suspend fun handleToolCalls(
        fullText: String?,
        thinkingText: String?,
        toolCalls: List<ToolCall>,
        @Suppress("UNUSED_PARAMETER") loadingView: TextView,
    ) {
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
                updateTimelineItem(
                    errView,
                    "工具调用次数过多",
                    "工具调用次数过多，已停止",
                    isLoading = false,
                    isError = true,
                )
                scrollToBottom()
            }
            return
        }

        try {
            processToolCalls(fullText, thinkingText, toolCalls)
        } catch (e: Exception) {
            toolCallDepth = 0
            handler.post {
                val errView = addAssistantBubble("", isLoading = false)
                updateTimelineItem(errView, "工具执行失败", "工具执行失败: ${e.message}", isLoading = false, isError = true)
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
            tag = TAG_ASSISTANT_TIMELINE
        }

        val label = TextView(context).apply {
            this.text = "AI 助手"
            setTextColor(onSurfaceVariantColor)
            textSize = 11f
            setPadding(dp(4f).toInt(), 0, 0, dp(4f).toInt())
        }
        container.addView(label)

        val timelineBody = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "timeline_body"
        }
        container.addView(timelineBody)
        messagesContainer.addView(container)
        currentTimelineBody = timelineBody

        val firstItem = addTimelineStep(
            title = if (isLoading) "思考" else "回答",
            content = text.ifEmpty { createLoadingDots().toString() },
            tone = if (isLoading) TimelineTone.THINKING else TimelineTone.ANSWER,
            targetBody = timelineBody,
            expanded = !isLoading,
        )
        currentAnswerView = firstItem.contentView.takeIf { !isLoading }
        scrollToBottom()

        return firstItem.contentView
    }

    private fun createLoadingDots(): CharSequence {
        return "●  ●  ●"
    }

    private fun ensureAnswerTimelineView(fallbackView: TextView): TextView {
        currentAnswerView?.let { return it }
        val item = addTimelineStep(
            title = "回答",
            content = fallbackView.text?.toString().orEmpty(),
            tone = TimelineTone.ANSWER,
            expanded = true,
        )
        currentAnswerView = item.contentView
        return item.contentView
    }

    private fun addTimelineStep(
        title: String,
        content: String,
        tone: TimelineTone,
        targetBody: LinearLayout? = currentTimelineBody,
        expanded: Boolean = false,
    ): TimelineItem {
        val body = targetBody ?: currentTimelineBody ?: error("Timeline body is not available")
        val item = createTimelineItem(title, content, tone)
        body.addView(item.container)
        if (expanded) {
            expandTimelineItem(item.container)
        } else {
            collapseTimelineItem(item.container)
        }
        scrollToBottom()
        return item
    }

    private fun createTimelineItem(title: String, content: String, tone: TimelineTone): TimelineItem {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            tag = TAG_TIMELINE_ITEM
            setPadding(0, dp(2f).toInt(), 0, dp(6f).toInt())
        }

        val card = createTimelineCard(title, content, tone)
        container.addView(
            createTimelineRail(tone),
            LinearLayout.LayoutParams(dp(TIMELINE_RAIL_WIDTH_DP).toInt(), LinearLayout.LayoutParams.MATCH_PARENT),
        )
        container.addView(card, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        container.setOnClickListener { toggleTimelineItem(container) }
        card.setOnClickListener { toggleTimelineItem(container) }

        val contentView = card.getChildAt(1) as TextView
        return TimelineItem(container, contentView)
    }

    private fun createTimelineRail(tone: TimelineTone): LinearLayout {
        val rail = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val dot = TextView(context).apply {
            text = "●"
            setTextColor(timelineAccentColor(tone))
            textSize = 13f
            gravity = Gravity.CENTER
        }
        val line = View(context).apply {
            setBackgroundColor(outlineVariantColor)
        }
        rail.addView(dot, LinearLayout.LayoutParams(dp(TIMELINE_DOT_SIZE_DP).toInt(), dp(TIMELINE_DOT_SIZE_DP).toInt()))
        rail.addView(line, LinearLayout.LayoutParams(dp(1f).toInt(), 0, 1f))
        return rail
    }

    private fun createTimelineCard(title: String, content: String, tone: TimelineTone): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = timelineCardBackground(tone)
            setPadding(dp(14f).toInt(), dp(10f).toInt(), dp(14f).toInt(), dp(10f).toInt())
            addView(createTimelineHeader(title, tone))
            addView(createTimelineContent(content, tone))
        }
    }

    private fun createTimelineHeader(title: String, tone: TimelineTone): LinearLayout {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(context).apply {
            text = title
            setTextColor(timelineAccentColor(tone))
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val indicator = TextView(context).apply {
            text = "展开"
            setTextColor(onSurfaceVariantColor)
            textSize = 11f
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(indicator)
        return header
    }

    private fun createTimelineContent(content: String, tone: TimelineTone): TextView {
        return TextView(context).apply {
            setTextColor(if (tone == TimelineTone.ERROR) errorColor else onSurfaceColor)
            textSize = if (tone == TimelineTone.ANSWER) 14f else 12f
            setLineSpacing(dp(3f), 1f)
            setPadding(0, dp(8f).toInt(), 0, 0)
            setLinkTextColor(tertiaryColor)
            highlightColor = primaryColor.copy(alpha = 64)
            movementMethod = LinkMovementMethod.getInstance()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                breakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY
                hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NORMAL
            }
            if (tone == TimelineTone.ANSWER && content.isNotBlank()) {
                renderMarkdown(this, content)
            } else {
                text = content
            }
        }
    }

    private fun createMarkwon(): Markwon {
        return Markwon.builder(serviceContext)
            .usePlugin(HtmlPlugin.create())
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(serviceContext))
            .usePlugin(TaskListPlugin.create(serviceContext))
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(MarkwonInlineParserPlugin.create())
            .usePlugin(
                JLatexMathPlugin.create(sp(14f)) { builder ->
                    builder.inlinesEnabled(true)
                },
            )
            .build()
    }

    private fun renderMarkdown(textView: TextView, markdown: String, showCursor: Boolean = false) {
        val preparedMarkdown = prepareMarkdownForDisplay(markdown, showCursor)
        if (preparedMarkdown.isBlank()) {
            textView.text = if (showCursor) STREAM_CURSOR else ""
        } else {
            markwon.setMarkdown(textView, preparedMarkdown)
        }
    }

    private fun prepareMarkdownForDisplay(markdown: String, showCursor: Boolean): String {
        val normalized = normalizeMarkdown(markdown).trimEnd()
        val closedMarkdown = closeUnmatchedMarkdownBlocks(normalized)
        return if (showCursor && closedMarkdown.isNotBlank()) {
            "$closedMarkdown\n\n$STREAM_CURSOR"
        } else {
            closedMarkdown
        }
    }

    private fun normalizeMarkdown(markdown: String): String {
        val normalizedLineEndings = markdown.replace("\r\n", "\n")
        val builder = StringBuilder()
        var openFence: String? = null
        normalizedLineEndings.lineSequence().forEachIndexed { index, line ->
            if (index > 0) builder.append('\n')
            val fence = markdownFenceDelimiter(line)
            val displayLine = if (openFence == null) normalizeMathDelimiters(line) else line
            builder.append(displayLine)
            if (fence != null) {
                openFence = if (openFence == fence) null else openFence ?: fence
            }
        }
        return builder.toString()
    }

    private fun normalizeMathDelimiters(line: String): String {
        return line
            .replace("\\[", "\n$BLOCK_MATH_DELIMITER\n")
            .replace("\\]", "\n$BLOCK_MATH_DELIMITER\n")
            .replace("\\(", INLINE_MATH_DELIMITER)
            .replace("\\)", INLINE_MATH_DELIMITER)
    }

    private fun closeUnmatchedMarkdownBlocks(markdown: String): String {
        val withClosedFence = findUnclosedFence(markdown)?.let { fence ->
            "$markdown\n$fence"
        } ?: markdown
        return if (hasUnclosedBlockMath(withClosedFence)) {
            "$withClosedFence\n$BLOCK_MATH_DELIMITER"
        } else {
            withClosedFence
        }
    }

    private fun findUnclosedFence(markdown: String): String? {
        var openFence: String? = null
        markdown.lineSequence().forEach { line ->
            val fence = markdownFenceDelimiter(line)
            if (fence != null) {
                openFence = if (openFence == fence) null else openFence ?: fence
            }
        }
        return openFence
    }

    private fun markdownFenceDelimiter(line: String): String? {
        val trimmed = line.trimStart()
        return when {
            trimmed.startsWith("```") -> "```"
            trimmed.startsWith("~~~") -> "~~~"
            else -> null
        }
    }

    private fun hasUnclosedBlockMath(markdown: String): Boolean {
        return markdown.lineSequence()
            .count { it.trim() == BLOCK_MATH_DELIMITER }
            .rem(2) == 1
    }

    private fun timelineCardBackground(tone: TimelineTone): GradientDrawable {
        return GradientDrawable().apply {
            setColor(
                when (tone) {
                    TimelineTone.THINKING -> tertiaryContainerColor.copy(alpha = 96)
                    TimelineTone.TOOL -> Color.parseColor("#1A4CAF50")
                    TimelineTone.ANSWER -> surfaceContainerHighColor
                    TimelineTone.ERROR -> errorContainerColor
                },
            )
            cornerRadius = dp(16f)
            setStroke(dp(1f).toInt(), timelineAccentColor(tone).copy(alpha = 90))
        }
    }

    private fun timelineAccentColor(tone: TimelineTone): Int {
        return when (tone) {
            TimelineTone.THINKING -> tertiaryColor
            TimelineTone.TOOL -> Color.parseColor("#66BB6A")
            TimelineTone.ANSWER -> primaryColor
            TimelineTone.ERROR -> errorColor
        }
    }

    private fun toggleTimelineItem(container: LinearLayout) {
        val expanded = container.isSelected
        if (expanded) {
            collapseTimelineItem(container)
        } else {
            expandTimelineItem(container)
        }
    }

    private fun collapseTimelineItem(container: LinearLayout?) {
        updateTimelineExpansion(container, expanded = false)
    }

    private fun expandTimelineItem(container: LinearLayout?) {
        updateTimelineExpansion(container, expanded = true)
    }

    private fun updateTimelineExpansion(container: LinearLayout?, expanded: Boolean) {
        val card = container?.getChildAt(1) as? LinearLayout ?: return
        val content = card.getChildAt(1) as? TextView ?: return
        val header = card.getChildAt(0) as? LinearLayout
        val indicator = header?.getChildAt(1) as? TextView
        container.isSelected = expanded
        content.visibility = if (expanded) VISIBLE else GONE
        indicator?.text = if (expanded) "收起" else "展开"
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
            "evaluate_js", "evaluate_expression" -> "计算表达式"
            "run_javascript", "run_js", "execute_javascript" -> "执行 JavaScript"
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
        addTimelineStep("调用工具: $toolName", args, TimelineTone.TOOL)
    }

    private fun addToolResultBubble(content: String, isError: Boolean) {
        addTimelineStep(
            title = if (isError) "工具执行失败" else "工具返回结果",
            content = content,
            tone = if (isError) TimelineTone.ERROR else TimelineTone.TOOL,
        )
    }

    private fun updateTimelineItem(
        contentView: TextView,
        title: String,
        content: String,
        isLoading: Boolean,
        isError: Boolean = false,
    ) {
        handler.post {
            val itemContainer = findTimelineItemContainer(contentView)
            updateTimelineTitle(itemContainer, title)
            contentView.setTextColor(if (isError) errorColor else onSurfaceColor)
            val displayContent = if (isLoading && content.isBlank()) createLoadingDots().toString() else content
            if (isError) {
                contentView.text = displayContent
            } else if (title == "回答") {
                renderMarkdown(contentView, displayContent, showCursor = isLoading)
            } else if (isLoading) {
                contentView.text = if (displayContent.isNotEmpty()) "$displayContent $STREAM_CURSOR" else displayContent
            } else {
                renderMarkdown(contentView, displayContent)
            }
            if (!isLoading && !isError && title == "回答") {
                expandTimelineItem(itemContainer)
            }
        }
    }

    private fun findTimelineItemContainer(view: View): LinearLayout? {
        var current = view.parent as? View
        while (current != null) {
            if (current is LinearLayout && current.tag == TAG_TIMELINE_ITEM) {
                return current
            }
            current = current.parent as? View
        }
        return null
    }

    private fun updateTimelineTitle(container: LinearLayout?, title: String) {
        val card = container?.getChildAt(1) as? LinearLayout
        val header = card?.getChildAt(0) as? LinearLayout
        val titleView = header?.getChildAt(0) as? TextView
        titleView?.text = title
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
        val lastUserIdx = messages.indexOfLast { it.role == ChatMessage.ROLE_USER }
        if (lastUserIdx < 0 || lastUserIdx == messages.lastIndex) {
            return
        }

        for (i in messages.lastIndex downTo lastUserIdx + 1) {
            messages.removeAt(i)
        }

        for (i in messagesContainer.childCount - 1 downTo 0) {
            val child = messagesContainer.getChildAt(i)
            if (child is LinearLayout && child.tag == TAG_ASSISTANT_TIMELINE) {
                messagesContainer.removeViewAt(i)
            } else {
                break
            }
        }
        currentTimelineBody = null
        currentAnswerView = null
        thinkingView = null
        thinkingContainer = null
        isThinkingPhase = false

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

                val previewSource = recognizedText.ifBlank { IMAGE_USER_PLACEHOLDER }
                val preview = if (previewSource.length > 60) {
                    previewSource.substring(0, 60) + "..."
                } else {
                    previewSource
                }

                val historyMessages = messages.map { message ->
                    if (sendDirectImage &&
                        message.role == ChatMessage.ROLE_USER &&
                        message.imageBitmap != null &&
                        message.content == IMAGE_SOLVING_PROMPT
                    ) {
                        message.copy(content = IMAGE_USER_PLACEHOLDER)
                    } else {
                        message
                    }
                }

                val history = QueryHistory(
                    id = if (historyId > 0) historyId else 0,
                    screenshotPath = screenshotFile.absolutePath,
                    recognizedText = if (sendDirectImage) IMAGE_USER_PLACEHOLDER else recognizedText,
                    conversations = historyMessages,
                    previewText = preview,
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

    private fun sp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            value,
            context.resources.displayMetrics,
        )
    }

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
        heightIndicator?.text = "$ratio%"
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
        hideHeightIndicatorRunnable?.let { heightIndicatorHandler.postDelayed(it, 800) }
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
