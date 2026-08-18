package com.family.base.ui

enum class SyncStatus {
    SYNCING,    // 🔄 синхронизация идет
    SYNCED,     // ✅ синхронизировано
    PENDING,    // ⚠️ есть изменения
    OFFLINE     // ❌ нет интернета
}
