package com.nndai.myhome.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

/** A chunk and its cursor commit in one SQLite transaction, including incomplete record bytes. */
class SegmentLogCache(context: Context, deviceId: String) : SQLiteOpenHelper(
    context, "log_segments_${MessageDigest.getInstance("SHA-256").digest(deviceId.toByteArray())
        .joinToString("") { "%02x".format(it) }}.db", null, 1
) {
    data class Cursor(val generation: Long, val segment: Long, val offset: Long)
    data class Segment(val stream: Int, val generation: Long, val id: Long, val data: ByteArray)
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE segments(stream INTEGER, generation INTEGER, id INTEGER, data BLOB NOT NULL, PRIMARY KEY(stream,generation,id))")
        db.execSQL("CREATE TABLE cursors(stream INTEGER PRIMARY KEY, generation INTEGER, segment INTEGER, offset INTEGER, token TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun cursor(stream: Int): Cursor? = readableDatabase.rawQuery(
        "SELECT generation,segment,offset FROM cursors WHERE stream=?", arrayOf(stream.toString())
    ).use { c -> if (c.moveToFirst()) Cursor(c.getLong(0), c.getLong(1), c.getLong(2)) else null }

    fun token(stream: Int): String? = readableDatabase.rawQuery(
        "SELECT token FROM cursors WHERE stream=?", arrayOf(stream.toString())
    ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun resetCursor(stream: Int, generation: Long) {
        writableDatabase.insertWithOnConflict("cursors", null, ContentValues().apply {
            put("stream", stream); put("generation", generation); put("segment", 0); put("offset", 0)
            putNull("token")
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun markSynced(stream: Int, generation: Long, token: String) {
        val current = cursor(stream)
        require(current == null || current.generation == generation)
        if (current == null) resetCursor(stream, generation)
        writableDatabase.update("cursors", ContentValues().apply { put("token", token) },
            "stream=?", arrayOf(stream.toString()))
    }

    fun append(stream: Int, generation: Long, id: Long, offset: Long, data: ByteArray, complete: Boolean) {
        require(data.isNotEmpty() && data.size <= 768 && offset in 0..65536)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val prefix = db.rawQuery("SELECT data FROM segments WHERE stream=? AND generation=? AND id=?",
                arrayOf(stream.toString(), generation.toString(), id.toString())).use { c ->
                if (c.moveToFirst()) c.getBlob(0) else ByteArray(0)
            }
            // Replayed requests may revisit an existing prefix after cursor expiry.
            require(offset <= prefix.size) { "Cache offset mismatch" }
            val overlap = minOf(data.size, prefix.size - offset.toInt())
            require((0 until overlap).all { prefix[offset.toInt() + it] == data[it] }) { "Cached segment was replaced" }
            val combined = if (offset + data.size <= prefix.size) prefix else prefix + data.copyOfRange(overlap, data.size)
            require(combined.size <= 65536) { "Segment exceeds cache limit" }
            LogRecordCodec.decode(combined, stream, generation, id, complete)
            db.insertWithOnConflict("segments", null, ContentValues().apply {
                put("stream", stream); put("generation", generation); put("id", id); put("data", combined)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.insertWithOnConflict("cursors", null, ContentValues().apply {
                put("stream", stream); put("generation", generation); put("segment", id)
                put("offset", offset + data.size); putNull("token")
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun forEachSegment(stream: Int, consume: (Segment) -> Unit) {
        readableDatabase.rawQuery("SELECT generation,id,data FROM segments WHERE stream=? ORDER BY generation,id",
            arrayOf(stream.toString())).use { c ->
            while (c.moveToNext()) consume(Segment(stream, c.getLong(0), c.getLong(1), c.getBlob(2)))
        }
    }
}
