package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.FtpListParser
import com.secretarrow.rockedit.core.WebDavParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FtpListParserTest {
    @Test
    fun parsesMlsdEntries() {
        val files =
            FtpListParser.parseMlsd(
                listOf(
                    "type=dir;size=0;modify=20240101120000; public",
                    "type=file;size=1024;modify=20240203130405; notes.txt",
                    "type=dir;size=0;modify=20240101120000; .",
                    "total 4",
                ),
            )
        assertEquals(2, files.size)
        val dir = files[0]
        assertTrue(dir.isFolder)
        assertEquals("public", dir.name)
        val file = files[1]
        assertFalse(file.isFolder)
        assertEquals(1024, file.size)
        assertEquals("notes.txt", file.name)
    }

    @Test
    fun parsesUnixListLines() {
        val files =
            FtpListParser.parseUnix(
                listOf(
                    "total 24",
                    "drwxr-xr-x  2 user group 4096 Jan  1 12:00 src",
                    "-rw-r--r--  1 user group  512 Feb  3 13:40 main.kt",
                    "drwxr-xr-x  2 user group 4096 Jan  1 12:00 ..",
                ),
            )
        assertEquals(2, files.size)
        assertTrue(files[0].isFolder)
        assertEquals("src", files[0].name)
        assertFalse(files[1].isFolder)
        assertEquals("main.kt", files[1].name)
        assertEquals(512, files[1].size)
    }

    @Test
    fun parseUnixLineRejectsGarbage() {
        assertNull(FtpListParser.parseUnixLine(""))
        assertNull(FtpListParser.parseUnixLine("just a name"))
        assertNull(FtpListParser.parseUnixLine("drwxr-xr-x 2 user group 4096 Jan 1"))
    }

    @Test
    fun timestampParsing() {
        val millis = FtpListParser.parseTimestamp("20240101120000")
        assertTrue(millis != null)
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        calendar.timeInMillis = millis!!
        assertEquals(2024, calendar.get(java.util.Calendar.YEAR))
        assertEquals(0, calendar.get(java.util.Calendar.MONTH))
        assertEquals(1, calendar.get(java.util.Calendar.DAY_OF_MONTH))
        assertNull(FtpListParser.parseTimestamp("bogus"))
    }
}

class WebDavParserTest {
    private val sampleXml =
        """
        <?xml version="1.0" encoding="utf-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response>
            <D:href>/dav/</D:href>
            <D:propstat><D:prop>
              <D:resourcetype><D:collection/></D:resourcetype>
              <D:getlastmodified>Tue, 01 Jan 2024 12:00:00 GMT</D:getlastmodified>
            </D:prop></D:propstat>
          </D:response>
          <D:response>
            <D:href>/dav/docs/</D:href>
            <D:propstat><D:prop>
              <D:resourcetype><D:collection/></D:resourcetype>
            </D:prop></D:propstat>
          </D:response>
          <D:response>
            <D:href>/dav/notes.txt</D:href>
            <D:propstat><D:prop>
              <D:resourcetype/>
              <D:getcontentlength>2048</D:getcontentlength>
            </D:prop></D:propstat>
          </D:response>
        </D:multistatus>
        """.trimIndent()

    @Test
    fun parsesCollectionsAndFiles() {
        val resources = WebDavParser.parsePropfind(sampleXml)
        assertEquals(3, resources.size)
        assertTrue(resources[0].isCollection)
        assertTrue(resources[1].isCollection)
        assertFalse(resources[2].isCollection)
        assertEquals("/dav/notes.txt", WebDavParser.hrefToPath(resources[2].href))
        assertEquals(2048, resources[2].size)
    }

    @Test
    fun hrefToPathDecodesAndNormalizes() {
        assertEquals("/dav/a b", WebDavParser.hrefToPath("/dav/a%20b"))
        assertEquals("/dav", WebDavParser.hrefToPath("https://host:8080/dav/"))
        assertEquals("/", WebDavParser.hrefToPath("/"))
    }

    @Test
    fun malformedXmlYieldsEmptyList() {
        assertTrue(WebDavParser.parsePropfind("<broken").isEmpty())
        assertTrue(WebDavParser.parsePropfind("").isEmpty())
    }
}
