package com.orangeflower.pet

/**
 * Kokoro v1.1-zh voices offered in the picker. In voices.bin, ids 3-57 are Chinese female voices and
 * 58-102 Chinese male voices (0-2 are English). These were picked from a sweep of all 103 that measured
 * pitch and checked with speech recognition that each one reads Chinese clearly; they are ordered from
 * higher to lower pitch so the row runs from bright to deep.
 */
object KokoroVoices {
    const val DEFAULT = 3

    private val female = listOf(11, 40, 3, 14, 52, 47, 32, 6, 8)
    private val male = listOf(77, 95, 69, 62, 58, 101)

    val list: List<Speaker> =
        female.mapIndexed { i, sid -> Speaker(sid, "女声 ${i + 1}") } +
            male.mapIndexed { i, sid -> Speaker(sid, "男声 ${i + 1}") }
}
