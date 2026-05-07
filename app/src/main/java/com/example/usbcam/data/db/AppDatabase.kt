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
        BoxInfoCache::class          // ← Thêm bảng cache mới
    ],
    version = 3,                    // ← Tăng version từ 2 → 3
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun shoeboxDao(): ShoeboxDao
    abstract fun boxInfoCacheDao(): BoxInfoCacheDao  // ← DAO mới

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

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "shoebox_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .enableMultiInstanceInvalidation()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
