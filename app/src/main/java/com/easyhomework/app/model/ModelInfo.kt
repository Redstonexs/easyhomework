package com.easyhomework.app.model

/**
 * Represents a model with its capabilities.
 */
data class ModelInfo(
    val id: String,
    val supportsVision: Boolean = false,
    val supportsFunctionCalling: Boolean = false,
    val supportsThinking: Boolean = false
)
