package com.playeverywhere.noiselog.speech

import java.util.Locale

/** Languages accepted by the multilingual Whisper model used for targeted ASR. */
object RecognitionLanguage {
    const val PREF_KEY = "recognition_language"
    const val AUTO = ""

    data class Option(val code: String, val label: String)

    // Keep this list aligned with Whisper's multilingual tokenizer language map.
    private val whisperCodes = listOf(
        "af", "am", "ar", "as", "az", "ba", "be", "bg", "bn", "bo", "br", "bs",
        "ca", "cs", "cy", "da", "de", "el", "en", "es", "et", "eu", "fa", "fi",
        "fo", "fr", "gl", "gu", "ha", "haw", "he", "hi", "hr", "ht", "hu", "hy",
        "id", "is", "it", "ja", "jw", "ka", "kk", "km", "kn", "ko", "la", "lb",
        "ln", "lo", "lt", "lv", "mg", "mi", "mk", "ml", "mn", "mr", "ms", "mt",
        "my", "ne", "nl", "nn", "no", "oc", "pa", "pl", "ps", "pt", "ro", "ru",
        "sa", "sd", "si", "sk", "sl", "sn", "so", "sq", "sr", "su", "sv", "sw",
        "ta", "te", "tg", "th", "tk", "tl", "tr", "tt", "uk", "ur", "uz", "vi",
        "yi", "yo", "zh"
    )

    fun options(displayLocale: Locale = Locale.getDefault()): List<Option> {
        val targeted = whisperCodes.map { code ->
            val name = Locale.forLanguageTag(code).getDisplayLanguage(displayLocale)
                .takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
                ?: fallbackName(code)
            Option(code, "${name.replaceFirstChar { it.titlecase(displayLocale) }} · $code")
        }.sortedBy { it.label.lowercase(displayLocale) }
        return listOf(Option(AUTO, "Автоматически · 1600+ языков")) + targeted
    }

    fun isSupported(code: String): Boolean = code == AUTO || code in whisperCodes

    private fun fallbackName(code: String): String = when (code) {
        "zh" -> "Китайский"
        else -> code.uppercase(Locale.ROOT)
    }
}
