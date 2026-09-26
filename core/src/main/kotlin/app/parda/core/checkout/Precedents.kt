package app.parda.core.checkout

/** A regulator's action against the company whose checkout is on screen, as publicly reported. */
data class Precedent(val company: String, val summary: String, val source: String)

/**
 * Regulator actions over dark patterns, shown on the intercept sheet so the user sees these are
 * not hypothetical in the very app they are using. Only actions a regulator took are listed;
 * complaints and allegations (Zomato, Blinkit, MakeMyTrip) are not.
 */
object Precedents {
    private class Entry(val match: Regex, val precedent: Precedent)

    private const val AUG_2026 = "CCPA orders against nine platforms, reported 6 Aug 2026 (afaqs!, IBTimes)"

    private val KNOWN = listOf(
        Entry(
            Regex("""(?i)zepto"""),
            Precedent("Zepto", "CCPA fined Zepto ₹7 lakh (Aug 2026) for a handling charge revealed only at checkout and a membership added on its own.", AUG_2026),
        ),
        Entry(
            Regex("""(?i)physics ?wallah|penpencil|\bpw\b"""),
            Precedent("Physics Wallah", "CCPA fined Physics Wallah ₹5 lakh (Aug 2026) for a ₹10 donation that was pre-selected.", AUG_2026),
        ),
        Entry(
            Regex("""(?i)bookmyshow|com\.bt\.bms"""),
            Precedent("BookMyShow", "CCPA penalised BookMyShow (Aug 2026) for a pre-ticked ₹1 contribution on each ticket.", AUG_2026),
        ),
        Entry(
            Regex("""(?i)indigo"""),
            Precedent("IndiGo", "CCPA penalised IndiGo (Aug 2026) for the opt-out wording “No I will take risk”.", AUG_2026),
        ),
        Entry(
            Regex("""(?i)pharmeasy|rxpal"""),
            Precedent("PharmEasy", "CCPA penalised PharmEasy (Aug 2026) for a membership added at checkout and misleading countdowns.", AUG_2026),
        ),
        Entry(
            Regex("""(?i)spicejet"""),
            Precedent("SpiceJet", "CCPA penalised SpiceJet (2026) for pre-ticked SpiceClub enrolment and promotional consent.", AUG_2026),
        ),
        Entry(
            Regex("""(?i)rapido"""),
            Precedent("Rapido", "CCPA fined Rapido ₹10 lakh (2026) for dark patterns in its ride-booking screens.", "CCPA order against Roppen Transportation Services, 2026"),
        ),
        Entry(
            Regex("""(?i)\buber(cab)?\b"""),
            Precedent("Uber", "CCPA sought Uber's explanation for “advance tips” asked before a ride is assigned (May 2025).", "Notice announced by the Consumer Affairs Minister, 21 May 2025"),
        ),
    )

    /** The action on record for the app on screen, matched by its package name or its label. */
    fun forApp(packageName: String, appLabel: String): Precedent? {
        val who = "$packageName $appLabel"
        return KNOWN.firstOrNull { it.match.containsMatchIn(who) }?.precedent
    }
}
