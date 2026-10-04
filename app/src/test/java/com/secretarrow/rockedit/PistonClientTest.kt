package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.PistonClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PistonClientTest {
    @Test
    fun executeBodyContainsLanguageVersionAndCode() {
        val body = JSONObject(PistonClient.buildExecuteBody("python", "3.10.0", "print(1)"))
        assertEquals("python", body.getString("language"))
        assertEquals("3.10.0", body.getString("version"))
        assertEquals("print(1)", body.getJSONArray("files").getJSONObject(0).getString("content"))
        assertEquals("", body.getString("stdin"))
    }

    @Test
    fun languageMapCoversCommonSyntaxIds() {
        val expected =
            listOf(
                "python",
                "javascript",
                "typescript",
                "shell",
                "kotlin",
                "java",
                "c",
                "cpp",
                "go",
                "rust",
                "ruby",
                "php",
                "lua",
                "perl",
                "dart",
                "scala",
            )
        for (id in expected) {
            assertTrue("missing piston mapping for $id", PistonClient.LANGUAGE_MAP.containsKey(id))
        }
        assertFalse(PistonClient.LANGUAGE_MAP.containsKey("markdown_html"))
    }

    @Test
    fun resolveVersionMatchesLanguageAndAliases() {
        val runtimes =
            """
            [
              {"language":"python","version":"3.10.0","aliases":["py","python3"]},
              {"language":"javascript","version":"18.15.0","aliases":["node","js"]}
            ]
            """.trimIndent()
        assertEquals("3.10.0", PistonClient.resolveVersion(runtimes, "python"))
        assertEquals("18.15.0", PistonClient.resolveVersion(runtimes, "js"))
        assertEquals("3.10.0", PistonClient.resolveVersion(runtimes, "py"))
        assertEquals("*", PistonClient.resolveVersion(runtimes, "cobol"))
        assertEquals("*", PistonClient.resolveVersion("broken json", "python"))
    }

    @Test
    fun parseResponseExtractsStreams() {
        val response =
            """
            {
              "language": "python",
              "version": "3.10.0",
              "run": {"stdout": "hello\n", "stderr": "", "code": 0, "output": "hello\n"},
              "compile": null
            }
            """.trimIndent()
        val result = PistonClient.parseResponse(response)
        assertTrue(result.isSuccess)
        assertEquals("hello\n", result.stdout)
        assertEquals("", result.stderr)
        assertEquals("hello", result.summary)
    }

    @Test
    fun parseResponseHandlesCompileOutputAndErrors() {
        val failing = PistonClient.parseResponse("""{"run":{"stdout":"","stderr":"boom","code":1},"compile":{"output":"warning"}}""")
        assertFalse(failing.isSuccess)
        assertTrue(failing.summary.contains("warning"))
        assertTrue(failing.summary.contains("boom"))

        val garbage = PistonClient.parseResponse("{nope")
        assertEquals(-1, garbage.statusCode)
        assertTrue(garbage.summary.contains("Unparseable"))
    }
}
