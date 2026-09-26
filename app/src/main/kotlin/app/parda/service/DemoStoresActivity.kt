package app.parda.service

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.parda.core.checkout.DemoCarts
import app.parda.core.checkout.Money

/** Lists the demo carts; each opens in [DemoCheckoutActivity], where the shield reads it. */
class DemoStoresActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (48 * density).toInt(), (20 * density).toInt(), (32 * density).toInt())
            setBackgroundColor(Color.WHITE)
        }
        list.addView(TextView(this).apply {
            text = "Demo stores"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(0x16, 0x18, 0x1D))
        })
        list.addView(TextView(this).apply {
            text = "Made-up stores with the tricks real ones use. Open one and Parda reads its checkout."
            textSize = 13f
            setTextColor(Color.GRAY)
        })
        for (cart in DemoCarts.ALL) {
            list.addView(Button(this).apply {
                isAllCaps = false
                text = "${cart.store} · ${KIND[cart.id] ?: cart.id} · ${Money.format(cart.total())}"
                setOnClickListener {
                    startActivity(Intent(this@DemoStoresActivity, DemoCheckoutActivity::class.java).putExtra(DemoCheckoutActivity.EXTRA_CART, cart.id))
                }
            })
        }
        setContentView(ScrollView(this).apply { addView(list) })
    }

    private companion object {
        val KIND = mapOf(
            "fashion" to "clothes", "food" to "food delivery", "flight" to "flights",
            "movie" to "movie tickets", "grocery" to "groceries", "honest" to "books, no tricks",
        )
    }
}
