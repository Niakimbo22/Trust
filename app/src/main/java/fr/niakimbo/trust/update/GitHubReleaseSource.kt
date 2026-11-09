package fr.niakimbo.trust.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Une version publiée sur GitHub, prête à être installée. */
data class ReleaseInfo(
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
    val apkSize: Long,
    val notes: String,
    val pageUrl: String,
)

/** Erreurs « compréhensibles » remontées à l'UI. */
class UpdateException(message: String) : IOException(message)

/**
 * Lit la dernière release publique du repo via l'API GitHub.
 * Repo public → aucun token, aucune minute GitHub Actions consommée.
 * Limite de l'API sans token : 60 requêtes/heure par IP, largement suffisant
 * (vérif auto au plus 1 fois par jour + bouton manuel).
 */
class GitHubReleaseSource(
    private val owner: String = "Niakimbo22",
    private val repo: String = "trust",
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Renvoie null s'il n'existe encore aucune release. */
    suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val conn = (URL("https://api.github.com/repos/$owner/$repo/releases/latest")
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            when (val code = conn.responseCode) {
                200 -> parse(conn.inputStream.bufferedReader().use { it.readText() })
                404 -> null
                403, 429 -> throw UpdateException("GitHub limite les vérifications. Réessaie dans une heure.")
                else -> throw UpdateException("Réponse inattendue de GitHub ($code).")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String): ReleaseInfo {
        val dto = json.decodeFromString<ReleaseDto>(body)
        val code = VersionCodes.fromTag(dto.tagName)
            ?: throw UpdateException("Tag de version invalide : ${dto.tagName}")
        val apk = dto.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            ?: throw UpdateException("La version ${dto.tagName} ne contient pas d'APK.")
        return ReleaseInfo(
            versionName = VersionCodes.nameFromTag(dto.tagName),
            versionCode = code,
            apkUrl = apk.downloadUrl,
            apkSize = apk.size,
            notes = dto.body.orEmpty().trim(),
            pageUrl = dto.htmlUrl,
        )
    }

    @Serializable
    private data class ReleaseDto(
        @SerialName("tag_name") val tagName: String,
        @SerialName("html_url") val htmlUrl: String = "",
        val body: String? = null,
        val assets: List<AssetDto> = emptyList(),
    )

    @Serializable
    private data class AssetDto(
        val name: String,
        val size: Long = 0,
        @SerialName("browser_download_url") val downloadUrl: String,
    )

    companion object {
        const val USER_AGENT = "Trust-Android"
    }
}
