package app.parda.service

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.CountDownTimer
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.parda.core.checkout.DemoCarts
import app.parda.core.checkout.Money

/**
 * A stand-in checkout, one of [DemoCarts] (`--es cart food`), built from plain Android Views so
 * the accessibility tree looks like a real store's. [CheckoutWatchService] ignores Parda's own
 * screens except this one, so the shield can be shown working without depending on a live
 * store's layout on demo day. The total follows the checkboxes, so a removed extra shows.
 */
class DemoCheckoutActivity : Activity() {
    private var timer: CountDownTimer? = null
    private val ticked = mutableMapOf<DemoCarts.Line, Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cart = DemoCarts.byId(intent.getStringExtra(EXTRA_CART))
        // A fresh cart: let the shield report it again even if it was just handled.
        CheckoutWatchService.forget(packageName)
        title = cart.store
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(32))
            setBackgroundColor(Color.WHITE)
        }
        list.addView(text("Checkout", 26f, bold = true))
        list.addView(text("${cart.store} · demo store, not a real shop", 13f, color = Color.GRAY))
        list.addView(space())

        val totalView = text("", 15f, bold = true)
        val pay = Button(this).apply {
            isAllCaps = false
            // Deliberately does nothing: Parda never taps Pay, and neither should a demo.
        }
        fun refresh() {
            val total = Money.format(cart.total { ticked[it] == true })
            totalView.text = total
            pay.text = "Pay $total"
        }
        for (l in cart.lines) {
            if (l.optional) {
                ticked[l] = l.kind == DemoCarts.Kind.TICKED
                list.addView(tick(l.label, l.price, ticked.getValue(l)) { checked -> ticked[l] = checked; refresh() })
            } else {
                list.addView(line(l.label, l.price))
            }
        }
        list.addView(row(
            text("Total payable", 15f, bold = true).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
            totalView,
        ))
        refresh()
        list.addView(space())
        val banners = cart.banners.map { b -> text(b, 14f, color = Color.rgb(0xB2, 0x3B, 0x1E)).also(list::addView) }
        list.addView(space())
        list.addView(pay)
        cart.decline?.let {
            list.addView(text(it, 13f, color = Color.GRAY).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
            })
        }
        setContentView(ScrollView(this).apply { addView(list) })

        // A countdown banner ("ends in 05:00") really counts down, as it would in a store.
        cart.banners.indexOfFirst { CLOCK.containsMatchIn(it) }.takeIf { it >= 0 }?.let { i ->
            val template = cart.banners[i]
            val (m, sec) = CLOCK.find(template)!!.destructured
            timer = object : CountDownTimer((m.toLong() * 60 + sec.toLong()) * 1000, 1_000L) {
                override fun onTick(left: Long) {
                    val s = left / 1000
                    banners[i].text = template.replace(CLOCK, "%02d:%02d".format(s / 60, s % 60))
                }
                override fun onFinish() = Unit
            }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        resumed++
        // The window-opened event can reach the shield before this screen counts as visible,
        // and a cart without a countdown sends no more events: announce it once it is up.
        window.decorView.post { window.decorView.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) }
    }

    /** Paused under Parda's own sheet: from here the shield must not read the screen, or it reads the sheet. */
    override fun onPause() {
        resumed--
        super.onPause()
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }

    /** A pre-ticked line item: the checkbox and its price share a row, as in real carts. */
    private fun tick(label: String, amount: String, checked: Boolean, onChange: (Boolean) -> Unit) = row(
        CheckBox(this).apply {
            text = label
            isChecked = checked
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnCheckedChangeListener { _, now -> onChange(now) }
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
        const val EXTRA_CART = "cart"
        private val CLOCK = Regex("""(\d{2}):(\d{2})""")

        /**
         * Demo carts in front. A count, not a flag, so a cart replacing another never leaves it
         * wrong; Android pauses the old one before resuming the new.
         */
        @Volatile
        private var resumed = 0

        /** Read by the service, which runs in this process. False while Parda's sheet covers the cart. */
        val visible: Boolean get() = resumed > 0
    }
}
