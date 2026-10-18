package com.family.base.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

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

            TaskReminderScheduler.rescheduleAll(context)
            Logger.log(TAG, "onReceive: rescheduleAll done")
        } catch (e: Exception) {
            Logger.log(TAG, "onReceive error: ${e.message}")
        }
    }
}
