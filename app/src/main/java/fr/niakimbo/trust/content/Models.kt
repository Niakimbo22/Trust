package fr.niakimbo.trust.content

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** content/books.json : les livres disponibles. */
@Serializable
data class BooksIndex(val version: Int = 0, val books: List<BookInfo> = emptyList())

@Serializable
data class BookInfo(
    val code: String,
    val name: String,
    val short: String,
    val testament: String,
    val chapters: Int,
    val verses: Int,
    val text: String,
    val timings: String? = null,
    val audio: List<AudioFile> = emptyList(),
) {
    val hasAudio: Boolean get() = timings != null && audio.isNotEmpty()
}

@Serializable
data class AudioFile(val url: String, val duration: Double)

/** content/text/<CODE>.json */
@Serializable
data class BookText(val code: String, val name: String, val chapters: List<ChapterText>)

@Serializable
data class ChapterText(val c: Int, val verses: List<Verse>)

@Serializable
data class Verse(
    val v: Int,
    val t: String,
    /** Titre de section placé avant ce verset. */
    val h: String? = null,
    /** Notes de Crampon. */
    @SerialName("n") val notes: List<String> = emptyList(),
)

/** content/timings/<CODE>.json — temps globaux en secondes (fichiers audio mis bout à bout). */
@Serializable
data class BookTimings(
    val code: String,
    val version: Int,
    val audio: List<AudioFile>,
    val duration: Double,
    val chapters: List<ChapterTimings>,
)

@Serializable
data class ChapterTimings(val c: Int, val start: Double, val verses: List<Double>)

/** Un livre prêt à l'emploi. */
data class Book(val info: BookInfo, val text: BookText, val timings: BookTimings?) {
    val code get() = info.code
    fun chapter(c: Int): ChapterText? = text.chapters.firstOrNull { it.c == c }
}
