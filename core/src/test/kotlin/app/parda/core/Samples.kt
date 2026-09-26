package app.parda.core

import app.parda.core.checkout.ScreenNode

object Samples {
    const val SALARY_LETTER = """Subject: Salary revision — FY 2026-27

Dear Mr Rajesh Kumar,

Your revised annual compensation is ₹18,40,000 effective 01 April 2026.
Payments continue to account number 50100234567891 held with HDFC Bank.
PAN on record: ABCPK1234M
Registered address: 12-4-89, Kondapur Main Road, Hyderabad 500084
Contact: rajesh.kumar@example.com / +91 98490 12345

Please confirm receipt within seven working days."""

    private fun row(id: String, label: String, amount: String, checked: Boolean) = ScreenNode(
        id = "row-$id",
        children = listOf(
            ScreenNode("cb-$id", text = label, checkable = true, checked = checked),
            ScreenNode("amt-$id", text = amount),
        ),
    )

    private fun line(id: String, label: String, amount: String) = ScreenNode(
        id = "row-$id",
        children = listOf(ScreenNode("lbl-$id", text = label), ScreenNode("amt-$id", text = amount)),
    )

    val CART = ScreenNode(
        id = "root",
        children = listOf(
            ScreenNode("title", text = "Checkout"),
            line("item", "Cotton kurta, size M", "₹1,299"),
            line("ship", "Standard delivery", "Free"),
            row("don", "Round up and donate to a cause", "₹10", checked = true),
            row("prot", "Purchase protection plan", "₹149", checked = true),
            row("prime", "Start a 30-day free trial (auto-renews at ₹299/mo)", "Free", checked = true),
            line("fee", "Handling fee", "₹49"),
            line("total", "Total payable", "₹1,507"),
            ScreenNode("urg", text = "Only 2 left — offer ends in 04:58"),
            ScreenNode("guilt", text = "No thanks, I don't care about supporting local artisans"),
            ScreenNode("pay", text = "Pay ₹1,507"),
        ),
    )
}
