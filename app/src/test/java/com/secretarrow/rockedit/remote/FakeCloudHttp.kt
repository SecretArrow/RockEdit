package com.secretarrow.rockedit.remote

import com.secretarrow.rockedit.core.CloudHttp
import com.secretarrow.rockedit.core.CloudResponse

/**
 * Deterministic in-memory [CloudHttp] for cloud client tests: the test
 * provides a routing lambda; every call is recorded for assertions.
 */
class FakeCloudHttp(
    val router: (method: String, url: String, headers: Map<String, String>, body: ByteArray?) -> CloudResponse,
) : CloudHttp {
    data class Call(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: ByteArray?,
    ) {
        fun bodyText(): String = body?.toString(Charsets.UTF_8).orEmpty()
    }

    val calls = mutableListOf<Call>()

    override fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): CloudResponse {
        calls.add(Call(method, url, headers, body))
        return router(method, url, headers, body)
    }

    fun json(
        code: Int,
        json: String,
        headers: Map<String, String> = emptyMap(),
    ): CloudResponse = CloudResponse(code, headers, json.toByteArray(Charsets.UTF_8))
}
