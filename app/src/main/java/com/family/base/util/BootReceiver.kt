package com.family.base.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Восстанавливает запланированное напоминание после перезагрузки устройства.
 *
 * WorkManager сам по себе НЕ восстанавливает PeriodicWork после reboot —
 * точнее, восстанавливает только те, что были enqueued через persist,
 * но initial delay мог быть потерян. Чтобы не гадать — просто перепланируем
 * при загрузке, если напоминания включены.
 *
 * Регистрируется в AndroidManifest.xml на action BOOT_COMPLETED.
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

            TaskReminderScheduler.reschedule(context)
            Logger.log(TAG, "onReceive: rescheduled")
        } catch (e: Exception) {
            Logger.log(TAG, "onReceive error: ${e.message}")
        }
    }
}
