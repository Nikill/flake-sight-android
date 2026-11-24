package com.tanukee.flakesight.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Port of `AfternoonWindowTests.swift`. Days are `LocalDate`s here, so the file
 * name round trip is `LocalDate`'s own; the two wording tests ("Today",
 * "Yesterday, 15:30") are the app's strings. Keep the two files in step.
 */
class AfternoonWindowTests {
    companion object {
        val paris: ZoneId = ZoneId.of("Europe/Paris")

        /** 2026-10-29 16:00 in Paris (CET, the Thursday after the DST change). */
        val thursday4pm: Instant = Instant.ofEpochSecond(1_793_286_000)
        val thursday: LocalDate = LocalDate.of(2026, 10, 29)

        fun paris(day: Int, hour: Int, minute: Int = 0): Instant =
            LocalDate.of(2026, 10, day).atTime(hour, minute).atZone(paris).toInstant()
    }

    // Days

    @Test
    fun dayFollowsTheResortTimeZone() {
        assertEquals(thursday, AfternoonWindow.day(thursday4pm, paris))
        // 23:59 Paris is already the next day in Tokyo, still the same day in Paris.
        val lateEvening = Instant.ofEpochSecond(1_793_314_740)
        assertEquals(thursday, AfternoonWindow.day(lateEvening, paris))
        assertEquals(LocalDate.of(2026, 10, 30), AfternoonWindow.day(lateEvening.plusSeconds(120), paris))
    }

    @Test
    fun dayRoundTripsThroughItsFileName() {
        assertEquals("2026-10-29", thursday.toString())
        assertEquals(thursday, LocalDate.parse("2026-10-29"))
        assertTrue(LocalDate.of(2026, 9, 30) < thursday)
    }

    @Test
    fun targetIsSixteenHundredLocalAcrossTheDSTChange() {
        // Summer time ended on Sunday 25 October: 16:00 is 14:00 UTC before, 15:00 UTC after.
        assertEquals(Instant.ofEpochSecond(1_792_850_400), AfternoonWindow.target(LocalDate.of(2026, 10, 24), paris))
        assertEquals(Instant.ofEpochSecond(1_792_940_400), AfternoonWindow.target(LocalDate.of(2026, 10, 25), paris))
        assertEquals(thursday4pm, AfternoonWindow.target(thursday, paris))
    }

    // Window

    @Test
    fun acceptsNinetyMinutesEitherSide() {
        assertFalse(AfternoonWindow.accepts(paris(29, 14, 29), paris))
        assertTrue(AfternoonWindow.accepts(paris(29, 14, 30), paris))
        assertTrue(AfternoonWindow.accepts(thursday4pm, paris))
        assertTrue(AfternoonWindow.accepts(paris(29, 17, 30), paris))
        assertFalse(AfternoonWindow.accepts(paris(29, 17, 31), paris))
        assertFalse(AfternoonWindow.accepts(paris(29, 9), paris))
    }

    @Test
    fun rejectsAnUnknownCaptureTime() {
        assertFalse(AfternoonWindow.accepts(null, paris))
    }

    @Test
    fun closerToSixteenHundredWins() {
        assertTrue(AfternoonWindow.isBetter(paris(29, 16, 10), paris(29, 15, 30), paris))
        assertFalse(AfternoonWindow.isBetter(paris(29, 16, 40), paris(29, 15, 30), paris))
        assertFalse(AfternoonWindow.isBetter(thursday4pm, thursday4pm, paris), "A tie keeps the image already there")
    }

    // Recent days

    @Test
    fun todayCountsOnceItsAfternoonImageCanExist() {
        val friday = LocalDate.of(2026, 10, 30)
        val morning = AfternoonWindow.recentDays(paris(30, 8), paris)
        assertEquals(7, morning.size)
        assertEquals(thursday, morning.first())
        assertEquals(LocalDate.of(2026, 10, 23), morning.last())

        assertEquals(thursday, AfternoonWindow.recentDays(paris(30, 16, 5), paris).first())
        val evening = AfternoonWindow.recentDays(paris(30, 16, 10), paris)
        assertEquals(friday, evening.first())
        assertEquals(LocalDate.of(2026, 10, 24), evening.last())
    }

    @Test
    fun recentDaysCrossTheDSTChangeWithoutGaps() {
        assertEquals(listOf(28, 27, 26, 25, 24, 23, 22), AfternoonWindow.recentDays(paris(29, 8), paris).map { it.dayOfMonth })
    }
}
