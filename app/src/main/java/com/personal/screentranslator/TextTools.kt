package com.personal.screentranslator

/** Фильтр мусора: цифры, проценты, таймеры, одиночные символы — не переводим. */
object TextFilter {

    fun isJunk(text: String): Boolean {
        val t = text.trim()
        val letters = t.count { it.isLetter() }
        if (letters < 2) return true
        // «100/100 HP», «x3», «12:30 AM» — букв мало, и нет ни одного нормального слова.
        val nonSpace = t.count { !it.isWhitespace() }
        val hasWord = Regex("\\p{L}{3,}").containsMatchIn(t) || t.any { isCjk(it) }
        return letters.toFloat() / nonSpace < 0.3f && !hasWord
    }

    private fun isCjk(c: Char): Boolean {
        val block = Character.UnicodeBlock.of(c)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.HANGUL_SYLLABLES
    }
}

/** Текст ЗАГЛАВНЫМИ модель переводит плохо — приводим к обычному регистру. */
object TextNormalizer {

    fun fixCaps(text: String): String {
        val letters = text.filter { it.isLetter() && (it.isUpperCase() || it.isLowerCase()) }
        if (letters.length < 4) return text
        val upper = letters.count { it.isUpperCase() }
        if (upper.toFloat() / letters.length < 0.8f) return text

        val lower = text.lowercase()
        val sb = StringBuilder(lower.length)
        var capitalizeNext = true
        for (c in lower) {
            if (capitalizeNext && c.isLetter()) {
                sb.append(c.uppercaseChar())
                capitalizeNext = false
            } else {
                sb.append(c)
            }
            if (c == '.' || c == '!' || c == '?' || c == '…') capitalizeNext = true
        }
        // Английское «I» всегда с большой.
        return sb.toString().replace(Regex("(?<![\\p{L}\\p{N}'’])i(?![\\p{L}\\p{N}])"), "I")
    }
}
