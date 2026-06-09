package com.easyhomework.app.ocr

import android.content.Context
import com.google.mlkit.common.MlKit

internal object MlKitInitializer {
    @Volatile
    private var initialized = false

    fun ensureInitialized(context: Context) {
        if (initialized) return

        synchronized(this) {
            if (!initialized) {
                MlKit.initialize(context.applicationContext)
                initialized = true
            }
        }
    }
}
