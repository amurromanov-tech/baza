package com.family.base.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.family.base.data.local.entity.LockEntity

@Dao
interface LockDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLock(lock: LockEntity)

    @Delete
    suspend fun deleteLock(lock: LockEntity)

    @Query("DELETE FROM locks WHERE objectId = :objectId")
    suspend fun deleteLockByObjectId(objectId: String)

    @Query("SELECT * FROM locks WHERE objectId = :objectId")
    suspend fun getLockByObjectId(objectId: String): LockEntity?

    @Query("SELECT * FROM locks WHERE objectId = :objectId AND expiresAt > :currentTime")
    suspend fun getActiveLock(objectId: String, currentTime: Long): LockEntity?

    @Query("SELECT * FROM locks WHERE expiresAt < :currentTime")
    suspend fun getExpiredLocks(currentTime: Long): List<LockEntity>

    @Query("DELETE FROM locks WHERE expiresAt < :currentTime")
    suspend fun deleteExpiredLocks(currentTime: Long)

    @Query("SELECT * FROM locks WHERE lockedBy = :userEmail")
    suspend fun getLocksByUser(userEmail: String): List<LockEntity>

    @Query("DELETE FROM locks")
    suspend fun deleteAllLocks()
}
