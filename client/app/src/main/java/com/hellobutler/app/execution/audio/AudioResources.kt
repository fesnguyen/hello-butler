package com.hellobutler.app.execution.audio

import com.hellobutler.app.R

object AudioResources {
    private val bundled = mapOf(
        "long_opening" to R.raw.long_opening, "short_opening" to R.raw.short_opening,
        "morning_warmup_0" to R.raw.morning_warmup_0, "morning_warmup_1" to R.raw.morning_warmup_1,
        "morning_warmup_2" to R.raw.morning_warmup_2, "morning_warmup_3" to R.raw.morning_warmup_3,
        "morning_warmup_4" to R.raw.morning_warmup_4,
        "evening_warmup_0" to R.raw.evening_warmup_0, "evening_warmup_1" to R.raw.evening_warmup_1,
        "evening_warmup_2" to R.raw.evening_warmup_2, "evening_warmup_3" to R.raw.evening_warmup_3,
        "evening_warmup_4" to R.raw.evening_warmup_4, "volume_preview" to R.raw.volume_preview,
    )
    fun resolve(name: String, select: (String) -> String): Int? =
        bundled[if (name in AudioWorkflows.groups) select(name) else name]
}
