package com.family.base.util

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.family.base.ui.viewmodel.MainViewModel

/**
 * Наблюдатель жизненного цикла приложения.
 *
 * Синхронизация запускается ТОЛЬКО ОДИН РАЗ — при первом запуске
 * приложения (холодный старт). Больше никаких автосинков:
 *  - при сворачивании приложения,
 *  - при разблокировке экрана,
 *  - при переключении между приложениями.
 *
 * Синк по кнопке "Синхронизировать сейчас" — через SettingsActivity.
 * Синк при выходе из аккаунта — через SettingsActivity (logout).
 */
class AppLifecycleObserver(
    private val viewModel: MainViewModel
) : DefaultLifecycleObserver {

    private val TAG = "AppLifecycleObserver"

    /**
     * Флаг: был ли уже синк при холодном старте.
     * Живёт, пока жив процесс приложения. Сбрасывается только при перезапуске.
     */
    private var hasSyncedOnColdStart = false

    override fun onStart(owner: LifecycleOwner) {
        if (!hasSyncedOnColdStart) {
            hasSyncedOnColdStart = true
            Logger.log(TAG, "Cold start detected, syncing...")
            viewModel.syncWithDisk()
        } else {
            Logger.log(TAG, "onStart: skip sync (already synced on cold start)")
        }
    }

    // onStop НЕ переопределяем — синк при уходе в фон не нужен
}
