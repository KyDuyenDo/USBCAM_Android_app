package com.example.usbcam.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.usbcam.data.model.DepLocationEntity
import com.example.usbcam.data.model.DepTypeEntity
import com.example.usbcam.data.model.DepartmentEntity
import com.example.usbcam.data.model.FactoryEntity

@Dao
interface ConfigCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFactories(items: List<FactoryEntity>)

    @Query("SELECT * FROM Config_Factory")
    suspend fun getFactories(): List<FactoryEntity>

    @Query("DELETE FROM Config_Factory")
    suspend fun clearFactories()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDepTypes(items: List<DepTypeEntity>)

    @Query("SELECT * FROM Config_DepType")
    suspend fun getDepTypes(): List<DepTypeEntity>

    @Query("DELETE FROM Config_DepType")
    suspend fun clearDepTypes()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDepLocations(items: List<DepLocationEntity>)

    @Query("SELECT * FROM Config_DepLocation WHERE depType = :depType")
    suspend fun getDepLocations(depType: Int): List<DepLocationEntity>

    @Query("DELETE FROM Config_DepLocation")
    suspend fun clearDepLocations()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDepartments(items: List<DepartmentEntity>)

    @Query("SELECT * FROM Config_Department WHERE depType = :depType AND loc = :loc")
    suspend fun getDepartments(depType: Int, loc: String): List<DepartmentEntity>

    @Query("DELETE FROM Config_Department")
    suspend fun clearDepartments()
    
    @Query("DELETE FROM Config_Factory")
    suspend fun clearAll() {
        clearFactories()
        clearDepTypes()
        clearDepLocations()
        clearDepartments()
    }
}
