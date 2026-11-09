package fr.niakimbo.trust.content

import java.text.Normalizer

/** Un verset trouvé. */
data class SearchHit(val code: String, val book: String, val chapter: Int, val verse: Int, val text: String)

/** Recherche plein texte, insensible aux accents et à la casse ; tous les mots doivent être présents. */
object BibleSearch {
    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('’', '\'')
            .replace("œ", "oe")

    fun words(query: String): List<String> =
        normalize(query).split(Regex("[^\\p{L}\\p{N}']+")).filter { it.length >= 2 }
}

/** Index prêt à chercher : textes normalisés une seule fois. */
class SearchIndex(texts: List<Pair<BookInfo, BookText>>) {
    private class Entry(val hit: SearchHit, val normalized: String)

    private val entries: List<Entry> = texts.flatMap { (info, text) ->
        text.chapters.flatMap { ch ->
            ch.verses.map { v -> Entry(SearchHit(info.code, info.short, ch.c, v.v, v.t), BibleSearch.normalize(v.t)) }
        }
    }

    val size: Int get() = entries.size

    fun search(query: String, limit: Int = 300): Pair<List<SearchHit>, Int> {
        val words = BibleSearch.words(query)
        if (words.isEmpty()) return emptyList<SearchHit>() to 0
        var total = 0
        val hits = ArrayList<SearchHit>()
        for (e in entries) {
            if (words.all { e.normalized.contains(it) }) {
                total++
                if (hits.size < limit) hits += e.hit
            }
        }
        return hits to total
    }
}
