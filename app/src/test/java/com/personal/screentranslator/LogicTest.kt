package com.personal.screentranslator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LogicTest {

    private fun line(text: String, top: Int, left: Int = 100, right: Int = 900, h: Int = 40) =
        OcrLine(text, Box(left, top, right, top + h))

    @Test
    fun joinsLinesAndRemovesHyphen() {
        val block = OcrBlock(
            listOf(
                line("I can't believe you actually came to the aca-", 1000),
                line("demy tonight. Everyone was", 1045),
                line("talking about you.", 1090),
            )
        )
        val seg = TextMerger.merge(listOf(block), cjk = false).single()
        assertEquals("I can't believe you actually came to the academy tonight. Everyone was talking about you.", seg.text)
        assertEquals(3, seg.lineCount)
    }

    @Test
    fun keepsHyphenBeforeCapital() {
        assertEquals("Spider- Man", TextMerger.joinLines(listOf("Spider-", "Man"), cjk = false))
    }

    @Test
    fun mergesSplitParagraphButNotSpeakerName() {
        val name = OcrBlock(listOf(line("Emma", 940, left = 100, right = 220)))
        val part1 = OcrBlock(listOf(line("Hey, are you new here? I don't", 1000), line("think I've seen you", 1045)))
        val part2 = OcrBlock(listOf(line("around before.", 1090, right = 450)))
        val segs = TextMerger.merge(listOf(part2, name, part1), cjk = false)
        assertEquals(2, segs.size)
        assertEquals("Emma", segs[0].text)
        assertEquals("Hey, are you new here? I don't think I've seen you around before.", segs[1].text)
    }

    @Test
    fun doesNotMergeDistantBlocks() {
        val a = OcrBlock(listOf(line("Start", 200)))
        val b = OcrBlock(listOf(line("Load game", 600)))
        assertEquals(2, TextMerger.merge(listOf(a, b), cjk = false).size)
    }

    @Test
    fun cjkJoinsWithoutSpaces() {
        assertEquals("今日はいい天気ですね", TextMerger.joinLines(listOf("今日はいい", "天気ですね"), cjk = true))
    }

    @Test
    fun filtersJunk() {
        assertTrue(TextFilter.isJunk("100/100"))
        assertTrue(TextFilter.isJunk("75%"))
        assertTrue(TextFilter.isJunk("x3"))
        assertTrue(TextFilter.isJunk("12:30 AM"))
        assertTrue(TextFilter.isJunk("♥"))
        assertFalse(TextFilter.isJunk("HP"))
        assertFalse(TextFilter.isJunk("Hello there"))
        assertFalse(TextFilter.isJunk("Day 3"))
        assertFalse(TextFilter.isJunk("こんにちは"))
    }

    @Test
    fun fixesCaps() {
        assertEquals("What are you doing? I don't know.", TextNormalizer.fixCaps("WHAT ARE YOU DOING? I DON'T KNOW."))
        assertEquals("New game", TextNormalizer.fixCaps("NEW GAME"))
        assertEquals("Normal Text stays", TextNormalizer.fixCaps("Normal Text stays"))
        assertEquals("OK", TextNormalizer.fixCaps("OK"))
    }

    @Test
    fun dictionaryKeepsGlossaryAndFixes() {
        val dict = UserDictionary()
        dict.load(
            """
            # comment
            Lust Academy
            HP = Здоровье
            > Здравоохранение = Здоровье
            """.trimIndent()
        )
        val p = dict.prepare("Welcome to Lust Academy! Your HP is low.")
        assertFalse(p.text.contains("Lust Academy"))
        assertFalse(p.text.contains("HP"))
        assertEquals(2, p.tokens.size)

        // Имитируем перевод: метки сохранены, одна — с пробелом внутри.
        val (t1, t2) = p.tokens.keys.toList()
        val spaced = t2[0] + " " + t2.substring(1)
        val translated = "Добро пожаловать в $t1! Ваше $spaced на исходе."
        assertEquals("Добро пожаловать в Lust Academy! Ваше Здоровье на исходе.", dict.finish(translated, p))

        val p2 = dict.prepare("Health matters")
        assertEquals("Здоровье важно", dict.finish("Здравоохранение важно", p2))
        assertEquals("очень здоровье", dict.finish("очень здравоохранение", p2))
    }

    @Test
    fun dictionaryMatchesWholeWordsIgnoringCase() {
        val dict = UserDictionary()
        dict.load("Rose")
        val p = dict.prepare("ROSE and roses, rose.")
        assertEquals(2, Regex("\\d{4}").findAll(p.text).count())
        assertTrue(p.text.contains("roses"))
    }

    @Test
    fun dictionaryTokensAvoidNumbersInText() {
        val dict = UserDictionary()
        dict.load("Max")
        val p = dict.prepare("Max scored 7301 points")
        val token = p.tokens.keys.single()
        assertTrue(token != "7301")
        assertEquals("Max набрал 7301 очков", dict.finish("$token набрал 7301 очков", p))
    }

    @Test
    fun cacheRoundTripAndLru() {
        val dir = java.nio.file.Files.createTempDirectory("st").toFile()
        try {
            val f = File(dir, "cache.tsv")
            val c = TranslationCache(f, capacity = 3)
            c.put("en|a\tb", "x\ny")
            c.put("en|c\\d", "z")
            c.save()
            val c2 = TranslationCache(f, capacity = 3)
            c2.load()
            assertEquals("x\ny", c2.get("en|a\tb"))
            assertEquals("z", c2.get("en|c\\d"))

            c2.put("k3", "3"); c2.get("en|a\tb"); c2.put("k4", "4")
            assertEquals(3, c2.size)
            assertEquals(null, c2.get("en|c\\d")) // самый старый вытеснен
            assertEquals("x\ny", c2.get("en|a\tb"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun frameDiff() {
        val a = IntArray(FrameDiff.THUMB_W * FrameDiff.THUMB_H) { 40 }
        val b = a.copyOf().also { it[5] = 45; it[6] = 50 }
        assertFalse(FrameDiff.changed(a, b))
        val c = a.copyOf().also { it[5] = 200; it[700] = 180 }
        assertTrue(FrameDiff.changed(a, c))
        assertTrue(FrameDiff.changed(null, a))
        assertEquals(255, FrameDiff.luma(0xFFFFFFFF.toInt()))
    }

    @Test
    fun parsesGoogleResponse() {
        val body = """[[["Привет. ","Hello. ",null,null,10],["Как \"дела\"?\nХорошо 7301.","How are you?",null,null,3,null,null,[[]],[[["x"]]]]],null,"en",null,null,null,1,[],[["en"],null,[1],["en"]]]"""
        assertEquals("Привет. Как \"дела\"?\nХорошо 7301.", GoogleApi.parse(body))
        assertEquals("", GoogleApi.parse("""[null,null,"en"]"""))
        assertEquals("aé", GoogleApi.parse("""[[["aé",null]],,"en"]"""))
        assertEquals(null, GoogleApi.parse("<html>Error 429</html>"))
        assertEquals(null, GoogleApi.parse("""{"error":1}"""))
    }

    @Test
    fun miniJson() {
        assertEquals(listOf(1.0, null, "x", true, mapOf("k" to listOf(-2.5e1))), MiniJson.parse("""[1,null,"x",true,{"k":[-2.5e1]}]"""))
        assertEquals(listOf(null, 2.0), MiniJson.parse("[,2]"))
        assertEquals(emptyList<Any?>(), MiniJson.parse(" [ ] "))
    }

    @Test
    fun googleUrlAndChunks() {
        val url = GoogleApi.url("Hi & bye?", SourceLang.ZH)
        assertTrue(url.contains("sl=zh-CN&tl=ru"))
        assertTrue(url.endsWith("q=Hi+%26+bye%3F"))

        assertEquals(listOf("short"), GoogleApi.chunks("short", 500))
        val text = "First sentence here. Second one is a bit longer! Third? And the final words go here"
        val parts = GoogleApi.chunks(text, 30)
        assertTrue(parts.all { it.length <= 30 })
        assertEquals(text.replace(" ", ""), parts.joinToString("").replace(" ", ""))
        assertEquals("First sentence here.", parts[0])
        val cjk = "あ".repeat(70)
        assertEquals(listOf(30, 30, 10), GoogleApi.chunks(cjk, 30).map { it.length })
    }

    @Test
    fun normRect() {
        val r = NormRect.of(Box(0, 800, 2400, 1080), 2400, 1080)
        assertEquals(r, NormRect.decode(r.encode()))
        assertEquals(Box(0, 800, 2400, 1080), r.toBox(2400, 1080))
        assertEquals(null, NormRect.decode("1,2,3"))
        assertEquals(null, NormRect.decode(null))
    }
}
