package dev.apollo.actions

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * The routines table's time words (server/apollo/routines.py), read on the phone so it can arm an alarm for the next
 * one: "HH:MM" in a zone, repeat daily | weekdays | weekends | mon,thu | once:YYYY-MM-DD. Pure, tested as it runs.
 */
object Repeat {
    private val DAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    fun valid(repeat: String): Boolean = repeat in setOf("daily", "weekdays", "weekends") || repeat.startsWith("once:") ||
        repeat.split(',').all { it.trim() in DAYS }

    private fun onDay(repeat: String, d: java.time.LocalDate): Boolean = when {
        repeat == "daily" -> true
        repeat == "weekdays" -> d.dayOfWeek < DayOfWeek.SATURDAY
        repeat == "weekends" -> d.dayOfWeek >= DayOfWeek.SATURDAY
        repeat.startsWith("once:") -> d.toString() == repeat.removePrefix("once:")
        else -> DAYS[d.dayOfWeek.value - 1] in repeat.split(',').map { it.trim().take(3) }
    }

    /** The first time after [after] (epoch ms) that "[hhmm] in [tz], [repeat]" comes round; null when it never does again. */
    fun next(hhmm: String, tz: String?, repeat: String?, after: Long): Long? {
        val t = runCatching { LocalTime.parse(hhmm.trim().take(5).padStart(5, '0')) }.getOrNull() ?: return null
        val zone = runCatching { ZoneId.of(tz?.ifBlank { null } ?: "America/New_York") }.getOrNull() ?: return null
        val rep = repeat?.trim()?.lowercase()?.ifEmpty { null } ?: "daily"
        if (!valid(rep)) return null
        val start = Instant.ofEpochMilli(after).atZone(zone).toLocalDate()
        for (i in 0..400) {
            val d = start.plusDays(i.toLong())
            if (!onDay(rep, d)) continue
            val at = d.atTime(t).atZone(zone).toInstant().toEpochMilli()
            if (at > after) return at
        }
        return null
    }

    /** One time row of the routines table, as the phone reads its copy. */
    data class Row(val id: Long, val whenTime: String?, val tz: String?, val repeat: String?, val on: Boolean)

    /** The soonest time any row that is on comes due after [after], or null. */
    fun soonest(rows: List<Row>, after: Long): Long? =
        rows.filter { it.on && !it.whenTime.isNullOrBlank() }.mapNotNull { next(it.whenTime!!, it.tz, it.repeat, after) }.minOrNull()
}
