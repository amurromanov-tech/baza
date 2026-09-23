package com.family.base.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "items")
data class ItemEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val parentId: String?,
    var quantity: Int = 1,
    val barcode: String? = null,
    val description: String? = null,
    val usageInstructions: String? = null,
    val imageUrl: String? = null,
    val thumbnailUrl: String? = null,
    val expiryDate: Long? = null,
    val manufacturedDate: Long? = null,
    val addedDate: Long = System.currentTimeMillis(),
    val addedBy: String,
    val updatedDate: Long = System.currentTimeMillis(),
    val updatedBy: String? = null,
    var daysUntilExpiry: Int = Int.MAX_VALUE,
    var isExpired: Boolean = false,
    val itemType: String? = null,
    val price: Double? = null,

    // ===== АРХИВ =====
    val isArchived: Boolean = false,
    val archivedReason: String? = null,   // "eaten", "broken", "thrown", "gifted", "sold", "expired", "other"
    val archivedDate: Long? = null,
    val archivedNote: String? = null,

    // ===== ЗАЙМ (ВЫДАЧА) =====
    val isLent: Boolean = false,          // Флаг: предмет выдан
    val lentTo: String? = null,           // Кому выдан (например, "Иванов")
    val lentDate: Long? = null,           // Когда выдан
    val lentNote: String? = null,         // Заметка (например, "на неделю")
    val returnDate: Long? = null          // Планируемая дата возврата
) {
    fun computeExpiryFields() {
        expiryDate?.let { exp ->
            val now = System.currentTimeMillis()
            val diff = exp - now
            daysUntilExpiry = (diff / (1000 * 60 * 60 * 24)).toInt()
            isExpired = diff < 0
        } ?: run {
            daysUntilExpiry = Int.MAX_VALUE
            isExpired = false
        }
    }
}
