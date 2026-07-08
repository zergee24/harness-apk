package com.harnessapk.storage

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderProfileDao {
    @Query("SELECT * FROM provider_profiles WHERE enabled = 1 ORDER BY updatedAt DESC")
    fun observeEnabled(): Flow<List<ProviderProfileEntity>>

    @Query("SELECT * FROM provider_profiles WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ProviderProfileEntity?

    @Query("SELECT * FROM provider_profiles WHERE enabled = 1 ORDER BY updatedAt DESC LIMIT 1")
    suspend fun firstEnabled(): ProviderProfileEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ProviderProfileEntity)

    @Update
    suspend fun update(entity: ProviderProfileEntity)

    @Delete
    suspend fun delete(entity: ProviderProfileEntity)
}
