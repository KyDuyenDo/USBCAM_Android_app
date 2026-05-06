package com.example.usbcam.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues

/**
 * SQLite database helper for storing production input entries locally.
 * Data is persisted until confirmed and synced to the server.
 */
class ProductionDbHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "production_queue.db"
        const val DATABASE_VERSION = 1

        // Table
        const val TABLE_ENTRIES = "production_entries"

        // Columns
        const val COL_ID          = "_id"
        const val COL_SCBH        = "SCBH"        // RY / lệnh sản xuất
        const val COL_DEPNO       = "DepNo"
        const val COL_GSBH        = "GSBH"        // size
        const val COL_XXCC        = "XXCC"
        const val COL_USERID      = "USERID"
        const val COL_INPUT_SRC   = "InputSource"
        const val COL_GXLB        = "GXLB"
        const val COL_QTY         = "QTY"
        const val COL_USER_DATE   = "userDate"
        const val COL_TS          = "ts"           // time-slot (1..n)
        const val COL_SYNCED      = "synced"       // 0 = pending, 1 = sent
        const val COL_SERVER_CODE = "serverCode"
        const val COL_CREATED_AT  = "createdAt"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_ENTRIES (
                $COL_ID          INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_SCBH        TEXT NOT NULL,
                $COL_DEPNO       TEXT NOT NULL,
                $COL_GSBH        TEXT NOT NULL,
                $COL_XXCC        TEXT NOT NULL,
                $COL_USERID      TEXT NOT NULL,
                $COL_INPUT_SRC   TEXT NOT NULL,
                $COL_GXLB        TEXT,
                $COL_QTY         INTEGER NOT NULL DEFAULT 0,
                $COL_USER_DATE   TEXT NOT NULL,
                $COL_TS          INTEGER NOT NULL DEFAULT 0,
                $COL_SYNCED      INTEGER NOT NULL DEFAULT 0,
                $COL_SERVER_CODE TEXT NOT NULL,
                $COL_CREATED_AT  TEXT NOT NULL
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_ENTRIES")
        onCreate(db)
    }

    /** Insert or update (upsert) an entry identified by SCBH + GSBH + ts */
    fun upsertEntry(
        scbh: String, depNo: String, gsbh: String, xxcc: String,
        userId: String, inputSource: String, gxlb: String?,
        qty: Int, userDate: String, ts: Int, serverCode: String
    ) {
        val db = writableDatabase
        val now = System.currentTimeMillis().toString()

        // Check for existing unsynced record for same RY and Size
        val cursor = db.query(
            TABLE_ENTRIES,
            arrayOf(COL_ID, COL_QTY),
            "$COL_SCBH=? AND $COL_XXCC=? AND $COL_SYNCED=0",
            arrayOf(scbh, xxcc),
            null, null, null
        )

        if (cursor.moveToFirst()) {
            val id = cursor.getLong(0)
            val cv = ContentValues().apply {
                put(COL_QTY, qty)
                put(COL_CREATED_AT, now)
                put(COL_INPUT_SRC, inputSource)
            }
            db.update(TABLE_ENTRIES, cv, "$COL_ID=?", arrayOf(id.toString()))
        } else {
            val cv = ContentValues().apply {
                put(COL_SCBH, scbh)
                put(COL_DEPNO, depNo)
                put(COL_GSBH, gsbh)
                put(COL_XXCC, xxcc)
                put(COL_USERID, userId)
                put(COL_INPUT_SRC, inputSource)
                put(COL_GXLB, gxlb)
                put(COL_QTY, qty)
                put(COL_USER_DATE, userDate)
                put(COL_TS, ts)
                put(COL_SERVER_CODE, serverCode)
                put(COL_CREATED_AT, now)
                put(COL_SYNCED, 0)
            }
            db.insert(TABLE_ENTRIES, null, cv)
        }
        cursor.close()
    }

    /** Return all pending (not yet synced) entries */
    fun getPendingEntries(): List<ProductionEntry> {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_ENTRIES, null,
            "$COL_SYNCED=0", null,
            null, null, COL_CREATED_AT + " ASC"
        )
        val list = mutableListOf<ProductionEntry>()
        while (cursor.moveToNext()) {
            list.add(
                ProductionEntry(
                    id          = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
                    scbh        = cursor.getString(cursor.getColumnIndexOrThrow(COL_SCBH)),
                    depNo       = cursor.getString(cursor.getColumnIndexOrThrow(COL_DEPNO)),
                    gsbh        = cursor.getString(cursor.getColumnIndexOrThrow(COL_GSBH)),
                    xxcc        = cursor.getString(cursor.getColumnIndexOrThrow(COL_XXCC)),
                    userId      = cursor.getString(cursor.getColumnIndexOrThrow(COL_USERID)),
                    inputSource = cursor.getString(cursor.getColumnIndexOrThrow(COL_INPUT_SRC)),
                    gxlb        = cursor.getString(cursor.getColumnIndexOrThrow(COL_GXLB)),
                    qty         = cursor.getInt(cursor.getColumnIndexOrThrow(COL_QTY)),
                    userDate    = cursor.getString(cursor.getColumnIndexOrThrow(COL_USER_DATE)),
                    ts          = cursor.getInt(cursor.getColumnIndexOrThrow(COL_TS)),
                    serverCode  = cursor.getString(cursor.getColumnIndexOrThrow(COL_SERVER_CODE)),
                    createdAt   = cursor.getString(cursor.getColumnIndexOrThrow(COL_CREATED_AT))
                )
            )
        }
        cursor.close()
        return list
    }

    /** Mark an entry as synced */
    fun markSynced(id: Long) {
        val db = writableDatabase
        val cv = ContentValues().apply { put(COL_SYNCED, 1) }
        db.update(TABLE_ENTRIES, cv, "$COL_ID=?", arrayOf(id.toString()))
    }

    /** Get map of size -> total pending qty for a specific RY */
    fun getPendingQtysForRy(scbh: String): Map<String, Int> {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_ENTRIES,
            arrayOf(COL_XXCC, COL_QTY),
            "$COL_SCBH=? AND $COL_SYNCED=0",
            arrayOf(scbh),
            null, null, null
        )
        val map = mutableMapOf<String, Int>()
        while (cursor.moveToNext()) {
            val size = cursor.getString(0)
            val qty = cursor.getInt(1)
            map[size] = (map[size] ?: 0) + qty
        }
        cursor.close()
        return map
    }

    /** Return set of RY (scbh) that have pending changes */
    fun getDirtyRys(): Set<String> {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_ENTRIES, arrayOf(COL_SCBH),
            "$COL_SYNCED=0", null,
            COL_SCBH, null, null
        )
        val set = mutableSetOf<String>()
        while (cursor.moveToNext()) {
            set.add(cursor.getString(0))
        }
        cursor.close()
        return set
    }

    /** Delete an entry from the database */
    fun deleteEntry(id: Long) {
        val db = writableDatabase
        db.delete(TABLE_ENTRIES, "$COL_ID=?", arrayOf(id.toString()))
    }

    /** Clear all entries that are marked as synced */
    fun clearAllSynced() {
        val db = writableDatabase
        db.delete(TABLE_ENTRIES, "$COL_SYNCED=1", null)
    }
}

data class ProductionEntry(
    val id: Long,
    val scbh: String,
    val depNo: String,
    val gsbh: String,
    val xxcc: String,
    val userId: String,
    val inputSource: String,
    val gxlb: String?,
    val qty: Int,
    val userDate: String,
    val ts: Int,
    val serverCode: String,
    val createdAt: String
)
