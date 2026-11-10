package fr.niakimbo.trust.playback

import fr.niakimbo.trust.content.BookTimings

/** Position dans un livre : chapitre + verset (verset 0 = annonce du chapitre). */
data class VerseRef(val chapter: Int, val verse: Int)

/**
 * Passage lu d'une traite dans un même chapitre, en secondes du livre.
 * C'est le chapitre entier, sauf dans Esther où les passages grecs coupent les chapitres en morceaux.
 */
data class Section(val chapter: Int, val start: Double, val end: Double)

/** Position dans les fichiers audio : index du fichier + position en ms. */
data class FilePosition(val index: Int, val positionMs: Long)

/**
 * Conversions entre le temps global du livre (fichiers audio mis bout à bout, en secondes)
 * et les versets / les fichiers. Logique pure, sans Android : facile à tester.
 *
 * Tout est rangé dans l'ordre de l'audio : il peut différer de l'ordre des numéros
 * (ex. Esther, dont les passages grecs numérotés 11 à 16 sont lus au fil du récit).
 * Un chapitre absent des timings n'a pas d'audio.
 */
class VerseClock(private val timings: BookTimings) {
    private val offsets: DoubleArray = DoubleArray(timings.audio.size).also { arr ->
        var acc = 0.0
        timings.audio.forEachIndexed { i, a -> arr[i] = acc; acc += a.duration }
    }

    private class Mark(val time: Double, val ref: VerseRef)

    /** Annonces de chapitre (verset 0) et débuts de verset, triés par temps. */
    private val marks: List<Mark> = timings.chapters.flatMap { ch ->
        listOf(Mark(ch.start, VerseRef(ch.c, 0))) +
            ch.verses.mapIndexed { i, t -> Mark(t, VerseRef(ch.c, i + 1)) }
    }.sortedWith(compareBy<Mark> { it.time }.thenBy { it.ref.verse })

    private val verseMarks: List<Mark> = marks.filter { it.ref.verse > 0 }
    private val indexOf: Map<VerseRef, Int> = verseMarks.withIndex().associate { (i, m) -> m.ref to i }

    val duration: Double get() = timings.duration

    /** Passages dans l'ordre de l'écoute : la barre de progression et « chapitre suivant » s'appuient dessus. */
    val sections: List<Section> = buildList {
        var first = 0
        for (i in marks.indices) {
            val last = i == marks.lastIndex
            if (last || marks[i + 1].ref.chapter != marks[first].ref.chapter) {
                add(Section(marks[first].ref.chapter, marks[first].time, if (last) timings.duration else marks[i + 1].time))
                first = i + 1
            }
        }
    }

    /** Passage en cours à ce temps (le premier si on est avant, ex. pendant le titre du livre). */
    fun sectionIndexAt(globalSeconds: Double): Int =
        sections.indexOfLast { it.start <= globalSeconds + 0.02 }.coerceAtLeast(0)

    fun sectionAt(globalSeconds: Double): Section =
        sections.getOrNull(sectionIndexAt(globalSeconds)) ?: Section(1, 0.0, duration)

    /** Où commencer pour écouter ce chapitre : son annonce, ou son premier verset s'il est lu avant elle. */
    fun listenStart(chapter: Int): Double? {
        val ch = timings.chapters.firstOrNull { it.c == chapter } ?: return null
        return minOf(ch.start, ch.verses.minOrNull() ?: ch.start)
    }

    /** Chapitres avec audio, dans l'ordre de l'écoute. */
    val chapters: List<Int> = marks.filter { it.ref.verse == 0 }.map { it.ref.chapter }

    fun hasAudio(chapter: Int): Boolean = timings.chapters.any { it.c == chapter }

    fun toGlobal(index: Int, positionMs: Long): Double =
        offsets.getOrElse(index) { 0.0 } + positionMs / 1000.0

    fun toFile(globalSeconds: Double): FilePosition {
        val g = globalSeconds.coerceIn(0.0, duration)
        var i = offsets.indexOfLast { it <= g }.coerceAtLeast(0)
        // pile en fin de fichier : on bascule au début du suivant
        if (i < offsets.size - 1 && g >= offsets[i] + timings.audio[i].duration - 0.05) i++
        return FilePosition(i, ((g - offsets[i]) * 1000).toLong().coerceAtLeast(0))
    }

    /** Verset en cours à ce temps (verset 0 = annonce du chapitre). */
    fun verseAt(globalSeconds: Double): VerseRef {
        if (marks.isEmpty()) return VerseRef(1, 1)
        if (globalSeconds < marks[0].time) return marks[0].ref
        var lo = 0
        var hi = marks.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (marks[mid].time <= globalSeconds + 0.02) lo = mid else hi = mid - 1
        }
        return marks[lo].ref
    }

    fun verseStart(chapter: Int, verse: Int): Double? {
        val ch = timings.chapters.firstOrNull { it.c == chapter } ?: return null
        if (verse <= 0) return ch.start
        return ch.verses.getOrNull(verse - 1)
    }

    fun chapterStart(chapter: Int): Double? = timings.chapters.firstOrNull { it.c == chapter }?.start

    /** Fin du chapitre = début de ce qui suit son dernier verset et n'appartient pas au chapitre. */
    fun chapterEnd(chapter: Int): Double {
        val last = timings.chapters.firstOrNull { it.c == chapter }?.verses?.maxOrNull() ?: return duration
        return marks.firstOrNull { it.time > last && it.ref.chapter != chapter }?.time ?: duration
    }

    fun verseCount(chapter: Int): Int = timings.chapters.firstOrNull { it.c == chapter }?.verses?.size ?: 0

    /** Verset suivant dans l'audio, ou null en fin de livre. */
    fun next(ref: VerseRef): VerseRef? {
        if (ref.verse == 0) return VerseRef(ref.chapter, 1).takeIf { it in indexOf }
        val i = indexOf[ref] ?: return null
        return verseMarks.getOrNull(i + 1)?.ref
    }

    /** Verset précédent dans l'audio (l'annonce du chapitre si on est au verset 1). */
    fun previous(ref: VerseRef): VerseRef? {
        if (ref.verse == 0) {
            val i = indexOf[VerseRef(ref.chapter, 1)] ?: return null
            return verseMarks.getOrNull(i - 1)?.ref
        }
        val i = indexOf[ref] ?: return null
        val prev = verseMarks.getOrNull(i - 1)?.ref
        return if (ref.verse == 1 && (prev == null || prev.chapter != ref.chapter)) VerseRef(ref.chapter, 0) else prev
    }
}
