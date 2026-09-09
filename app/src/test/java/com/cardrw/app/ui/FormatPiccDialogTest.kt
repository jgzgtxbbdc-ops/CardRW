package com.cardrw.app.ui

import com.cardrw.app.ui.screens.card.formatConfirmToken
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatPiccDialogTest {
    @Test
    fun lastFourHexDigits() {
        assertEquals("E580", formatConfirmToken("04 A3 B2 C1 D4 E5 80"))
        assertEquals("E580", formatConfirmToken("04A3B2C1D4E580"))
    }

    @Test
    fun shortOrEmptyFallsBackToFormat() {
        assertEquals("FORMAT", formatConfirmToken("AB"))
        assertEquals("FORMAT", formatConfirmToken("Random ID"))
    }
}
