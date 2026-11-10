package fr.niakimbo.trust

import fr.niakimbo.trust.content.AudioFile
import fr.niakimbo.trust.content.BookTimings
import fr.niakimbo.trust.content.BooksIndex
import fr.niakimbo.trust.content.ChapterTimings
import fr.niakimbo.trust.playback.PlaybackHub
import fr.niakimbo.trust.playback.PlaybackLogic
import fr.niakimbo.trust.playback.SleepTimer
import fr.niakimbo.trust.playback.VerseClock
import fr.niakimbo.trust.playback.VerseRef
import fr.niakimbo.trust.ui.screens.formatSpeed
import fr.niakimbo.trust.ui.screens.formatTime
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Fiabilité du lecteur : règles pures (réseau, minuteur, reprise) et horloge des versets sur les vrais livres. */
class PlaybackLogicTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val content = File("../content")
    private val index by lazy { json.decodeFromString<BooksIndex>(File(content, "books.json").readText()) }

    private fun timings(code: String) = json.decodeFromString<BookTimings>(File(content, "timings/$code.json").readText())

    /** Petit livre inventé : 2 fichiers de 100 s, 3 chapitres, le 3e lu avant le 2e (comme Esther). */
    private val toy = BookTimings(
        code = "TOY", version = 1,
        audio = listOf(AudioFile("a", 100.0), AudioFile("b", 100.0)),
        duration = 200.0,
        chapters = listOf(
            ChapterTimings(1, 0.0, listOf(2.0, 20.0, 40.0)),
            ChapterTimings(2, 140.0, listOf(142.0, 160.0)),
            ChapterTimings(3, 60.0, listOf(62.0, 100.0, 120.0)),
        ),
    )

    // ---------------- Réseau ----------------

    @Test fun noticeOnlyWhileWaiting() {
        assertNull(PlaybackLogic.networkNotice(waiting = false, online = false, waitingForMs = 60_000))
        assertNull(PlaybackLogic.networkNotice(waiting = true, online = true, waitingForMs = 3_000))
        // un chargement très court hors-ligne (livre téléchargé) ne fait pas clignoter le message
        assertNull(PlaybackLogic.networkNotice(waiting = true, online = false, waitingForMs = 200))
        assertEquals(PlaybackLogic.MSG_OFFLINE, PlaybackLogic.networkNotice(true, online = false, waitingForMs = 2_000))
        assertEquals(PlaybackLogic.MSG_SLOW, PlaybackLogic.networkNotice(true, online = true, waitingForMs = 12_000))
    }

    @Test fun networkErrorsAreRetriedOthersAreNot() {
        // codes de PlaybackException : 2001 connexion, 2002 délai, 2004 statut HTTP, 2005 fichier absent, 3001 fichier illisible
        assertTrue(PlaybackLogic.isRetryable(2001))
        assertTrue(PlaybackLogic.isRetryable(2002))
        assertTrue(PlaybackLogic.isRetryable(2004))
        assertFalse(PlaybackLogic.isRetryable(2005))
        assertFalse(PlaybackLogic.isRetryable(3001))
        assertFalse(PlaybackLogic.isRetryable(1000))

        assertEquals(PlaybackLogic.MSG_OFFLINE, PlaybackLogic.errorMessage(2001, "X", online = true))
        assertEquals(PlaybackLogic.MSG_OFFLINE, PlaybackLogic.errorMessage(2004, "X", online = false))
        assertEquals(PlaybackLogic.MSG_SERVER, PlaybackLogic.errorMessage(2004, "X", online = true))
        assertTrue(PlaybackLogic.errorMessage(3001, "ERROR_CODE_PARSING", online = true).contains("ERROR_CODE_PARSING"))
    }

    // ---------------- Reprise ----------------

    @Test fun resumeRewindsALittleAndStaysInTheBook() {
        assertEquals(98.0, PlaybackLogic.resumeTarget(100.0, 200.0), 0.001)
        assertEquals(0.0, PlaybackLogic.resumeTarget(1.2, 200.0), 0.001)
        assertEquals(200.0, PlaybackLogic.resumeTarget(5_000.0, 200.0), 0.001) // timings raccourcis depuis
        assertEquals(0.0, PlaybackLogic.resumeTarget(-3.0, 0.0), 0.001)
    }

    // ---------------- Minuteur ----------------

    @Test fun sleepTimerAtIsDueOnlyAfterItsEnd() {
        val t = SleepTimer.At(endAtMillis = 10_000, minutes = 15)
        assertFalse(PlaybackLogic.isDue(t, 9_999))
        assertTrue(PlaybackLogic.isDue(t, 10_000))
        assertFalse(PlaybackLogic.isDue(null, 99_999))
        assertFalse(PlaybackLogic.isDue(SleepTimer.EndOfChapter(3), 99_999))
    }

    @Test fun endOfChapterStopsWhenTheChapterChanges() {
        val t = SleepTimer.EndOfChapter(3)
        assertFalse(PlaybackLogic.chapterEnded(t, VerseRef(3, 12)))
        assertFalse(PlaybackLogic.chapterEnded(t, VerseRef(3, 0)))
        assertTrue(PlaybackLogic.chapterEnded(t, VerseRef(4, 0)))
        assertFalse(PlaybackLogic.chapterEnded(null, VerseRef(4, 0)))
        assertFalse(PlaybackLogic.chapterEnded(SleepTimer.At(0, 15), VerseRef(4, 0)))
    }

    @Test fun jumpingElsewhereMovesEndOfChapterTimer() {
        // l'auditeur saute au chapitre 7 : le minuteur vise la fin du 7, il ne coupe pas tout de suite
        val moved = PlaybackLogic.retarget(SleepTimer.EndOfChapter(3), 7)
        assertEquals(SleepTimer.EndOfChapter(7), moved)
        assertFalse(PlaybackLogic.chapterEnded(moved, VerseRef(7, 1)))
        val same = SleepTimer.EndOfChapter(3)
        assertTrue(same === PlaybackLogic.retarget(same, 3))
        val at = SleepTimer.At(5_000, 15)
        assertTrue(at === PlaybackLogic.retarget(at, 7))
        assertNull(PlaybackLogic.retarget(null, 7))
    }

    // ---------------- Chapitres ----------------

    @Test fun chapterJumpsFollowAudioOrder() {
        val clock = VerseClock(toy)
        assertEquals(listOf(1, 3, 2), clock.chapters)
        // suivant : 1 → 3 → 2 → rien
        assertEquals(60.0, PlaybackLogic.chapterTarget(clock, 30.0, +1)!!, 0.001)
        assertEquals(140.0, PlaybackLogic.chapterTarget(clock, 70.0, +1)!!, 0.001)
        assertNull(PlaybackLogic.chapterTarget(clock, 150.0, +1))
        // précédent : d'abord le début du chapitre en cours, puis le chapitre d'avant
        assertEquals(60.0, PlaybackLogic.chapterTarget(clock, 90.0, -1)!!, 0.001)
        assertEquals(0.0, PlaybackLogic.chapterTarget(clock, 61.0, -1)!!, 0.001)
        assertEquals(0.0, PlaybackLogic.chapterTarget(clock, 30.0, -1)!!, 0.001)
        assertNull(PlaybackLogic.chapterTarget(clock, 1.0, -1))
    }

    @Test fun chapterEndFollowsAudioOrder() {
        val clock = VerseClock(toy)
        assertEquals(60.0, clock.chapterEnd(1), 0.001)
        assertEquals(140.0, clock.chapterEnd(3), 0.001)
        assertEquals(200.0, clock.chapterEnd(2), 0.001)
        assertEquals(200.0, clock.chapterEnd(99), 0.001) // chapitre sans audio
        assertFalse(clock.hasAudio(99))
        assertNull(clock.verseStart(99, 1))
        assertNull(clock.chapterStart(99))
    }

    @Test fun stepsCrossFilesAndChapters() {
        val clock = VerseClock(toy)
        // 3,2 commence pile au début du 2e fichier
        assertEquals(100.0, PlaybackHub.targetForStep(clock, 70.0, +1)!!, 0.001)
        val p = clock.toFile(100.0)
        assertEquals(1, p.index)
        assertEquals(0L, p.positionMs)
        // dernier verset du livre : pas de suivant
        assertNull(PlaybackHub.targetForStep(clock, 170.0, +1))
        // tout début du livre : « précédent » reste au début
        assertEquals(0.0, PlaybackHub.targetForStep(clock, 0.5, -1)!!, 0.001)
        // fin de fichier : on bascule au début du suivant plutôt que de viser la dernière milliseconde
        assertEquals(1, clock.toFile(99.99).index)
        assertEquals(0, clock.toFile(99.0).index)
        // au-delà de la fin : borné à la fin du dernier fichier
        assertEquals(1, clock.toFile(500.0).index)
        assertEquals(100_000L, clock.toFile(500.0).positionMs)
    }

    // ---------------- Vrais livres ----------------

    @Test fun audioFilesMatchBetweenIndexAndTimings() {
        index.books.forEach { b ->
            val t = timings(b.code)
            assertEquals("${b.code} : mêmes fichiers audio dans books.json et dans les timings", b.audio, t.audio)
            assertTrue("${b.code} : durées positives", t.audio.all { it.duration > 0 })
        }
    }

    @Test fun everyVerseOfEveryBookIsReachable() {
        index.books.forEach { b ->
            val t = timings(b.code)
            val clock = VerseClock(t)
            var previous: VerseRef? = null
            var count = 0
            // on parcourt le livre de verset en verset, comme le bouton « verset suivant »
            var ref: VerseRef? = clock.verseAt(0.0).let { if (it.verse == 0) clock.next(it) else it }
            while (ref != null) {
                val start = clock.verseStart(ref.chapter, ref.verse)
                assertNotNull("${b.code} $ref", start)
                assertEquals("${b.code} $ref : verset retrouvé à son début", ref, clock.verseAt(start!!))
                // aller-retour temps du livre ↔ fichier audio (à la milliseconde)
                val p = clock.toFile(start)
                assertTrue("${b.code} $ref : fichier", p.index in t.audio.indices)
                assertTrue("${b.code} $ref : position dans le fichier", p.positionMs <= t.audio[p.index].duration * 1000 + 1)
                assertEquals("${b.code} $ref : aller-retour", start, clock.toGlobal(p.index, p.positionMs), 0.002)
                previous?.let { assertEquals("${b.code} $ref : précédent", it, clock.previous(ref!!).let { r -> if (r?.verse == 0) clock.previous(r) else r }) }
                previous = ref
                count++
                ref = clock.next(ref)
            }
            assertEquals("${b.code} : tous les versets parcourus", t.chapters.sumOf { it.verses.size }, count)
            assertEquals("${b.code} : chapitres", t.chapters.size, clock.chapters.size)
            // les passages se suivent sans trou ni recouvrement, jusqu'à la fin du livre
            val s = clock.sections
            assertTrue("${b.code} : passages", s.all { it.end > it.start })
            assertTrue("${b.code} : passages enchaînés", s.zipWithNext().all { (a, n) -> a.end == n.start })
            assertEquals("${b.code} : dernier passage", t.duration, s.last().end, 0.001)
            // partout sauf dans Esther, un passage = un chapitre entier
            if (b.code != "EST") {
                assertEquals("${b.code} : un passage par chapitre", clock.chapters, s.map { it.chapter })
                s.forEach {
                    assertEquals("${b.code} ${it.chapter} : début", clock.chapterStart(it.chapter)!!, it.start, 0.001)
                    assertEquals("${b.code} ${it.chapter} : fin", clock.chapterEnd(it.chapter), it.end, 0.001)
                    assertEquals("${b.code} ${it.chapter} : écoute", it.start, clock.listenStart(it.chapter)!!, 0.001)
                }
            }
        }
    }

    @Test fun bookRevisionsMatchContentFiles() {
        // l'app ne retélécharge un livre que si son empreinte change : elle doit suivre le texte et les timings
        index.books.forEach { b ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            listOfNotNull(b.text, b.timings).forEach { digest.update(File(content, it).readBytes()) }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }.take(12)
            assertEquals("${b.code} : lancer tools/update_books.py après un changement de contenu", hex, b.rev)
        }
    }

    @Test fun psalmsSpanFiveFiles() {
        val t = timings("PSA")
        assertEquals(5, t.audio.size)
        val clock = VerseClock(t)
        val used = clock.chapters.map { clock.toFile(clock.chapterStart(it)!!).index }
        assertEquals("les psaumes avancent de fichier en fichier", used.sorted(), used)
        assertEquals(setOf(0, 1, 2, 3, 4), used.toSet())
        assertEquals(VerseRef(150, clock.verseCount(150)), clock.verseAt(clock.duration))
    }

    @Test fun estherIsReadInAudioOrder() {
        val clock = VerseClock(timings("EST"))
        // le livre s'ouvre sur 11,2-12 (songe de Mardochée), avant toute annonce de chapitre
        assertEquals(VerseRef(11, 2), clock.verseAt(0.0))
        assertEquals(listOf(11, 12, 1, 2, 3, 13, 3, 4), clock.sections.take(8).map { it.chapter })
        // après 3,13 l'audio enchaîne sur le passage grec 13,1-7, puis revient à 3,14
        assertEquals(VerseRef(13, 1), clock.next(VerseRef(3, 13)))
        assertEquals(VerseRef(3, 14), clock.next(VerseRef(13, 7)))
        // la barre de progression couvre le passage en cours, pas un chapitre éclaté dans tout le livre
        val in3 = clock.verseStart(3, 5)!!
        assertEquals(clock.chapterStart(3)!!, clock.sectionAt(in3).start, 0.001)
        assertEquals(clock.chapterStart(13)!!, clock.sectionAt(in3).end, 0.001)
        val in11 = clock.verseStart(11, 5)!!
        assertEquals(clock.verseStart(11, 2)!!, clock.sectionAt(in11).start, 0.001)
        assertEquals(clock.chapterStart(12)!!, clock.sectionAt(in11).end, 0.001)
        // « chapitre suivant » avance de passage en passage, même au tout début du livre
        assertEquals(clock.chapterStart(12)!!, PlaybackLogic.chapterTarget(clock, in11, +1)!!, 0.001)
        assertEquals(clock.chapterStart(13)!!, PlaybackLogic.chapterTarget(clock, in3, +1)!!, 0.001)
        assertNull(PlaybackLogic.chapterTarget(clock, clock.duration - 1, +1))
        // écouter le chapitre 11 commence à 11,2 (lu au début), pas à l'annonce placée en fin de livre
        assertEquals(clock.verseStart(11, 2)!!, clock.listenStart(11)!!, 0.001)
        assertEquals(clock.chapterStart(13)!!, clock.listenStart(13)!!, 0.001)
    }

    // ---------------- Affichage ----------------

    @Test fun timesAndSpeedsAreFormatted() {
        assertEquals("0:00", formatTime(-4.0))
        assertEquals("0:59", formatTime(59.9))
        assertEquals("12:05", formatTime(725.0))
        assertEquals("1:02:03", formatTime(3723.0))
        assertEquals("1×", formatSpeed(1f))
        assertEquals("1,25×", formatSpeed(1.25f))
        assertEquals("0,9×", formatSpeed(0.9f))
        assertEquals("2×", formatSpeed(2f))
    }
}
