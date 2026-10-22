package ng.pidgingpt.voice

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Extracts a model ZIP into [dest] with zip-slip, size and entry-count protection. If the archive has one top-level folder it is flattened. */
object SafeUnzip {
    class UnsafeArchive(msg: String) : IOException(msg)

    fun extract(input: InputStream, dest: File, maxTotalBytes: Long = 2L shl 30, maxEntries: Int = 20_000): Int {
        dest.mkdirs(); val root = dest.canonicalFile
        // ZIP streams are single-pass, so entries are validated and spooled to a temp dir first, then moved (flattening one top-level folder).
        val tmp = File(dest.parentFile ?: dest, dest.name + ".tmp-" + System.nanoTime()).also { it.mkdirs() }
        val tmpRoot = tmp.canonicalFile; var total = 0L; var n = 0; val tops = HashSet<String>(); var hasTopLevelFile = false
        try {
            ZipInputStream(input).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (++n > maxEntries) throw UnsafeArchive("Too many entries")
                    val name = e.name.replace('\\', '/')
                    if (name.startsWith("/") || name.split('/').any { it == ".." } || name.contains(':')) throw UnsafeArchive("Unsafe path: ${e.name}")
                    val out = File(tmpRoot, name).canonicalFile
                    if (!out.path.startsWith(tmpRoot.path + File.separator) && out != tmpRoot) throw UnsafeArchive("Path escapes target: ${e.name}")
                    val first = name.substringBefore('/'); if (name.contains('/')) tops.add(first) else if (!e.isDirectory) hasTopLevelFile = true
                    if (e.isDirectory) { out.mkdirs(); continue }
                    out.parentFile.mkdirs()
                    out.outputStream().use { o -> val buf = ByteArray(1 shl 16); while (true) { val r = zin.read(buf); if (r < 0) break
                        total += r; if (total > maxTotalBytes) throw UnsafeArchive("Archive too large when unpacked"); o.write(buf, 0, r) } }
                }
            }
            val src = if (tops.size == 1 && !hasTopLevelFile) File(tmpRoot, tops.first()) else tmpRoot
            var moved = 0
            src.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(src).path; val target = File(root, rel); target.parentFile.mkdirs(); f.copyTo(target, overwrite = true); moved++ }
            return moved
        } finally { tmp.deleteRecursively() }
    }
}
