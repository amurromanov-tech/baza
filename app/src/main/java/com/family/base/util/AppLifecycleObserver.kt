package com.family.base.util

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.family.base.ui.viewmodel.MainViewModel

class AppLifecycleObserver(
    private val viewModel: MainViewModel
) : DefaultLifecycleObserver {

    private val TAG = "AppLifecycleObserver"

    override fun onStart(owner: LifecycleOwner) {
        // Приложение вышло на передний план (запуск или возврат из фона)
        Logger.log(TAG, "App entered foreground, syncing...")
        viewModel.syncWithDisk()
    }

    override fun onStop(owner: LifecycleOwner) {
        // Приложение ушло в фон (свёрнуто, заблокирован экран)
        Logger.log(TAG, "App went to background, syncing...")
        viewModel.syncWithDisk()
    }
}
