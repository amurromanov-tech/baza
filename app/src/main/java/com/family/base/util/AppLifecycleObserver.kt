package com.family.base.util

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.family.base.ui.viewmodel.MainViewModel

class AppLifecycleObserver(
    private val viewModel: MainViewModel
) : DefaultLifecycleObserver {

    private val TAG = "AppLifecycleObserver"

    // ===== DEBOUNCE: не чаще, чем раз в 3 секунды =====
    private var lastSyncAt = 0L
    private val minIntervalMs = 3_000L

    override fun onStart(owner: LifecycleOwner) {
        Logger.log(TAG, "App entered foreground")
        trySync()
    }

    override fun onStop(owner: LifecycleOwner) {
        Logger.log(TAG, "App went to background")
        trySync()
    }

    private fun trySync() {
        val now = System.currentTimeMillis()
        if (now - lastSyncAt < minIntervalMs) {
            Logger.log(TAG, "Sync debounced (last was ${now - lastSyncAt}ms ago)")
            return
        }
        lastSyncAt = now
        Logger.log(TAG, "Triggering sync")
        viewModel.syncWithDisk()
    }
}
