package com.wangzan101.mika.automation

import android.content.Context
import com.wangzan101.mika.core.automation.LuaAutomationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AutomationPhase { Idle, Running, Paused, Finished, Failed }

data class AutomationUiState(
    val phase: AutomationPhase = AutomationPhase.Idle,
    val message: String = "",
)

object HaoyishengAutomationStatus {
    private val mutable = MutableStateFlow(AutomationUiState())
    val state = mutable.asStateFlow()

    internal fun publish(phase: AutomationPhase, message: String) {
        mutable.value = AutomationUiState(phase, message)
    }
}

internal class PreferencesAutomationState(context: Context) : LuaAutomationState {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun get(key: String): String? = preferences.getString(key, null)

    override fun set(key: String, value: String?) {
        preferences.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }

    private companion object {
        const val PREFS_NAME = "lua-automation-state"
    }
}
