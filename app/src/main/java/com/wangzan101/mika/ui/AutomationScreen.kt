package com.wangzan101.mika.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.wangzan101.mika.R
import com.wangzan101.mika.automation.AutomationPhase
import com.wangzan101.mika.automation.HaoyishengAutomationService
import com.wangzan101.mika.automation.HaoyishengAutomationStatus
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowActions
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.StateCard
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaIcons

@Composable
fun AutomationHubScreen(onBack: () -> Unit, onOpenHaoyisheng: () -> Unit) {
    PageScaffold(stringResource(R.string.automation_title), onBack) {
        GroupLabel(stringResource(R.string.automation_apps_group))
        GroupCard {
            ListRow(
                MikaIcons.Phone,
                stringResource(R.string.automation_haoyisheng_title),
                stringResource(R.string.automation_haoyisheng_sub),
                first = true,
                onClick = onOpenHaoyisheng,
            ) { RowChevron() }
        }
        FooterNote(stringResource(R.string.automation_hub_note))
    }
}

@Composable
fun AutomationScreen(
    onBack: () -> Unit,
    onOpenAccessibility: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
) {
    val context = LocalContext.current
    val state by HaoyishengAutomationStatus.state.collectAsState()
    val accessibility by ScreenAccessibility.instance.collectAsState()
    var notificationsReady by remember { mutableStateOf(HaoyishengAutomationService.canShowStopNotification(context)) }
    LifecycleResumeEffect(context) {
        notificationsReady = HaoyishengAutomationService.canShowStopNotification(context)
        onPauseOrDispose { }
    }
    val running = state.phase == AutomationPhase.Running
    val tone = when (state.phase) {
        AutomationPhase.Running, AutomationPhase.Finished -> TileTone.Ok
        AutomationPhase.Paused -> TileTone.Warn
        AutomationPhase.Failed -> TileTone.Bad
        AutomationPhase.Idle -> TileTone.Neutral
    }
    val title = stringResource(
        when (state.phase) {
            AutomationPhase.Running -> R.string.automation_state_running
            AutomationPhase.Paused -> R.string.automation_state_paused
            AutomationPhase.Finished -> R.string.automation_state_finished
            AutomationPhase.Failed -> R.string.automation_state_failed
            AutomationPhase.Idle -> R.string.automation_state_idle
        },
    )

    PageScaffold(stringResource(R.string.automation_haoyisheng_title), onBack) {
        StateCard(
            MikaIcons.Code, title,
            state.message.ifBlank { stringResource(R.string.automation_state_idle_detail) },
            tone,
            Modifier.testTag("automation-status"),
        )

        GroupLabel(stringResource(R.string.automation_ready_group))
        GroupCard {
            ListRow(
                MikaIcons.Phone,
                stringResource(R.string.automation_accessibility),
                stringResource(R.string.automation_accessibility_sub),
                first = true,
                onClick = if (accessibility == null) onOpenAccessibility else null,
            ) {
                RowValue(
                    stringResource(if (accessibility != null) R.string.state_on else R.string.state_off),
                    if (accessibility != null) ValueTone.Ok else ValueTone.Warn,
                )
            }
            ListRow(
                MikaIcons.Bell,
                stringResource(R.string.automation_notifications),
                stringResource(R.string.automation_notifications_sub),
                onClick = if (!notificationsReady) onOpenNotifications else null,
            ) {
                RowValue(
                    stringResource(if (notificationsReady) R.string.state_on else R.string.state_off),
                    if (notificationsReady) ValueTone.Ok else ValueTone.Warn,
                )
            }
            ListRow(
                MikaIcons.Shield,
                stringResource(R.string.automation_local_only),
                stringResource(R.string.automation_local_only_sub),
            ) { RowValue(stringResource(R.string.automation_zero_token), ValueTone.Ok) }
            RowActions {
                if (running) {
                    MikaButton(
                        stringResource(R.string.automation_stop),
                        { HaoyishengAutomationService.stop(context) },
                        kind = ButtonKind.Tonal,
                        size = ButtonSize.Small,
                    )
                } else {
                    MikaButton(
                        stringResource(R.string.automation_start),
                        { HaoyishengAutomationService.start(context) },
                        size = ButtonSize.Small,
                        enabled = accessibility != null && notificationsReady,
                    )
                }
            }
        }
        FooterNote(stringResource(R.string.automation_usage_note))

        GroupLabel(stringResource(R.string.automation_boundary_group))
        GroupCard {
            ListRow(
                MikaIcons.Shield,
                stringResource(R.string.automation_boundary_title),
                stringResource(R.string.automation_boundary_sub),
                first = true,
            )
        }
    }
}
