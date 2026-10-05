package com.secretarrow.rockedit.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.secretarrow.rockedit.core.WasmFormatterContract
import com.secretarrow.rockedit.core.WasmHostUnavailableException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Headless WebView host for the prettier engine (backlog item 10).
 *
 * Owns exactly one lazily created [WebView] that loads
 * `file:///android_asset/formatter/host.html` (prettier 2.8.8 + five
 * parser plugins, 100% local, no network). [launch] runs one synchronous
 * format request: it transfers the request envelope into
 * `window.__rockeditPayload` and evaluates
 * `rockeditFormat(window.__rockeditPayload)`, whose JSON string return
 * value is passed back to [com.secretarrow.rockedit.core.WasmCodeFormatter].
 *
 * Threading: [init] may be called on any thread (normally app start);
 * [launch] MUST be called from a non-main thread (the editor formats on
 * Dispatchers.Default). All WebView operations are marshalled to the main
 * thread through a [Handler]; waiting threads block on latches bounded by
 * the caller's time budget.
 *
 * Warm-up: [init] already builds the engine, so the one-off asset load
 * (~2.4 MB of JS) is paid during startup instead of during the first
 * format. Without init (or after a crash) [launch] recreates the engine
 * on demand — creation happens at most once and only while the WASM path
 * is actually used.
 *
 * Memory note: the engine is kept for the process lifetime (a fresh
 * WebView + renderer would cost more than it saves); documents up to
 * [com.secretarrow.rockedit.core.WasmFormatterCatalog.MAX_INPUT_CHARS]
 * travel through evaluateJavascript, which is chunked internally and does
 * not hit the 1 MB Binder transaction limit.
 *
 * Defensive rules (kejadian -> penanganan):
 *
 * | Event                                   | Handling |
 * |-----------------------------------------|----------|
 * | 1. launch() called on the main thread    | throw WasmHostUnavailableException("called on main thread") before touching any WebView state |
 * | 2. init() never called                   | throw WasmHostUnavailableException("host not initialized") |
 * | 3. Asset load error (main frame)         | pageFailed set; waiters fail fast with WasmHostUnavailableException; engine destroyed |
 * | 4. Page load exceeds the budget          | engine destroyed + WasmHostUnavailableException("timeout after Xms") |
 * | 5. evaluateJavascript callback never fires / engine died | same timeout path — destroy + throw; a late callback is dropped by a once-guard |
 * | 6. Engine returns null / "null" / garbage | WasmHostUnavailableException("empty engine response" / envelope check) |
 * | 7. Any unexpected Throwable (creation, IPC, interruption) | engine destroyed, converted to WasmHostUnavailableException — NOTHING else escapes to the caller |
 * | 8. Renderer process gone (WebView crash) | onRenderProcessGone: destroy + clear state so the next call builds a fresh engine; current callers fail with a clear reason |
 */
object WasmFormatterHost {
    private const val HOST_PAGE_URL = "file:///android_asset/formatter/host.html"
    private const val SET_PAYLOAD_SCRIPT = "window.__rockeditPayload = "
    private const val CALL_FORMAT_SCRIPT = "rockeditFormat(window.__rockeditPayload)"

    @Volatile
    private var appContext: Context? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    /** The single engine; only touched on the main thread, guarded by [lock]. */
    private var engine: WebView? = null

    /** True once host.html reported onPageFinished. Guarded by [lock]. */
    private var ready: Boolean = false

    /** First fatal load/creation problem; cleared when a new engine is built. */
    private var pageFailed: String? = null

    /**
     * Stores the application context (idempotent, last writer wins) and
     * starts an optional warm-up so the first format is fast. Never throws.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        try {
            scheduleEngineCreation()
        } catch (_: Throwable) {
            // Warm-up is best effort; launch() rebuilds on demand and
            // reports real problems through WasmHostUnavailableException.
        }
    }

    /**
     * Runs one format request synchronously and returns the JSON response
     * envelope produced by host.html.
     *
     * @param payload  request envelope from [WasmFormatterContract.buildPayload]
     * @param budgetMs total wall-clock budget for page readiness, payload
     *     transfer and the format call
     * @throws WasmHostUnavailableException whenever the engine could not be
     *     run — and NEVER any other exception (rule 7)
     */
    fun launch(
        payload: String,
        budgetMs: Long,
    ): String =
        try {
            launchInternal(payload, budgetMs)
        } catch (e: WasmHostUnavailableException) {
            throw e
        } catch (t: Throwable) {
            synchronized(lock) {
                destroyEngineLocked()
                (lock as java.lang.Object).notifyAll()
            }
            throw WasmHostUnavailableException(
                "prettier host failed: ${t.message ?: t::class.java.simpleName}",
            )
        }

    private fun launchInternal(
        payload: String,
        budgetMs: Long,
    ): String {
        // Rule 1 — a WebView wait on the UI thread would ANR the editor.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw WasmHostUnavailableException(
                "called on main thread — run the formatter on Dispatchers.Default or IO",
            )
        }
        // Rule 2 — without a context no WebView can ever be built.
        if (appContext == null) {
            throw WasmHostUnavailableException(
                "host not initialized — call WasmFormatterHost.init(context) first",
            )
        }
        val deadlineAt = System.currentTimeMillis() + budgetMs.coerceAtLeast(1)
        scheduleEngineCreation()
        val webView = awaitReady(deadlineAt, budgetMs)
        // Rule 6/7 — two bounded steps: transfer, then call.
        evaluateJs(webView, SET_PAYLOAD_SCRIPT + jsStringLiteral(payload), deadlineAt, budgetMs, "payload transfer")
        val response = evaluateJs(webView, CALL_FORMAT_SCRIPT, deadlineAt, budgetMs, "prettier format call")
        return unwrapResponse(response)
    }

    // ------------------------------------------------------------- engine

    private fun scheduleEngineCreation() {
        mainHandler.post { createEngineOnMain() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createEngineOnMain() {
        try {
            synchronized(lock) {
                if (engine != null) return
                ready = false
                pageFailed = null
            }
            val context = appContext
            if (context == null) {
                markPageFailed("host not initialized")
                return
            }
            val webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            // file:///android_asset access is required to load host.html.
            webView.settings.allowFileAccess = true
            webView.webViewClient =
                object : WebViewClient() {
                    override fun onPageFinished(
                        view: WebView,
                        url: String,
                    ) {
                        synchronized(lock) {
                            ready = true
                            (lock as java.lang.Object).notifyAll()
                        }
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        // Subresource hiccups are not fatal; a broken main
                        // frame means host.html itself is unreadable.
                        // Only the description is used: code is redundant and
                        // its getter is not resolvable on every SDK stub.
                        if (!request.isForMainFrame) return
                        markPageFailed("asset load failed: ${error.description}")
                    }

                    override fun onRenderProcessGone(
                        view: WebView,
                        detail: RenderProcessGoneDetail,
                    ): Boolean {
                        // Rule 8 — renderer crash: drop the engine so the
                        // next call builds a fresh one; wake current waiters.
                        markPageFailed("render process gone")
                        return true
                    }
                }
            synchronized(lock) { engine = webView }
            webView.loadUrl(HOST_PAGE_URL)
        } catch (t: Throwable) {
            markPageFailed("engine creation failed: ${t.message ?: t::class.java.simpleName}")
        }
    }

    /** Blocks until the host page is loaded, the budget ends or loading fails. */
    private fun awaitReady(
        deadlineAt: Long,
        budgetMs: Long,
    ): WebView =
        synchronized(lock) {
            while (true) {
                // Rule 3 — a recorded failure wakes every waiter immediately.
                pageFailed?.let { reason ->
                    throw WasmHostUnavailableException("host page failed to load: $reason")
                }
                val current = engine
                if (ready && current != null) return current
                val remainingMs = deadlineAt - System.currentTimeMillis()
                if (remainingMs <= 0) {
                    // Rule 4 — never leave a half-loaded engine behind.
                    destroyEngineLocked()
                    (lock as java.lang.Object).notifyAll()
                    throw WasmHostUnavailableException(
                        "timeout after ${budgetMs}ms waiting for the prettier WebView",
                    )
                }
                (lock as java.lang.Object).wait(remainingMs)
            }
            // Unreachable: the loop above only exits through return/throw.
            // This terminal expression keeps the lambda's type as WebView.
            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException("awaitReady loop exited unexpectedly")
        }

    /**
     * Runs one script on the main thread and returns the raw
     * evaluateJavascript result (a JSON-encoded JS value).
     */
    private fun evaluateJs(
        webView: WebView,
        script: String,
        deadlineAt: Long,
        budgetMs: Long,
        step: String,
    ): String {
        val holder = AtomicReference<String?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val done = CountDownLatch(1)
        val settled = AtomicBoolean(false)
        mainHandler.post {
            try {
                webView.evaluateJavascript(script) { value ->
                    // Rule 5 — drop late callbacks after a timeout.
                    if (settled.compareAndSet(false, true)) {
                        holder.set(value)
                        done.countDown()
                    }
                }
            } catch (t: Throwable) {
                if (settled.compareAndSet(false, true)) {
                    failure.set(t)
                    done.countDown()
                }
            }
        }
        val remainingMs = deadlineAt - System.currentTimeMillis()
        val finished = remainingMs > 0 && done.await(remainingMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            synchronized(lock) {
                destroyEngineLocked()
                (lock as java.lang.Object).notifyAll()
            }
            throw WasmHostUnavailableException("timeout after ${budgetMs}ms during $step")
        }
        failure.get()?.let {
            throw WasmHostUnavailableException("$step failed: ${it.message ?: it::class.java.simpleName}")
        }
        // Rule 6 — evaluateJavascript yields the literal string "null" when
        // the JS value is null/undefined (e.g. host.html did not load).
        return holder.get()
            ?: throw WasmHostUnavailableException("empty engine response during $step")
    }

    /** Validates and unwraps the evaluateJavascript result. */
    private fun unwrapResponse(response: String): String {
        val trimmed = response.trim()
        if (trimmed.isEmpty() || trimmed == "null") {
            throw WasmHostUnavailableException("empty engine response")
        }
        val envelope =
            if (trimmed.startsWith("\"")) {
                // rockeditFormat returns a JS string, so the callback value
                // is that string JSON-encoded once more — decode it.
                WasmFormatterContract.decodeJsonString(trimmed)
                    ?: throw WasmHostUnavailableException("engine response is not a decodable JSON string")
            } else {
                // Defensive: a non-string result arrives as raw JSON.
                trimmed
            }
        if (!envelope.trim().startsWith("{")) {
            throw WasmHostUnavailableException("engine response is not a JSON envelope")
        }
        return envelope
    }

    private fun markPageFailed(reason: String) {
        synchronized(lock) {
            if (pageFailed == null) pageFailed = reason
            // pageFailed stays set until the next engine build resets it,
            // so every current waiter fails fast with the same reason.
            destroyEngineLocked()
            (lock as java.lang.Object).notifyAll()
        }
    }

    /** Must be called while holding [lock]; destroy is posted to the main thread. */
    private fun destroyEngineLocked() {
        val current = engine
        engine = null
        ready = false
        if (current != null) {
            mainHandler.post {
                try {
                    current.destroy()
                } catch (_: Throwable) {
                    // Destroying an already-dead WebView must never crash.
                }
            }
        }
    }

    // ------------------------------------------------------- JS utilities

    /**
     * Encodes [value] as a double-quoted JavaScript string literal. Escapes
     * quotes, backslashes, control characters, `<`, `>`, `&` (so a payload
     * can never form `</script>` or an entity even in an HTML context) and
     * U+2028/U+2029 (legacy JS line terminators).
     */
    private fun jsStringLiteral(value: String): String {
        val out = StringBuilder(value.length + 16)
        out.append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '<' -> out.append("\\u003c")
                '>' -> out.append("\\u003e")
                '&' -> out.append("\\u0026")
                '\u2028' -> out.append("\\u2028")
                '\u2029' -> out.append("\\u2029")
                else ->
                    if (ch.code < 0x20) {
                        // JS \uXXXX always needs exactly four hex digits.
                        out.append("\\u")
                        val hex = "0123456789abcdef"
                        out.append(hex[(ch.code shr 12) and 0xF])
                        out.append(hex[(ch.code shr 8) and 0xF])
                        out.append(hex[(ch.code shr 4) and 0xF])
                        out.append(hex[ch.code and 0xF])
                    } else {
                        out.append(ch)
                    }
            }
        }
        out.append('"')
        return out.toString()
    }
}
