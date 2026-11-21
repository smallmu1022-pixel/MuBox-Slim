package com.mubox.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mubox.app.database.entity.ActiveStateEntity

@Dao
interface ActiveStateDao {

    @Query("SELECT * FROM active_state WHERE id = 1")
    suspend fun get(): ActiveStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(state: ActiveStateEntity)
}
