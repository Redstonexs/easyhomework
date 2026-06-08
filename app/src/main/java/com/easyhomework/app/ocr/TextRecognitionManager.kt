package com.easyhomework.app.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Manages text recognition using Google ML Kit.
 * Supports both Chinese and Latin text recognition.
 */
class TextRecognitionManager {

    private var chineseRecognizer: TextRecognizer? = null
    private var latinRecognizer: TextRecognizer? = null

    private companion object {
        const val MIN_USEFUL_TEXT_LENGTH = 5
        const val TARGET_MIN_EDGE_PX = 720
        const val MAX_UPSCALED_EDGE_PX = 2400
        const val MIN_SCALE_DELTA = 1.05f
        const val CONTRAST_SCALE = 1.25f
        const val BRIGHTNESS_OFFSET = 10f
        const val TEXT_SCORE_WEIGHT = 2
    }

    /**
     * Recognize text from a bitmap image.
     * Uses Chinese recognizer by default (handles both Chinese and English).
     */
    suspend fun recognizeText(bitmap: Bitmap): RecognitionResult {
        val generatedBitmaps = mutableListOf<Bitmap>()
        var retryBitmaps: List<Bitmap>? = null
        var canRecycleGeneratedBitmaps = false

        return try {
            var bestResult: RecognitionResult? = null
            var primaryError: Throwable? = null

            getChineseRecognizer().fold(
                onSuccess = { recognizer ->
                    try {
                        bestResult = betterResult(
                            bestResult,
                            recognizeWithRecognizer(bitmap, recognizer),
                        )

                        if (!bestResult.hasEnoughText()) {
                            retryBitmaps = buildRetryBitmaps(bitmap, generatedBitmaps)
                            retryBitmaps.orEmpty().forEach { candidate ->
                                bestResult = betterResult(
                                    bestResult,
                                    recognizeWithRecognizer(candidate, recognizer),
                                )
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        primaryError = e
                    }
                },
                onFailure = { error ->
                    primaryError = error
                },
            )

            // If Chinese recognizer finds very little text, try Latin as backup.
            if (!bestResult.hasEnoughText()) {
                getLatinRecognizer().getOrNull()?.let { recognizer ->
                    val candidates = listOf(bitmap) + (
                        retryBitmaps ?: buildRetryBitmaps(bitmap, generatedBitmaps)
                            .also { retryBitmaps = it }
                        )

                    candidates.forEach { candidate ->
                        bestResult = betterResult(
                            bestResult,
                            recognizeWithRecognizer(candidate, recognizer),
                        )
                    }
                }
            }

            val best = bestResult
            canRecycleGeneratedBitmaps = true
            if (best != null && (best.text.isNotBlank() || primaryError == null)) {
                best
            } else {
                RecognitionResult(
                    text = "",
                    confidence = 0f,
                    error = primaryError?.toOcrMessage(),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            canRecycleGeneratedBitmaps = true
            RecognitionResult(
                text = "",
                confidence = 0f,
                error = e.message,
            )
        } finally {
            if (canRecycleGeneratedBitmaps) {
                generatedBitmaps.forEach { generated ->
                    if (!generated.isRecycled) {
                        generated.recycle()
                    }
                }
            }
        }
    }

    private fun getChineseRecognizer(): Result<TextRecognizer> {
        return runCatching {
            chineseRecognizer ?: TextRecognition.getClient(
                ChineseTextRecognizerOptions.Builder().build(),
            ).also { chineseRecognizer = it }
        }
    }

    private fun getLatinRecognizer(): Result<TextRecognizer> {
        return runCatching {
            latinRecognizer ?: TextRecognition.getClient(
                TextRecognizerOptions.Builder().build(),
            ).also { latinRecognizer = it }
        }
    }

    private suspend fun recognizeWithRecognizer(
        bitmap: Bitmap,
        recognizer: TextRecognizer,
    ): RecognitionResult = suspendCancellableCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val fullText = buildString {
                    visionText.textBlocks.forEach { block ->
                        appendLine(block.text)
                    }
                }.trim()

                val cleanedText = cleanOCRText(fullText)

                if (continuation.isActive) {
                    continuation.resume(
                        RecognitionResult(
                            text = cleanedText,
                            confidence = 1.0f,
                            blocks = visionText.textBlocks.map { block ->
                                TextBlock(
                                    text = block.text,
                                    boundingBox = block.boundingBox,
                                )
                            },
                        ),
                    )
                }
            }
            .addOnFailureListener { e ->
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }
    }

    private fun RecognitionResult?.hasEnoughText(): Boolean {
        return this != null &&
            text.count { !it.isWhitespace() } >= MIN_USEFUL_TEXT_LENGTH &&
            blocks.isNotEmpty()
    }

    private fun betterResult(
        current: RecognitionResult?,
        candidate: RecognitionResult,
    ): RecognitionResult {
        return if (current == null || candidate.score() > current.score()) {
            candidate
        } else {
            current
        }
    }

    private fun RecognitionResult.score(): Int {
        return text.count { !it.isWhitespace() } * TEXT_SCORE_WEIGHT + blocks.size
    }

    private fun buildRetryBitmaps(
        bitmap: Bitmap,
        generatedBitmaps: MutableList<Bitmap>,
    ): List<Bitmap> {
        val retryBitmaps = mutableListOf<Bitmap>()
        val scaledBitmap = scaleForOcrIfNeeded(bitmap)
        if (scaledBitmap !== bitmap) {
            generatedBitmaps.add(scaledBitmap)
            retryBitmaps.add(scaledBitmap)
        }

        val contrastSource = retryBitmaps.lastOrNull() ?: bitmap
        val contrastBitmap = createHighContrastBitmap(contrastSource)
        generatedBitmaps.add(contrastBitmap)
        retryBitmaps.add(contrastBitmap)

        return retryBitmaps
    }

    private fun scaleForOcrIfNeeded(bitmap: Bitmap): Bitmap {
        val minEdge = min(bitmap.width, bitmap.height)
        val maxEdge = max(bitmap.width, bitmap.height)
        if (minEdge <= 0 || minEdge >= TARGET_MIN_EDGE_PX) return bitmap

        val scale = (TARGET_MIN_EDGE_PX.toFloat() / minEdge)
            .coerceAtMost(MAX_UPSCALED_EDGE_PX.toFloat() / maxEdge)
            .coerceAtLeast(1f)
        if (scale < MIN_SCALE_DELTA) return bitmap

        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun createHighContrastBitmap(bitmap: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val colorMatrix = ColorMatrix().apply {
            setSaturation(0f)
            postConcat(
                ColorMatrix(
                    floatArrayOf(
                        CONTRAST_SCALE, 0f, 0f, 0f, BRIGHTNESS_OFFSET,
                        0f, CONTRAST_SCALE, 0f, 0f, BRIGHTNESS_OFFSET,
                        0f, 0f, CONTRAST_SCALE, 0f, BRIGHTNESS_OFFSET,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
        Canvas(output).drawBitmap(bitmap, 0f, 0f, paint)
        return output
    }

    private fun Throwable.toOcrMessage(): String {
        return message ?: javaClass.simpleName
    }

    /**
     * Clean up common OCR artifacts.
     */
    private fun cleanOCRText(text: String): String {
        return text
            // Normalize whitespace
            .replace(Regex("[ \\t]+"), " ")
            // Remove excessive blank lines
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    fun close() {
        chineseRecognizer?.close()
        latinRecognizer?.close()
        chineseRecognizer = null
        latinRecognizer = null
    }

    data class RecognitionResult(
        val text: String,
        val confidence: Float = 0f,
        val blocks: List<TextBlock> = emptyList(),
        val error: String? = null,
    )

    data class TextBlock(
        val text: String,
        val boundingBox: android.graphics.Rect?,
    )
}
