package com.example.domain.model

import java.util.Locale

fun formatWeightKg(weightKg: Double): String {
    return String.format(Locale.US, "%.1f", weightKg)
}
