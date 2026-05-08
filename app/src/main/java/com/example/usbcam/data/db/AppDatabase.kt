package com.example.usbcam.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.usbcam.data.model.BoxInfoCache
import com.example.usbcam.data.model.ShoeboxDetail
import com.example.usbcam.data.model.ShoeboxDetailRfid
import com.example.usbcam.data.model.ShoeboxTotal

@Database(
    entities = [
        ShoeboxDetail::class,
        ShoeboxTotal::class,
        ShoeboxDetailRfid::class,
        BoxInfoCache::class,
        com.example.usbcam.data.model.FactoryEntity::class,
        com.example.usbcam.data.model.DepTypeEntity::class,
        com.example.usbcam.data.model.DepLocationEntity::class,
        com.example.usbcam.data.model.DepartmentEntity::class
    ],
    version = 5,                    // ← Tăng version từ 4 → 5
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun shoeboxDao(): ShoeboxDao
    abstract fun boxInfoCacheDao(): BoxInfoCacheDao
    abstract fun configCacheDao(): ConfigCacheDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // Migration 1 → 2: Thêm bảng RFID mismatch
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `Data_Shoebox_RFID_Detail` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `RY` TEXT,
                        `Size` TEXT,
                        `PO` TEXT,
                        `UPC` TEXT,
                        `Qty` INTEGER NOT NULL,
                        `Article` TEXT,
                        `RFID` TEXT,
                        `Size_RFID` TEXT,
                        `PO_RFID` TEXT,
                        `UPC_RFID` TEXT,
                        `Article_RFID` TEXT,
                        `RY_RFID` TEXT,
                        `MismatchFields` TEXT,
                        `DateScan` TEXT NOT NULL,
                        `Modify` TEXT,
                        `ShoeImage` TEXT,
                        `User_Serial_Key` TEXT,
                        `Line` TEXT,
                        `Synced` INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        // Migration 2 → 3: Thêm bảng Box_Info_Cache
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `Box_Info_Cache` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `UPC` TEXT,
                        `SIZE` TEXT,
                        `PO` TEXT,
                        `RY` TEXT,
                        `Article` TEXT,
                        `Article_Image` TEXT,
                        `Quantity` INTEGER,
                        `CachedAt` INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_Box_Info_Cache_UPC` ON `Box_Info_Cache` (`UPC`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_Box_Info_Cache_PO` ON `Box_Info_Cache` (`PO`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_Box_Info_Cache_UPC_PO` ON `Box_Info_Cache` (`UPC`, `PO`)")
            }
        }

        // Migration 3 → 4: Thêm các trường mới cho Box_Info_Cache
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `Box_Info_Cache` ADD COLUMN `TotalQuantity` INTEGER")
                database.execSQL("ALTER TABLE `Box_Info_Cache` ADD COLUMN `COUNTRY` TEXT")
                database.execSQL("ALTER TABLE `Box_Info_Cache` ADD COLUMN `LEAN` TEXT")
                database.execSQL("ALTER TABLE `Box_Info_Cache` ADD COLUMN `Remain` INTEGER")
            }
        }

        // Migration 4 → 5: Thêm các bảng cấu hình hệ thống
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `Config_Factory` (`value` TEXT NOT NULL, `label` TEXT, PRIMARY KEY(`value`))")
                database.execSQL("CREATE TABLE IF NOT EXISTS `Config_DepType` (`value` INTEGER NOT NULL, `label` TEXT, PRIMARY KEY(`value`))")
                database.execSQL("CREATE TABLE IF NOT EXISTS `Config_DepLocation` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `depType` INTEGER NOT NULL, `loc` TEXT)")
                database.execSQL("CREATE TABLE IF NOT EXISTS `Config_Department` (`id` TEXT NOT NULL, `depName` TEXT, `depType` INTEGER NOT NULL, `loc` TEXT NOT NULL, PRIMARY KEY(`id`))")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "shoebox_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigration()
                .enableMultiInstanceInvalidation()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
