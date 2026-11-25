package com.tanukee.flakesight.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The explanation of each weather model stays in step with the models the app
 * compares. Port of `WeatherModelInfoTests.swift`; the provider, origin and
 * details tests are the app's strings (`wordsReadInFrench` too).
 */
class WeatherModelInfoTests {
    @Test
    fun oneExplanationPerComparedModelInTheSameOrder() {
        assertEquals(SnowfallRange.MODELS, WeatherModelInfo.ALL.map { it.id })
        assertEquals(SnowfallRange.MODEL_NAMES, WeatherModelInfo.ALL.map { it.name })
    }
}
