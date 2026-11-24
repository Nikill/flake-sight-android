package com.tanukee.flakesight.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

/**
 * Port of `ResortSeasonTests.swift`. The summaries ("Opening planned on
 * 12 December") are the app's strings, so these check the phase and the day
 * each summary reads. Keep the two files in step.
 */
class ResortSeasonTests {
    private val paris = ZoneId.of("Europe/Paris")
    private val source = "https://www.lessaisies.com/hiver"
    private val season = ResortSeason(LocalDate.of(2026, 12, 12), LocalDate.of(2027, 4, 18), source)

    /** `hour:minute` on the given day, Paris time. */
    private fun moment(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0) =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, paris).toInstant()

    @Test
    fun beforeTheOpeningDayTheOpeningIsPlanned() {
        assertEquals(ResortSeason.Phase.Upcoming(season.opening), season.phase(moment(2026, 12, 11, 23, 59), paris))
    }

    @Test
    fun fromTheOpeningDayToTheClosingDayTheSeasonRuns() {
        for (date in listOf(moment(2026, 12, 12, 0, 0), moment(2027, 2, 1), moment(2027, 4, 18, 23, 59))) {
            assertEquals(ResortSeason.Phase.Running(season.closing), season.phase(date, paris))
        }
    }

    @Test
    fun afterTheClosingDayTheSeasonHasEnded() {
        assertEquals(ResortSeason.Phase.Ended(season.closing!!), season.phase(moment(2027, 4, 19, 0, 0), paris))
    }

    /** The app says nothing for a running season without a closing day. */
    @Test
    fun aRunningSeasonWithoutClosingDaySaysNothing() {
        val open = ResortSeason(season.opening, sourceURL = source)
        assertEquals(ResortSeason.Phase.Running(null), open.phase(moment(2027, 1, 10), paris))
        assertEquals(ResortSeason.Phase.Upcoming(season.opening), open.phase(moment(2026, 12, 1), paris))
    }

    @Test
    fun theDayIsTheResortsDay() {
        // 23:30 UTC on 11 December is already 12 December in Paris.
        val date = ZonedDateTime.of(2026, 12, 11, 23, 30, 0, 0, ZoneId.of("UTC")).toInstant()
        assertEquals(ResortSeason.Phase.Running(season.closing), season.phase(date, paris))
        assertEquals(ResortSeason.Phase.Upcoming(season.opening), season.phase(date, ZoneId.of("UTC")))
    }

    @Test
    fun decodesAndEncodesDaysAsText() {
        val json = """{"opening": "2026-12-12", "closing": "2027-04-18", "sourceURL": "https://www.lessaisies.com/hiver"}"""
        val decoded = Json.decodeFromString<ResortSeason>(json)
        assertEquals(season, decoded)
        val encoded = Json.parseToJsonElement(Json.encodeToString(decoded)).jsonObject
        assertEquals("2026-12-12", encoded["opening"]?.jsonPrimitive?.content)
        assertEquals("2027-04-18", encoded["closing"]?.jsonPrimitive?.content)
    }

    @Test
    fun closingIsOptionalButADayMustBeADay() {
        assertNull(Json.decodeFromString<ResortSeason>("""{"opening": "2026-12-12", "sourceURL": "https://example.com"}""").closing)
        assertFails { Json.decodeFromString<ResortSeason>("""{"opening": "12/12/2026", "sourceURL": "https://example.com"}""") }
    }
}
