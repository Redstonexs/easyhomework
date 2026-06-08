package com.easyhomework.app.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Detects the most likely "question" region in a screenshot using ML Kit text detection.
 * Analyzes text block positions, density, and layout to suggest a crop region.
 */
class SmartRegionDetector {

    private var recognizer: com.google.mlkit.vision.text.TextRecognizer? = null

    private companion object {
        const val NO_TEXT_CONFIDENCE = 0.25f
        const val WEAK_TEXT_CONFIDENCE = 0.4f
        const val MIN_REGION_HEIGHT_RATIO = 0.045f
        const val MAX_REGION_HEIGHT_RATIO = 0.82f
        const val MAX_LOOSE_GAP_RATIO = 0.09f
        const val RELAXED_CUE_GAP_RATIO = 0.16f
        const val STATUS_BAR_BOTTOM_RATIO = 0.055f
        const val NAV_BAR_TOP_RATIO = 0.94f
        const val MIN_BLOCK_HEIGHT_PX = 8
        const val MIN_BLOCK_HEIGHT_RATIO = 0.006f
        const val MIN_BLOCK_WIDTH_RATIO = 0.018f
        const val MIN_MEANINGFUL_CHARS = 2
        const val MIN_LINE_COUNT = 1
        const val SMALL_BLOCK_COUNT = 2
        const val MIN_WINDOW_CHARS = 6
        const val DENSE_MIN_WINDOW_CHARS = 14
        const val MIN_WINDOW_WIDTH_RATIO = 0.18f
        const val COMPACTNESS_GAP_RATIO = 0.07f
        const val IDEAL_MIN_HEIGHT_RATIO = 0.08f
        const val IDEAL_MAX_HEIGHT_RATIO = 0.58f
        const val IDEAL_MIN_WIDTH_RATIO = 0.32f
        const val OK_MIN_HEIGHT_RATIO = 0.05f
        const val OK_MAX_HEIGHT_RATIO = 0.70f
        const val OK_MIN_WIDTH_RATIO = 0.24f
        const val HIGH_AREA_RATIO = 0.65f
        const val MEDIUM_AREA_RATIO = 0.52f
        const val SIZE_SCORE_IDEAL = 1f
        const val SIZE_SCORE_OK = 0.65f
        const val SIZE_SCORE_WEAK = 0.25f
        const val AREA_PENALTY_HIGH = 1f
        const val AREA_PENALTY_MEDIUM = 0.45f
        const val NO_PENALTY = 0f
        const val SINGLE_BLOCK_PENALTY = 18.0
        const val MAX_SCORE_CHARS = 140
        const val MAX_SCORE_LINES = 12
        const val CHAR_SCORE_WEIGHT = 1.4
        const val LINE_SCORE_WEIGHT = 11.0
        const val QUESTION_CUE_SCORE_WEIGHT = 24.0
        const val OPTION_SCORE_WEIGHT = 18.0
        const val DENSITY_SCORE_WEIGHT = 90.0
        const val COMPACTNESS_SCORE_WEIGHT = 28.0
        const val SIZE_SCORE_WEIGHT = 26.0
        const val AREA_PENALTY_WEIGHT = 44.0
        const val BASE_CONFIDENCE = 0.34f
        const val CONFIDENCE_CHAR_DIVISOR = 90f
        const val CONFIDENCE_CHAR_MAX = 0.18f
        const val CONFIDENCE_LINE_DIVISOR = 7f
        const val CONFIDENCE_LINE_MAX = 0.12f
        const val CONFIDENCE_COMPACTNESS_WEIGHT = 0.14f
        const val CONFIDENCE_QUESTION_CUE = 0.16f
        const val CONFIDENCE_MULTI_OPTION = 0.1f
        const val CONFIDENCE_SINGLE_OPTION = 0.04f
        const val CONFIDENCE_COVERAGE_RATIO = 0.45f
        const val CONFIDENCE_COVERAGE = 0.06f
        const val CONFIDENCE_MIN_HEIGHT_RATIO = 0.06f
        const val CONFIDENCE_MAX_HEIGHT_RATIO = 0.62f
        const val CONFIDENCE_MIN_WIDTH_RATIO = 0.3f
        const val CONFIDENCE_SHAPE = 0.11f
        const val CONFIDENCE_MULTI_BLOCK = 0.05f
        const val CONFIDENCE_AREA_PENALTY = 0.16f
        const val CONFIDENCE_SINGLE_BLOCK_PENALTY = 0.12f
        const val MAX_CONFIDENCE = 0.95f
        const val PADDING_H_RATIO = 0.06f
        const val MIN_PADDING_H_RATIO = 0.025f
        const val PADDING_TOP_RATIO = 0.1f
        const val MIN_PADDING_TOP_RATIO = 0.012f
        const val PADDING_BOTTOM_RATIO = 0.14f
        const val MIN_PADDING_BOTTOM_RATIO = 0.018f
        const val DEFAULT_LEFT_DIVISOR = 12
        const val DEFAULT_TOP_DIVISOR = 8
        const val DEFAULT_RIGHT_MULTIPLIER = 11
        const val DEFAULT_BOTTOM_MULTIPLIER = 4
        const val DEFAULT_BOTTOM_DIVISOR = 5
        const val SHORT_UI_CHAR_COUNT = 4
        const val TOP_UI_RATIO = 0.12f
        const val BOTTOM_UI_RATIO = 0.9f

        val OPTION_PREFIX_REGEX = Regex("""^\s*([A-Ha-h][\.．、)]|[①②③④⑤⑥⑦⑧]|[一二三四五六七八][、.．])""")
        val QUESTION_SYMBOL_REGEX = Regex("""[？?]|_{2,}|（\s*）|\(\s*\)|[=≥≤≈]|√|∠|△|[+\-×÷*/]""")
        val CLOCK_REGEX = Regex("""^\d{1,2}:\d{2}$""")
        val QUESTION_KEYWORDS = listOf(
            "题",
            "下列",
            "正确",
            "错误",
            "选择",
            "计算",
            "证明",
            "解答",
            "求",
            "若",
            "已知",
            "填空",
            "判断",
            "答案",
            "多少",
            "哪",
            "为什么",
        )
    }

    /**
     * Analyze a screenshot bitmap and return the suggested crop region.
     */
    suspend fun detectQuestionRegion(bitmap: Bitmap): DetectionResult {
        return try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val blocks = detectTextBlocks(inputImage)

            if (blocks.isEmpty()) {
                return DetectionResult(
                    suggestedRegion = Rect(
                        bitmap.width / DEFAULT_LEFT_DIVISOR,
                        bitmap.height / DEFAULT_TOP_DIVISOR,
                        bitmap.width * DEFAULT_RIGHT_MULTIPLIER / DEFAULT_LEFT_DIVISOR,
                        bitmap.height * DEFAULT_BOTTOM_MULTIPLIER / DEFAULT_BOTTOM_DIVISOR,
                    ),
                    confidence = NO_TEXT_CONFIDENCE,
                    allTextBlocks = emptyList(),
                )
            }

            // Filter out navigation bars, status bars, etc.
            val contentBlocks = filterContentBlocks(blocks, bitmap.width, bitmap.height)

            if (contentBlocks.isEmpty()) {
                return DetectionResult(
                    suggestedRegion = mergeBlocks(blocks, bitmap.width, bitmap.height),
                    confidence = WEAK_TEXT_CONFIDENCE,
                    allTextBlocks = blocks,
                )
            }

            val questionCandidate = findBestQuestionCandidate(contentBlocks, bitmap.width, bitmap.height)

            DetectionResult(
                suggestedRegion = questionCandidate.rect,
                confidence = questionCandidate.confidence,
                allTextBlocks = blocks,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DetectionResult(
                suggestedRegion = Rect(
                    bitmap.width / DEFAULT_LEFT_DIVISOR,
                    bitmap.height / DEFAULT_TOP_DIVISOR,
                    bitmap.width * DEFAULT_RIGHT_MULTIPLIER / DEFAULT_LEFT_DIVISOR,
                    bitmap.height * DEFAULT_BOTTOM_MULTIPLIER / DEFAULT_BOTTOM_DIVISOR,
                ),
                confidence = NO_TEXT_CONFIDENCE,
                allTextBlocks = emptyList(),
            )
        }
    }

    private suspend fun detectTextBlocks(
        image: InputImage,
    ): List<TextBlockInfo> = suspendCancellableCoroutine { continuation ->
        val recognizer = getRecognizer().getOrElse { error ->
            continuation.resumeWithException(error)
            return@suspendCancellableCoroutine
        }
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val blocks = visionText.textBlocks.mapNotNull { block ->
                    block.boundingBox?.let { rect ->
                        val text = block.text.trim()
                        if (text.isEmpty()) {
                            null
                        } else {
                            TextBlockInfo(
                                text = text,
                                rect = rect,
                                lineCount = max(MIN_LINE_COUNT, block.lines.size),
                            )
                        }
                    }
                }
                if (continuation.isActive) {
                    continuation.resume(blocks)
                }
            }
            .addOnFailureListener { e ->
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }
    }

    /**
     * Filter out blocks that are likely UI elements (status bar, navigation, toolbars).
     */
    private fun filterContentBlocks(
        blocks: List<TextBlockInfo>,
        imageWidth: Int,
        imageHeight: Int,
    ): List<TextBlockInfo> {
        val statusBarBottom = imageHeight * STATUS_BAR_BOTTOM_RATIO
        val navBarTop = imageHeight * NAV_BAR_TOP_RATIO
        val minBlockHeight = max(MIN_BLOCK_HEIGHT_PX, (imageHeight * MIN_BLOCK_HEIGHT_RATIO).toInt())
        val minBlockWidth = max(MIN_BLOCK_HEIGHT_PX, (imageWidth * MIN_BLOCK_WIDTH_RATIO).toInt())

        return blocks.filter { block ->
            val rect = block.rect
            val hasMeaningfulText =
                block.charCount >= MIN_MEANINGFUL_CHARS ||
                    block.hasQuestionSignal ||
                    block.isOptionLike
            val isShortUiText = block.charCount <= SHORT_UI_CHAR_COUNT && !block.hasQuestionSignal
            val isTopUi = rect.top < imageHeight * TOP_UI_RATIO && isShortUiText
            val isBottomUi = rect.bottom > imageHeight * BOTTOM_UI_RATIO && isShortUiText
            val isClock = CLOCK_REGEX.matches(block.text.trim())

            rect.bottom > statusBarBottom &&
                rect.top < navBarTop &&
                rect.height() >= minBlockHeight &&
                rect.width() >= minBlockWidth &&
                hasMeaningfulText &&
                !isTopUi &&
                !isBottomUi &&
                !isClock
        }
    }

    /**
     * Find the best contiguous text cluster by combining text density, question cues,
     * option labels, compactness, and plausible crop size.
     */
    private fun findBestQuestionCandidate(
        blocks: List<TextBlockInfo>,
        imageWidth: Int,
        imageHeight: Int,
    ): QuestionCandidate {
        if (blocks.size <= SMALL_BLOCK_COUNT) {
            return buildCandidate(blocks, blocks.sumOf { it.charCount }, imageWidth, imageHeight)
        }

        val sortedBlocks = blocks.sortedWith(
            compareBy<TextBlockInfo> { it.rect.top }.thenBy { it.rect.left },
        )
        val totalCharCount = sortedBlocks.sumOf { it.charCount }.coerceAtLeast(1)
        val bestCandidate = buildCandidate(sortedBlocks, totalCharCount, imageWidth, imageHeight)

        return sortedBlocks.indices.asSequence()
            .flatMap { start ->
                (start until sortedBlocks.size).asSequence()
                    .map { end -> sortedBlocks.subList(start, end + 1) }
                    .map { windowBlocks ->
                        TextWindow(windowBlocks, mergeBlocksWithoutPadding(windowBlocks))
                    }
                    .takeWhile { window ->
                        window.rect.height().toFloat() / imageHeight <= MAX_REGION_HEIGHT_RATIO
                    }
                    .filter { window ->
                        isPlausibleWindow(window.blocks, window.rect, imageWidth, imageHeight)
                    }
                    .map { window ->
                        buildCandidate(window.blocks, totalCharCount, imageWidth, imageHeight)
                    }
            }
            .fold(bestCandidate) { best, candidate ->
                if (candidate.score > best.score) candidate else best
            }
    }

    private fun isPlausibleWindow(
        blocks: List<TextBlockInfo>,
        rect: Rect,
        imageWidth: Int,
        imageHeight: Int,
    ): Boolean {
        val charCount = blocks.sumOf { it.charCount }
        val heightRatio = rect.height().toFloat() / imageHeight
        val widthRatio = rect.width().toFloat() / imageWidth
        val hasQuestionCue = blocks.any { it.hasQuestionSignal || it.isOptionLike }
        val gapStats = verticalGapStats(blocks)

        return charCount >= MIN_WINDOW_CHARS &&
            (heightRatio >= MIN_REGION_HEIGHT_RATIO || charCount >= DENSE_MIN_WINDOW_CHARS) &&
            (widthRatio >= MIN_WINDOW_WIDTH_RATIO || hasQuestionCue) &&
            (
                blocks.size == MIN_LINE_COUNT ||
                    gapStats.maxGap <= imageHeight * MAX_LOOSE_GAP_RATIO ||
                    (hasQuestionCue && gapStats.maxGap <= imageHeight * RELAXED_CUE_GAP_RATIO)
                )
    }

    private fun buildCandidate(
        blocks: List<TextBlockInfo>,
        totalCharCount: Int,
        imageWidth: Int,
        imageHeight: Int,
    ): QuestionCandidate {
        val tightRect = mergeBlocksWithoutPadding(blocks)
        val paddedRect = mergeBlocks(blocks, imageWidth, imageHeight)
        val charCount = blocks.sumOf { it.charCount }
        val lineCount = blocks.sumOf { it.lineCount }
        val questionCueCount = blocks.count { it.hasQuestionSignal }
        val optionCount = blocks.count { it.isOptionLike }
        val heightRatio = tightRect.height().toFloat() / imageHeight
        val widthRatio = tightRect.width().toFloat() / imageWidth
        val areaRatio = tightRect.width().toFloat() *
            tightRect.height().toFloat() /
            imageWidth /
            imageHeight
        val density = charCount.toDouble() / max(MIN_LINE_COUNT, tightRect.height())
        val gapStats = verticalGapStats(blocks)
        val compactness = SIZE_SCORE_IDEAL -
            (gapStats.averageGap / (imageHeight * COMPACTNESS_GAP_RATIO))
                .coerceIn(NO_PENALTY, SIZE_SCORE_IDEAL)
        val metrics = CandidateMetrics(
            charCount = charCount,
            lineCount = lineCount,
            blockCount = blocks.size,
            questionCueCount = questionCueCount,
            optionCount = optionCount,
            coverage = charCount.toFloat() / totalCharCount,
            density = density,
            compactness = compactness,
            heightRatio = heightRatio,
            widthRatio = widthRatio,
            areaRatio = areaRatio,
        )

        return QuestionCandidate(
            rect = paddedRect,
            score = metrics.score(),
            confidence = metrics.confidence(),
        )
    }

    /**
     * Merge multiple text blocks into a single bounding rectangle with padding.
     */
    private fun mergeBlocks(
        blocks: List<TextBlockInfo>,
        imageWidth: Int,
        imageHeight: Int,
    ): Rect {
        val rect = mergeBlocksWithoutPadding(blocks)

        val paddingH = max(rect.width() * PADDING_H_RATIO, imageWidth * MIN_PADDING_H_RATIO)
        val paddingTop = max(rect.height() * PADDING_TOP_RATIO, imageHeight * MIN_PADDING_TOP_RATIO)
        val paddingBottom = max(rect.height() * PADDING_BOTTOM_RATIO, imageHeight * MIN_PADDING_BOTTOM_RATIO)

        return Rect(
            max(0, (rect.left - paddingH).toInt()),
            max(0, (rect.top - paddingTop).toInt()),
            min(imageWidth, (rect.right + paddingH).toInt()),
            min(imageHeight, (rect.bottom + paddingBottom).toInt()),
        )
    }

    private fun mergeBlocksWithoutPadding(blocks: List<TextBlockInfo>): Rect {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = 0
        var bottom = 0

        blocks.forEach { block ->
            left = min(left, block.rect.left)
            top = min(top, block.rect.top)
            right = max(right, block.rect.right)
            bottom = max(bottom, block.rect.bottom)
        }

        return Rect(left, top, right, bottom)
    }

    private fun verticalGapStats(blocks: List<TextBlockInfo>): GapStats {
        if (blocks.size < SMALL_BLOCK_COUNT) {
            return GapStats(maxGap = 0, averageGap = NO_PENALTY)
        }
        val gaps = blocks.sortedBy { it.rect.top }
            .zipWithNext { first, second ->
                (second.rect.top - first.rect.bottom).coerceAtLeast(0)
            }
        return GapStats(
            maxGap = gaps.maxOrNull() ?: 0,
            averageGap = gaps.average().toFloat(),
        )
    }

    fun close() {
        recognizer?.close()
        recognizer = null
    }

    private fun getRecognizer(): Result<com.google.mlkit.vision.text.TextRecognizer> {
        return runCatching {
            recognizer ?: TextRecognition.getClient(
                ChineseTextRecognizerOptions.Builder().build(),
            ).also { recognizer = it }
        }
    }

    data class TextBlockInfo(
        val text: String,
        val rect: Rect,
        val lineCount: Int,
    ) {
        val charCount: Int = text.count { !it.isWhitespace() }
        val isOptionLike: Boolean = OPTION_PREFIX_REGEX.containsMatchIn(text)
        val hasQuestionSignal: Boolean =
            isOptionLike ||
                QUESTION_SYMBOL_REGEX.containsMatchIn(text) ||
                QUESTION_KEYWORDS.any { keyword -> text.contains(keyword) }
    }

    private data class CandidateMetrics(
        val charCount: Int,
        val lineCount: Int,
        val blockCount: Int,
        val questionCueCount: Int,
        val optionCount: Int,
        val coverage: Float,
        val density: Double,
        val compactness: Float,
        val heightRatio: Float,
        val widthRatio: Float,
        val areaRatio: Float,
    ) {
        fun score(): Double {
            val sizeScore = when {
                heightRatio in IDEAL_MIN_HEIGHT_RATIO..IDEAL_MAX_HEIGHT_RATIO &&
                    widthRatio >= IDEAL_MIN_WIDTH_RATIO -> SIZE_SCORE_IDEAL
                heightRatio in OK_MIN_HEIGHT_RATIO..OK_MAX_HEIGHT_RATIO &&
                    widthRatio >= OK_MIN_WIDTH_RATIO -> SIZE_SCORE_OK
                else -> SIZE_SCORE_WEAK
            }
            val areaPenalty = when {
                areaRatio > HIGH_AREA_RATIO -> AREA_PENALTY_HIGH
                areaRatio > MEDIUM_AREA_RATIO -> AREA_PENALTY_MEDIUM
                else -> NO_PENALTY
            }
            val singleBlockPenalty = if (
                blockCount == MIN_LINE_COUNT &&
                questionCueCount == NO_PENALTY.toInt()
            ) {
                SINGLE_BLOCK_PENALTY
            } else {
                NO_PENALTY.toDouble()
            }

            return min(charCount, MAX_SCORE_CHARS) * CHAR_SCORE_WEIGHT +
                min(lineCount, MAX_SCORE_LINES) * LINE_SCORE_WEIGHT +
                questionCueCount * QUESTION_CUE_SCORE_WEIGHT +
                optionCount * OPTION_SCORE_WEIGHT +
                density * DENSITY_SCORE_WEIGHT +
                compactness * COMPACTNESS_SCORE_WEIGHT +
                sizeScore * SIZE_SCORE_WEIGHT -
                areaPenalty * AREA_PENALTY_WEIGHT -
                singleBlockPenalty
        }

        fun confidence(): Float {
            var confidence = BASE_CONFIDENCE
            confidence += (charCount / CONFIDENCE_CHAR_DIVISOR).coerceAtMost(CONFIDENCE_CHAR_MAX)
            confidence += (lineCount / CONFIDENCE_LINE_DIVISOR).coerceAtMost(CONFIDENCE_LINE_MAX)
            confidence += compactness * CONFIDENCE_COMPACTNESS_WEIGHT
            if (questionCueCount > NO_PENALTY.toInt()) confidence += CONFIDENCE_QUESTION_CUE
            if (optionCount >= MIN_MEANINGFUL_CHARS) {
                confidence += CONFIDENCE_MULTI_OPTION
            } else if (optionCount == MIN_LINE_COUNT) {
                confidence += CONFIDENCE_SINGLE_OPTION
            }
            if (coverage >= CONFIDENCE_COVERAGE_RATIO) confidence += CONFIDENCE_COVERAGE
            if (
                heightRatio in CONFIDENCE_MIN_HEIGHT_RATIO..CONFIDENCE_MAX_HEIGHT_RATIO &&
                widthRatio >= CONFIDENCE_MIN_WIDTH_RATIO
            ) {
                confidence += CONFIDENCE_SHAPE
            }
            if (blockCount >= SMALL_BLOCK_COUNT) confidence += CONFIDENCE_MULTI_BLOCK
            if (areaRatio > HIGH_AREA_RATIO) confidence -= CONFIDENCE_AREA_PENALTY
            if (blockCount == MIN_LINE_COUNT && questionCueCount == NO_PENALTY.toInt()) {
                confidence -= CONFIDENCE_SINGLE_BLOCK_PENALTY
            }

            return confidence.coerceIn(NO_TEXT_CONFIDENCE, MAX_CONFIDENCE)
        }
    }

    private data class GapStats(
        val maxGap: Int,
        val averageGap: Float,
    )

    private data class TextWindow(
        val blocks: List<TextBlockInfo>,
        val rect: Rect,
    )

    private data class QuestionCandidate(
        val rect: Rect,
        val score: Double,
        val confidence: Float,
    )

    data class DetectionResult(
        val suggestedRegion: Rect,
        val confidence: Float,
        val allTextBlocks: List<TextBlockInfo>,
    )
}
