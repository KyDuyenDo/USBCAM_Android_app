package com.example.usbcam.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity lưu trữ danh sách xưởng (Factory)
 */
@Entity(tableName = "Config_Factory")
data class FactoryEntity(
    @PrimaryKey val value: String,
    val label: String?
)

/**
 * Entity lưu trữ loại bộ phận (Department Type)
 */
@Entity(tableName = "Config_DepType")
data class DepTypeEntity(
    @PrimaryKey val value: Int,
    val label: String?
)

/**
 * Entity lưu trữ vị trí bộ phận theo loại (Department Location)
 */
@Entity(tableName = "Config_DepLocation")
data class DepLocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val depType: Int,
    val loc: String?
)

/**
 * Entity lưu trữ danh sách bộ phận (Department/Line)
 */
@Entity(tableName = "Config_Department")
data class DepartmentEntity(
    @PrimaryKey val id: String,
    val depName: String?,
    val depType: Int,
    val loc: String
)
