package com.example.usbcam.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.usbcam.data.model.ShoeboxDetail
import com.example.usbcam.data.model.ShoeboxDetailRfid
import com.example.usbcam.data.model.ShoeboxTotal

@Database(
    entities = [
        ShoeboxDetail::class,
        ShoeboxTotal::class,
        ShoeboxDetailRfid::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun shoeboxDao(): ShoeboxDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // Migration from version 1 to 2 (add RFID detail table)
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

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "shoebox_database"
                )
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration() // For development; remove in production
                .enableMultiInstanceInvalidation()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
