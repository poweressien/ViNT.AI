package ng.pidgingpt.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import java.io.File

data class SlangEntry(val expression: String, val meaning: String, val contextual: String, val examples: List<String>,
                      val altSpellings: List<String>, val related: List<String>, val category: String, val tone: String,
                      val source: String, val license: String, val provenance: String, val confidence: Double,
                      val humanVerified: Boolean, val synthetic: Boolean)

/** Read-only access to slang.db, built by `pidgin-pipeline build-db` and bundled in assets/. Uses FTS5 (bundled SQLite on API 26+ supports it; verify on device). */
class SlangRepository(private val ctx: Context) {
    private val dbFile get() = File(ctx.filesDir, "slang.db")

    private fun db(): SQLiteDatabase {
        val assetLen = ctx.assets.openFd("slang.db").use { it.length }                // db is stored uncompressed (noCompress "db")
        if (!dbFile.exists() || dbFile.length() != assetLen) ctx.assets.open("slang.db").use { i -> dbFile.outputStream().use { o -> i.copyTo(o) } }   // refresh after app updates
        return SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
    }

    fun search(query: String, limit: Int = 30): List<SlangEntry> {
        val q = query.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s']"), " ").trim()
        if (q.isEmpty()) return emptyList()
        val fts = q.split(Regex("\\s+")).joinToString(" ") { "\"$it\"*" }
        return db().use { d ->
            d.rawQuery("SELECT e.expression,e.meaning_in_pidgin,e.contextual_meaning,e.usage_examples,e.alternative_spellings," +
                "e.related_expressions,e.category,e.tone,e.source,e.license,e.provenance,e.confidence,e.human_verified,e.synthetic " +
                "FROM expressions_fts f JOIN expressions e ON e.id=f.id WHERE expressions_fts MATCH ? ORDER BY rank LIMIT ?",
                arrayOf(fts, "$limit")).use { c ->
                buildList { while (c.moveToNext()) add(SlangEntry(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "",
                    list(c.getString(3)), list(c.getString(4)), list(c.getString(5)), c.getString(6) ?: "", c.getString(7) ?: "",
                    c.getString(8) ?: "", c.getString(9) ?: "", c.getString(10) ?: "", c.getDouble(11), c.getInt(12) == 1, c.getInt(13) == 1)) } }
        }
    }

    private fun list(json: String?): List<String> = runCatching { JSONArray(json ?: "[]").let { a -> List(a.length()) { a.getString(it) } } }.getOrDefault(emptyList())

    fun count(): Int = db().use { d -> d.rawQuery("SELECT COUNT(*) FROM expressions", null).use { it.moveToFirst(); it.getInt(0) } }
}
