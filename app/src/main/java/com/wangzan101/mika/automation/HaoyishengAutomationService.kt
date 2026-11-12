package com.wangzan101.mika.automation

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.wangzan101.mika.R
import com.wangzan101.mika.core.automation.AutomationResult
import com.wangzan101.mika.core.automation.AutomationTargetPolicy
import com.wangzan101.mika.core.automation.LuaAutomationEngine
import com.wangzan101.mika.task.TaskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** 运行打包在 APK 内的好医生 Lua 脚本；不创建 ChatModel，也不进入 AgentLoop。 */
class HaoyishengAutomationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cancelled = AtomicBoolean(false)
    private var runJob: Job? = null
    private var terminalState: AutomationUiState? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            cancelled.set(true)
            publish(AutomationPhase.Running, getString(R.string.automation_stopping))
            stopSelf()
            return START_NOT_STICKY
        }
        if (runJob?.isActive == true) return START_NOT_STICKY

        TaskService.ensureChannels(this)
        if (!canShowStopNotification(this)) {
            publishFailure(getString(R.string.automation_notification_required))
            stopSelf()
            return START_NOT_STICKY
        }
        if (!enterForeground(getString(R.string.automation_starting))) {
            publish(AutomationPhase.Failed, getString(R.string.automation_notification_failed))
            stopSelf()
            return START_NOT_STICKY
        }

        cancelled.set(false)
        publish(AutomationPhase.Running, getString(R.string.automation_starting))
        scope.launch {
            while (!cancelled.get()) {
                delay(NOTIFICATION_CHECK_MS)
                if (!isStopNotificationAvailable(this@HaoyishengAutomationService)) {
                    cancelled.set(true)
                    publishFailure(getString(R.string.automation_notification_required))
                    stopSelf()
                    break
                }
            }
        }
        runJob = scope.launch {
            val bridge = AndroidLuaAutomationBridge(this@HaoyishengAutomationService, POLICY, cancelled) { message ->
                publish(AutomationPhase.Running, message)
            }
            val source = runCatching {
                assets.open(SCRIPT_ASSET).bufferedReader().use { it.readText() }
            }.getOrElse { error ->
                finish(AutomationResult.Failed(error.message ?: "Could not read Lua script"))
                return@launch
            }
            finish(LuaAutomationEngine(bridge, PreferencesAutomationState(this@HaoyishengAutomationService)).run(source, SCRIPT_ASSET))
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelled.set(true)
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        val terminal = terminalState
        if (terminal != null) {
            HaoyishengAutomationStatus.publish(terminal.phase, terminal.message)
        } else if (HaoyishengAutomationStatus.state.value.phase == AutomationPhase.Running) {
            HaoyishengAutomationStatus.publish(AutomationPhase.Idle, getString(R.string.automation_stopped))
        }
        super.onDestroy()
    }

    private fun finish(result: AutomationResult) {
        terminalState = when (result) {
            is AutomationResult.Finished -> AutomationUiState(AutomationPhase.Finished, result.message)
            is AutomationResult.Paused -> AutomationUiState(AutomationPhase.Paused, result.message)
            is AutomationResult.Stopped -> AutomationUiState(AutomationPhase.Idle, result.message)
            is AutomationResult.Failed -> AutomationUiState(AutomationPhase.Failed, result.message)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publish(phase: AutomationPhase, message: String) {
        if (phase == AutomationPhase.Running && !isStopNotificationAvailable(this)) {
            cancelled.set(true)
            publishFailure(getString(R.string.automation_notification_required))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        HaoyishengAutomationStatus.publish(phase, message)
        if (phase == AutomationPhase.Running) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
        }
    }

    private fun publishFailure(message: String) {
        HaoyishengAutomationStatus.publish(AutomationPhase.Failed, message)
    }

    private fun enterForeground(message: String): Boolean = runCatching {
        val notification = notification(message)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }.onFailure { Log.w(TAG, "automation service could not enter foreground", it) }.isSuccess

    private fun notification(message: String): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HaoyishengAutomationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, TaskService.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.automation_notification_title))
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(TaskService.openApp(this))
            .addAction(Notification.Action.Builder(null, getString(R.string.automation_stop), stop).build())
            .build()
    }

    companion object {
        private const val TAG = "MikaAutomation"
        private const val ACTION_START = "com.wangzan101.mika.automation.START"
        private const val ACTION_STOP = "com.wangzan101.mika.automation.STOP"
        private const val SCRIPT_ASSET = "automation/haoyisheng.lua"
        private const val NOTIFICATION_ID = 30_003
        private const val NOTIFICATION_CHECK_MS = 2_000L

        private val POLICY = AutomationTargetPolicy(
            packageName = "com.haoyisheng.mobile.app",
            protectedText = setOf("考试", "答题", "人脸", "身份验证", "身份核验", "学习声明", "最终提交"),
            protectedScreenText = setOf(
                "考试", "答题", "人脸识别", "人脸验证", "身份验证", "身份核验", "身份认证", "实名认证",
                "学习声明", "诚信声明", "确认申领", "申领学分", "提交学分", "确认申请", "提交申请", "最终提交", "确认提交",
            ),
            allowedClickResourceIds = setOf(
                "com.haoyisheng.mobile.app:id/cme_page",
                "com.haoyisheng.mobile.app:id/rl_play_video",
                "com.haoyisheng.mobile.app:id/iv_play_pause",
                "com.haoyisheng.mobile.app:id/recommend_course_name",
                "com.haoyisheng.mobile.app:id/tv_score_apply",
            ),
            allowedClickText = setOf("课程列表"),
            allowedRowText = setOf("学习中", "未学习"),
        )

        fun canShowStopNotification(context: Context): Boolean = runCatching {
            TaskService.ensureChannels(context)
            isStopNotificationAvailable(context)
        }.getOrDefault(false)

        private fun isStopNotificationAvailable(context: Context): Boolean = runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.areNotificationsEnabled() &&
                manager.getNotificationChannel(TaskService.CHANNEL_PROGRESS)
                    ?.let { it.importance != NotificationManager.IMPORTANCE_NONE } == true
        }.getOrDefault(false)

        fun start(context: Context) {
            if (!canShowStopNotification(context)) {
                HaoyishengAutomationStatus.publish(
                    AutomationPhase.Failed,
                    context.getString(R.string.automation_notification_required),
                )
                return
            }
            runCatching {
                context.startForegroundService(Intent(context, HaoyishengAutomationService::class.java).setAction(ACTION_START))
            }.onFailure { error ->
                Log.w(TAG, "could not start automation service", error)
                HaoyishengAutomationStatus.publish(AutomationPhase.Failed, context.getString(R.string.automation_notification_failed))
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(Intent(context, HaoyishengAutomationService::class.java).setAction(ACTION_STOP))
            }.onFailure { error -> Log.w(TAG, "could not stop automation service", error) }
        }
    }
}
