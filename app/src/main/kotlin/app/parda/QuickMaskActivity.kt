package app.parda

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.parda.core.ledger.Channel
import app.parda.core.ledger.Verdict
import app.parda.data.ImageAirlock
import app.parda.ui.components.PrimaryButton
import app.parda.ui.components.QuietButton
import app.parda.ui.components.SectionLabel
import app.parda.ui.theme.Frost
import app.parda.ui.theme.PardaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Mask & share" in any app's share sheet, and the "Mask clipboard" tile: a small sheet over the
 * app the user is in, without opening Parda. Text is masked by the policy; a photo is read by
 * on-device OCR and its personal fields covered. The masked copy leaves only the way the user
 * sends it: the share sheet or the clipboard. The vault is kept so a reply can be brought back.
 */
class QuickMaskActivity : ComponentActivity() {
    private sealed interface State {
        data object Reading : State
        data class Text(val masked: String, val hidden: Int, val fromClipboard: Boolean) : State
        data class Photo(val masked: Bitmap, val covered: Int) : State
        data class Empty(val why: String) : State
    }

    private var state by mutableStateOf<State>(State.Reading)

    /** Android lets an app read the clipboard only once its window has focus. */
    private var clipboardPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when {
            intent.action == ACTION_MASK_CLIPBOARD -> clipboardPending = true
            intent.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true ->
                intent.stream()?.let(::maskPhoto) ?: run { state = State.Empty("Nothing to mask was shared.") }
            intent.action == Intent.ACTION_SEND ->
                intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { maskText(it, fromClipboard = false) }
                    ?: run { state = State.Empty("Nothing to mask was shared.") }
            else -> { finish(); return }
        }
        setContent { PardaTheme { Sheet() } }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !clipboardPending) return
        clipboardPending = false
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) state = State.Empty("The clipboard is empty.") else maskText(text, fromClipboard = true)
    }

    private fun maskText(text: String, fromClipboard: Boolean) {
        val result = store.sanitizer.sanitize(text, store.policy.value)
        val hidden = result.vault.withheld
        if (hidden > 0) {
            store.record(
                Channel.B, Verdict.HANDED_BACK,
                "Masked $hidden item(s) in ${if (fromClipboard) "the clipboard" else "shared text"} (${result.blockedCount} blocked); nothing sent",
                masked = hidden,
            )
            // Kept so the reply can be brought back in the Airlock, named by the safe copy.
            val title = result.sanitized.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
            lifecycleScope.launch(Dispatchers.IO) { store.vaults.remember(title, result.vault) }
        }
        state = State.Text(result.sanitized, hidden, fromClipboard)
    }

    private fun maskPhoto(uri: Uri) {
        lifecycleScope.launch {
            val read = withContext(Dispatchers.IO) { runCatching { ImageAirlock.read(this@QuickMaskActivity, uri, store.policy.value) } }
            state = read.fold(
                onSuccess = { r ->
                    val plan = r.plan
                    State.Photo(ImageAirlock.render(r.original, plan.boxes(plan.categories.toSet())), plan.fields.size)
                },
                onFailure = { State.Empty("Could not read that image.") },
            )
        }
    }

    private fun share(send: Intent) {
        startActivity(Intent.createChooser(send, "Send the masked copy"))
        finish()
    }

    @Composable
    private fun Sheet() {
        Box(
            Modifier.fillMaxSize().background(Color(0x471E222C)).clickable { finish() },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier
                    .navigationBarsPadding()
                    .padding(10.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(36.dp))
                    .background(Color.White.copy(alpha = 0.96f))
                    .border(1.dp, Color.White, RoundedCornerShape(36.dp))
                    .clickable(enabled = false) {}
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (val s = state) {
                    State.Reading -> Title("Reading on this phone…", "Nothing leaves while Parda looks.")
                    is State.Empty -> {
                        Title("Nothing to mask", s.why)
                        QuietButton("Close", onClick = ::finish)
                    }
                    is State.Text -> TextResult(s)
                    is State.Photo -> PhotoResult(s)
                }
            }
        }
    }

    @Composable
    private fun Title(title: String, detail: String) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = Frost.Ink2)
        }
    }

    @Composable
    private fun TextResult(s: State.Text) {
        Title(
            if (s.hidden > 0) "Parda masked ${s.hidden} item(s)" else "Nothing personal found",
            "Checked on this phone. Nothing was sent; the copy below is what you can send.",
        )
        SectionLabel("Masked copy")
        Text(
            s.masked, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())
                .clip(RoundedCornerShape(16.dp)).background(Frost.GlassStrong).padding(12.dp),
        )
        val copy = {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Masked by Parda", s.masked))
            // Android 13+ shows its own confirmation when the clipboard changes.
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Masked copy is on the clipboard", Toast.LENGTH_SHORT).show()
            finish()
        }
        val send = { share(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, s.masked)) }
        if (s.fromClipboard) {
            PrimaryButton("Replace the clipboard with it", onClick = copy)
            QuietButton("Share it instead", onClick = send)
        } else {
            PrimaryButton("Share masked copy", onClick = send)
            QuietButton("Copy it", onClick = copy)
        }
    }

    @Composable
    private fun PhotoResult(s: State.Photo) {
        Title(
            if (s.covered > 0) "Parda covered ${s.covered} field(s)" else "Nothing personal found",
            "Read on this phone. Your original photo is untouched.",
        )
        Image(
            s.masked.asImageBitmap(), contentDescription = "Masked copy of the photo",
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(16.dp)),
        )
        PrimaryButton("Share masked photo", enabled = s.covered > 0, onClick = {
            store.record(Channel.B, Verdict.HANDED_BACK, "Covered ${s.covered} field(s) on a shared photo; masked copy handed to you", masked = s.covered)
            val uri = ImageAirlock.export(this, s.masked)
            share(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        })
        QuietButton("Cancel", onClick = ::finish)
    }

    private fun Intent.stream(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(Intent.EXTRA_STREAM)

    companion object {
        const val ACTION_MASK_CLIPBOARD = "app.parda.action.MASK_CLIPBOARD"
    }
}
