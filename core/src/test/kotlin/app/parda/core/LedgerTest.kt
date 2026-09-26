package app.parda.core

import app.parda.core.ledger.Channel
import app.parda.core.ledger.Ledger
import app.parda.core.ledger.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals

class LedgerTest {
    private var now = 1_000L
    private fun ledger() = Ledger(clock = { now++ })

    @Test fun `chain verifies and survives a round trip`() {
        val l = ledger()
        l.append(Channel.B, Verdict.HANDED_BACK, "7 items withheld", masked = 7)
        l.append(Channel.A, Verdict.FLAGGED, "5 dark patterns on a checkout", patterns = 5)
        l.append(Channel.A, Verdict.FIXED, "Pre-ticked add-ons removed", savedPaise = 14900)
        assertEquals(-1, l.verify())
        val back = Ledger.fromJsonLines(l.toJsonLines())
        assertEquals(-1, back.verify())
        assertEquals(Ledger.Totals(5, 14900, 7), back.totals())
    }

    @Test fun `tampering is detected`() {
        val l = ledger()
        l.append(Channel.A, Verdict.FIXED, "one", savedPaise = 100)
        l.append(Channel.A, Verdict.FIXED, "two", savedPaise = 200)
        val forged = l.toJsonLines().replace("\"savedPaise\":100", "\"savedPaise\":999")
        assertEquals(0, Ledger.fromJsonLines(forged).verify())
    }
}
