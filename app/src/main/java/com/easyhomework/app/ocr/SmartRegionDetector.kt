package com.easyhomework.app.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

/**
 * Detects the most likely "question" region in a screenshot using ML Kit text detection.
 * Analyzes text block positions, density, and layout to suggest a crop region.
 */
class SmartRegionDetector {

    private val recognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )

    /**
     * Analyze a screenshot bitmap and return the suggested crop region.
     */
    suspend fun detectQuestionRegion(bitmap: Bitmap): DetectionResult {
        return try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val blocks = detectTextBlocks(inputImage)

            if (blocks.isEmpty()) {
                // No text found, return center region as default
                val defaultRect = Rect(
                    bitmap.width / 8,
                    bitmap.height / 6,
                    bitmap.width * 7 / 8,
                    bitmap.height * 5 / 6
                )
                return DetectionResult(
                    suggestedRegion = defaultRect,
                    confidence = 0.3f,
                    allTextBlocks = emptyList()
                )
            }

            // Filter out navigation bars, status bars, etc.
            val contentBlocks = filterContentBlocks(blocks, bitmap.width, bitmap.height)

            if (contentBlocks.isEmpty()) {
                return DetectionResult(
                    suggestedRegion = mergeAllBlocks(blocks, bitmap.width, bitmap.height),
                    confidence = 0.4f,
                    allTextBlocks = blocks
                )
            }

            // Find the densest text cluster (likely the question area)
            val questionRegion = findDensestCluster(contentBlocks, bitmap.width, bitmap.height)

            DetectionResult(
                suggestedRegion = questionRegion,
                confidence = 0.75f,
                allTextBlocks = blocks
            )
        } catch (e: Exception) {
            // Fallback to center region
            DetectionResult(
                suggestedRegion = Rect(
                    bitmap.width / 8,
                    bitmap.height / 6,
                    bitmap.width * 7 / 8,
                    bitmap.height * 5 / 6
                ),
                confidence = 0.2f,
                allTextBlocks = emptyList()
            )
        }
    }

    private suspend fun detectTextBlocks(
        image: InputImage
    ): List<TextBlockInfo> = suspendCancellableCoroutine { continuation ->
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val blocks = visionText.textBlocks.mapNotNull { block ->
                    block.boundingBox?.let { rect ->
                        TextBlockInfo(
                            text = block.text,
                            rect = rect,
                            lineCount = block.lines.size
                        )
                    }
                }
                continuation.resume(blocks)
            }
            .addOnFailureListener { e ->
                continuation.resumeWithException(e)
            }
    }

    /**
     * Filter out blocks that are likely UI elements (status bar, navigation, toolbars).
     */
    @Suppress("UNUSED_PARAMETER")
    private fun filterContentBlocks(
        blocks: List<TextBlockInfo>,
        imageWidth: Int,
        imageHeight: Int
    ): List<TextBlockInfo> {
        val statusBarHeight = imageHeight * 0.05  // Top 5% is likely status bar
        val navBarHeight = imageHeight * 0.08      // Bottom 8% is likely nav bar
        val minBlockHeight = imageHeight * 0.01    // Too small blocks are UI elements

        return blocks.filter { block ->
            val rect = block.rect
            // Not in status bar area
            rect.top > statusBarHeight &&
            // Not in navigation bar area
            rect.bottom < (imageHeight - navBarHeight) &&
            // Not too small (likely UI labels)
            (rect.height()) > minBlockHeight &&
            // Has meaningful text content
            block.text.length > 2
        }
    }

    /**
     * Find the densest cluster of text blocks, which is likely the question content.
     */
    private fun findDensestCluster(
        blocks: List<TextBlockInfo>,
        imageWidth: Int,
        imageHeight: Int
    ): Rect {
        if (blocks.size <= 2) {
            return mergeAllBlocks(blocks, imageWidth, imageHeight)
        }

        // Sort blocks by vertical position
        val sortedBlocks = blocks.sortedBy { it.rect.top }

        // Use sliding window to find the densest group
        var bestScore = 0.0
        var bestStart = 0
        var bestEnd = blocks.size - 1
        val windowSize = max(2, blocks.size * 2 / 3) // Use 2/3 of blocks as window

        for (i in 0..sortedBlocks.size - windowSize) {
            val windowBlocks = sortedBlocks.subList(i, i + windowSize)
            val totalTextLength = windowBlocks.sumOf { it.text.length }
            val verticalSpan = windowBlocks.last().rect.bottom - windowBlocks.first().rect.top
            val density = if (verticalSpan > 0) totalTextLength.toDouble() / verticalSpan else 0.0

            // Favor regions with more text and more lines
            val lineCount = windowBlocks.sumOf { it.lineCount }
            val score = density * lineCount

            if (score > bestScore) {
                bestScore = score
                bestStart = i
                bestEnd = i + windowSize - 1
            }
        }

        val selectedBlocks = sortedBlocks.subList(bestStart, bestEnd + 1)
        return mergeBlocks(selectedBlocks, imageWidth, imageHeight)
    }

    /**
     * Merge multiple text blocks into a single bounding rectangle with padding.
     */
    private fun mergeBlocks(
        blocks: List<TextBlockInfo>,
        imageWidth: Int,
        imageHeight: Int
    ): Rect {
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

        // Add padding
        val paddingH = (right - left) * 0.05f
        val paddingV = (bottom - top) * 0.08f

        return Rect(
            max(0, (left - paddingH).toInt()),
            max(0, (top - paddingV).toInt()),
            min(imageWidth, (right + paddingH).toInt()),
            min(imageHeight, (bottom + paddingV).toInt())
        )
    }

    private fun mergeAllBlocks(
        blocks: List<TextBlockInfo>,
        imageWidth: Int,
        imageHeight: Int
    ): Rect {
        if (blocks.isEmpty()) {
            return Rect(
                imageWidth / 8, imageHeight / 6,
                imageWidth * 7 / 8, imageHeight * 5 / 6
            )
        }
        return mergeBlocks(blocks, imageWidth, imageHeight)
    }

    fun close() {
        recognizer.close()
    }

    data class TextBlockInfo(
        val text: String,
        val rect: Rect,
        val lineCount: Int
    )

    data class DetectionResult(
        val suggestedRegion: Rect,
        val confidence: Float,
        val allTextBlocks: List<TextBlockInfo>
    )
}
