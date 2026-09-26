package app.parda.core.ledger

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

@Serializable
enum class Channel { A, B }

@Serializable
enum class Verdict(val label: String) {
    HELD_LOCALLY("held locally"),
    HANDED_BACK("handed back"),
    BLOCKED("blocked"),
    FLAGGED("flagged"),
    FIXED("fixed"),
    KEPT("kept"),
}

/**
 * One decision. [detail] is composed by Parda's own code from counts and category names; it
 * never contains the sensitive values themselves.
 */
@Serializable
data class LedgerEntry(
    val seq: Long,
    val timeMillis: Long,
    val channel: Channel,
    val verdict: Verdict,
    val detail: String,
    /** Money kept in the user's pocket by this decision, in paise. */
    val savedPaise: Long = 0,
    /** Personal fields withheld by this decision. */
    val masked: Int = 0,
    /** Dark patterns identified by this decision. */
    val patterns: Int = 0,
    val prevHash: String,
    val hash: String,
)

/**
 * Append-only, hash-chained decision log. Each entry commits to the one before it, so an
 * edited or deleted entry breaks [verify].
 */
class Ledger(initial: List<LedgerEntry> = emptyList(), private val clock: () -> Long = System::currentTimeMillis) {
    private val entries = initial.toMutableList()

    val all: List<LedgerEntry> get() = entries.toList()

    @Synchronized
    fun append(
        channel: Channel,
        verdict: Verdict,
        detail: String,
        savedPaise: Long = 0,
        masked: Int = 0,
        patterns: Int = 0,
    ): LedgerEntry {
        val prev = entries.lastOrNull()?.hash ?: GENESIS
        val seq = (entries.lastOrNull()?.seq ?: 0) + 1
        val time = clock()
        val entry = LedgerEntry(
            seq, time, channel, verdict, detail, savedPaise, masked, patterns, prev,
            hashOf(seq, time, channel, verdict, detail, savedPaise, masked, patterns, prev),
        )
        entries += entry
        return entry
    }

    /** Index of the first entry that does not chain correctly, or -1 if the log is intact. */
    fun verify(): Int {
        var prev = GENESIS
        entries.forEachIndexed { i, e ->
            val expected = hashOf(e.seq, e.timeMillis, e.channel, e.verdict, e.detail, e.savedPaise, e.masked, e.patterns, prev)
            if (e.prevHash != prev || e.hash != expected) return i
            prev = e.hash
        }
        return -1
    }

    fun totals(): Totals = Totals(
        patternsCaught = entries.sumOf { it.patterns },
        savedPaise = entries.sumOf { it.savedPaise },
        fieldsMasked = entries.sumOf { it.masked },
    )

    fun toJsonLines(): String = entries.joinToString("\n") { json.encodeToString(it) }

    data class Totals(val patternsCaught: Int, val savedPaise: Long, val fieldsMasked: Int)

    companion object {
        const val GENESIS = "0000000000000000000000000000000000000000000000000000000000000000"
        private val json = Json

        fun fromJsonLines(text: String, clock: () -> Long = System::currentTimeMillis): Ledger =
            Ledger(text.lines().filter { it.isNotBlank() }.map { json.decodeFromString<LedgerEntry>(it) }, clock)

        private fun hashOf(
            seq: Long, time: Long, channel: Channel, verdict: Verdict, detail: String,
            saved: Long, masked: Int, patterns: Int, prev: String,
        ): String {
            val material = listOf(seq, time, channel, verdict, detail, saved, masked, patterns, prev).joinToString("\u001f")
            return MessageDigest.getInstance("SHA-256").digest(material.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }
}
