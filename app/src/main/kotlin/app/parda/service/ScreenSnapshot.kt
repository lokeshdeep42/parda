package app.parda.service

import android.view.accessibility.AccessibilityNodeInfo
import app.parda.core.checkout.ScreenNode

/**
 * Converts the live accessibility tree into the core's [ScreenNode] model. Node ids are child
 * index paths ("0.3.1") so a node can be found again for the one action Parda may take.
 */
object ScreenSnapshot {
    private const val MAX_NODES = 800

    fun capture(root: AccessibilityNodeInfo): ScreenNode {
        var count = 0
        fun build(n: AccessibilityNodeInfo, path: String): ScreenNode {
            count++
            val children = (0 until n.childCount).mapNotNull { i ->
                if (count >= MAX_NODES) null else n.getChild(i)?.let { build(it, "$path.$i") }
            }
            return ScreenNode(
                id = path,
                text = n.text?.toString(),
                contentDescription = n.contentDescription?.toString(),
                checkable = n.isCheckable,
                checked = n.isCheckedCompat,
                children = children,
            )
        }
        return build(root, "0")
    }

    /** isChecked is deprecated from API 36 in favour of a tri-state; a boolean is all Parda needs. */
    @Suppress("DEPRECATION")
    val AccessibilityNodeInfo.isCheckedCompat: Boolean get() = isChecked

    /** Indented outline of a snapshot: id, checkbox state and label. For debug logs only. */
    fun dump(node: ScreenNode, depth: Int = 0): String = buildString {
        val box = if (node.checkable) (if (node.checked) "[x] " else "[ ] ") else ""
        if (node.label.isNotBlank() || node.checkable) append("  ".repeat(depth)).append(node.id).append(' ').append(box).append(node.label).append('\n')
        node.children.forEach { append(dump(it, depth + 1)) }
    }

    fun resolve(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? =
        id.split('.').drop(1).fold(root as AccessibilityNodeInfo?) { node, i -> node?.getChild(i.toInt()) }
}
