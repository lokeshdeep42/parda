package app.parda.service

import app.parda.R
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
            text = getString(R.string.demo_stores)
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(0x16, 0x18, 0x1D))
        })
        list.addView(TextView(this).apply {
            text = getString(R.string.ds_sub)
            textSize = 13f
            setTextColor(Color.GRAY)
        })
        for (cart in DemoCarts.ALL) {
            list.addView(Button(this).apply {
                isAllCaps = false
                text = "${cart.store} · ${KIND[cart.id]?.let(::getString) ?: cart.id} · ${Money.format(cart.total())}"
                setOnClickListener {
                    startActivity(Intent(this@DemoStoresActivity, DemoCheckoutActivity::class.java).putExtra(DemoCheckoutActivity.EXTRA_CART, cart.id))
                }
            })
        }
        setContentView(ScrollView(this).apply { addView(list) })
    }

    private companion object {
        val KIND = mapOf(
            "fashion" to R.string.ds_fashion, "food" to R.string.ds_food, "flight" to R.string.ds_flight,
            "movie" to R.string.ds_movie, "grocery" to R.string.ds_grocery, "honest" to R.string.ds_honest,
        )
    }
}
