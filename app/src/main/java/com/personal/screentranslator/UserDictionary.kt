package com.personal.screentranslator

/**
 * Словарь пользователя. Формат — по правилу на строку:
 *
 *     Lust Academy              не переводить (оставить как есть)
 *     HP = Здоровье             свой перевод термина
 *     > Здравоохранение = Здоровье   исправить слово в готовом переводе
 *     # комментарий
 *
 * Термины перед переводом заменяются на числовые метки (модель оставляет числа
 * как есть), после перевода метки меняются обратно.
 */
class UserDictionary {

    data class Rules(
        /** термин → чем заменить в переводе (для «не переводить» — сам термин) */
        val terms: List<Pair<String, String>> = emptyList(),
        /** исправления готового перевода: было → стало */
        val fixes: List<Pair<String, String>> = emptyList(),
    )

    class Prepared(val text: String, val tokens: Map<String, String>)

    @Volatile
    var rules: Rules = Rules()
        private set

    fun load(text: String) {
        rules = parse(text)
    }

    fun prepare(text: String): Prepared {
        val terms = rules.terms
        if (terms.isEmpty()) return Prepared(text, emptyMap())

        var result = text
        val tokens = LinkedHashMap<String, String>()
        var next = TOKEN_BASE
        for ((term, replacement) in terms.sortedByDescending { it.first.length }) {
            val re = termRegex(term)
            if (!re.containsMatchIn(result)) continue
            var token = next.toString()
            while (result.contains(token) || text.contains(token)) token = (++next).toString()
            next++
            result = re.replace(result, token)
            tokens[token] = replacement
        }
        return Prepared(result, tokens)
    }

    fun finish(translated: String, prepared: Prepared): String {
        var result = translated
        for ((token, replacement) in prepared.tokens) {
            // Модель может вставить пробел внутрь числа: «7 301».
            val re = Regex(token.toCharArray().joinToString("[\\s\\u00A0\\u202F]?") { Regex.escape(it.toString()) })
            result = re.replace(result) { replacement }
        }
        for ((from, to) in rules.fixes) {
            // Регистр первой буквы берём из найденного слова: начало фразы или середина.
            result = termRegex(from).replace(result) { m ->
                if (m.value.firstOrNull()?.isUpperCase() == true) to.replaceFirstChar { it.uppercaseChar() }
                else to.replaceFirstChar { it.lowercaseChar() }
            }
        }
        return result.trim()
    }

    companion object {
        private const val TOKEN_BASE = 7301

        val DEFAULT_TEXT = """
            |# Каждая строка — одно правило. Строки с # — комментарии.
            |#
            |# Не переводить (имена, названия) — просто слово:
            |#   Lust Academy
            |#
            |# Свой перевод термина:  оригинал = перевод
            |#   HP = Здоровье
            |#
            |# Исправить слово в готовом переводе:  > было = стало
            |#   > Здравоохранение = Здоровье
            |
        """.trimMargin()

        fun parse(text: String): Rules {
            val terms = mutableListOf<Pair<String, String>>()
            val fixes = mutableListOf<Pair<String, String>>()
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                if (line.startsWith(">")) {
                    val (from, to) = splitPair(line.removePrefix(">")) ?: continue
                    fixes += from to to
                    continue
                }
                val pair = splitPair(line)
                if (pair != null) terms += pair else terms += line to line
            }
            return Rules(terms.distinctBy { it.first.lowercase() }, fixes)
        }

        private fun splitPair(s: String): Pair<String, String>? {
            val i = s.indexOf('=')
            if (i < 0) return null
            val a = s.substring(0, i).trim()
            val b = s.substring(i + 1).trim()
            return if (a.isEmpty()) null else a to b
        }

        /** Совпадение целым словом, без учёта регистра. */
        private fun termRegex(term: String) =
            Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(term) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    }
}
