package com.personal.screentranslator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * Онлайн-перевод через бесплатный веб-адрес Google Переводчика — без ключа и регистрации.
 * Адрес неофициальный: при очень частых запросах Google может ненадолго отвечать ошибкой,
 * тогда пайплайн сам переключается на офлайн (см. TranslationPipeline).
 */
class GoogleTranslator : Translator {

    /** Не больше нескольких запросов одновременно — чтобы Google не счёл это злоупотреблением. */
    private val permits = Semaphore(MAX_PARALLEL)

    override suspend fun translate(text: String, source: SourceLang): String {
        if (text.isBlank()) return text
        return GoogleApi.chunks(text, MAX_CHUNK_CHARS)
            .map { request(it, source) }
            .joinToString(" ")
            .trim()
    }

    override fun reset() = Unit

    private suspend fun request(text: String, source: SourceLang): String = permits.withPermit {
        withContext(Dispatchers.IO) {
            val conn = URI(GoogleApi.url(text, source)).toURL().openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.setRequestProperty("User-Agent", USER_AGENT)
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) throw IOException("Google ответил кодом $code")
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                GoogleApi.parse(body) ?: throw IOException("Непонятный ответ Google")
            } finally {
                conn.disconnect()
            }
        }
    }

    companion object {
        private const val MAX_PARALLEL = 4
        private const val MAX_CHUNK_CHARS = 500
        private const val CONNECT_TIMEOUT_MS = 3000
        private const val READ_TIMEOUT_MS = 5000
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    }
}

/** Формирование запроса и разбор ответа — без Android, чтобы проверять тестами. */
object GoogleApi {

    fun langCode(lang: SourceLang): String = when (lang) {
        SourceLang.EN -> "en"
        SourceLang.JA -> "ja"
        SourceLang.ZH -> "zh-CN"
        SourceLang.KO -> "ko"
    }

    fun url(text: String, source: SourceLang): String =
        "https://translate.googleapis.com/translate_a/single?client=gtx" +
            "&sl=" + langCode(source) +
            "&tl=" + LanguagePacks.TARGET +
            "&dt=t&q=" + URLEncoder.encode(text, "UTF-8")

    /**
     * Ответ — вложенные массивы: [[["перевод","оригинал",…],["перевод 2",…]], null, "en", …].
     * Склеиваем первые строки каждого кусочка. null — если формат не тот.
     */
    fun parse(body: String): String? {
        val root = runCatching { MiniJson.parse(body) }.getOrNull() as? List<*> ?: return null
        val parts = root.firstOrNull() ?: return ""
        if (parts !is List<*>) return null
        val sb = StringBuilder()
        for (part in parts) {
            val piece = (part as? List<*>)?.firstOrNull() as? String ?: continue
            sb.append(piece)
        }
        return sb.toString()
    }

    /** Длинный текст режем на куски по границам предложений/слов — у адреса есть предел длины. */
    fun chunks(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val result = mutableListOf<String>()
        var rest = text
        while (rest.length > maxChars) {
            val window = rest.substring(0, maxChars)
            var cut = window.indexOfLast { it in SENTENCE_ENDS }
            if (cut < maxChars / 3) cut = window.indexOfLast { it.isWhitespace() }
            if (cut < maxChars / 3) cut = maxChars - 1
            result += rest.substring(0, cut + 1).trim()
            rest = rest.substring(cut + 1)
        }
        if (rest.isNotBlank()) result += rest.trim()
        return result.filter { it.isNotEmpty() }
    }

    private const val SENTENCE_ENDS = ".!?…。！？"
}

/** Крошечный разборщик JSON: массивы → List, объекты → Map, строки, числа, true/false/null. */
object MiniJson {

    fun parse(s: String): Any? {
        val p = Parser(s)
        val v = p.value()
        p.skipWs()
        if (!p.atEnd()) throw IllegalArgumentException("Лишние символы в JSON на позиции ${p.pos}")
        return v
    }

    private class Parser(val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            skipWs()
            if (atEnd()) throw IllegalArgumentException("Неожиданный конец JSON")
            return when (val c = s[pos]) {
                '[' -> array()
                '{' -> obj()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else throw IllegalArgumentException("Символ '$c' на позиции $pos")
            }
        }

        private fun array(): List<Any?> {
            pos++ // [
            val list = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') {
                pos++
                return list
            }
            while (true) {
                skipWs()
                // Google иногда пишет пустые места в массиве: [1,,2] — считаем их null.
                if (peek() == ',' || peek() == ']') list += null else list += value()
                skipWs()
                when (next()) {
                    ',' -> continue
                    ']' -> return list
                    else -> throw IllegalArgumentException("Ожидалась , или ] на позиции ${pos - 1}")
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            pos++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') {
                pos++
                return map
            }
            while (true) {
                skipWs()
                val key = string()
                skipWs()
                if (next() != ':') throw IllegalArgumentException("Ожидалось : на позиции ${pos - 1}")
                map[key] = value()
                skipWs()
                when (next()) {
                    ',' -> continue
                    '}' -> return map
                    else -> throw IllegalArgumentException("Ожидалась , или } на позиции ${pos - 1}")
                }
            }
        }

        private fun string(): String {
            if (next() != '"') throw IllegalArgumentException("Ожидалась строка на позиции ${pos - 1}")
            val sb = StringBuilder()
            while (true) {
                val c = next()
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = next()) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > s.length) throw IllegalArgumentException("Обрезанный \\u")
                            sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                            pos += 4
                        }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): Double {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
            return s.substring(start, pos).toDouble()
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) throw IllegalArgumentException("Ожидалось $word на позиции $pos")
            pos += word.length
            return v
        }

        private fun peek(): Char? = if (pos < s.length) s[pos] else null

        private fun next(): Char {
            if (pos >= s.length) throw IllegalArgumentException("Неожиданный конец JSON")
            return s[pos++]
        }
    }
}
