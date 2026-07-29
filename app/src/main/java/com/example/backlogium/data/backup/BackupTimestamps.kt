package com.example.backlogium.data.backup

import java.time.Instant

/**
 * Epoch-millis <-> ISO-8601 string conversion at the backup format's boundary, so every
 * timestamp in a [BackupFile] is human/LLM-legible rather than a bare epoch value (design.md
 * decision 1).
 */
internal fun Long.toIso8601(): String = Instant.ofEpochMilli(this).toString()

internal fun String.iso8601ToEpochMilli(): Long = Instant.parse(this).toEpochMilli()
