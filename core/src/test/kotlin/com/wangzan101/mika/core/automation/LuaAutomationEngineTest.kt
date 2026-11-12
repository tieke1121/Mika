package com.wangzan101.mika.core.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LuaAutomationEngineTest {
    @Test
    fun sandboxDoesNotExposeFileNetworkOrJavaEscapeHatches() {
        val result = LuaAutomationEngine(FakeBridge(), MemoryState()).run(
            """
            assert(io == nil)
            assert(os == nil)
            assert(debug == nil)
            assert(package == nil)
            assert(require == nil)
            assert(luajava == nil)
            assert(dofile == nil)
            assert(loadfile == nil)
            assert(load == nil)
            assert(loadstring == nil)
            task.finish("sandboxed")
            """.trimIndent(),
        )

        assertEquals(AutomationResult.Finished("sandboxed"), result)
    }

    @Test
    fun bridgeAndPersistentStateAreAvailableWithoutAModel() {
        val bridge = FakeBridge()
        val state = MemoryState()
        val result = LuaAutomationEngine(bridge, state).run(
            """
            phone.status("opening")
            assert(phone.launch("com.haoyisheng.mobile.app"))
            assert(phone.package_name() == "com.haoyisheng.mobile.app")
            assert(phone.exists_id("course"))
            assert(phone.exists_text("未学习"))
            assert(phone.text_id("time") == "00:10")
            assert(phone.click_id("course"))
            assert(phone.click_text("未学习"))
            assert(phone.course_list_state() == "complete")
            assert(phone.course_list_window() == "0,0,1")
            assert(phone.credit_ready())
            assert(not phone.scroll_backward())
            assert(phone.scroll_forward())
            assert(phone.back())
            state.set("course", "demo")
            assert(state.get("course") == "demo")
            phone.sleep(25)
            task.pause("manual step")
            """.trimIndent(),
        )

        assertEquals(AutomationResult.Paused("manual step"), result)
        assertEquals("demo", state.get("course"))
        assertEquals(
            listOf("status:opening", "launch:com.haoyisheng.mobile.app", "clickId:course", "clickText:未学习", "scroll", "back", "sleep:25"),
            bridge.calls,
        )
    }

    @Test
    fun cancellationAndScriptErrorsEndWithExplicitResults() {
        val cancelled = FakeBridge(cancelled = true)
        assertEquals(
            AutomationResult.Stopped("cancelled"),
            LuaAutomationEngine(cancelled, MemoryState()).run("phone.status('never')"),
        )

        val failed = LuaAutomationEngine(FakeBridge(), MemoryState()).run("error('broken script')")
        assertTrue(failed is AutomationResult.Failed)
        assertTrue((failed as AutomationResult.Failed).message.contains("broken script"))
    }

    private class FakeBridge(var cancelled: Boolean = false) : LuaAutomationBridge {
        val calls = mutableListOf<String>()

        override fun isCancelled() = cancelled
        override fun launch(packageName: String) = true.also { calls += "launch:$packageName" }
        override fun currentPackage() = "com.haoyisheng.mobile.app"
        override fun existsById(resourceId: String) = resourceId == "course"
        override fun existsByText(text: String) = text == "未学习"
        override fun textById(resourceId: String) = if (resourceId == "time") "00:10" else null
        override fun clickById(resourceId: String) = true.also { calls += "clickId:$resourceId" }
        override fun clickByText(text: String) = true.also { calls += "clickText:$text" }
        override fun courseListState() = "complete"
        override fun courseListWindow() = "0,0,1"
        override fun canApplyCredit() = true
        override fun scrollForward() = true.also { calls += "scroll" }
        override fun back() = true.also { calls += "back" }
        override fun sleep(milliseconds: Long) { calls += "sleep:$milliseconds" }
        override fun status(message: String) { calls += "status:$message" }
    }

    private class MemoryState : LuaAutomationState {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun set(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }
}
