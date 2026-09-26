# Parda

A local agent that governs what leaves your phone: your **data** and your **money**.
Parda stands at both boundaries under one policy you own, and it declares no network
permission, so it cannot transmit anything itself.

Team GOLD · IQOO Hackathon

| Channel | Boundary | What Parda does |
|---|---|---|
| **A** | Money | Reads checkout screens through an AccessibilityService, finds dark patterns (pre-ticked add-ons, subscription traps, last-step fees, fake urgency, confirmshaming), pauses the checkout and offers to untick what you didn't choose. It never taps Pay. |
| **B** | Data | Classifies personal data in text you are about to send. If the on-device agent can answer, nothing leaves. If not, it prepares a sanitized copy with surrogates (`<AMOUNT_1>`) for you to take elsewhere by hand, and restores real values in the reply. |

## Design principle

**The agent plans and classifies; deterministic code decides and executes.**

- The model's output is constrained by a GBNF grammar (`AgentGrammar.GBNF`) to one of two
  function calls with enumerated arguments. It cannot emit free text, so it cannot carry data.
- Anything that leaves (the sanitized copy) is produced by `Sanitizer`, never by the model. A
  hostile or broken model yields a wrong suggestion, never a wrong disclosure. See
  `GateTest.a hostile model cannot change what is handed back`.
- On screen, `CheckoutGate.mayUntick` only authorises unticking a box the scanner itself
  identified. There is no code path that clicks anything else.
- `android.permission.INTERNET` is removed at manifest-merge time (`tools:node="remove"`), and CI
  fails if the built APK declares it. The Ledger screen reads the installed manifest and the OS
  byte counter (`TrafficStats`) so the "0 bytes sent" claim is checked, not asserted.
- Every decision goes into an append-only, SHA-256 hash-chained ledger. Entries hold counts and
  categories, never the sensitive values.

## Layout

```
core/   Pure Kotlin/JVM. All of the logic, fully unit-tested.
  policy/      Policy: data categories, dark-pattern kinds, and the user's action for each
  detect/      Detectors: Aadhaar (Verhoeff), PAN, cards (Luhn), accounts, phone, email,
               amounts, names, addresses, DOB. Overlap resolution by priority.
  disclosure/  Sanitizer + Vault: block / surrogate / keep-last-4 / allow; rehydrate replies
  agent/       LocalAgent interface, GBNF grammar + fail-closed parser, RuleBasedAgent
               fallback, DisclosureGate (Channel B)
  checkout/    ScreenNode tree, DarkPatternScanner, CheckoutGate (Channel A), Money
  ledger/      Hash-chained Ledger
app/    Android (Kotlin, Jetpack Compose), "Frost" design direction.
  service/     CheckoutWatchService (AccessibilityService), ScreenSnapshot, InterceptActivity
  ProcessTextActivity   "Mask with Parda" in any app's text-selection menu, replaces in place
  ui/screens/  Home, Firewall (policy), Airlock (Channel B), Ledger, Onboarding
index.html   The original single-file web prototype of both channels.
```

## Build

Requirements: JDK 17+, Android SDK (API 35). The Gradle plugin installs the NDK and CMake on
first build. llama.cpp is compiled from source and is expected next to this repo:

```sh
git clone https://github.com/ggml-org/llama.cpp ../llama.cpp   # or -Pparda.llamaDir=<path>
```

```sh
./gradlew :core:test                 # logic tests, no Android SDK needed
./gradlew :app:assembleDebug         # APK at app/build/outputs/apk/debug/
./gradlew -Pparda.coreOnly=true :core:test   # on a machine with no Android SDK at all
```

CI (`.github/workflows/build.yml`) runs the core tests, builds the APK, checks it declares no
network permission, and uploads it as an artifact.

## On-device model

Channel B plans with a local LLM when one is on the phone, and falls back to the rule-based
agent when not. Parda never downloads it. Put a GGUF on the phone by hand, either:

```sh
adb push Hammer2.1-1.5b-Q4_K_M.gguf /sdcard/Android/data/app.parda/files/models/
```

or **Airlock → Local model → Import** and pick the file. The model loads at app start
(`ModelStatus` on the Airlock card). Planning runs under `AgentGrammar.GBNF`, so the model can
only emit one of the two enumerated calls; the answer to a local task is shown on screen only.
Load and per-request timings are logged under the Logcat tag `PardaLLM`.

Code: `core/.../agent/ModelAgent.kt` (prompt + agent, tested with a fake engine) and
`app/src/main/cpp/parda_llm.cpp` + `app/.../llm/LlamaEngine.kt` (JNI to llama.cpp).

## Try it

1. Install, open Parda, and turn on **Accessibility → Parda checkout shield** from onboarding.
2. **Home → Try a demo checkout** opens a stand-in store with every pattern; the shield scans it
   like any other app (Parda's own screens are otherwise never scanned). Or open any shopping or
   food-delivery checkout. Pre-ticked protection plans, donations and
   trials trigger the "Parda paused this checkout" sheet; fees and timers are flagged.
3. **Airlock** tab: the sample salary letter is loaded. *Summarise* is answered on the device;
   *Am I underpaid?* is handed back sanitized. Paste a reply that uses `<AMOUNT_1>` to see it
   restored locally. **Open file** (or share a file to Parda) reads PDF, Word (.docx), Excel
   (.xlsx), CSV and text on the device; only the extracted text enters the Airlock.
4. In any app, select text in a message box → **Mask with Parda**, and the selection is
   replaced with its masked form before you send it.
5. **Firewall** tab: tap any action pill to cycle it. One policy drives both channels.

## Status and next steps

Done: the core engine for both channels, with tests; the Android app shell, accessibility
service, intercept sheet, text-selection masking, share target, policy editor and ledger.

Also done: the on-device model (llama.cpp, GBNF-constrained planning), with the rule-based agent
as fallback.

Next:
- Measure Hammer2.1-1.5B plan/answer latency on the target phone; tune the prompt if it
  misroutes the demo questions.
- **Airlock for images.** Mask ID numbers and addresses in photos before sharing, using an
  on-device OCR model bundled in the APK.
- Tune the checkout scanner against real apps' accessibility trees, and add app-specific rules
  where row grouping differs.
- The Manrope typeface from the design is not bundled yet; the system sans is used.
