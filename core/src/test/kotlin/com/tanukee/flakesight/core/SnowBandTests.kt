package com.tanukee.flakesight.core

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of `SnowBandTests.swift`: the map's snow scale. Titles and sentences are
 * built in the app, so their tests check the bounds and totals they read.
 * Keep the two files in step.
 */
class SnowBandTests {
    private val totals = SnowTotals(
        mapOf("dry" to SnowTotals.Entry(recent = 0.4, forecast = 0.0), "snowy" to SnowTotals.Entry(recent = 12.3, forecast = 49.5)),
        Instant.now(),
    )

    // Steps

    @ParameterizedTest
    @CsvSource(
        "0.0, NONE", "0.4, NONE", "0.5, LIGHT", "9.4, LIGHT", "9.5, MODERATE", "29.4, MODERATE",
        "29.5, HEAVY", "49.4, HEAVY", "49.5, DEEP", "50, DEEP", "180, DEEP", "-1, NONE",
    )
    fun stepFollowsTheWholeCentimetreShown(centimetres: Double, band: SnowBand) {
        assertEquals(band, SnowBand.of(centimetres))
    }

    /** A pin with no figure is grey, and every figure shown sits in a coloured step. */
    @Test
    fun stepAgreesWithTheLabel() {
        for (tenths in 0..600) {
            val centimetres = tenths / 10.0
            assertEquals(DailyForecast.centimetres(centimetres) == null, SnowBand.of(centimetres) == SnowBand.NONE, "$centimetres cm")
        }
    }

    @Test
    fun stepsAreOrdered() {
        assertEquals(SnowBand.entries.sorted(), SnowBand.entries)
        assertTrue(SnowBand.NONE < SnowBand.DEEP)
    }

    /** "0", "1-9", "10-29", "30-49", "50+" and "Under 1 cm" ... "50 cm or more" in the app. */
    @Test
    fun shortTitlesCoverEveryWholeCentimetre() {
        assertEquals(listOf(0, 1, 10, 30, 50), SnowBand.entries.map { it.lowerBound })
        assertEquals(listOf(0, 9, 29, 49, null), SnowBand.entries.map { it.upperBound })
    }

    // Totals

    @Test
    fun bandIsNilForAResortNotFetched() {
        assertNull(totals.band(SnowTotals.Kind.FORECAST, "missing"))
        assertEquals(SnowBand.NONE, totals.band(SnowTotals.Kind.RECENT, "dry"))
        assertEquals(SnowBand.MODERATE, totals.band(SnowTotals.Kind.RECENT, "snowy"))
        assertEquals(SnowBand.DEEP, totals.band(SnowTotals.Kind.FORECAST, "snowy"))
    }

    @Test
    fun periodsNameTheirDays() {
        assertEquals(7, SnowTotals.Kind.RECENT.dayCount)
        assertEquals(5, SnowTotals.Kind.FORECAST.dayCount)
    }

    /** Stored by the map: the raw values must not change. */
    @Test
    fun kindRawValuesAreStable() {
        assertEquals(listOf("recent", "forecast"), SnowTotals.Kind.entries.map { it.rawValue })
    }
}
