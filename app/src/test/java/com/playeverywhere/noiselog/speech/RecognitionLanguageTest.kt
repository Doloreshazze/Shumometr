package com.playeverywhere.noiselog.speech

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionLanguageTest {
    @Test
    fun autoIsFirstAndTargetLanguagesAreUnique() {
        val options = RecognitionLanguage.options(Locale.forLanguageTag("ru"))
        assertEquals(RecognitionLanguage.AUTO, options.first().code)
        assertEquals(options.size, options.map { it.code }.toSet().size)
        assertTrue(options.any { it.code == "ru" })
        assertTrue(options.any { it.code == "en" })
        assertTrue(options.size >= 100)
    }

    @Test
    fun unknownLanguageIsRejected() {
        assertTrue(RecognitionLanguage.isSupported(""))
        assertTrue(RecognitionLanguage.isSupported("uk"))
        assertTrue(!RecognitionLanguage.isSupported("not-a-language"))
    }
}
