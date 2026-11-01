package com.dynokore.gst.ingestion.domain.pull

sealed interface SequenceVerdict {
    data object NotChecked : SequenceVerdict

    data object Continuous : SequenceVerdict

    data class Gap(
        val code: String,
        val missing: List<LongRange>,
    ) : SequenceVerdict
}

object WatermarkSequenceCheck {
    const val ING_008 = "ING-008"
    private const val NEXT = 1L

    fun check(
        gapless: Boolean,
        lastChecked: Long?,
        observed: Collection<Long>,
    ): SequenceVerdict {
        if (!gapless) return SequenceVerdict.NotChecked
        val numbers =
            observed
                .filter { lastChecked == null || it > lastChecked }
                .distinct()
                .sorted()
        val missing = mutableListOf<LongRange>()
        var expected = lastChecked?.plus(NEXT) ?: numbers.firstOrNull() ?: NEXT
        for (number in numbers) {
            if (number > expected) missing.add(expected..(number - NEXT))
            expected = number + NEXT
        }
        return if (missing.isEmpty()) SequenceVerdict.Continuous else SequenceVerdict.Gap(ING_008, missing)
    }
}
