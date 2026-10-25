package ng.pidgingpt.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Conversation(val id: Long, val title: String, val updatedAt: Long)
data class StoredMessage(val id: Long, val conversationId: Long, val role: String, val content: String, val createdAt: Long, val provider: String? = null)

/** Local-only chat history (SQLite in app-private storage). No sync, no cloud. Schema v2 adds messages.provider (which provider wrote a reply), so history stays honest when providers change. */
class ConversationStore(ctx: Context) : SQLiteOpenHelper(ctx, "conversations.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE conversations(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT, conversation_id INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE, role TEXT NOT NULL, content TEXT NOT NULL, created_at INTEGER NOT NULL, provider TEXT)")
        db.execSQL("CREATE INDEX idx_msg_conv ON messages(conversation_id)")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) { if (o < 2) db.execSQL("ALTER TABLE messages ADD COLUMN provider TEXT") }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }

    fun newConversation(title: String = "New chat"): Long = writableDatabase.insert("conversations", null,
        ContentValues().apply { put("title", title); put("updated_at", System.currentTimeMillis()) })

    fun rename(id: Long, title: String) { writableDatabase.update("conversations", ContentValues().apply { put("title", title) }, "id=?", arrayOf("$id")) }
    fun delete(id: Long) { writableDatabase.delete("conversations", "id=?", arrayOf("$id")) }
    fun clearAll() { writableDatabase.delete("messages", null, null); writableDatabase.delete("conversations", null, null) }

    fun addMessage(convId: Long, role: String, content: String, provider: String? = null): Long {
        val now = System.currentTimeMillis()
        writableDatabase.update("conversations", ContentValues().apply { put("updated_at", now) }, "id=?", arrayOf("$convId"))
        return writableDatabase.insert("messages", null, ContentValues().apply {
            put("conversation_id", convId); put("role", role); put("content", content); put("created_at", now); put("provider", provider) })
    }

    fun updateMessage(id: Long, content: String, provider: String? = null) { writableDatabase.update("messages", ContentValues().apply { put("content", content); if (provider != null) put("provider", provider) }, "id=?", arrayOf("$id")) }

    fun messages(convId: Long): List<StoredMessage> = readableDatabase.rawQuery(
        "SELECT id,conversation_id,role,content,created_at,provider FROM messages WHERE conversation_id=? ORDER BY id", arrayOf("$convId")).use { c ->
        buildList { while (c.moveToNext()) add(StoredMessage(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getLong(4), if (c.isNull(5)) null else c.getString(5))) } }

    fun conversations(query: String = ""): List<Conversation> {
        val like = "%${query.replace("%", "").replace("_", "")}%"
        return readableDatabase.rawQuery(
            "SELECT DISTINCT c.id,c.title,c.updated_at FROM conversations c LEFT JOIN messages m ON m.conversation_id=c.id " +
            "WHERE ?='%%' OR c.title LIKE ? OR m.content LIKE ? ORDER BY c.updated_at DESC", arrayOf(like, like, like)).use { c ->
            buildList { while (c.moveToNext()) add(Conversation(c.getLong(0), c.getString(1), c.getLong(2))) } }
    }

    /** Retention: delete conversations not updated within [days]. days<=0 means keep forever. */
    fun purgeOlderThan(days: Int) {
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        writableDatabase.delete("conversations", "updated_at<?", arrayOf("$cutoff"))
    }
}
