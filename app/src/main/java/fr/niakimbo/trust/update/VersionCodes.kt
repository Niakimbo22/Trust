package fr.niakimbo.trust.update

/**
 * Convertit un tag de release ("v1.2.3" ou "1.2.3") en versionCode.
 * Même formule que app/build.gradle.kts : majeur*10000 + mineur*100 + patch.
 * Ainsi la MAJ in-app compare directement le tag GitHub avec la version installée.
 */
object VersionCodes {
    private val regex = Regex("""^v?(\d+)\.(\d+)\.(\d+)$""")

    fun fromTag(tag: String): Int? {
        val (ma, mi, pa) = regex.matchEntire(tag.trim())?.destructured ?: return null
        if (mi.toInt() >= 100 || pa.toInt() >= 100) return null
        return ma.toInt() * 10000 + mi.toInt() * 100 + pa.toInt()
    }

    fun nameFromTag(tag: String): String = tag.trim().removePrefix("v")
}
