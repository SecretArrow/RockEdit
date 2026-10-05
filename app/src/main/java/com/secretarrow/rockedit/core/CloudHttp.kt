package com.secretarrow.rockedit.core

/**
 * Minimal injectable HTTP transport for OAuth and cloud API calls so every
 * cloud component stays pure-JVM and unit testable. The production
 * implementation lives in `remote.HttpUrlCloudHttp`.
 */
interface CloudHttp {
    /**
     * Performs [method] against [url]. [body] is sent as-is (may be null for
     * GET/DELETE). Implementations must not follow cross-host redirects
     * blindly when an Authorization header is present — clients handle
     * redirects explicitly where the provider requires it.
     */
    fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): CloudResponse
}

/** Response snapshot of [CloudHttp]. */
class CloudResponse(
    val code: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    fun text(): String = body.toString(Charsets.UTF_8)

    /** Case-insensitive header lookup; empty string when absent. */
    fun header(name: String): String =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value.orEmpty()

    val isSuccess: Boolean get() = code in 200..299
}
