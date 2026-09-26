package app.parda.core.checkout

/**
 * Stand-in checkouts in the shapes of real shopping, food, travel, ticket and grocery apps. The
 * demo store on the phone draws them and the tests scan them, so both see the same carts. Store
 * names are made up. One cart is honest, to show the shield stays quiet when nothing is wrong.
 */
object DemoCarts {
    enum class Kind {
        /** Something the user chose. */
        ITEM,
        /** An extra that arrives already ticked. */
        TICKED,
        /** An extra offered but left for the user to tick. */
        OFFERED,
        /** A charge that cannot be removed. */
        FEE,
    }

    data class Line(val label: String, val price: String, val kind: Kind = Kind.ITEM) {
        val optional: Boolean get() = kind == Kind.TICKED || kind == Kind.OFFERED
    }

    data class Cart(
        val id: String,
        val store: String,
        val lines: List<Line>,
        /** Banners such as countdowns; an "mm:ss" in one counts down on the phone. */
        val banners: List<String> = emptyList(),
        /** The small link under Pay. */
        val decline: String? = null,
    ) {
        /** What Pay charges, in paise, given which extras are ticked (by default, as the store serves it). */
        fun total(ticked: (Line) -> Boolean = { it.kind == Kind.TICKED }): Long =
            lines.filter { !it.optional || ticked(it) }.sumOf { Money.oneOffAmounts(it.price).firstOrNull() ?: 0 }
    }

    val FASHION = Cart(
        "fashion", "Threadline",
        listOf(
            Line("Cotton kurta, size M", "₹1,299"),
            Line("Standard delivery", "Free"),
            Line("Round up and donate to a cause", "₹10", Kind.TICKED),
            Line("Purchase protection plan", "₹149", Kind.TICKED),
            Line("Start a 30-day free trial (auto-renews at ₹299/mo)", "Free", Kind.TICKED),
            Line("Handling fee", "₹49", Kind.FEE),
        ),
        banners = listOf("Only 2 left — offer ends in 05:00"),
        decline = "No thanks, I don't care about supporting local artisans",
    )

    val FOOD = Cart(
        "food", "Tiffin Run",
        listOf(
            Line("Chicken biryani × 1", "₹349"),
            Line("Paneer tikka × 1", "₹249"),
            Line("Delivery", "Free"),
            Line("Tip your delivery partner", "₹30", Kind.TICKED),
            Line("Gold membership: free trial, then ₹149/month", "Free", Kind.TICKED),
            Line("Platform fee", "₹10", Kind.FEE),
            Line("Packaging fee", "₹25", Kind.FEE),
        ),
        banners = listOf("Hurry! 18 people are looking at this restaurant"),
        decline = "No thanks, I don't want to save on every order",
    )

    val FLIGHT = Cart(
        "flight", "SkyHop",
        listOf(
            Line("BLR → HYD, 1 adult, 14 Oct", "₹4,899"),
            Line("Travel insurance for this trip", "₹299", Kind.TICKED),
            Line("Donate to plant trees", "₹10", Kind.TICKED),
            Line("Seat selection", "₹0"),
            Line("Convenience fee", "₹350", Kind.FEE),
        ),
        banners = listOf("Only 3 left at this fare"),
        decline = "No thanks, I'd rather risk losing my money",
    )

    val MOVIE = Cart(
        "movie", "ReelSeat",
        listOf(
            Line("2 × Recliner, Screen 3, 7:30 PM", "₹560"),
            Line("Ticket cancellation protection", "₹29", Kind.TICKED),
            Line("Convenience fee (incl. GST)", "₹70.80", Kind.FEE),
        ),
        banners = listOf("Seats selling fast"),
    )

    val GROCERY = Cart(
        "grocery", "QuickBasket",
        listOf(
            Line("Toned milk, 1 L", "₹66"),
            Line("Whole wheat bread", "₹45"),
            Line("Delivery partner tip", "₹20", Kind.TICKED),
            Line("Pro membership: 30-day free trial, then ₹99/month", "Free", Kind.TICKED),
            Line("Small cart fee", "₹35", Kind.FEE),
            Line("Handling charge", "₹9", Kind.FEE),
        ),
        banners = listOf("Offer ends in 09:59"),
    )

    val HONEST = Cart(
        "honest", "Plain Books",
        listOf(
            Line("Paperback: The Monsoon Map", "₹399"),
            Line("Delivery", "Free"),
            Line("Gift wrap", "₹30", Kind.OFFERED),
        ),
    )

    val ALL = listOf(FASHION, FOOD, FLIGHT, MOVIE, GROCERY, HONEST)

    fun byId(id: String?): Cart = ALL.firstOrNull { it.id == id } ?: FASHION
}
