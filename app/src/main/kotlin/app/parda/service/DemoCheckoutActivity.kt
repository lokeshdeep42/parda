package app.parda.service

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.CountDownTimer
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A stand-in shopping checkout with every dark pattern Parda knows, built from plain Android
 * Views so the accessibility tree looks like a real shopping app's. [CheckoutWatchService]
 * ignores Parda's own screens except this one, so the shield can be shown working without
 * depending on a live store's layout on demo day.
 */
class DemoCheckoutActivity : Activity() {
    private var timer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Demo store"
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(32))
            setBackgroundColor(Color.WHITE)
        }
        list.addView(text("Checkout", 26f, bold = true))
        list.addView(text("Demo store · not a real shop", 13f, color = Color.GRAY))
        list.addView(space())
        list.addView(line("Cotton kurta, size M", "₹1,299"))
        list.addView(line("Standard delivery", "Free"))
        list.addView(tick("Round up and donate to a cause", "₹10"))
        list.addView(tick("Purchase protection plan", "₹149"))
        list.addView(tick("Start a 30-day free trial (auto-renews at ₹299/mo)", "Free"))
        list.addView(line("Handling fee", "₹49"))
        list.addView(line("Total payable", "₹1,507", bold = true))
        list.addView(space())
        val urgency = text("Only 2 left — offer ends in 05:00", 14f, color = Color.rgb(0xB2, 0x3B, 0x1E))
        list.addView(urgency)
        list.addView(space())
        list.addView(Button(this).apply {
            text = "Pay ₹1,507"
            isAllCaps = false
            // Deliberately does nothing: Parda never taps Pay, and neither should a demo.
        })
        list.addView(text("No thanks, I don't care about supporting local artisans", 13f, color = Color.GRAY).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(list) })

        timer = object : CountDownTimer(5 * 60_000L, 1_000L) {
            override fun onTick(left: Long) {
                val s = left / 1000
                urgency.text = "Only 2 left — offer ends in %02d:%02d".format(s / 60, s % 60)
            }
            override fun onFinish() = Unit
        }.start()
    }

    override fun onResume() {
        super.onResume()
        visible = true
    }

    override fun onPause() {
        visible = false
        super.onPause()
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }

    /** A pre-ticked line item: the checkbox and its price share a row, as in real carts. */
    private fun tick(label: String, amount: String) = row(
        CheckBox(this).apply {
            text = label
            isChecked = true
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        },
        text(amount, 15f),
    )

    private fun line(label: String, amount: String, bold: Boolean = false) = row(
        text(label, 15f, bold = bold).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        },
        text(amount, 15f, bold = bold),
    )

    private fun row(vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        // Keep the row in the accessibility tree so label and price stay grouped.
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        children.forEach(::addView)
    }

    private fun text(s: String, size: Float, bold: Boolean = false, color: Int = Color.rgb(0x16, 0x18, 0x1D)) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun space() = View(this).apply { minimumHeight = dp(12) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Read by the service, which runs in this process. */
        @Volatile
        var visible: Boolean = false
            private set
    }
}
