package com.family.base.util

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.family.base.ui.viewmodel.MainViewModel

/**
 * Наблюдатель жизненного цикла приложения.
 *
 * 🆕 v17.2.8: АВТОСИНК ПОЛНОСТЬЮ ОТКЛЮЧЁН.
 *
 * Раньше: при холодном старте запускался syncWithDisk() — это приводило
 * к удалению локальных данных, если items.json на Диске был неполным
 * или folders.json отсутствовал (инцидент 16.10.2026, потеряно ~190 предметов).
 *
 * Теперь:
 *  - Синк запускается ТОЛЬКО по явной команде пользователя:
 *      • кнопка «🔄» в тулбаре MainActivity (viewModel.forceSync());
 *      • кнопка «Синхронизировать сейчас» в SettingsActivity.
 *  - Никаких автосинков при:
 *      • холодном старте,
 *      • сворачивании/разворачивании,
 *      • разблокировке экрана,
 *      • переключении между приложениями.
 *
 * Класс оставлен как заглушка для логов и на случай будущих
 * «безопасных» действий по onStart (например, локальная проверка целостности
 * БД без сети).
 */
class AppLifecycleObserver(
    @Suppress("unused") private val viewModel: MainViewModel
) : DefaultLifecycleObserver {

    private val TAG = "AppLifecycleObserver"

    override fun onStart(owner: LifecycleOwner) {
        Logger.log(TAG, "onStart: auto-sync disabled (v17.2.8). Use toolbar 🔄 to sync manually.")
    }

    // onStop НЕ переопределяем — синк при уходе в фон не нужен
}
