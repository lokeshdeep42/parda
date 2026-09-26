package app.parda.core

import app.parda.core.checkout.DarkPatternScanner
import app.parda.core.checkout.ScreenNode
import java.io.File
import kotlin.test.Test

/**
 * Replays the shield's debug log from a phone against the current scanner, so a miss seen in a
 * real app can be checked on the laptop without going back to the phone:
 *
 *   adb logcat -d -s PardaShield:I > shield.log
 *   PARDA_REPLAY=shield.log gradle :core:test --tests '*ReplayTest*' -i
 *
 * Does nothing unless PARDA_REPLAY is set.
 */
class ReplayTest {
    @Test fun `replay a shield log`() {
        val file = System.getenv("PARDA_REPLAY")?.let(::File)?.takeIf { it.isFile } ?: return
        val scanner = DarkPatternScanner()
        val seen = HashSet<String>()
        for ((app, lines) in screens(file.readLines())) {
            val scan = scanner.scan(tree(lines))
            val summary = scan.findings.joinToString("\n") { "    ${it.kind} fixable=${it.fixable} cost=${it.cost}: ${it.evidence}" }
            if (seen.add(app + summary)) println("REPLAY $app: checkout=${scan.isCheckout}, ${scan.findings.size} finding(s)\n$summary")
        }
    }

    /** Splits the log into screens: each starts with "checkout in <package>". */
    private fun screens(log: List<String>): List<Pair<String, List<String>>> {
        val out = mutableListOf<Pair<String, MutableList<String>>>()
        for (raw in log) {
            val line = raw.substringAfter("PardaShield: ", "")
            if (line.startsWith("checkout in ")) out += line.removePrefix("checkout in ") to mutableListOf()
            else if (out.isNotEmpty() && !line.startsWith("finding ")) out.last().second += line
        }
        return out
    }

    /** Rebuilds the tree from "0.3.1 [x] label" lines; unlabelled parents are implied by the ids. */
    private fun tree(lines: List<String>): ScreenNode {
        data class Raw(var label: String = "", var checkable: Boolean = false, var checked: Boolean = false)
        val nodes = linkedMapOf<String, Raw>()
        var last: Raw? = null
        for (line in lines) {
            val m = LINE.matchEntire(line.trim())
            if (m == null) { last?.let { it.label += "\n" + line.trim() }; continue }
            val (id, box, label) = m.destructured
            val parts = id.split('.')
            parts.indices.forEach { i -> nodes.getOrPut(parts.take(i + 1).joinToString(".")) { Raw() } }
            last = nodes.getValue(id).also { it.label = label; it.checkable = box.isNotEmpty(); it.checked = box == "[x] " }
        }
        val children = nodes.keys.groupBy { it.substringBeforeLast('.', "") }
        fun build(id: String): ScreenNode {
            val r = nodes.getValue(id)
            return ScreenNode(id, text = r.label.ifEmpty { null }, checkable = r.checkable, checked = r.checked, children = children[id].orEmpty().map(::build))
        }
        return build(nodes.keys.firstOrNull() ?: return ScreenNode("0"))
    }

    private companion object {
        val LINE = Regex("""^(\d+(?:\.\d+)*) (\[[x ]\] )?(.*)$""")
    }
}
