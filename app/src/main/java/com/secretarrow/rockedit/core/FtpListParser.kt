package com.secretarrow.rockedit.core

/**
 * Parser for FTP directory listings. Handles MLSD machine-readable format
 * and the common Unix `LIST` format. Pure JVM, fully unit tested.
 */
object FtpListParser {
    /**
     * Parses an MLSD listing: `type=dir;size=0;modify=20240101120000; name`.
     * Lines without a fact list are ignored.
     */
    fun parseMlsd(lines: List<String>): List<RemoteFile> =
        lines.mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val sep = line.indexOf("; ")
            if (sep < 0) return@mapNotNull null
            val facts = line.substring(0, sep).split(';')
            val name = line.substring(sep + 2)
            if (name.isEmpty() || name == "." || name == "..") return@mapNotNull null
            var isDir = false
            var size = -1L
            var mtime = 0L
            for (fact in facts) {
                val kv = fact.trim().split('=', limit = 2)
                if (kv.size != 2) continue
                when (kv[0].lowercase()) {
                    "type" -> isDir = kv[1].equals("dir", ignoreCase = true)
                    "size" -> size = kv[1].toLongOrNull() ?: -1L
                    "modify" -> mtime = parseTimestamp(kv[1]) ?: 0L
                }
            }
            RemoteFile(name = name, path = name, isFolder = isDir, size = size, lastModified = mtime)
        }

    /**
     * Parses a Unix-style `LIST` line:
     * `drwxr-xr-x  2 owner group 4096 Jan  1 12:00 name`
     * Returns null for lines it cannot interpret (totals, headers, Windows).
     */
    fun parseUnixLine(line: String): RemoteFile? {
        if (line.isBlank()) return null
        val parts = line.trim().split(Regex("\\s+"), limit = 9)
        if (parts.size < 9) return null
        val permissions = parts[0]
        if (permissions.length < 10) return null
        val isDir = permissions[0] == 'd'
        val size = parts[4].toLongOrNull() ?: return null
        // Name starts after the fixed 8 columns (perm, links, owner, group, size, month, day, time/year)
        val name = parts[8]
        if (name.isEmpty() || name == "." || name == "..") return null
        val mtime = parseListDate(parts[5], parts[6], parts[7]) ?: 0L
        return RemoteFile(name = name, path = name, isFolder = isDir, size = size, lastModified = mtime)
    }

    fun parseUnix(lines: List<String>): List<RemoteFile> = lines.mapNotNull { parseUnixLine(it) }

    /**
     * Parses "Jan  1 12:00" (current year assumed) or "Jan  1  2024".
     * Returns epoch millis or null when unparseable.
     */
    fun parseListDate(
        month: String,
        day: String,
        timeOrYear: String,
    ): Long? {
        val monthNum =
            when (month.take(3).lowercase()) {
                "jan" -> 0
                "feb" -> 1
                "mar" -> 2
                "apr" -> 3
                "may" -> 4
                "jun" -> 5
                "jul" -> 6
                "aug" -> 7
                "sep" -> 8
                "oct" -> 9
                "nov" -> 10
                "dec" -> 11
                else -> return null
            }
        val dayNum = day.toIntOrNull() ?: return null
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        calendar.clear()
        return if (timeOrYear.contains(':')) {
            val (h, m) =
                timeOrYear.split(':', limit = 2).let {
                    (it.getOrNull(0)?.toIntOrNull() ?: 0) to (it.getOrNull(1)?.toIntOrNull() ?: 0)
                }
            // Year unknown: assume current year.
            calendar.set(
                java.util.Calendar
                    .getInstance()
                    .get(java.util.Calendar.YEAR),
                monthNum,
                dayNum,
                h,
                m,
                0,
            )
            calendar.timeInMillis
        } else {
            val year = timeOrYear.toIntOrNull() ?: return null
            calendar.set(year, monthNum, dayNum, 0, 0, 0)
            calendar.timeInMillis
        }
    }

    /** Parses MLSD "modify=20240101120000" timestamps. */
    fun parseTimestamp(stamp: String): Long? {
        if (stamp.length < 14) return null
        val year = stamp.substring(0, 4).toIntOrNull() ?: return null
        val month = stamp.substring(4, 6).toIntOrNull() ?: return null
        val day = stamp.substring(6, 8).toIntOrNull() ?: return null
        val hour = stamp.substring(8, 10).toIntOrNull() ?: return null
        val minute = stamp.substring(10, 12).toIntOrNull() ?: return null
        val second = stamp.substring(12, 14).toIntOrNull() ?: return null
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        calendar.clear()
        calendar.set(year, month - 1, day, hour, minute, second)
        return calendar.timeInMillis
    }
}
