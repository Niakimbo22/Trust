package fr.niakimbo.trust

import fr.niakimbo.trust.content.BookText
import fr.niakimbo.trust.content.BookTimings
import fr.niakimbo.trust.content.BooksIndex
import fr.niakimbo.trust.playback.PlaybackHub
import fr.niakimbo.trust.playback.VerseClock
import fr.niakimbo.trust.playback.VerseRef
import fr.niakimbo.trust.update.VersionCodes
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Vérifie que le contenu du repo se lit bien et que la logique « verset » est juste. */
class ContentAndClockTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val content = File("../content")

    private fun timings(code: String) = json.decodeFromString<BookTimings>(File(content, "timings/$code.json").readText())

    @Test fun indexAndBooksParse() {
        val index = json.decodeFromString<BooksIndex>(File(content, "books.json").readText())
        assertTrue(index.books.isNotEmpty())
        index.books.forEach { b ->
            val text = json.decodeFromString<BookText>(File(content, b.text).readText())
            assertEquals(b.chapters, text.chapters.size)
            assertEquals(b.verses, text.chapters.sumOf { it.verses.size })
            b.timings?.let { path ->
                val t = json.decodeFromString<BookTimings>(File(content, path).readText())
                // un temps par verset, dans le même ordre que le texte
                text.chapters.zip(t.chapters).forEach { (tc, cc) ->
                    assertEquals("${b.code} ${tc.c}", tc.c, cc.c)
                    assertEquals("${b.code} ${tc.c}", tc.verses.size, cc.verses.size)
                }
                val all = t.chapters.flatMap { it.verses }
                assertTrue("${b.code} : temps croissants", all.zipWithNext().all { (a, c) -> c > a })
                assertEquals(b.audio.sumOf { it.duration }, t.duration, 0.5)
            }
        }
    }

    @Test fun verseAtFindsVerses() {
        val clock = VerseClock(timings("JHN"))
        val t316 = clock.verseStart(3, 16)!!
        assertEquals(VerseRef(3, 16), clock.verseAt(t316))
        assertEquals(VerseRef(3, 16), clock.verseAt(t316 + 1.0))
        assertEquals(VerseRef(3, 15), clock.verseAt(t316 - 0.5))
        assertEquals(VerseRef(1, 0), clock.verseAt(0.0))
        // annonce du chapitre 2, entre la fin du ch. 1 et le verset 2,1
        val ch2 = clock.chapterStart(2)!!
        assertEquals(VerseRef(2, 0), clock.verseAt(ch2 + 0.1))
        assertEquals(VerseRef(21, 25), clock.verseAt(clock.duration))
    }

    @Test fun nextAndPreviousCrossChapters() {
        val clock = VerseClock(timings("JHN"))
        assertEquals(VerseRef(2, 1), clock.next(VerseRef(1, 51)))
        assertEquals(VerseRef(1, 51), clock.previous(VerseRef(2, 1)))
        assertNull(clock.next(VerseRef(21, 25)))
    }

    @Test fun stepGoesBackToVerseStartFirst() {
        val clock = VerseClock(timings("JHN"))
        val v = clock.verseStart(3, 16)!!
        assertEquals(v, PlaybackHub.targetForStep(clock, v + 3.0, -1)!!, 0.001)
        assertEquals(clock.verseStart(3, 15)!!, PlaybackHub.targetForStep(clock, v + 0.5, -1)!!, 0.001)
        assertEquals(clock.verseStart(3, 17)!!, PlaybackHub.targetForStep(clock, v + 0.5, +1)!!, 0.001)
    }

    @Test fun multiFileConversion() {
        val t = timings("JHN")
        // livre fictif en deux fichiers : on coupe la durée en deux
        val half = t.duration / 2
        val split = t.copy(audio = listOf(t.audio[0].copy(duration = half), t.audio[0].copy(duration = half)))
        val clock = VerseClock(split)
        val p = clock.toFile(half + 12.5)
        assertEquals(1, p.index)
        assertEquals(12_500L, p.positionMs)
        assertEquals(half + 12.5, clock.toGlobal(1, 12_500), 0.001)
        assertEquals(0, clock.toFile(10.0).index)
        assertNotNull(clock.toFile(t.duration + 5))
    }

    @Test fun versionCodesStayConsistent() {
        assertEquals(10203, VersionCodes.fromTag("v1.2.3"))
        assertEquals(200, VersionCodes.fromTag("0.2.0"))
    }
}
