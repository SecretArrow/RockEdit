package com.secretarrow.rockedit.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Online code execution via the Piston API (https://emkc.org/api/v2/piston),
 * a free keyless runner. Privacy note baked into the design: the feature is
 * opt-in (explicit user consent) because submitting code sends it to a
 * third-party service.
 *
 * The network call is a plain [HttpURLConnection] POST; request building and
 * response parsing are pure and unit tested.
 */
object PistonClient {

    const val RUNTIMES_URL = "https://emkc.org/api/v2/piston/runtimes"
    const val EXECUTE_URL = "https://emkc.org/api/v2/piston/execute"

    data class ExecutionResult(
        val stdout: String,
        val stderr: String,
        val compileOutput: String,
        val statusCode: Int
    ) {
        val isSuccess: Boolean get() = statusCode == 0
        val summary: String
            get() = buildString {
                if (compileOutput.isNotBlank()) append(compileOutput).append('\n')
                if (stdout.isNotBlank()) append(stdout)
                if (stderr.isNotBlank()) append(stderr)
                if (isEmpty()) append("(no output)")
            }.trimEnd()
    }

    /** Mapping from Rock Edit syntax language ids to Piston runtime names. */
    val LANGUAGE_MAP: Map<String, String> = mapOf(
        "python" to "python",
        "javascript" to "javascript",
        "typescript" to "typescript",
        "shell" to "bash",
        "lua" to "lua",
        "perl" to "perl",
        "dart" to "dart",
        "scala" to "scala",
        "kotlin" to "kotlin",
        "java" to "java",
        "c" to "c",
        "cpp" to "c++",
        "csharp" to "c#",
        "go" to "go",
        "rust" to "rust",
        "ruby" to "ruby",
        "php" to "php",
        "swift" to "swift",
        "zig" to "zig",
        "haskell" to "haskell",
        "elixir" to "elixir",
        "julia" to "julia",
        "nim" to "nim",
        "r" to "r",
        "groovy" to "groovy",
        "fsharp" to "fsharp",
        "clojure" to "clojure",
        "erlang" to "erlang",
        "fortran" to "fortran"
    )

    /** Builds the JSON request body for /execute. */
    fun buildExecuteBody(language: String, version: String, code: String, stdin: String = ""): String =
        JSONObject()
            .put("language", language)
            .put("version", version)
            .put(
                "files",
                JSONArray().put(JSONObject().put("name", "main").put("content", code))
            )
            .put("stdin", stdin)
            .toString()

    /**
     * Finds the runtime version for [language] in a /runtimes response.
     * Returns "*" when absent (Piston accepts it in most deployments).
     */
    fun resolveVersion(runtimesJson: String, language: String): String {
        return try {
            val arr = JSONArray(runtimesJson)
            for (i in 0 until arr.length()) {
                val runtime = arr.getJSONObject(i)
                val runtimeLanguage = runtime.optString("language")
                val aliases = runtime.optJSONArray("aliases") ?: continue
                val aliasMatch = (0 until aliases.length()).any { aliases.getString(it) == language }
                if (runtimeLanguage == language || aliasMatch) {
                    return runtime.optString("version", "*")
                }
            }
            "*"
        } catch (_: Exception) {
            "*"
        }
    }

    /** Parses the /execute response into an [ExecutionResult]. */
    fun parseResponse(json: String): ExecutionResult {
        val obj = try {
            JSONObject(json)
        } catch (_: Exception) {
            return ExecutionResult("", "Unparseable response", "", -1)
        }
        val run = obj.optJSONObject("run")
        val compile = obj.optJSONObject("compile")
        return ExecutionResult(
            stdout = run?.optString("stdout").orEmpty(),
            stderr = run?.optString("stderr").orEmpty(),
            compileOutput = compile?.optString("output").orEmpty(),
            statusCode = run?.optInt("code", -1) ?: -1
        )
    }

    /**
     * Performs the whole flow over the network: fetch runtimes (cached by the
     * caller), resolve version, execute. Runs on a worker dispatcher.
     */
    fun execute(language: String, code: String, stdin: String = ""): ExecutionResult {
        val version = try {
            val conn = open(RUNTIMES_URL)
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            conn.disconnect()
            resolveVersion(body, language)
        } catch (_: Exception) {
            "*"
        }
        val conn = open(EXECUTE_URL)
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setFixedLengthStreamingMode(
                buildExecuteBody(language, version, code, stdin).toByteArray(Charsets.UTF_8).size
            )
            conn.outputStream.use {
                it.write(buildExecuteBody(language, version, code, stdin).toByteArray(Charsets.UTF_8))
            }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            parseResponse(stream?.use { s -> s.readBytes().toString(Charsets.UTF_8) } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): java.net.HttpURLConnection {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        return conn
    }
}
