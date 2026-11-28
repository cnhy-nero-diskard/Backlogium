package com.example.backlogium.domain

import java.util.Currency
import java.util.Locale

internal fun normalizedPriceCurrency(value: String?): String? {
    val code = value?.trim()?.uppercase(Locale.ROOT) ?: return null
    if (code.length != 3 || code.any { it !in 'A'..'Z' }) return null
    return try {
        Currency.getInstance(code).currencyCode
    } catch (_: IllegalArgumentException) {
        null
    }
}
