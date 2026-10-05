package com.secretarrow.rockedit.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.secretarrow.rockedit.R
import com.secretarrow.rockedit.core.App
import com.secretarrow.rockedit.core.LoopbackRedirectServer
import com.secretarrow.rockedit.core.OAuthCallback
import com.secretarrow.rockedit.core.OAuthState
import java.io.IOException
import java.util.Locale

/**
 * In-app OAuth authorization screen (v0.16.0, backlog "OAuth loopback
 * in-app browser"): loads the provider authorization URL in a programmatic
 * WebView and captures the redirect through a [LoopbackRedirectServer] bound
 * on 127.0.0.1. This is the alternative flow — the v0.15.0 copy/paste flow
 * stays untouched. The WebView itself never yields the code: only the
 * loopback server records the callback, and the state check decides whether
 * it is accepted (RFC 8252 §7.1 plain-HTTP-loopback assumption).
 *
 * Defensive contract (scenario → handling):
 *
 * | Scenario | Handling |
 * |---|---|
 * | EXTRA_URL missing or not http(s) | RESULT_CANCELED, no extras, finish before server/UI work |
 * | Loopback bind fails (port taken) | RESULT_CANCELED + EXTRA_OUT_ERROR="bind_failed" + finish |
 * | Callback state mismatch | inline status error; page kept; NEVER accepted (note below) |
 * | Callback with error=... | RESULT_OK + EXTRA_OUT_ERROR / EXTRA_OUT_ERROR_DESCRIPTION |
 * | Callback without code and error | RESULT_OK + EXTRA_OUT_ERROR="invalid_callback" (documented) |
 * | Callback with matching state | RESULT_OK + EXTRA_OUT_CODE + finish |
 * | No callback within 5 minutes | RESULT_CANCELED + EXTRA_OUT_ERROR="timeout" + finish |
 * | Back press | default finish → RESULT_CANCELED, no extras |
 * | Main-frame load error | inline status text; navigation still allowed; Back works |
 * | WebView crash | RESULT_CANCELED + EXTRA_OUT_ERROR="webview_gone" + finish (minSdk 26) |
 * | onDestroy | handler callbacks removed; server.close() ALWAYS (finally-style) |
 *
 * After a state mismatch the user presses Back and restarts from the Storage
 * Manager: the one-shot loopback server cannot record a second callback.
 *
 * UI strings come from inapp_auth_* resources (EN/ID) added at wiring time.
 */
class InAppAuthActivity : AppCompatActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var server: LoopbackRedirectServer? = null
    private var expectedState: String? = null
    private var finished = false

    private lateinit var statusView: TextView

    /** Polls the loopback server every [POLL_INTERVAL_MS] until a callback. */
    private val poller =
        object : Runnable {
            override fun run() {
                if (finished) return
                val callback = server?.pollCallback()
                if (callback != null) resolve(callback)
                if (!finished) mainHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }

    private val timeoutRun =
        Runnable {
            if (!finished) finishCanceled(ERROR_TIMEOUT)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val settings = App.settings(this)
        if (settings.isBlackTheme()) setTheme(R.style.Theme_RockEdit_Black)
        settings.applyTheme()
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank() || !isHttpUrl(url)) {
            finishCanceled(null)
            return
        }
        expectedState = intent.getStringExtra(EXTRA_STATE)
        val provider = intent.getStringExtra(EXTRA_PROVIDER).orEmpty()
        supportActionBar?.title =
            if (provider.isBlank()) {
                getString(R.string.inapp_auth_title, getString(R.string.app_name))
            } else {
                getString(R.string.inapp_auth_title, provider)
            }

        val newServer = LoopbackRedirectServer(intent.getIntExtra(EXTRA_PORT, 0))
        server = newServer
        try {
            newServer.start()
        } catch (e: IOException) {
            // Documented decision: the caller reads EXTRA_OUT_ERROR to tell a
            // bind failure (e.g. port 8642 already taken) from a plain cancel.
            finishCanceled(ERROR_BIND_FAILED)
            return
        }
        setUpView(url, provider)
        mainHandler.post(poller)
        mainHandler.postDelayed(timeoutRun, TIMEOUT_MS)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUpView(
        url: String,
        provider: String,
    ) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
            }
        val titleView =
            TextView(this).apply {
                text =
                    if (provider.isBlank()) {
                        getString(R.string.inapp_auth_title, getString(R.string.app_name))
                    } else {
                        getString(R.string.inapp_auth_title, provider)
                    }
                textSize = 18f
                setPadding(0, 0, 0, pad / 2)
            }
        statusView =
            TextView(this).apply {
                text = getString(R.string.inapp_auth_status)
                setPadding(0, pad / 2, 0, 0)
            }
        val webView =
            WebView(this).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f,
                    )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                webViewClient = InAppClient()
            }
        layout.addView(titleView)
        layout.addView(webView)
        layout.addView(statusView)
        setContentView(layout)
        // Keep the 16 dp content padding and add the system-bar insets on top
        // so the OAuth screen also clears the status/navigation bars.
        SystemBars.install(this, layout)
        webView.loadUrl(url)
    }

    private inner class InAppClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val host = request.url.host?.lowercase(Locale.US)
            if (host == LOOPBACK_HOST || host == LOOPBACK_ALIAS) {
                // Any port: the loopback server answers this navigation with
                // the static success page and the poller picks the callback up.
                return false
            }
            // Foreign hosts (provider consent pages, sign-in helpers) navigate
            // normally. The guard: only loopback responses can carry a callback —
            // a foreign redirect is never sniffed for a code nor reloaded
            // secretly; it is just ordinary navigation.
            return false
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            // Simple resilience path: show the problem inline and let the user
            // press Back (only description — error.code is unreliable on stubs).
            if (!request.isForMainFrame) return
            statusView.text = getString(R.string.inapp_auth_error_load, "${error.description}")
        }

        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail,
        ): Boolean {
            finishCanceled(ERROR_WEBVIEW_GONE)
            return true
        }
    }

    private fun resolve(callback: OAuthCallback) {
        if (!OAuthState.matches(expectedState, callback.state)) {
            // Security failure: a crafted or stale redirect. Never accept;
            // keep the page so the user sees the error and can go back.
            statusView.text = getString(R.string.inapp_auth_error_state)
            return
        }
        val data = Intent()
        val code = callback.code
        if (code != null) {
            data.putExtra(EXTRA_OUT_CODE, code)
        } else {
            data.putExtra(EXTRA_OUT_ERROR, callback.error ?: ERROR_INVALID_CALLBACK)
            data.putExtra(EXTRA_OUT_ERROR_DESCRIPTION, callback.errorDescription.orEmpty())
        }
        if (finished) return
        finished = true
        setResult(RESULT_OK, data)
        finish()
    }

    private fun finishCanceled(error: String?) {
        if (finished) return
        finished = true
        setResult(RESULT_CANCELED, error?.let { Intent().putExtra(EXTRA_OUT_ERROR, it) })
        finish()
    }

    private fun isHttpUrl(value: String): Boolean {
        val scheme = Uri.parse(value).scheme?.lowercase(Locale.US)
        return scheme == "http" || scheme == "https"
    }

    override fun onDestroy() {
        // Finally-style guard: runs on every teardown path, even one that
        // skipped a result (crash, system kill of the activity).
        mainHandler.removeCallbacksAndMessages(null)
        val closing = server
        server = null
        try {
            closing?.close()
        } catch (t: Throwable) {
            // Teardown must never crash; the accept thread is a daemon and
            // exits on its own once the socket is closed.
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "com.secretarrow.rockedit.extra.INAPP_AUTH_URL"
        const val EXTRA_PORT = "com.secretarrow.rockedit.extra.INAPP_AUTH_PORT"
        const val EXTRA_STATE = "com.secretarrow.rockedit.extra.INAPP_AUTH_STATE"
        const val EXTRA_PROVIDER = "com.secretarrow.rockedit.extra.INAPP_AUTH_PROVIDER"
        const val EXTRA_OUT_CODE = "com.secretarrow.rockedit.extra.INAPP_AUTH_OUT_CODE"
        const val EXTRA_OUT_ERROR = "com.secretarrow.rockedit.extra.INAPP_AUTH_OUT_ERROR"
        const val EXTRA_OUT_ERROR_DESCRIPTION =
            "com.secretarrow.rockedit.extra.INAPP_AUTH_OUT_ERROR_DESCRIPTION"

        /** Values carried in [EXTRA_OUT_ERROR] on failure results. */
        const val ERROR_BIND_FAILED = "bind_failed"
        const val ERROR_TIMEOUT = "timeout"
        const val ERROR_WEBVIEW_GONE = "webview_gone"
        const val ERROR_INVALID_CALLBACK = "invalid_callback"

        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val LOOPBACK_ALIAS = "localhost"
        private const val POLL_INTERVAL_MS = 250L
        private const val TIMEOUT_MS = 300_000L
    }
}
