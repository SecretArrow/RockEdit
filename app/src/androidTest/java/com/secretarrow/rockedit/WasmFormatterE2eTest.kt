package com.secretarrow.rockedit

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.secretarrow.rockedit.core.FormatRequest
import com.secretarrow.rockedit.core.FormatResult
import com.secretarrow.rockedit.core.WasmCodeFormatter
import com.secretarrow.rockedit.ui.EditorActivity
import com.secretarrow.rockedit.ui.WasmFormatterHost
import org.hamcrest.Matchers.containsString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Real-path e2e for the prettier WASM/WebView formatter engine (backlog
 * item 10): a messy JavaScript file is opened, the Format toolbar action is
 * pressed, and the editor must end up showing prettier's output — spaces
 * inside the object literal plus the exact prettier rendering are proof
 * that the JS engine ran, not just any whitespace pass.
 *
 * Wiring note: the editor path (test 1) requires EditorActivity to route
 * through FormatterRegistry.withWasm(...WasmFormatterHost::launch...) (the
 * integration change); test 2 drives the engine host directly, so it
 * passes independently and pinpoints WebView-side regressions.
 */
@RunWith(AndroidJUnit4::class)
class WasmFormatterE2eTest {
    @Before
    fun warmUpEngine() {
        // Idempotent; also kicks off the engine warm-up so the one-off
        // asset load does not eat into the editor's 5 s format budget.
        WasmFormatterHost.init(ApplicationProvider.getApplicationContext())
    }

    private fun newTestFile(
        name: String,
        content: String,
    ): Pair<File, Intent> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.filesDir, name)
        file.writeText(content)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setClass(context, EditorActivity::class.java)
                setDataAndType(uri, "text/plain")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        return file to intent
    }

    private fun waitForText(
        expected: String,
        timeoutMs: Long = 5000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var found = false
        while (System.currentTimeMillis() < deadline && !found) {
            try {
                onView(withId(R.id.editor)).check(matches(withText(containsString(expected))))
                found = true
            } catch (_: Throwable) {
                Thread.sleep(100)
            }
        }
        assertTrue("Editor never showed text: $expected", found)
    }

    /**
     * Presses the Format action. The icon normally sits on the toolbar, but
     * on narrow toolbars it can be pushed into the overflow menu — try the
     * icon first, then fall back to the overflow.
     */
    private fun pressFormat() {
        try {
            onView(withContentDescription(R.string.format)).perform(click())
        } catch (_: Throwable) {
            Espresso.openActionBarOverflowOrOptionsMenu(
                ApplicationProvider.getApplicationContext<android.content.Context>(),
            )
            onView(withText(R.string.format)).perform(click())
        }
    }

    @Test
    fun formatsJavaScriptThroughToolbarAction() {
        val (_, intent) = newTestFile("e2e_format.js", "let x={a:1,b:2};")
        ActivityScenario.launch<EditorActivity>(intent)

        // Wait for the async load, then press Format.
        waitForText("let x={")
        pressFormat()

        // Poll generously: the WebView engine needs time to load prettier
        // on the first run (engine warm-up was started in @Before).
        waitForText("let x = {", timeoutMs = 20_000)
        onView(withId(R.id.editor)).check(matches(withText(containsString("let x = { a: 1, b: 2 };"))))
    }

    @Test
    fun wasmHostFormatsJavaScriptDirectly() {
        val formatter =
            WasmCodeFormatter(
                launchHost = { payload, budgetMs -> WasmFormatterHost.launch(payload, budgetMs) },
            )
        val holder = AtomicReference<FormatResult?>(null)
        // launch() blocks on latches — never run it on the test main thread.
        val worker =
            Thread {
                holder.set(
                    formatter.format(
                        FormatRequest("let x={a:1,b:2};", "javascript", timeBudgetMs = 30_000L),
                    ),
                )
            }
        worker.start()
        worker.join(35_000)
        val result = holder.get()
        assertTrue("engine did not finish in time: $result", result != null)
        assertTrue("expected Success but was $result", result is FormatResult.Success)
        assertEquals("let x = { a: 1, b: 2 };\n", (result as FormatResult.Success).formattedText)
    }
}
