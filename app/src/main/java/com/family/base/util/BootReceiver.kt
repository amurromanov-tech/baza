package com.family.base.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.family.base.BaseApplication
import kotlinx.coroutines.launch

/**
 * Восстанавливает запланированные напоминания после перезагрузки устройства
 * или обновления приложения.
 *
 * 🆕 Замена WorkManager на AlarmManager:
 *  - Android стирает ВСЕ alarms при выключении/перезагрузке.
 *  - После boot их надо восстановить — иначе напоминания молча исчезнут.
 *  - Аналогично после обновления приложения (MY_PACKAGE_REPLACED):
 *    новая версия кода, alarms могли остаться от старой — перепланируем.
 *
 * Регистрируется в AndroidManifest.xml на BOOT_COMPLETED / QUICKBOOT_POWERON /
 * MY_PACKAGE_REPLACED.
 *
 * 🆕 v18.2.0 (шаг 1): ФИКС ПАДЕНИЯ НА ГЛАВНОМ ПОТОКЕ.
 *
 * Было: onReceive → TaskReminderScheduler.rescheduleAll(context) →
 * getAllTasksBlocking() прямо в onReceive (главный поток) →
 * Room бросал "Cannot access database on the main thread" →
 * try/catch → ни одна задача не планировалась после перезагрузки.
 *
 * Стало: goAsync() + applicationScope.launch { rescheduleAllSuspend(...) }.
 *  - goAsync() даёт ресиверу время дожить до конца корутины
 *    (Android убивает onReceive сразу после return, если не вызвать
 *    pendingResult.finish()).
 *  - rescheduleAllSuspend читает БД на Dispatchers.IO — главный поток свободен.
 *
 * Вся логика — в try/catch, никаких крашей.
 */
class BootReceiver : BroadcastReceiver() {

    private val TAG = "BootReceiver"

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            val action = intent?.action ?: return
            if (action != Intent.ACTION_BOOT_COMPLETED &&
                action != Intent.ACTION_MY_PACKAGE_REPLACED &&
                action != "android.intent.action.QUICKBOOT_POWERON"
            ) {
                return
            }

            Logger.log(TAG, "onReceive: action=$action")

            if (!TaskReminderPreferences.isEnabled(context)) {
                Logger.log(TAG, "onReceive: reminders disabled, skip")
                return
            }

            // 🆕 goAsync: даём ресиверу дожить до конца корутины.
            // Без этого Android может убить процесс сразу после return
            // из onReceive, и rescheduleAll не успеет выполниться.
            val pendingResult = goAsync()
            val appContext = context.applicationContext

            BaseApplication.applicationScope.launch {
                try {
                    TaskReminderScheduler.rescheduleAllSuspend(appContext)
                    Logger.log(TAG, "onReceive: rescheduleAllSuspend done")
                } catch (e: Exception) {
                    Logger.log(TAG, "onReceive: rescheduleAllSuspend error: ${e.message}")
                } finally {
                    // Обязательно: сообщаем системе, что ресивер закончил работу.
                    try {
                        pendingResult.finish()
                    } catch (e: Exception) {
                        Logger.log(TAG, "onReceive: pendingResult.finish error: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Logger.log(TAG, "onReceive error: ${e.message}")
        }
    }
}
