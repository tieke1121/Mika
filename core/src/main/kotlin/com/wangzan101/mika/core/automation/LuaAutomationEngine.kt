package com.wangzan101.mika.core.automation

import org.luaj.vm2.Globals
import org.luaj.vm2.LoadState
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.BaseLib
import org.luaj.vm2.lib.CoroutineLib
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.PackageLib
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.ZeroArgFunction
import org.luaj.vm2.lib.jse.JseMathLib

/** Lua 能调用的最小手机操作面；实现负责限制目标 App 与敏感操作。 */
interface LuaAutomationBridge {
    fun isCancelled(): Boolean
    fun launch(packageName: String): Boolean
    fun currentPackage(): String?
    fun hasProtectedScreen(): Boolean = false
    fun existsById(resourceId: String): Boolean
    fun existsByText(text: String): Boolean
    fun textById(resourceId: String): String?
    fun clickById(resourceId: String): Boolean
    fun clickByText(text: String): Boolean
    fun clickRowByText(text: String): Boolean = clickByText(text)
    fun courseListState(): String = "unknown"
    fun courseListWindow(): String? = null
    fun canApplyCredit(): Boolean = false
    fun scrollBackward(): Boolean = false
    fun scrollForward(): Boolean
    fun back(): Boolean
    fun sleep(milliseconds: Long)
    fun status(message: String)
}

/** 少量可恢复状态；不允许 Lua 直接访问文件系统。 */
interface LuaAutomationState {
    fun get(key: String): String?
    fun set(key: String, value: String?)
}

sealed interface AutomationResult {
    data class Finished(val message: String) : AutomationResult
    data class Paused(val message: String) : AutomationResult
    data class Stopped(val message: String) : AutomationResult
    data class Failed(val message: String) : AutomationResult
}

/**
 * 只运行应用内打包的可信 Lua 脚本。Lua 看不到文件、网络、Java 反射或动态代码加载入口，
 * 所有手机动作都经 [LuaAutomationBridge]，因此不会进入模型调用与 token 计量链路。
 */
class LuaAutomationEngine(
    private val bridge: LuaAutomationBridge,
    private val state: LuaAutomationState,
) {
    fun run(source: String, chunkName: String = "automation.lua"): AutomationResult {
        if (bridge.isCancelled()) return AutomationResult.Stopped(CANCELLED)
        return try {
            globals().load(source, chunkName).call()
            AutomationResult.Finished("completed")
        } catch (signal: TaskSignal) {
            signal.result
        } catch (error: LuaError) {
            (error.cause as? TaskSignal)?.result
                ?: AutomationResult.Failed(error.message.orEmpty().ifBlank { "Lua script failed" }.take(MAX_ERROR_LENGTH))
        } catch (error: RuntimeException) {
            AutomationResult.Failed(error.message.orEmpty().ifBlank { error.javaClass.simpleName }.take(MAX_ERROR_LENGTH))
        }
    }

    private fun globals(): Globals = Globals().apply {
        load(BaseLib())
        load(PackageLib())
        load(TableLib())
        load(StringLib())
        load(CoroutineLib())
        load(JseMathLib())
        LoadState.install(this)
        LuaC.install(this)

        set("phone", phoneApi())
        set("state", stateApi())
        set("task", taskApi())
        RESTRICTED_GLOBALS.forEach { set(it, LuaValue.NIL) }
    }

    private fun phoneApi() = LuaTable().apply {
        set("launch", object : OneArgFunction() {
            override fun call(arg: LuaValue) = bool { bridge.launch(arg.checkjstring()) }
        })
        set("package_name", object : ZeroArgFunction() {
            override fun call() = guarded { bridge.currentPackage()?.let(LuaValue::valueOf) ?: LuaValue.NIL }
        })
        set("protected_screen", object : ZeroArgFunction() {
            override fun call() = bool { bridge.hasProtectedScreen() }
        })
        set("exists_id", object : OneArgFunction() {
            override fun call(arg: LuaValue) = bool { bridge.existsById(arg.checkjstring()) }
        })
        set("exists_text", object : OneArgFunction() {
            override fun call(arg: LuaValue) = bool { bridge.existsByText(arg.checkjstring()) }
        })
        set("text_id", object : OneArgFunction() {
            override fun call(arg: LuaValue) = guarded {
                bridge.textById(arg.checkjstring())?.let(LuaValue::valueOf) ?: LuaValue.NIL
            }
        })
        set("click_id", object : OneArgFunction() {
            override fun call(arg: LuaValue) = bool { bridge.clickById(arg.checkjstring()) }
        })
        set("click_text", object : OneArgFunction() {
            override fun call(arg: LuaValue) = bool { bridge.clickByText(arg.checkjstring()) }
        })
        set("click_row_text", object : OneArgFunction() {
            override fun call(arg: LuaValue) = bool { bridge.clickRowByText(arg.checkjstring()) }
        })
        set("course_list_state", object : ZeroArgFunction() {
            override fun call() = guarded { LuaValue.valueOf(bridge.courseListState()) }
        })
        set("course_list_window", object : ZeroArgFunction() {
            override fun call() = guarded { bridge.courseListWindow()?.let(LuaValue::valueOf) ?: LuaValue.NIL }
        })
        set("credit_ready", object : ZeroArgFunction() {
            override fun call() = bool { bridge.canApplyCredit() }
        })
        set("scroll_forward", object : ZeroArgFunction() {
            override fun call() = bool { bridge.scrollForward() }
        })
        set("scroll_backward", object : ZeroArgFunction() {
            override fun call() = bool { bridge.scrollBackward() }
        })
        set("back", object : ZeroArgFunction() {
            override fun call() = bool { bridge.back() }
        })
        set("sleep", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = guarded {
                bridge.sleep(arg.checklong().coerceIn(0L, MAX_SLEEP_MS))
                LuaValue.NIL
            }
        })
        set("status", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = guarded {
                bridge.status(arg.checkjstring().take(MAX_STATUS_LENGTH))
                LuaValue.NIL
            }
        })
    }

    private fun stateApi() = LuaTable().apply {
        set("get", object : OneArgFunction() {
            override fun call(arg: LuaValue) = guarded {
                state.get(key(arg))?.let(LuaValue::valueOf) ?: LuaValue.NIL
            }
        })
        set("set", object : TwoArgFunction() {
            override fun call(keyValue: LuaValue, value: LuaValue): LuaValue = guarded {
                state.set(key(keyValue), if (value.isnil()) null else value.checkjstring().take(MAX_STATE_VALUE_LENGTH))
                LuaValue.NIL
            }
        })
    }

    private fun taskApi() = LuaTable().apply {
        set("finish", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = throw TaskSignal(AutomationResult.Finished(message(arg)))
        })
        set("pause", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = throw TaskSignal(AutomationResult.Paused(message(arg)))
        })
    }

    private fun bool(block: () -> Boolean): LuaValue = guarded { LuaValue.valueOf(block()) }

    private fun <T> guarded(block: () -> T): T {
        if (bridge.isCancelled()) throw TaskSignal(AutomationResult.Stopped(CANCELLED))
        return block()
    }

    private fun key(value: LuaValue) = value.checkjstring().take(MAX_STATE_KEY_LENGTH)
    private fun message(value: LuaValue) = value.checkjstring().take(MAX_STATUS_LENGTH)

    private class TaskSignal(val result: AutomationResult) : RuntimeException(null, null, false, false)

    private companion object {
        const val CANCELLED = "cancelled"
        const val MAX_SLEEP_MS = 30_000L
        const val MAX_STATUS_LENGTH = 300
        const val MAX_STATE_KEY_LENGTH = 80
        const val MAX_STATE_VALUE_LENGTH = 2_000
        const val MAX_ERROR_LENGTH = 1_000
        val RESTRICTED_GLOBALS = listOf(
            "io", "os", "debug", "package", "require", "luajava", "dofile", "loadfile", "load", "loadstring", "collectgarbage",
        )
    }
}
