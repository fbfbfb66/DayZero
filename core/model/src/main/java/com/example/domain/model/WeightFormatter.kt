package com.example.domain.model

import java.util.Locale

/**
 * Formats a weight value for UI display.
 *
 * Rules:
 * - At most one decimal place.
 * - `87.8` displays as `"87.8"`.
 * - `88.0` displays as `"88"`.
 * - Trailing zeros after the decimal place are removed.
 * - Uses US Locale so the decimal separator is always `.` regardless of device locale.
 */
fun formatWeightKg(weightKg: Double): String {
    val rounded = kotlin.math.round(weightKg * 10.0) / 10.0
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        String.format(Locale.US, "%.1f", rounded)
    }
}

/**
 * Normalizes a parsed weight input to at most one decimal place.
 *
 * This should be called as soon as a weight value enters the domain layer so
 * that floating-point noise (e.g. `87.800003`) does not propagate to storage
 * or downstream UI.
 */
fun normalizeWeightKg(weightKg: Double): Double {
    return kotlin.math.round(weightKg * 10.0) / 10.0
}
