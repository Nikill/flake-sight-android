package com.tanukee.flakesight.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Port of `AppIconChoiceTests.swift`. The previews, names and their French are
 * app resources (`everyChoiceHasItsOwnPreviewAndName`,
 * `theNamesAreTranslatedIntoFrench`). Keep the two files in step.
 */
class AppIconChoiceTests {
    @Test
    fun theDefaultIsThePrimaryIcon() {
        assertEquals(AppIconChoice.MOUNTAIN, AppIconChoice.DEFAULT)
        assertNull(AppIconChoice.MOUNTAIN.alternateIconName)
        assertEquals(AppIconChoice.MOUNTAIN, AppIconChoice.fromAlternateIconName(null))
    }

    @Test
    fun eachAlternateNameLeadsBackToItsChoice() {
        for (choice in AppIconChoice.entries) {
            assertEquals(choice, AppIconChoice.fromAlternateIconName(choice.alternateIconName))
        }
        assertEquals("LauncherGoggles", AppIconChoice.GOGGLES.alternateIconName)
        assertEquals("LauncherClassic", AppIconChoice.CLASSIC.alternateIconName)
    }

    @Test
    fun anUnknownNameFallsBackToTheDefault() {
        assertEquals(AppIconChoice.MOUNTAIN, AppIconChoice.fromAlternateIconName("LauncherRemoved"))
    }
}
