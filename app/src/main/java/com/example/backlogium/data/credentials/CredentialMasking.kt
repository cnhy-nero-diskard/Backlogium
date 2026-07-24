package com.example.backlogium.data.credentials

/**
 * Mask a Steam Web API key for display: reveal only the last [visible] characters, replacing the
 * rest with bullets. Short/blank keys are fully masked. Used wherever the key is shown so the raw
 * value never reaches the UI (and, by construction, never a log line).
 */
fun maskApiKey(apiKey: String, visible: Int = 4): String {
    val key = apiKey.trim()
    if (key.isEmpty()) return ""
    if (key.length <= visible) return "•".repeat(key.length)
    return "•".repeat(key.length - visible) + key.takeLast(visible)
}
