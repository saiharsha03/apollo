package dev.apollo.net

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.apollo.ui.Tone
import dev.apollo.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** One line of "What left the phone": [count] requests to [host] for [purpose], the last at [at]. */
@Immutable data class NetRow(val at: Long, val host: String, val purpose: String, val count: Int, val bytes: Long, val blocked: Boolean)

/**
 * netgate.db: the outbound log (Zeus's Outbound, simply). Host, purpose, method, request size and whether NetGate
 * blocked it; never a path, query, header or body. The same host and purpose within 10 minutes add to one row. Kept a week.
 */
class NetLog private constructor(c: Context) : SQLiteOpenHelper(c, "netgate.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sent (id INTEGER PRIMARY KEY AUTOINCREMENT, first INTEGER NOT NULL, at INTEGER NOT NULL, host TEXT NOT NULL, purpose TEXT NOT NULL, " +
            "count INTEGER NOT NULL, bytes INTEGER NOT NULL, blocked INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized fun add(host: String, purpose: String, method: String, bytes: Long, blocked: Boolean = false, now: Long = System.currentTimeMillis()) {
        runCatching {
            val db = writableDatabase
            val b = if (blocked) 1 else 0
            val merged = db.compileStatement("UPDATE sent SET at = ?, count = count + 1, bytes = bytes + ? WHERE id = (SELECT id FROM sent WHERE host = ? AND purpose = ? AND blocked = ? AND at > ? ORDER BY id DESC LIMIT 1)").apply {
                bindLong(1, now); bindLong(2, bytes); bindString(3, host); bindString(4, purpose); bindLong(5, b.toLong()); bindLong(6, now - MERGE_MS)
            }.executeUpdateDelete()
            if (merged == 0) db.insert("sent", null, ContentValues().apply {
                put("first", now); put("at", now); put("host", host); put("purpose", purpose); put("count", 1); put("bytes", bytes); put("blocked", b)
            })
            if (now % 50 == 0L) db.execSQL("DELETE FROM sent WHERE at < ?", arrayOf(now - KEEP_MS))
        }
    }

    fun rows(limit: Int = 300): List<NetRow> = readableDatabase.rawQuery("SELECT at, host, purpose, count, bytes, blocked FROM sent ORDER BY at DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
        buildList { while (c.moveToNext()) add(NetRow(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getLong(4), c.getInt(5) != 0)) }
    }

    companion object {
        const val KEEP_MS = 7 * 86_400_000L
        const val MERGE_MS = 10 * 60_000L
        @Volatile private var instance: NetLog? = null
        fun get(c: Context): NetLog = instance ?: synchronized(this) { instance ?: NetLog(c.applicationContext).also { instance = it } }
    }
}

/** "What left the phone": every host Apollo talked to this week, why, how often and how much it sent; blocked ones in red. */
@Composable
fun NetLogScreen() {
    val c = LocalContext.current
    var rows by remember { mutableStateOf<List<NetRow>>(emptyList()) }
    LaunchedEffect(Unit) { rows = withContext(Dispatchers.IO) { NetLog.get(c).rows() } }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("What left the phone", style = Type.title)
        Text("Every host Apollo reached this week and why. Only the Apollo server, CloudHost and the hosts you allowed for pages can be reached; anything else is blocked.", style = Type.small)
        Spacer(Modifier.height(12.dp))
        if (rows.isEmpty()) Text("Nothing yet.", style = Type.small)
        LazyColumn {
            items(rows, key = { "${it.host}${it.purpose}${it.at}${it.blocked}" }) { r ->
                Column(Modifier.padding(vertical = 9.dp)) {
                    Text(r.purpose, style = Type.body.copy(color = if (r.blocked) Tone.alarm else Tone.ink))
                    Text("${r.host}, ${r.count} ${if (r.count == 1) "time" else "times"}${if (r.bytes > 0) ", ${size(r.bytes)} sent" else ""}, last " +
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.at)), style = Type.small)
                }
            }
        }
    }
}

private fun size(b: Long): String = when {
    b >= 1 shl 20 -> "%.1f MB".format(b / 1048576.0)
    b >= 1024 -> "${b / 1024} KB"
    else -> "$b bytes"
}
