package com.easyhomework.app.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Manages text recognition using Google ML Kit.
 * Supports both Chinese and Latin text recognition.
 */
class TextRecognitionManager {

    // Chinese text recognizer (also handles Latin/English text)
    private val chineseRecognizer: TextRecognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    // Latin text recognizer as fallback
    private val latinRecognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.Builder().build())

    /**
     * Recognize text from a bitmap image.
     * Uses Chinese recognizer by default (handles both Chinese and English).
     */
    suspend fun recognizeText(bitmap: Bitmap): RecognitionResult {
        return try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val text = recognizeWithRecognizer(inputImage, chineseRecognizer)

            // If Chinese recognizer finds very little text, try Latin as backup
            if (text.text.length < 5) {
                val latinText = recognizeWithRecognizer(inputImage, latinRecognizer)
                if (latinText.text.length > text.text.length) {
                    return latinText
                }
            }

            text
        } catch (e: Exception) {
            RecognitionResult(
                text = "",
                confidence = 0f,
                error = e.message
            )
        }
    }

    private suspend fun recognizeWithRecognizer(
        image: InputImage,
        recognizer: TextRecognizer
    ): RecognitionResult = suspendCancellableCoroutine { continuation ->
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val fullText = buildString {
                    visionText.textBlocks.forEach { block ->
                        appendLine(block.text)
                    }
                }.trim()

                val cleanedText = cleanOCRText(fullText)

                continuation.resume(
                    RecognitionResult(
                        text = cleanedText,
                        confidence = 1.0f,
                        blocks = visionText.textBlocks.map { block ->
                            TextBlock(
                                text = block.text,
                                boundingBox = block.boundingBox
                            )
                        }
                    )
                )
            }
            .addOnFailureListener { e ->
                continuation.resumeWithException(e)
            }
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
        chineseRecognizer.close()
        latinRecognizer.close()
    }

    data class RecognitionResult(
        val text: String,
        val confidence: Float = 0f,
        val blocks: List<TextBlock> = emptyList(),
        val error: String? = null
    )

    data class TextBlock(
        val text: String,
        val boundingBox: android.graphics.Rect?
    )
}
