package com.easyhomework.app.network

import android.content.Context
import com.easyhomework.app.model.ModelCatalog
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * IO layer for [ModelCatalog]: loads the bundled snapshot (or a newer cached copy) and
 * best-effort refreshes it from models.dev. All network work is gated by a TTL and never
 * throws — on any failure the previously loaded snapshot (at worst the bundled asset)
 * stays in effect.
 */
object ModelCatalogLoader {
    private const val ASSET_NAME = "models_dev.json"
    private const val CACHE_NAME = "models_dev.json"
    private const val API_URL = "https://models.dev/api.json"
    private const val REFRESH_TTL_DAYS = 7L
    private const val CONNECT_TIMEOUT_SEC = 15L
    private const val READ_TIMEOUT_SEC = 30L
    private val REFRESH_TTL_MS = TimeUnit.DAYS.toMillis(REFRESH_TTL_DAYS)

    private val gson = Gson()
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var loadedOnce = false

    /**
     * Load the cached snapshot if present, otherwise the bundled asset. Cheap and
     * idempotent — safe to call from app startup and again before resolving capabilities.
     */
    fun loadLocal(context: Context): Boolean {
        if (loadedOnce && ModelCatalog.isLoaded) return true
        val cached = cacheFile(context).takeIf { it.exists() }
            ?.let { runCatching { it.readText() }.getOrNull() }
        val ok = (cached != null && ModelCatalog.load(cached)) || loadBundled(context)
        if (ok) loadedOnce = true
        return ok
    }

    private fun loadBundled(context: Context): Boolean {
        val json = runCatching {
            context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return false
        return ModelCatalog.load(json)
    }

    /**
     * Ensure something is loaded, then download + trim + cache a fresh snapshot if the
     * cache is missing or older than [REFRESH_TTL_MS]. Best-effort; swallows all errors.
     */
    suspend fun refreshIfStale(context: Context): Unit = withContext(Dispatchers.IO) {
        runCatching {
            loadLocal(context)
            val cache = cacheFile(context)
            val stale = !cache.exists() ||
                System.currentTimeMillis() - cache.lastModified() > REFRESH_TTL_MS
            if (!stale) return@runCatching
            val request = Request.Builder().url(API_URL).get().build()
            client.newCall(request).execute().use { response ->
                val body = response.takeIf { it.isSuccessful }?.body?.string() ?: return@runCatching
                val trimmed = trimApiJson(body)
                cache.writeText(trimmed)
                ModelCatalog.load(trimmed)
            }
        }
        Unit
    }

    private fun cacheFile(context: Context) = File(context.filesDir, CACHE_NAME)

    /**
     * Reduce the full models.dev `api.json` to the trimmed `{full,suffix}` mask snapshot.
     * Each model id votes its capabilities across every provider that lists it; the bit is
     * set only on a strict majority (ties resolve to unset, the safe direction for vision).
     * Mirrors the offline generator used to produce the bundled asset.
     */
    private fun trimApiJson(apiJson: String): String {
        val root = JsonParser.parseString(apiJson).asJsonObject
        val full = HashMap<String, Votes>()
        val suffix = HashMap<String, Votes>()
        for ((_, providerEl) in root.entrySet()) {
            val models = providerEl.asJsonObject.getAsJsonObject("models") ?: continue
            for ((rawId, modelEl) in models.entrySet()) {
                val model = modelEl.asJsonObject
                val vision = modalitiesHaveImage(model)
                val tool = booleanField(model, "tool_call")
                val reasoning = booleanField(model, "reasoning")
                val id = rawId.lowercase()
                full.getOrPut(id) { Votes() }.add(vision, tool, reasoning)
                if (id.contains("/")) {
                    suffix.getOrPut(id.substringAfterLast("/")) { Votes() }.add(vision, tool, reasoning)
                }
            }
        }
        val out = JsonObject()
        out.add("full", toMaskObject(full))
        out.add("suffix", toMaskObject(suffix))
        return gson.toJson(out)
    }

    private fun booleanField(model: JsonObject, field: String): Boolean =
        model.get(field)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

    private fun modalitiesHaveImage(model: JsonObject): Boolean {
        val input = model.getAsJsonObject("modalities")?.getAsJsonArray("input") ?: return false
        return input.any { it.isJsonPrimitive && it.asString.equals("image", ignoreCase = true) }
    }

    private fun toMaskObject(votes: Map<String, Votes>): JsonObject {
        val obj = JsonObject()
        for ((key, value) in votes) obj.addProperty(key, value.mask())
        return obj
    }

    /** Per-model capability vote tallies across providers; a strict majority sets each bit. */
    private class Votes {
        private var visionYes = 0
        private var visionNo = 0
        private var toolYes = 0
        private var toolNo = 0
        private var reasoningYes = 0
        private var reasoningNo = 0

        fun add(vision: Boolean, tool: Boolean, reasoning: Boolean) {
            if (vision) visionYes++ else visionNo++
            if (tool) toolYes++ else toolNo++
            if (reasoning) reasoningYes++ else reasoningNo++
        }

        fun mask(): Int {
            var mask = 0
            if (visionYes > visionNo) mask = mask or BIT_VISION
            if (toolYes > toolNo) mask = mask or BIT_TOOL
            if (reasoningYes > reasoningNo) mask = mask or BIT_REASONING
            return mask
        }
    }

    private const val BIT_VISION = 1
    private const val BIT_TOOL = 2
    private const val BIT_REASONING = 4
}
