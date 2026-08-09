package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import me.rerere.rikkahub.data.db.entity.VaultCredentialEntity

@Dao
interface VaultCredentialDao {
    @Query("SELECT * FROM vault_credentials ORDER BY grp ASC, name ASC")
    suspend fun getAll(): List<VaultCredentialEntity>

    @Query("SELECT * FROM vault_credentials WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): VaultCredentialEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: VaultCredentialEntity): Long

    @Update
    suspend fun update(entry: VaultCredentialEntity)

    @Delete
    suspend fun delete(entry: VaultCredentialEntity)

    @Query("SELECT COUNT(*) FROM vault_credentials")
    suspend fun count(): Int

    @Query("DELETE FROM vault_credentials")
    suspend fun clearAll()
}
