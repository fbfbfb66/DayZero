package com.goings.dayzero.domain.model.ai.assistant

/**
 * JSON-compatible, forward-compatible context carried by intermediate assistant cards.
 * It must contain only a compact recognition summary and stable media-id references.
 * Binary image data, URLs, and filesystem paths are forbidden.
 */
typealias AssistantContinuationContext = Map<String, Any?>

object AssistantContinuationContextPolicy {
    private const val MAX_DEPTH = 6
    private const val MAX_MAP_ENTRIES = 48
    private const val MAX_LIST_ITEMS = 48
    private const val MAX_STRING_LENGTH = 512
    private val safeMediaId = Regex("^[A-Za-z0-9._-]{1,160}$")
    private val forbiddenKey = Regex("(?i).*(base64|data_?url|image_?url|file_?path|absolute_?path|binary|bytes).*")
    private val absoluteWindowsPath = Regex("^[A-Za-z]:[\\\\/].*")

    fun sanitize(context: AssistantContinuationContext?): AssistantContinuationContext? {
        if (context == null) return null
        val sanitized = sanitizeMap(context, depth = 0)
        return sanitized.takeIf { it.isNotEmpty() }
    }

    private fun sanitizeMap(value: Map<*, *>, depth: Int): Map<String, Any?> {
        require(depth <= MAX_DEPTH) { "continuation context is too deeply nested" }
        require(value.size <= MAX_MAP_ENTRIES) { "continuation context has too many fields" }
        return buildMap {
            value.forEach { (rawKey, rawValue) ->
                val key = rawKey as? String
                    ?: throw IllegalArgumentException("continuation context keys must be strings")
                require(key.isNotBlank() && key.length <= 80) { "invalid continuation context key" }
                require(!forbiddenKey.matches(key)) { "forbidden continuation context field" }
                put(key, sanitizeValue(key, rawValue, depth + 1))
            }
        }
    }

    private fun sanitizeValue(key: String, value: Any?, depth: Int): Any? {
        require(depth <= MAX_DEPTH) { "continuation context is too deeply nested" }
        return when (value) {
            null, is Boolean -> value
            is Number -> {
                val number = value.toDouble()
                require(number.isFinite()) { "continuation context contains a non-finite number" }
                value
            }
            is String -> {
                require(value.length <= MAX_STRING_LENGTH) { "continuation context string is too long" }
                require(!value.startsWith("data:", ignoreCase = true)) { "data URLs are forbidden" }
                require(!value.startsWith("http://", ignoreCase = true) && !value.startsWith("https://", ignoreCase = true)) {
                    "remote URLs are forbidden"
                }
                require(!value.startsWith("file:", ignoreCase = true)) { "file URLs are forbidden" }
                require(!absoluteWindowsPath.matches(value) && !value.startsWith("/")) {
                    "filesystem paths are forbidden"
                }
                if (key == "mediaIds") {
                    require(safeMediaId.matches(value)) { "invalid media id reference" }
                }
                value
            }
            is Map<*, *> -> sanitizeMap(value, depth)
            is List<*> -> {
                require(value.size <= MAX_LIST_ITEMS) { "continuation context list is too large" }
                value.map { item ->
                    if (key == "mediaIds" && item is String) {
                        require(safeMediaId.matches(item)) { "invalid media id reference" }
                    }
                    sanitizeValue(key, item, depth + 1)
                }
            }
            else -> throw IllegalArgumentException("unsupported continuation context value")
        }
    }
}
