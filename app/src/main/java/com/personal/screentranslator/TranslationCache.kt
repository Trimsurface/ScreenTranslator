package com.personal.screentranslator

import java.io.File

/**
 * LRU-кеш переводов в памяти + сохранение в файл.
 * Меню, кнопки и повторяющиеся реплики переводятся один раз — быстрее и единообразно.
 */
class TranslationCache(private val file: File, private val capacity: Int = 3000) {

    private val map = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > capacity
    }
    private var unsaved = 0

    @get:Synchronized
    val size: Int get() = map.size

    @Synchronized
    fun get(key: String): String? = map[key]

    /** @return true, если пора сохранить на диск. */
    @Synchronized
    fun put(key: String, value: String): Boolean {
        map[key] = value
        unsaved++
        return unsaved >= SAVE_EVERY
    }

    @Synchronized
    fun clear() {
        map.clear()
        unsaved = 0
        file.delete()
    }

    @Synchronized
    fun load() {
        if (!file.exists()) return
        runCatching {
            file.forEachLine(Charsets.UTF_8) { line ->
                val i = line.indexOf('\t')
                if (i > 0) map[unescape(line.substring(0, i))] = unescape(line.substring(i + 1))
            }
        }
    }

    @Synchronized
    fun save() {
        if (unsaved == 0 && file.exists()) return
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                for ((k, v) in map) {
                    w.write(escape(k)); w.write("\t"); w.write(escape(v)); w.write("\n")
                }
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
            unsaved = 0
        }
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> sb.append('\t')
                    'n' -> sb.append('\n')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    companion object {
        private const val SAVE_EVERY = 20
    }
}
