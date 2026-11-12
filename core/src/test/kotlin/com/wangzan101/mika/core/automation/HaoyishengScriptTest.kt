package com.wangzan101.mika.core.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HaoyishengScriptTest {
    @Test fun courseDetailPlaysUnfinishedVideosInOrderThenClicksCreditApplication() {
        val bridge = CourseBridge()
        val state = MemoryState()
        val result = LuaAutomationEngine(bridge, state).run(script(), "haoyisheng.lua")

        assertTrue("result=$result", result is AutomationResult.Finished)
        assertTrue((result as AutomationResult.Finished).message.contains("学分申请"))
        assertEquals("测试视频2", state.get("last_video"))
        assertEquals(2, bridge.startedVideos)
        assertEquals("两节视频都应经课程列表回退路径查找", 2, bridge.backCalls)
        assertEquals("学习中只允许在首次续播阶段探测", 1, bridge.rowAttempts.count { it == "学习中" })
        assertFalse(bridge.clickedText.any { it.contains("申请学分") || it.contains("考试") || it.contains("人脸") })
        assertFalse(bridge.clickedText.any { it in setOf("我知道了", "知道了", "继续学习", "允许播放") })
        assertEquals(1, bridge.clickedIds.count { it.endsWith("tv_score_apply") })
    }

    @Test fun cmeHomeAutomaticallyOpensARecommendedCourse() {
        val bridge = CourseBridge(startAtCme = true)
        val result = LuaAutomationEngine(bridge, MemoryState()).run(script(), "haoyisheng.lua")

        assertTrue("result=$result", result is AutomationResult.Finished)
        assertEquals(1, bridge.courseClicks)
        assertEquals(2, bridge.startedVideos)
    }

    @Test fun protectedScreenPausesBeforeAnyClick() {
        val bridge = CourseBridge(protected = true)
        val result = LuaAutomationEngine(bridge, MemoryState()).run(script(), "haoyisheng.lua")

        assertTrue(result is AutomationResult.Paused)
        assertTrue((result as AutomationResult.Paused).message.contains("手动处理"))
        assertTrue(bridge.clickedText.isEmpty())
        assertTrue(bridge.clickedIds.isEmpty())
        assertTrue(bridge.rowAttempts.isEmpty())
    }

    @Test fun stalledVideoPausesInsteadOfRunningForever() {
        val result = LuaAutomationEngine(CourseBridge(stalled = true), MemoryState()).run(script(), "haoyisheng.lua")

        assertTrue(result is AutomationResult.Paused)
        assertTrue((result as AutomationResult.Paused).message.contains("长时间未变化"))
    }

    @Test fun unknownCourseCompletionNeverClicksCreditApplication() {
        val bridge = CourseBridge(unknownCourseState = true)
        val result = LuaAutomationEngine(bridge, MemoryState()).run(script(), "haoyisheng.lua")

        assertTrue("result=$result", result is AutomationResult.Paused)
        assertFalse(bridge.clickedIds.any { it.endsWith("tv_score_apply") })
    }

    @Test fun protectedPageAfterCreditClickPausesForManualWork() {
        val bridge = CourseBridge(protectAfterCredit = true)
        val result = LuaAutomationEngine(bridge, MemoryState()).run(script(), "haoyisheng.lua")

        assertTrue("result=$result", result is AutomationResult.Paused)
        assertEquals(1, bridge.clickedIds.count { it.endsWith("tv_score_apply") })
    }

    @Test fun creditResultWithoutSuccessEvidenceRemainsPaused() {
        val bridge = CourseBridge(creditResultUnknown = true)
        val result = LuaAutomationEngine(bridge, MemoryState()).run(script(), "haoyisheng.lua")

        assertTrue("result=$result", result is AutomationResult.Paused)
        assertTrue((result as AutomationResult.Paused).message.contains("未能确认结果"))
    }

    @Test fun courseScanRewindsToTopBeforeCheckingCompletion() {
        val bridge = CourseBridge(startAtListBottom = true)
        val result = LuaAutomationEngine(bridge, MemoryState()).run(script(), "haoyisheng.lua")

        assertTrue("result=$result", result is AutomationResult.Finished)
        assertTrue(bridge.backwardScrolls > 0)
        assertEquals(2, bridge.startedVideos)
    }

    @Test fun rowClickWithoutPlayerTransitionTimesOut() {
        val result = LuaAutomationEngine(CourseBridge(navigationFails = true), MemoryState()).run(script(), "haoyisheng.lua")

        assertTrue("result=$result", result is AutomationResult.Paused)
        assertTrue((result as AutomationResult.Paused).message.contains("播放器没有打开"))
    }

    private fun script(): String {
        val candidates = listOf(
            File("../app/src/main/assets/automation/haoyisheng.lua"),
            File("app/src/main/assets/automation/haoyisheng.lua"),
        )
        return candidates.first { it.isFile }.readText()
    }

    private class CourseBridge(
        private val protected: Boolean = false,
        private val stalled: Boolean = false,
        private val startAtCme: Boolean = false,
        private val unknownCourseState: Boolean = false,
        private val protectAfterCredit: Boolean = false,
        private val navigationFails: Boolean = false,
        private val creditResultUnknown: Boolean = false,
        private val startAtListBottom: Boolean = false,
    ) : LuaAutomationBridge {
        private val idPrefix = "com.haoyisheng.mobile.app:id/"
        private var screen = when {
            stalled -> "player"
            startAtCme -> "cme"
            else -> "detail"
        }
        private var current = 0
        private var videoIndex = if (stalled) 1 else 0
        private var protectedScreen = protected
        private var creditClicked = false
        private var atTop = !startAtListBottom
        val clickedText = mutableListOf<String>()
        val clickedIds = mutableListOf<String>()
        val rowAttempts = mutableListOf<String>()
        var startedVideos = 0
        var backCalls = 0
        var courseClicks = 0
        var backwardScrolls = 0

        override fun isCancelled() = false
        override fun launch(packageName: String) = packageName == "com.haoyisheng.mobile.app"
        override fun currentPackage() = "com.haoyisheng.mobile.app"
        override fun hasProtectedScreen() = protectedScreen
        override fun existsById(resourceId: String): Boolean = when (resourceId) {
            idPrefix + "course_name" -> screen == "detail" || screen == "list"
            idPrefix + "tv_video_title1" -> screen == "player"
            idPrefix + "cme_page" -> screen == "cme"
            idPrefix + "recommend_course_name" -> screen == "cme"
            idPrefix + "tv_score_apply" -> screen in setOf("detail", "list") && startedVideos == 2
            else -> false
        }
        override fun existsByText(text: String) =
            creditClicked && !protectAfterCredit && !creditResultUnknown && text == "申请成功"
        override fun textById(resourceId: String): String? = when (resourceId) {
            idPrefix + "tv_video_title1" -> if (screen == "player") "测试视频$videoIndex" else null
            idPrefix + "tv_portrait_current_time" -> if (screen == "player") "00:00:0$current" else null
            idPrefix + "tv_portrait_video_time" -> if (screen == "player") "00:00:04" else null
            else -> null
        }
        override fun clickById(resourceId: String): Boolean {
            clickedIds += resourceId
            if (resourceId == idPrefix + "cme_page" && screen == "cme") return true
            if (resourceId == idPrefix + "recommend_course_name" && screen == "cme") {
                screen = "detail"
                courseClicks++
                return true
            }
            if (resourceId == idPrefix + "tv_score_apply" && screen in setOf("detail", "list") && startedVideos == 2) {
                creditClicked = true
                if (protectAfterCredit) protectedScreen = true
                return true
            }
            return false
        }
        override fun clickByText(text: String): Boolean {
            clickedText += text
            if (text == "课程列表" && screen == "detail") {
                screen = "list"
                atTop = !startAtListBottom
                return true
            }
            return false
        }
        override fun clickRowByText(text: String): Boolean {
            rowAttempts += text
            if (text == "未学习" && screen == "list" && startedVideos < 2) {
                if (!navigationFails) screen = "player"
                current = 0
                startedVideos++
                videoIndex = startedVideos
                return true
            }
            return false
        }
        override fun courseListState(): String = when {
            unknownCourseState && screen == "list" -> "unknown"
            screen == "list" && !atTop -> "complete"
            screen == "list" && startedVideos < 2 -> "incomplete"
            screen == "list" -> "complete"
            else -> "unknown"
        }
        override fun courseListWindow(): String? = when {
            screen != "list" -> null
            atTop -> "0,0,2"
            else -> "1,1,2"
        }
        override fun canApplyCredit(): Boolean = screen == "list" && startedVideos == 2 && !atTop
        override fun scrollBackward(): Boolean {
            if (screen == "list" && !atTop) {
                atTop = true
                backwardScrolls++
                return true
            }
            return false
        }
        override fun scrollForward(): Boolean {
            if (screen == "list" && atTop) {
                atTop = false
                return true
            }
            return false
        }
        override fun back(): Boolean {
            backCalls++
            screen = "detail"
            return true
        }
        override fun sleep(milliseconds: Long) {
            if (screen == "player") {
                current = if (stalled) maxOf(current, 1) else (current + 2).coerceAtMost(4)
            }
        }
        override fun status(message: String) = Unit
    }

    private class MemoryState : LuaAutomationState {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun set(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }
}
