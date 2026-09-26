package app.parda.service

import app.parda.core.checkout.CheckoutPlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A checkout Parda has paused and is waiting on the user about. Held in memory only. */
data class Intercept(val packageName: String, val appLabel: String, val plan: CheckoutPlan)

object InterceptState {
    private val _current = MutableStateFlow<Intercept?>(null)
    val current: StateFlow<Intercept?> = _current.asStateFlow()

    fun show(intercept: Intercept) { _current.value = intercept }
    fun clear() { _current.value = null }
}
