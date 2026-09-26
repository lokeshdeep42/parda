package app.parda.core.disclosure

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One sanitized copy the user took outside, with the vault that turns a reply back into real names. */
@Serializable
data class HandBack(val id: Long, val at: Long, val title: String, val entries: List<VaultEntry>) {
    val vault: Vault get() = Vault(entries)
}

/**
 * The vaults of recent hand-backs, so a reply pasted back hours later still reads with real
 * names. Held on the phone only, for [KEEP_MS], and at most [MAX] of them; the app stores the
 * JSON encrypted. Only surrogates are kept: blocked values never left, so there is nothing to restore.
 */
@Serializable
data class VaultArchive(val handBacks: List<HandBack> = emptyList()) {

    fun add(title: String, vault: Vault, now: Long): VaultArchive {
        val restorable = vault.entries.filter { it.restorable }
        if (restorable.isEmpty()) return expire(now)
        val next = HandBack(id = now, at = now, title = title.take(80), entries = restorable)
        return VaultArchive((listOf(next) + handBacks).take(MAX)).expire(now)
    }

    fun expire(now: Long): VaultArchive = VaultArchive(handBacks.filter { now - it.at < KEEP_MS })

    fun without(id: Long): VaultArchive = VaultArchive(handBacks.filterNot { it.id == id })

    /**
     * The hand-back a reply answers: the one whose tokens it mentions most, the newest on a tie.
     * Null when the reply mentions no token Parda made.
     */
    fun bestFor(reply: String): HandBack? {
        val mentioned = TOKEN.findAll(reply).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toSet()
        if (mentioned.isEmpty()) return null
        return handBacks
            .map { hb -> hb to hb.entries.count { it.token.removeSurrounding("<", ">") in mentioned } }
            .filter { it.second > 0 }
            .maxWithOrNull(compareBy<Pair<HandBack, Int>> { it.second }.thenBy { it.first.at })
            ?.first
    }

    fun toJson(): String = JSON.encodeToString(this)

    companion object {
        const val KEEP_MS = 24 * 60 * 60 * 1000L
        const val MAX = 20
        private val TOKEN = Regex("""<([A-Z]+_\d+)>|\b([A-Z]+_\d+)\b""")
        private val JSON = Json { ignoreUnknownKeys = true }

        fun fromJson(json: String): VaultArchive = runCatching { JSON.decodeFromString<VaultArchive>(json) }.getOrDefault(VaultArchive())

        /** True when [text] carries a token Parda makes, like "<PERSON_1>": probably a reply to bring back. */
        fun looksLikeReply(text: String): Boolean = TOKEN.containsMatchIn(text)
    }
}
