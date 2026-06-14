package com.easyhomework.app.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * In-memory lookup of model capabilities sourced from [models.dev](https://models.dev).
 *
 * The data is a curated, cross-provider capability database. A trimmed snapshot is
 * bundled as `assets/models_dev.json` and can be refreshed from the network by the
 * loader (`network/ModelCatalogLoader`). This object is pure: it only holds the parsed
 * index and answers context-free lookups. Every lookup returns `null` when the model is
 * absent, so callers fall back to provider metadata / name heuristics.
 *
 * Snapshot format: `{"full": {id: mask}, "suffix": {id: mask}}` where `id` is lowercased
 * and `mask` is a bitfield — `1` = vision/image input, `2` = tool/function calling,
 * `4` = reasoning/thinking. `full` is keyed by the exact model id; `suffix` is keyed by
 * the part after the last `/` for ids that carry a vendor prefix (e.g. `openai/gpt-4o`).
 */
object ModelCatalog {
    private const val BIT_VISION = 1
    private const val BIT_TOOL = 2
    private const val BIT_REASONING = 4

    @Volatile
    private var index: Index? = null

    private class Index(val full: Map<String, Int>, val suffix: Map<String, Int>)

    /** Whether a snapshot has been loaded yet. */
    val isLoaded: Boolean get() = index != null

    /** `true`/`false` if the catalog knows this model's image-input support, else `null`. */
    fun supportsVision(modelName: String): Boolean? = hasBit(modelName, BIT_VISION)

    /** `true`/`false` if the catalog knows this model's tool-calling support, else `null`. */
    fun supportsToolCalling(modelName: String): Boolean? = hasBit(modelName, BIT_TOOL)

    /** `true`/`false` if the catalog knows this model's reasoning support, else `null`. */
    fun supportsReasoning(modelName: String): Boolean? = hasBit(modelName, BIT_REASONING)

    private fun hasBit(modelName: String, bit: Int): Boolean? =
        lookupMask(modelName)?.let { (it and bit) != 0 }

    /**
     * Resolve a user-entered model name against the index. Tries the raw id, the id with
     * any `:tag` (e.g. `:free`) stripped, and the part after the last `/`, preferring exact
     * `full` matches over vendor-prefix `suffix` matches.
     */
    private fun lookupMask(modelName: String): Int? {
        val idx = index ?: return null
        val base = modelName.trim().lowercase()
        val candidates = buildSet {
            if (base.isNotEmpty()) {
                for (form in listOf(base, base.substringBefore(":"))) {
                    add(form)
                    if (form.contains("/")) add(form.substringAfterLast("/"))
                }
            }
        }
        return candidates.firstNotNullOfOrNull { idx.full[it] }
            ?: candidates.firstNotNullOfOrNull { idx.suffix[it] }
    }

    /**
     * Replace the in-memory index from a trimmed snapshot JSON string. Keeps the previous
     * index (and returns `false`) on parse failure or empty data, so a bad network refresh
     * never wipes the bundled snapshot.
     */
    fun load(trimmedJson: String): Boolean {
        val parsed = runCatching {
            val root = JsonParser.parseString(trimmedJson).asJsonObject
            Index(readMaskMap(root.getAsJsonObject("full")), readMaskMap(root.getAsJsonObject("suffix")))
        }.getOrNull()
        if (parsed == null || (parsed.full.isEmpty() && parsed.suffix.isEmpty())) return false
        index = parsed
        return true
    }

    private fun readMaskMap(obj: JsonObject?): Map<String, Int> {
        if (obj == null) return emptyMap()
        val map = HashMap<String, Int>(obj.size())
        for ((key, value) in obj.entrySet()) {
            map[key] = runCatching { value.asInt }.getOrDefault(0)
        }
        return map
    }
}
