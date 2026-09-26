package app.parda.ui.screens

import androidx.compose.foundation.horizontalScroll
import app.parda.core.document.SuggestedQuestions
import app.parda.core.agent.Planner
import kotlinx.coroutines.Job
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.Image
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import app.parda.data.ImageAirlock
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parda.core.agent.GateDecision
import app.parda.data.DocumentReader
import app.parda.data.ModelStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.parda.core.ledger.Channel
import app.parda.core.ledger.Verdict
import app.parda.store
import app.parda.ui.components.GlassCard
import app.parda.ui.components.NightCard
import app.parda.ui.components.Pill
import app.parda.ui.components.PrimaryButton
import app.parda.ui.components.QuietButton
import app.parda.ui.components.SectionLabel
import app.parda.ui.theme.Frost

/**
 * Channel B. Paste or share text in, ask a question. The local agent answers if it can; if it
 * can't, Parda prepares a sanitized copy for the user to take elsewhere by their own hand.
 */
@Composable
fun AirlockScreen(initialText: String?, initialImage: Uri? = null) {
    val context = LocalContext.current
    val store = context.store
    val clipboard = LocalClipboardManager.current

    var document by rememberSaveable(initialText) { mutableStateOf(initialText ?: SAMPLE) }
    var request by rememberSaveable { mutableStateOf("") }
    var reply by rememberSaveable { mutableStateOf("") }
    var decision by remember { mutableStateOf<GateDecision?>(null) }
    var thinking by remember { mutableStateOf(false) }
    val model by store.model.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = displayName(context, uri) ?: "model.gguf"
            scope.launch(Dispatchers.IO) { store.importModel(uri, name) }
        }
    }

    var fileNote by remember { mutableStateOf<String?>(null) }
    // The file behind the text box: summaries use all of it, the box shows the first 20,000 characters.
    var preview by remember { mutableStateOf<String?>(null) }
    var fullText by remember { mutableStateOf<String?>(null) }
    var pages by remember { mutableStateOf<Int?>(null) }
    var fullRead by remember { mutableStateOf<Job?>(null) }
    var streamed by remember { mutableStateOf("") }
    var image by remember { mutableStateOf<ImageAirlock.Result?>(null) }
    var imageName by remember { mutableStateOf("") }
    fun openImage(uri: Uri, scannedPdf: Boolean = false) {
        fileNote = "Reading the text in this image, on this phone…"
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching {
                    if (scannedPdf) ImageAirlock.readScannedPdf(context, uri, store.policy.value)
                    else ImageAirlock.read(context, uri, store.policy.value)
                }
            }
            read.onSuccess {
                image = it
                imageName = displayName(context, uri) ?: "image"
                fileNote = null
            }.onFailure { fileNote = "Could not read that image." }
        }
    }
    LaunchedEffect(initialImage) { initialImage?.let(::openImage) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (context.contentResolver.getType(uri)?.startsWith("image/") == true) {
            openImage(uri)
            return@rememberLauncherForActivityResult
        }
        image = null
        run {
            fileNote = "Reading…"
            scope.launch {
                val read = withContext(Dispatchers.IO) { runCatching { DocumentReader.read(context, uri) } }
                read.onSuccess { r ->
                    document = r.text
                    preview = r.text
                    fullText = r.full
                    pages = r.pages
                    decision = null
                    fileNote = fileNote(r)
                    if (!r.complete) {
                        // A long PDF: show the preview now, read the rest for the summary meanwhile.
                        fullRead = scope.launch {
                            val whole = withContext(Dispatchers.IO) { runCatching { DocumentReader.read(context, uri, whole = true) }.getOrNull() }
                            if (whole != null) {
                                fullText = whole.full
                                fileNote = fileNote(whole)
                            }
                        }
                    }
                }.onFailure {
                    // No text layer: a scanned PDF. Read it with OCR like a photo instead.
                    if (it is DocumentReader.NoText && it.pdf) openImage(uri, scannedPdf = true)
                    else fileNote = (it as? DocumentReader.Unsupported)?.message ?: "Could not read that file."
                }
            }
        }
    }

    ScreenColumn {
        Column {
            Text("Airlock", style = MaterialTheme.typography.headlineLarge)
            Text("Mask it before you share it", style = MaterialTheme.typography.bodyLarge, color = Frost.Ink2)
        }

        ModelCard(model, onImport = { pickModel.launch(arrayOf("*/*")) })

        image?.let { img ->
            ImageCard(img, imageName, onClose = { image = null })
            return@ScreenColumn
        }

        GlassCard(padding = 16.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Inside this phone")
                Pill("Open file", onClick = { pickFile.launch(DocumentReader.MIME_TYPES + "image/*") })
            }
            fileNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Frost.Ink2) }
            OutlinedTextField(
                value = document,
                onValueChange = { document = it; decision = null },
                // Capped so a long document scrolls inside the box instead of pushing the
                // question and the Ask button off the bottom of the screen.
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                label = { Text("Text you are about to send") },
                colors = fieldColors(),
            )
            OutlinedTextField(
                value = request,
                onValueChange = { request = it; decision = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("What do you want to know?") },
                placeholder = { Text("Ask about this document, or tap a suggestion") },
                colors = fieldColors(),
            )
            // Questions that fit whatever is loaded: a statement, a lease, a payroll export, an ID.
            val suggestions = remember(document) { SuggestedQuestions.of(document) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { q -> Pill(q.label, strong = request == q.request, onClick = { request = q.request; decision = null }) }
            }
            PrimaryButton(
                if (thinking) "Thinking on this phone…" else "Ask the agent",
                enabled = document.isNotBlank() && !thinking,
                onClick = {
                    thinking = true
                    streamed = ""
                    scope.launch {
                        fullRead?.join()
                        // If the text box was edited, it is the document; otherwise the whole file is.
                        val fromFile = preview != null && document == preview
                        val whole = if (fromFile) fullText ?: document else document
                        val d = withContext(Dispatchers.Default) {
                            store.gate.handle(
                                document, request.ifBlank { SuggestedQuestions.of(document).firstOrNull()?.request ?: EASY }, store.policy.value,
                                full = whole, pages = if (fromFile) pages else null,
                            ) { piece -> scope.launch(Dispatchers.Main) { streamed += piece } }
                        }
                        val planner = d.plannedBy.label +
                            if (d.plannedBy == Planner.MODEL) " (${(model as? ModelStatus.Ready)?.name ?: "model"})" else ""
                        decision = d
                        thinking = false
                        when (d) {
                            is GateDecision.HeldLocally -> store.record(
                                Channel.B, Verdict.HELD_LOCALLY,
                                "${d.result.detections.size} sensitive item(s) classified; answered on the device by $planner, nothing prepared to send",
                            )
                            is GateDecision.HandedBack -> store.record(
                                Channel.B, Verdict.HANDED_BACK,
                                "${d.result.vault.withheld} item(s) withheld (${d.result.blockedCount} blocked); planned by $planner; sanitized copy prepared, nothing transmitted",
                                masked = d.result.vault.withheld,
                            )
                        }
                    }
                },
            )
        }

        when (val d = decision) {
            null -> if (thinking && streamed.isNotBlank()) {
                GlassCard(padding = 16.dp) {
                    SectionLabel("Writing on this phone…")
                    Text(streamed, style = MaterialTheme.typography.bodyLarge)
                }
            }
            is GateDecision.HeldLocally -> {
                NightCard {
                    SectionLabel("Answered on this phone", Frost.NightInk2)
                    Text("Nothing needed to leave — not even a surrogate.", color = Color.White, style = MaterialTheme.typography.bodyLarge)
                    Text("Planned by ${d.plannedBy.label}", color = Frost.NightInk2, style = MaterialTheme.typography.bodyMedium)
                }
                GlassCard(padding = 16.dp) {
                    SectionLabel("Answer")
                    SelectionContainer { Text(d.answer, style = MaterialTheme.typography.bodyLarge) }
                }
            }
            is GateDecision.HandedBack -> {
                NightCard {
                    SectionLabel("Handed back sanitized", Frost.NightInk2)
                    Text(
                        "${d.reason.explanation} Parda prepared a safe copy. It did not send it — it has no way to.",
                        color = Color.White, style = MaterialTheme.typography.bodyLarge,
                    )
                }
                GlassCard(padding = 16.dp) {
                    SectionLabel("What you can take outside · ${d.result.vault.withheld} withheld")
                    if (preview != null && document == preview && (fullText?.length ?: 0) > document.length) {
                        Text("This copy is the text box: the first 20,000 characters of the file.", style = MaterialTheme.typography.bodyMedium, color = Frost.Ink2)
                    }
                    Mono(d.outbound)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Pill("Copy", strong = true, onClick = { clipboard.setText(AnnotatedString(d.outbound)) })
                        Pill("Share…", onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, d.outbound)
                            context.startActivity(Intent.createChooser(send, "Send sanitized text"))
                        })
                    }
                }
                GlassCard(padding = 16.dp) {
                    SectionLabel("Vault — never leaves this phone")
                    val entries = d.result.vault.entries
                    entries.take(VAULT_ROWS).forEach { e ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(e.token, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                            Text(e.real, style = MaterialTheme.typography.bodyMedium, color = Frost.WarnInk)
                        }
                    }
                    if (entries.size > VAULT_ROWS) {
                        Text("and ${entries.size - VAULT_ROWS} more, all held on this phone", style = MaterialTheme.typography.bodyMedium, color = Frost.Ink2)
                    }
                }
                GlassCard(padding = 16.dp) {
                    SectionLabel("Bring the reply back")
                    OutlinedTextField(
                        value = reply,
                        onValueChange = { reply = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                        label = { Text("Paste the outside model's answer") },
                        colors = fieldColors(),
                    )
                    if (reply.isNotBlank()) {
                        SectionLabel("Reads back to you as")
                        SelectionContainer { Text(d.result.vault.rehydrate(reply), style = MaterialTheme.typography.bodyLarge) }
                    }
                }
                QuietButton("Start over", onClick = { decision = null; reply = "" })
            }
        }
    }
}

/**
 * A picture after OCR: the masked preview, one tick per kind of data found (as in the Frost
 * design), and the only way out, a flattened copy handed to the share sheet.
 */
@Composable
private fun ImageCard(img: ImageAirlock.Result, name: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val store = context.store
    val plan = img.plan
    var masked by remember(img) { mutableStateOf(plan.categories.toSet()) }
    val preview = remember(img, masked) { ImageAirlock.render(img.original, plan.boxes(masked)) }

    GlassCard(padding = 16.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Masked copy · original untouched")
            Pill("Close", onClick = onClose)
        }
        Text("$name · read on this phone", style = MaterialTheme.typography.bodyMedium, color = Frost.Ink2)
        Image(
            preview.asImageBitmap(), contentDescription = "Masked preview of $name",
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(18.dp)),
            contentScale = ContentScale.Fit,
        )
        if (plan.fields.isEmpty()) {
            Text(
                "No personal data found in this image. If it is blurry or at an angle, try a straighter photo.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        plan.categories.forEach { c ->
            val fields = plan.fields.filter { it.category == c }
            val label = c.label + (if (fields.any { it.keepsLast4 }) " · keep last 4" else "") + " · ${fields.size}"
            // The whole row is the target, not just the box.
            Row(
                Modifier.fillMaxWidth().toggleable(
                    value = c in masked, role = Role.Checkbox,
                    onValueChange = { on -> masked = if (on) masked + c else masked - c },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = c in masked, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
        PrimaryButton("Share masked copy", enabled = plan.fields.isNotEmpty(), onClick = {
            val uri = ImageAirlock.export(context, preview)
            val count = plan.fields.count { it.category in masked }
            store.record(
                Channel.B, Verdict.HANDED_BACK,
                "Covered $count field(s) on an image; masked copy handed to you, original untouched",
                masked = count,
            )
            val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, "Send masked image"))
        })
    }
}

/** Which planner is on duty. The model file only ever arrives by hand: nothing is downloaded. */
@Composable
private fun ModelCard(model: ModelStatus, onImport: () -> Unit) {
    GlassCard(padding = 16.dp) {
        SectionLabel("Local model")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val (title, detail) = when (model) {
                    is ModelStatus.Ready -> model.name to "Running on this phone's CPU · loaded in ${model.loadMillis} ms"
                    is ModelStatus.Loading -> model.name to "Loading…"
                    is ModelStatus.Failed -> model.name to "Could not load: ${model.reason}. Using the rule-based agent."
                    ModelStatus.Missing -> "Rule-based agent" to "Import a .gguf (e.g. Hammer2.1-1.5B Q4) to plan with a local LLM."
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = Frost.Ink2)
            }
            if (model is ModelStatus.Missing || model is ModelStatus.Failed) {
                Pill("Import", strong = true, onClick = onImport)
            }
        }
    }
}

/** Says plainly how much of the file was read and what each part of the screen covers. */
private fun fileNote(r: DocumentReader.Result): String {
    val parts = mutableListOf(r.name)
    r.pages?.let { parts += "$it page" + if (it == 1) "" else "s" }
    parts += "read on this phone"
    if (r.truncated) {
        parts += if (r.complete) "summaries cover the whole file; the box shows the first 20,000 characters"
        else "reading the rest for the summary…"
    }
    return parts.joinToString(" · ")
}

private fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }

@Composable
private fun Mono(text: String) {
    SelectionContainer {
        Text(
            text,
            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(14.dp))
                .background(Color.White).verticalScroll(rememberScrollState()).padding(12.dp),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color.White,
    unfocusedContainerColor = Color.White.copy(alpha = 0.8f),
    focusedBorderColor = Frost.Night,
    unfocusedBorderColor = Color.Transparent,
)

private const val VAULT_ROWS = 40
private const val EASY = "Summarise the key terms of this letter in three lines."

private const val SAMPLE = """Subject: Salary revision — FY 2026-27

Dear Mr Rajesh Kumar,

Your revised annual compensation is ₹18,40,000 effective 01 April 2026.
Payments continue to account number 50100234567891 held with HDFC Bank.
PAN on record: ABCPK1234M
Registered address: 12-4-89, Kondapur Main Road, Hyderabad 500084
Contact: rajesh.kumar@example.com / +91 98490 12345

Please confirm receipt within seven working days."""
