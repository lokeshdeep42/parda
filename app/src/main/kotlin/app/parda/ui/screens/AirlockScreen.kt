package app.parda.ui.screens

import app.parda.ui.components.asHeading
import app.parda.ui.explanationText
import app.parda.ui.plural
import app.parda.ui.str
import app.parda.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.parda.ui.title
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
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
        fileNote = str(R.string.air_reading_image)
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
            }.onFailure {
                android.util.Log.w("PardaOCR", "could not read image", it)
                fileNote = str(R.string.air_image_failed)
            }
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
            fileNote = str(R.string.air_reading)
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
                    else fileNote = (it as? DocumentReader.Unsupported)?.message ?: str(R.string.air_file_failed)
                }
            }
        }
    }

    ScreenColumn {
        Column {
            Text(stringResource(R.string.nav_airlock), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.asHeading())
            Text(stringResource(R.string.air_sub), style = MaterialTheme.typography.bodyLarge, color = Frost.Ink2)
        }

        val installed = remember(model) { store.installedModels().map { it.nameWithoutExtension to it.length() } }
        ModelCard(
            model, installed,
            onImport = { pickModel.launch(arrayOf("*/*")) },
            onPick = { name -> scope.launch(Dispatchers.IO) { store.useModel(name) } },
        )

        image?.let { img ->
            ImageCard(img, imageName, onClose = { image = null })
            return@ScreenColumn
        }

        GlassCard(padding = 16.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(stringResource(R.string.air_inside))
                Pill(stringResource(R.string.air_open_file), onClick = { pickFile.launch(DocumentReader.MIME_TYPES + "image/*") })
            }
            fileNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Frost.Ink2) }
            OutlinedTextField(
                value = document,
                onValueChange = { document = it; decision = null },
                // Capped so a long document scrolls inside the box instead of pushing the
                // question and the Ask button off the bottom of the screen.
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                label = { Text(stringResource(R.string.air_text_label)) },
                colors = fieldColors(),
            )
            OutlinedTextField(
                value = request,
                onValueChange = { request = it; decision = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.air_question_label)) },
                placeholder = { Text(stringResource(R.string.air_question_hint)) },
                colors = fieldColors(),
            )
            // Questions that fit whatever is loaded: a statement, a lease, a payroll export, an ID.
            val suggestions = remember(document) { SuggestedQuestions.of(document) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { q -> Pill(q.label, strong = request == q.request, onClick = { request = q.request; decision = null }) }
            }
            PrimaryButton(
                if (thinking) stringResource(R.string.air_thinking) else stringResource(R.string.air_ask),
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
                        val planner = d.plannedBy.title +
                            if (d.plannedBy == Planner.MODEL) " (${modelName(model) ?: "model"})" else ""
                        decision = d
                        thinking = false
                        when (d) {
                            is GateDecision.HeldLocally -> store.record(
                                Channel.B, Verdict.HELD_LOCALLY,
                                "${d.result.detections.size} sensitive item(s) classified; answered on the device by $planner, nothing prepared to send",
                            )
                            is GateDecision.HandedBack -> {
                                store.record(
                                    Channel.B, Verdict.HANDED_BACK,
                                    "${d.result.vault.withheld} item(s) withheld (${d.result.blockedCount} blocked); planned by $planner; sanitized copy prepared, nothing transmitted",
                                    masked = d.result.vault.withheld,
                                )
                                // Named by the safe copy's first line, so the title itself holds no real value.
                                val title = d.outbound.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
                                withContext(Dispatchers.IO) { store.vaults.remember(title, d.result.vault) }
                            }
                        }
                    }
                },
            )
        }

        when (val d = decision) {
            null -> if (thinking && streamed.isNotBlank()) {
                GlassCard(padding = 16.dp) {
                    SectionLabel(stringResource(R.string.air_writing))
                    Text(streamed, style = MaterialTheme.typography.bodyLarge)
                }
            } else if (!thinking) {
                ReplyCard()
            }
            is GateDecision.HeldLocally -> {
                NightCard {
                    SectionLabel(stringResource(R.string.air_answered), Frost.NightInk2)
                    Text(stringResource(R.string.air_nothing_left), color = Color.White, style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.air_planned_by, d.plannedBy.title), color = Frost.NightInk2, style = MaterialTheme.typography.bodyMedium)
                }
                GlassCard(padding = 16.dp) {
                    SectionLabel(stringResource(R.string.air_answer))
                    SelectionContainer { Text(d.answer, style = MaterialTheme.typography.bodyLarge) }
                }
            }
            is GateDecision.HandedBack -> {
                NightCard {
                    SectionLabel(stringResource(R.string.air_handed_back), Frost.NightInk2)
                    Text(
                        stringResource(R.string.air_handed_back_expl, d.reason.explanationText),
                        color = Color.White, style = MaterialTheme.typography.bodyLarge,
                    )
                }
                GlassCard(padding = 16.dp) {
                    SectionLabel(stringResource(R.string.air_take_outside, d.result.vault.withheld))
                    if (preview != null && document == preview && (fullText?.length ?: 0) > document.length) {
                        Text(stringResource(R.string.air_copy_is_box), style = MaterialTheme.typography.bodyMedium, color = Frost.Ink2)
                    }
                    Mono(d.outbound)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Pill(stringResource(R.string.copy), strong = true, onClick = { clipboard.setText(AnnotatedString(d.outbound)) })
                        Pill(stringResource(R.string.share_dots), onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, d.outbound)
                            context.startActivity(Intent.createChooser(send, str(R.string.air_send_sanitized)))
                        })
                    }
                }
                GlassCard(padding = 16.dp) {
                    SectionLabel(stringResource(R.string.air_vault))
                    val entries = d.result.vault.entries
                    entries.take(VAULT_ROWS).forEach { e ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(e.token, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                            Text(e.real, style = MaterialTheme.typography.bodyMedium, color = Frost.WarnInk)
                        }
                    }
                    if (entries.size > VAULT_ROWS) {
                        Text(stringResource(R.string.air_vault_more, entries.size - VAULT_ROWS), style = MaterialTheme.typography.bodyMedium, color = Frost.Ink2)
                    }
                }
                GlassCard(padding = 16.dp) {
                    SectionLabel(stringResource(R.string.air_bring_back))
                    Text(
                        stringResource(R.string.air_no_rush),
                        style = MaterialTheme.typography.bodyMedium, color = Frost.Ink2,
                    )
                    OutlinedTextField(
                        value = reply,
                        onValueChange = { reply = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                        label = { Text(stringResource(R.string.air_paste_answer)) },
                        colors = fieldColors(),
                    )
                    if (reply.isNotBlank()) {
                        SectionLabel(stringResource(R.string.air_reads_back))
                        SelectionContainer { Text(d.result.vault.rehydrate(reply), style = MaterialTheme.typography.bodyLarge) }
                    }
                }
                QuietButton(stringResource(R.string.air_start_over), onClick = { decision = null; reply = "" })
            }
        }
    }
}

/**
 * Brings an outside assistant's answer back after the fact: the placeholders it mentions are
 * matched to the copy it answers, and the real names return, on this phone only.
 */
@Composable
private fun ReplyCard() {
    val context = LocalContext.current
    val store = context.store
    val clipboard = LocalClipboardManager.current
    val archive by store.vaults.archive.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { store.vaults.refresh() } }
    if (archive.handBacks.isEmpty()) return
    var reply by rememberSaveable { mutableStateOf("") }

    GlassCard(padding = 16.dp) {
        SectionLabel(stringResource(R.string.air_bring_a_reply))
        val n = archive.handBacks.size
        Text(
            pluralStringResource(R.plurals.pl_copies_kept, n, n) + " " + stringResource(R.string.air_reply_hint),
            style = MaterialTheme.typography.bodyMedium, color = Frost.Ink2,
        )
        OutlinedTextField(
            value = reply,
            onValueChange = { reply = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
            label = { Text(stringResource(R.string.air_outside_answer)) },
            colors = fieldColors(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill(stringResource(R.string.paste), onClick = { reply = clipboard.getText()?.text.orEmpty() })
            Pill(stringResource(R.string.air_forget_all), onClick = { store.vaults.forgetAll(); reply = "" })
        }
        if (reply.isNotBlank()) {
            val match = archive.bestFor(reply)
            if (match == null) {
                Text(stringResource(R.string.air_no_placeholders), style = MaterialTheme.typography.bodyMedium, color = Frost.Ink2)
            } else {
                val restored = match.vault.rehydrate(reply)
                SectionLabel(stringResource(R.string.air_reads_back_from, match.title.take(40), DateUtils.getRelativeTimeSpanString(match.at)))
                SelectionContainer { Text(restored, style = MaterialTheme.typography.bodyLarge) }
                Pill(stringResource(R.string.copy), strong = true, onClick = { clipboard.setText(AnnotatedString(restored)) })
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
            SectionLabel(stringResource(R.string.air_masked_copy_title))
            Pill(stringResource(R.string.close), onClick = onClose)
        }
        Text(stringResource(R.string.air_read_on_phone_name, name), style = MaterialTheme.typography.bodyMedium, color = Frost.Ink2)
        Image(
            preview.asImageBitmap(), contentDescription = stringResource(R.string.air_masked_preview_of, name),
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(18.dp)),
            contentScale = ContentScale.Fit,
        )
        if (plan.fields.isEmpty()) {
            Text(
                stringResource(R.string.air_no_data_in_image),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        plan.categories.forEach { c ->
            val fields = plan.fields.filter { it.category == c }
            val label = c.title + (if (fields.any { it.keepsLast4 }) stringResource(R.string.air_keep_last_4) else "") + " · ${fields.size}"
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
        PrimaryButton(stringResource(R.string.air_share_masked), enabled = plan.fields.isNotEmpty(), onClick = {
            val uri = ImageAirlock.export(context, preview)
            val count = plan.fields.count { it.category in masked }
            store.record(
                Channel.B, Verdict.HANDED_BACK,
                "Covered $count field(s) on an image; masked copy handed to you, original untouched",
                masked = count,
            )
            val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, str(R.string.air_send_masked_image)))
        })
    }
}

/** Which planner is on duty. The model file only ever arrives by hand: nothing is downloaded. */
@Composable
private fun ModelCard(model: ModelStatus, installed: List<Pair<String, Long>>, onImport: () -> Unit, onPick: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    GlassCard(padding = 16.dp) {
        SectionLabel(stringResource(R.string.air_local_model))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val (title, detail) = when (model) {
                    is ModelStatus.Resting -> model.name to stringResource(R.string.air_model_resting) +
                        if (installed.size > 1) stringResource(R.string.air_models_installed, installed.size) else ""
                    is ModelStatus.Ready -> model.name to stringResource(R.string.air_model_ready, model.loadMillis.toInt()) +
                        if (installed.size > 1) stringResource(R.string.air_models_installed, installed.size) else ""
                    is ModelStatus.Loading -> model.name to stringResource(R.string.air_model_loading)
                    is ModelStatus.Failed -> model.name to stringResource(R.string.air_model_failed, model.reason)
                    ModelStatus.Missing -> stringResource(R.string.air_rule_agent) to stringResource(R.string.air_import_hint)
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = Frost.Ink2)
            }
            if (model is ModelStatus.Missing || model is ModelStatus.Failed) {
                Pill(stringResource(R.string.air_import), strong = true, onClick = onImport)
            } else if ((model is ModelStatus.Ready || model is ModelStatus.Resting) && installed.size > 1) {
                Box {
                    Pill(stringResource(R.string.air_switch), onClick = { menu = true })
                    // Every installed model, in the benchmark's order; the one running is ticked.
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        installed.forEach { (name, bytes) ->
                            DropdownMenuItem(
                                text = { Text((if (name == modelName(model)) "✓ " else "") + name + " · " + "%.1f GB".format(bytes / 1e9)) },
                                onClick = { menu = false; if (name != modelName(model)) onPick(name) },
                            )
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.air_import_another)) }, onClick = { menu = false; onImport() })
                    }
                }
            }
        }
    }
}

/** Says plainly how much of the file was read and what each part of the screen covers. */
private fun fileNote(r: DocumentReader.Result): String {
    val parts = mutableListOf(r.name)
    r.pages?.let { parts += plural(R.plurals.pl_pages, it) }
    parts += str(R.string.air_read_on_phone)
    if (r.truncated) {
        parts += if (r.complete) str(R.string.air_whole_file)
        else str(R.string.air_reading_rest)
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

/** The model the agent will use, whether it is loaded right now or resting. */
private fun modelName(model: ModelStatus): String? = when (model) {
    is ModelStatus.Ready -> model.name
    is ModelStatus.Resting -> model.name
    else -> null
}
