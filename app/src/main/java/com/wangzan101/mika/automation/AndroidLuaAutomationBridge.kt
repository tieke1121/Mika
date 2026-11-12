package com.wangzan101.mika.automation

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.core.automation.AutomationTargetPolicy
import com.wangzan101.mika.core.automation.LuaAutomationBridge
import java.util.concurrent.atomic.AtomicBoolean

/** 在用户主屏上操作好医生；离开目标包或碰到受保护节点时 fail closed。 */
internal class AndroidLuaAutomationBridge(
    private val context: Context,
    private val policy: AutomationTargetPolicy,
    private val cancelled: AtomicBoolean,
    private val onStatus: (String) -> Unit,
) : LuaAutomationBridge {
    override fun isCancelled(): Boolean = cancelled.get()

    override fun launch(packageName: String): Boolean {
        if (!policy.allowsPackage(packageName)) return false
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    override fun currentPackage(): String? = runCatching { root()?.packageName?.toString() }.getOrNull()

    override fun hasProtectedScreen(): Boolean = runCatching {
        val root = targetRoot() ?: return@runCatching true
        isProtectedScreen(root)
    }.getOrDefault(true)

    override fun existsById(resourceId: String): Boolean = runCatching {
        nodes().any { it.viewIdResourceName == resourceId }
    }.getOrDefault(false)

    override fun existsByText(text: String): Boolean = runCatching {
        nodes().any { node -> node.text?.toString() == text || node.contentDescription?.toString() == text }
    }.getOrDefault(false)

    override fun textById(resourceId: String): String? = runCatching {
        nodes().firstOrNull { it.viewIdResourceName == resourceId }
            ?.let { it.text ?: it.contentDescription }?.toString()
    }.getOrNull()

    override fun clickById(resourceId: String): Boolean {
        if (!policy.allowsResourceClick(resourceId)) return false
        return click(preferOutermost = false) { it.viewIdResourceName == resourceId }
    }

    override fun clickByText(text: String): Boolean {
        if (!policy.allowsTextClick(text)) return false
        return click(preferOutermost = false) { node ->
            node.text?.toString() == text || node.contentDescription?.toString() == text
        }
    }

    override fun clickRowByText(text: String): Boolean = runCatching {
        if (!policy.allowsRowClick(text)) return@runCatching false
        val root = targetRoot() ?: return@runCatching false
        if (isProtectedScreen(root)) {
            onStatus("检测到需要本人处理的步骤")
            return@runCatching false
        }
        for (matched in descendants(root).filter { it.isVisibleToUser && it.text?.toString() == text }) {
            var ancestor: AccessibilityNodeInfo? = matched
            repeat(MAX_ANCESTORS + 1) {
                val candidate = ancestor
                if (candidate?.isClickable == true && candidate.isEnabled && descendants(candidate).any(::isCourseTitle)) {
                    if (isProtected(candidate)) {
                        onStatus("需要手动处理受保护步骤")
                        return@runCatching false
                    }
                    if (candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@runCatching true
                }
                ancestor = candidate?.parent
            }
        }
        false
    }.getOrDefault(false)

    override fun courseListState(): String = runCatching {
        val root = targetRoot() ?: return@runCatching COURSE_UNKNOWN
        if (isProtectedScreen(root)) return@runCatching COURSE_UNKNOWN
        val statuses = courseListSnapshot(root)?.statuses ?: return@runCatching COURSE_UNKNOWN
        when {
            statuses.any { it == "学习中" || it == "未学习" } -> COURSE_INCOMPLETE
            statuses.all { it in COMPLETED_COURSE_STATUSES } -> COURSE_COMPLETE
            else -> COURSE_UNKNOWN
        }
    }.getOrDefault(COURSE_UNKNOWN)

    override fun courseListWindow(): String? = runCatching {
        val root = targetRoot() ?: return@runCatching null
        if (isProtectedScreen(root)) return@runCatching null
        val snapshot = courseListSnapshot(root) ?: return@runCatching null
        "${snapshot.firstRow},${snapshot.lastRow},${snapshot.totalRows}"
    }.getOrNull()

    override fun canApplyCredit(): Boolean = runCatching {
        val root = targetRoot() ?: return@runCatching false
        if (isProtectedScreen(root)) return@runCatching false
        val snapshot = courseListSnapshot(root) ?: return@runCatching false
        if (snapshot.lastRow != snapshot.totalRows - 1) return@runCatching false
        if (snapshot.statuses.any { it !in COMPLETED_COURSE_STATUSES }) return@runCatching false
        descendants(root).any { node ->
            node.isVisibleToUser && node.isEnabled && node.viewIdResourceName == CREDIT_APPLY_ID &&
                clickableAncestor(node, preferOutermost = false)?.isEnabled == true
        }
    }.getOrDefault(false)

    override fun scrollBackward(): Boolean = scrollCourseList(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)

    override fun scrollForward(): Boolean = scrollCourseList(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)

    override fun back(): Boolean {
        val service = service() ?: return false
        val root = targetRoot() ?: return false
        if (isProtectedScreen(root)) return false
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    override fun sleep(milliseconds: Long) {
        var remaining = milliseconds.coerceAtLeast(0)
        while (remaining > 0 && !cancelled.get()) {
            val slice = minOf(remaining, SLEEP_SLICE_MS)
            try {
                Thread.sleep(slice)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                cancelled.set(true)
                return
            }
            remaining -= slice
        }
    }

    override fun status(message: String) = onStatus(message)

    private fun scrollCourseList(action: Int): Boolean = runCatching {
        val root = targetRoot() ?: return@runCatching false
        if (isProtectedScreen(root)) return@runCatching false
        courseListNode(root)?.performAction(action) == true
    }.getOrDefault(false)

    private fun courseListNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? = descendants(root).firstOrNull { node ->
        node.isVisibleToUser && node.isScrollable && descendants(node).any(::isCourseTitle) &&
            descendants(node).any { it.viewIdResourceName == COURSE_STATUS_ID }
    }

    private fun courseListSnapshot(root: AccessibilityNodeInfo): CourseListSnapshot? {
        val list = courseListNode(root) ?: return null
        val total = list.collectionInfo?.rowCount?.takeIf { it > 0 } ?: return null
        val scan = scanNodes(list, MAX_NODES)
        if (scan.truncated) return null
        val rowStatuses = mutableListOf<Pair<Int, String>>()
        for (node in scan.nodes) {
            if (!node.isVisibleToUser || node.viewIdResourceName != COURSE_STATUS_ID) continue
            val row = collectionRow(node, list) ?: return null
            val status = node.text?.toString()?.trim()?.takeIf(String::isNotEmpty) ?: return null
            rowStatuses += row to status
        }
        rowStatuses.sortBy { it.first }
        if (rowStatuses.isEmpty() || rowStatuses.map(Pair<Int, String>::first).distinct().size != rowStatuses.size) {
            return null
        }
        val first = rowStatuses.first().first
        val last = rowStatuses.last().first
        if (first < 0 || last >= total || rowStatuses.size != last - first + 1) return null
        return CourseListSnapshot(first, last, total, rowStatuses.map(Pair<Int, String>::second))
    }

    private fun collectionRow(start: AccessibilityNodeInfo, list: AccessibilityNodeInfo): Int? {
        var node: AccessibilityNodeInfo? = start
        repeat(MAX_ANCESTORS + 1) {
            node?.collectionItemInfo?.let { return it.rowIndex }
            if (node == list) return null
            node = node?.parent
        }
        return null
    }

    private fun click(preferOutermost: Boolean, match: (AccessibilityNodeInfo) -> Boolean): Boolean {
        return runCatching {
            val root = targetRoot() ?: return@runCatching false
            if (isProtectedScreen(root)) {
                onStatus("检测到需要本人处理的步骤")
                return@runCatching false
            }
            for (node in descendants(root).filter { it.isVisibleToUser }.filter(match)) {
                val clickable = clickableAncestor(node, preferOutermost) ?: continue
                if (!clickable.isEnabled || isProtected(clickable)) {
                    onStatus("需要手动处理受保护步骤")
                    continue
                }
                if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@runCatching true
            }
            false
        }.getOrDefault(false)
    }

    private fun targetRoot(): AccessibilityNodeInfo? = root()?.takeIf {
        policy.allowsPackage(it.packageName?.toString())
    }

    private fun root(): AccessibilityNodeInfo? = service()?.rootInActiveWindow
    private fun service(): ScreenAccessibility? = ScreenAccessibility.instance.value

    private fun nodes(): Sequence<AccessibilityNodeInfo> = targetRoot()?.let(::descendants) ?: emptySequence()

    private fun descendants(root: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = scanNodes(root, MAX_NODES).nodes.asSequence()

    private fun scanNodes(root: AccessibilityNodeInfo, limit: Int): NodeScan {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        val nodes = ArrayList<AccessibilityNodeInfo>(minOf(limit, 128))
        while (queue.isNotEmpty() && nodes.size < limit) {
            val node = queue.removeFirst()
            nodes += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
        }
        return NodeScan(nodes, queue.isNotEmpty())
    }

    private fun clickableAncestor(start: AccessibilityNodeInfo, preferOutermost: Boolean): AccessibilityNodeInfo? {
        var node: AccessibilityNodeInfo? = start
        var found: AccessibilityNodeInfo? = null
        repeat(MAX_ANCESTORS + 1) {
            if (node?.isClickable == true) {
                found = node
                if (!preferOutermost) return node
            }
            node = node?.parent
        }
        return found
    }

    private fun isProtected(node: AccessibilityNodeInfo): Boolean {
        val scan = scanNodes(node, PROTECTED_SCAN_NODES)
        return scan.truncated || scan.nodes.any {
            policy.isProtected(it.viewIdResourceName, it.text?.toString(), it.contentDescription?.toString())
        }
    }

    private fun isProtectedScreen(root: AccessibilityNodeInfo): Boolean {
        val scan = scanNodes(root, MAX_NODES)
        return scan.truncated || scan.nodes.any {
            policy.isProtectedScreen(it.text?.toString(), it.contentDescription?.toString())
        }
    }

    private fun isCourseTitle(node: AccessibilityNodeInfo): Boolean =
        node.viewIdResourceName == "com.haoyisheng.mobile.app:id/tv_name"

    private data class NodeScan(val nodes: List<AccessibilityNodeInfo>, val truncated: Boolean)
    private data class CourseListSnapshot(
        val firstRow: Int,
        val lastRow: Int,
        val totalRows: Int,
        val statuses: List<String>,
    )

    private companion object {
        const val COURSE_STATUS_ID = "com.haoyisheng.mobile.app:id/tv_test_status"
        const val CREDIT_APPLY_ID = "com.haoyisheng.mobile.app:id/tv_score_apply"
        const val COURSE_UNKNOWN = "unknown"
        const val COURSE_INCOMPLETE = "incomplete"
        const val COURSE_COMPLETE = "complete"
        val COMPLETED_COURSE_STATUSES = setOf("已学习", "已完成", "已观看", "完成")
        const val MAX_NODES = 800
        const val MAX_ANCESTORS = 8
        const val PROTECTED_SCAN_NODES = 100
        const val SLEEP_SLICE_MS = 250L
    }
}
