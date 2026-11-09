package fr.niakimbo.trust.playback

import fr.niakimbo.trust.content.BookTimings

/** Position dans un livre : chapitre + verset (verset 0 = annonce du chapitre). */
data class VerseRef(val chapter: Int, val verse: Int)

/** Position dans les fichiers audio : index du fichier + position en ms. */
data class FilePosition(val index: Int, val positionMs: Long)

/**
 * Conversions entre le temps global du livre (fichiers audio mis bout à bout, en secondes)
 * et les versets / les fichiers. Logique pure, sans Android : facile à tester.
 */
class VerseClock(private val timings: BookTimings) {
    private val offsets: DoubleArray = DoubleArray(timings.audio.size).also { arr ->
        var acc = 0.0
        timings.audio.forEachIndexed { i, a -> arr[i] = acc; acc += a.duration }
    }

    /** Tous les débuts de verset, à plat et triés : (temps, chapitre, verset). */
    private val starts: List<Triple<Double, Int, Int>> = timings.chapters.flatMap { ch ->
        ch.verses.mapIndexed { i, t -> Triple(t, ch.c, i + 1) }
    }

    val duration: Double get() = timings.duration
    val chapters: List<Int> get() = timings.chapters.map { it.c }

    fun toGlobal(index: Int, positionMs: Long): Double =
        offsets.getOrElse(index) { 0.0 } + positionMs / 1000.0

    fun toFile(globalSeconds: Double): FilePosition {
        val g = globalSeconds.coerceIn(0.0, duration)
        var i = offsets.indexOfLast { it <= g }.coerceAtLeast(0)
        // pile en fin de fichier : on bascule au début du suivant
        if (i < offsets.size - 1 && g >= offsets[i] + timings.audio[i].duration - 0.05) i++
        return FilePosition(i, ((g - offsets[i]) * 1000).toLong().coerceAtLeast(0))
    }

    /** Verset en cours à ce temps (verset 0 = annonce du chapitre avant le verset 1). */
    fun verseAt(globalSeconds: Double): VerseRef {
        if (starts.isEmpty()) return VerseRef(1, 1)
        // recherche dichotomique du dernier début <= t
        var lo = 0
        var hi = starts.size - 1
        if (globalSeconds < starts[0].first) return VerseRef(starts[0].second, 0)
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid].first <= globalSeconds + 0.02) lo = mid else hi = mid - 1
        }
        val (_, c, v) = starts[lo]
        // entre la fin d'un chapitre et le verset 1 du suivant : annonce du chapitre suivant
        val next = starts.getOrNull(lo + 1)
        if (next != null && next.third == 1) {
            val chStart = timings.chapters.first { it.c == next.second }.start
            if (globalSeconds >= chStart) return VerseRef(next.second, 0)
        }
        return VerseRef(c, v)
    }

    fun verseStart(chapter: Int, verse: Int): Double? {
        val ch = timings.chapters.firstOrNull { it.c == chapter } ?: return null
        if (verse <= 0) return ch.start
        return ch.verses.getOrNull(verse - 1)
    }

    fun chapterStart(chapter: Int): Double? = timings.chapters.firstOrNull { it.c == chapter }?.start

    fun chapterEnd(chapter: Int): Double {
        val idx = timings.chapters.indexOfFirst { it.c == chapter }
        return timings.chapters.getOrNull(idx + 1)?.start ?: duration
    }

    fun verseCount(chapter: Int): Int = timings.chapters.firstOrNull { it.c == chapter }?.verses?.size ?: 0

    /** Verset suivant (passe au chapitre suivant si besoin), ou null en fin de livre. */
    fun next(ref: VerseRef): VerseRef? {
        if (ref.verse < verseCount(ref.chapter)) return VerseRef(ref.chapter, ref.verse + 1)
        val i = chapters.indexOf(ref.chapter)
        return chapters.getOrNull(i + 1)?.let { VerseRef(it, 1) }
    }

    fun previous(ref: VerseRef): VerseRef? {
        if (ref.verse > 1) return VerseRef(ref.chapter, ref.verse - 1)
        val i = chapters.indexOf(ref.chapter)
        val prev = chapters.getOrNull(i - 1) ?: return if (ref.verse == 0) null else VerseRef(ref.chapter, 0)
        return VerseRef(prev, verseCount(prev))
    }
}
