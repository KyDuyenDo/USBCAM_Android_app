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

        val existing = db.query(
            TABLE_ENTRIES,
            arrayOf(COL_ID),
            "$COL_SCBH=? AND $COL_GSBH=? AND $COL_TS=? AND $COL_SYNCED=0",
            arrayOf(scbh, gsbh, ts.toString()),
            null, null, null
        )

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
        }

        if (existing.moveToFirst()) {
            val id = existing.getLong(0)
            db.update(TABLE_ENTRIES, cv, "$COL_ID=?", arrayOf(id.toString()))
        } else {
            cv.put(COL_SYNCED, 0)
            db.insert(TABLE_ENTRIES, null, cv)
        }
        existing.close()
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
