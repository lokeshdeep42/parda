package app.parda.data

import android.content.Context
import android.net.TrafficStats
import android.net.Uri
import android.os.Process
import app.parda.core.agent.DisclosureGate
import app.parda.core.agent.ModelAgent
import app.parda.core.agent.RuleBasedAgent
import app.parda.core.checkout.DarkPatternScanner
import app.parda.core.disclosure.Sanitizer
import app.parda.core.ledger.Channel
import app.parda.core.ledger.Ledger
import app.parda.core.ledger.LedgerEntry
import app.parda.core.ledger.Verdict
import app.parda.core.policy.Policy
import app.parda.llm.LlamaEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Everything Parda keeps, all of it in app-private storage on this device: the policy, the
 * ledger and the onboarding flag. There is no sync and no account.
 */
class PardaStore(context: Context) {
    private val prefs = context.getSharedPreferences("parda", Context.MODE_PRIVATE)
    private val ledgerFile = File(context.filesDir, "ledger.jsonl")
    private val json = Json { ignoreUnknownKeys = true }

    val sanitizer = Sanitizer()
    val scanner = DarkPatternScanner()

    private val nativeLibDir = context.applicationInfo.nativeLibraryDir
    private val importDir = File(context.filesDir, "models")
    /** `adb push model.gguf /sdcard/Android/data/app.parda/files/models/` lands here. */
    private val pushDir: File? = context.getExternalFilesDir("models")
    private val contentResolver = context.contentResolver

    @Volatile private var engine: LlamaEngine? = null
    private val _model = MutableStateFlow<ModelStatus>(ModelStatus.Missing)
    val model: StateFlow<ModelStatus> = _model.asStateFlow()

    /** The on-device model when one is loaded; the rule-based agent otherwise. */
    val gate: DisclosureGate
        get() = DisclosureGate(engine?.let(::ModelAgent) ?: RuleBasedAgent(), sanitizer = sanitizer)

    private val _policy = MutableStateFlow(loadPolicy())
    val policy: StateFlow<Policy> = _policy.asStateFlow()

    private val ledger: Ledger = runCatching {
        if (ledgerFile.exists()) Ledger.fromJsonLines(ledgerFile.readText()) else Ledger()
    }.getOrElse { Ledger() }
    private val _entries = MutableStateFlow(ledger.all)
    val entries: StateFlow<List<LedgerEntry>> = _entries.asStateFlow()

    private val _onboarded = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDED, false))
    val onboarded: StateFlow<Boolean> = _onboarded.asStateFlow()

    fun setPolicy(policy: Policy) {
        _policy.value = policy
        prefs.edit().putString(KEY_POLICY, json.encodeToString(Policy.serializer(), policy)).apply()
    }

    fun setOnboarded() {
        _onboarded.value = true
        prefs.edit().putBoolean(KEY_ONBOARDED, true).apply()
    }

    @Synchronized
    fun record(
        channel: Channel,
        verdict: Verdict,
        detail: String,
        savedPaise: Long = 0,
        masked: Int = 0,
        patterns: Int = 0,
    ) {
        ledger.append(channel, verdict, detail, savedPaise, masked, patterns)
        ledgerFile.writeText(ledger.toJsonLines())
        _entries.value = ledger.all
    }

    /** Every .gguf on the phone, best first: the user's pick, then the benchmark's ranking. */
    fun installedModels(): List<File> {
        val all = listOfNotNull(importDir, pushDir)
            .flatMap { it.listFiles { f -> f.extension.equals("gguf", ignoreCase = true) }.orEmpty().toList() }
            .distinctBy { it.name }
        val chosen = prefs.getString(KEY_MODEL, null)
        return all.sortedWith(
            compareBy<File> { it.nameWithoutExtension != chosen }
                .thenBy { f -> RANKED.indexOfFirst { it in f.name.lowercase() }.let { if (it < 0) RANKED.size else it } }
                .thenByDescending { it.lastModified() },
        )
    }

    /** Loads the next installed model and remembers it. Blocking: call off the main thread. */
    fun switchModel() {
        val models = installedModels()
        if (models.size < 2) return
        val current = (_model.value as? ModelStatus.Ready)?.name
        val next = models[(models.indexOfFirst { it.nameWithoutExtension == current } + 1) % models.size]
        prefs.edit().putString(KEY_MODEL, next.nameWithoutExtension).apply()
        loadModel()
    }

    /**
     * Finds a .gguf on the phone and loads it. Blocking and slow (seconds): call off the main
     * thread. Nothing is downloaded; the model gets here by `adb push` or [importModel].
     */
    @Synchronized
    fun loadModel() {
        val file = installedModels().firstOrNull() ?: run { _model.value = ModelStatus.Missing; return }
        if ((_model.value as? ModelStatus.Ready)?.name == file.nameWithoutExtension) return

        _model.value = ModelStatus.Loading(file.nameWithoutExtension)
        val started = System.currentTimeMillis()
        runCatching { LlamaEngine.load(nativeLibDir, file) }
            .onSuccess { loaded ->
                engine?.close()
                engine = loaded
                _model.value = ModelStatus.Ready(loaded.modelName, System.currentTimeMillis() - started)
            }
            .onFailure { _model.value = ModelStatus.Failed(file.nameWithoutExtension, it.message ?: "load failed") }
    }

    /** Copies a .gguf picked with the system file picker into app storage, then loads it. Blocking. */
    fun importModel(uri: Uri, displayName: String) {
        _model.value = ModelStatus.Loading(displayName.substringBeforeLast('.'))
        runCatching {
            importDir.mkdirs()
            val target = File(importDir, displayName.takeIf { it.endsWith(".gguf", true) } ?: "$displayName.gguf")
            contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        }.onFailure {
            _model.value = ModelStatus.Failed(displayName, it.message ?: "copy failed")
            return
        }
        loadModel()
    }

    fun totals(): Ledger.Totals = ledger.totals()

    fun ledgerIntact(): Boolean = ledger.verify() == -1

    /** Bytes this app's UID has sent over any network since boot, as counted by the OS. */
    fun egressBytes(): Long = TrafficStats.getUidTxBytes(Process.myUid()).coerceAtLeast(0)

    private fun loadPolicy(): Policy = prefs.getString(KEY_POLICY, null)
        ?.let { runCatching { json.decodeFromString(Policy.serializer(), it) }.getOrNull() }
        ?: Policy()

    private companion object {
        const val KEY_POLICY = "policy"
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_MODEL = "model"

        /**
         * Measured on an iQOO (SM8850) with ModelBench: Hammer Q4_0 plans best for its speed
         * (10/12, 4.3 s, 39 tok/s); Qwen2.5 3B plans perfectly but takes 12 s; Gemma 3 1B
         * misroutes outside-knowledge questions and drops facts from answers.
         */
        val RANKED = listOf("hammer2.1-1.5b-q4_0", "hammer", "qwen2.5-3b", "qwen", "gemma")
    }
}

/** Which planner Channel B is using. */
sealed interface ModelStatus {
    /** No model on the phone: the rule-based agent plans. */
    data object Missing : ModelStatus
    data class Loading(val name: String) : ModelStatus
    data class Ready(val name: String, val loadMillis: Long) : ModelStatus
    data class Failed(val name: String, val reason: String) : ModelStatus
}
