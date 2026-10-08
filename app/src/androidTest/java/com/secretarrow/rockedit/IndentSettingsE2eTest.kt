package com.secretarrow.rockedit

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.widget.TextView
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
import com.secretarrow.rockedit.core.App
import com.secretarrow.rockedit.core.SettingsRepository
import com.secretarrow.rockedit.ui.EditorActivity
import org.hamcrest.Matchers.containsString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * v0.23.0 e2e for the indentation + font family settings.
 *
 * Indentation path: settings are written into the real KeyValueStore BEFORE
 * the activity is created, then a JSON file is formatted through the same
 * toolbar action as [FormatterE2eTest]; the expected output switches from
 * spaces to tab characters — proof that the stored preference reaches the
 * formatter request, not just the store.
 *
 * Font path: assertions run directly on the views inside onActivity (the
 * deterministic dialog-window pattern proven by AboutE2eTest) — no Espresso
 * root picker or window-focus dependency.
 *
 * Every test resets its keys in [resetSettings]: FormatterE2eTest asserts a
 * 4-space default and would break if a tabs setting leaked across tests.
 */
@RunWith(AndroidJUnit4::class)
class IndentSettingsE2eTest {
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

    @After
    fun resetSettings() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val kv = App.keyValueStore(context)
        kv.remove(SettingsRepository.KEY_INDENT_STYLE)
        kv.remove(SettingsRepository.KEY_INDENT_SIZE)
        kv.remove(SettingsRepository.KEY_FONT_FAMILY)
    }

    @Test
    fun tabsSettingFormatsWithTabs() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val kv = App.keyValueStore(context)
        kv.putString(SettingsRepository.KEY_INDENT_STYLE, SettingsRepository.INDENT_TABS)
        kv.putString(SettingsRepository.KEY_INDENT_SIZE, "2")

        val (_, intent) = newTestFile("e2e_indent_tabs.json", """{"b":1,"a":2}""")
        ActivityScenario.launch<EditorActivity>(intent)

        waitForText("\"b\":1")
        pressFormat()
        waitForText("\"a\": 2", timeoutMs = 10_000)

        // prettier useTabs=true indents with real tab characters; two levels
        // of nesting would double them, one level shows exactly one tab.
        onView(withId(R.id.editor)).check(
            matches(withText(containsString("{\n\t\"b\": 1,\n\t\"a\": 2\n}"))),
        )
    }

    @Test
    fun spacesSettingKeepsDesktopDefault() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val kv = App.keyValueStore(context)
        kv.putString(SettingsRepository.KEY_INDENT_STYLE, SettingsRepository.INDENT_SPACES)
        kv.putString(SettingsRepository.KEY_INDENT_SIZE, "2")

        val (_, intent) = newTestFile("e2e_indent_spaces.json", """{"b":1,"a":2}""")
        ActivityScenario.launch<EditorActivity>(intent)

        waitForText("\"b\":1")
        pressFormat()
        waitForText("\"a\": 2", timeoutMs = 10_000)

        // indentSize=2 → two spaces per level (default build uses 4).
        onView(withId(R.id.editor)).check(
            matches(withText(containsString("{\n  \"b\": 1,\n  \"a\": 2\n}"))),
        )
    }

    @Test
    fun fontFamilyAppliesToEditorButGutterStaysMonospace() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val kv = App.keyValueStore(context)
        kv.putString(SettingsRepository.KEY_FONT_FAMILY, SettingsRepository.FONT_SANS)

        val (_, intent) = newTestFile("e2e_font_family.txt", "hello")
        val scenario = ActivityScenario.launch<EditorActivity>(intent)

        scenario.onActivity { activity ->
            val editor = activity.findViewById<TextView>(R.id.editor)
            val gutter = activity.findViewById<TextView>(R.id.gutter)
            assertEquals("editor must use the sans family", Typeface.SANS_SERIF, editor.typeface)
            assertEquals(
                "gutter must stay monospace for numeral alignment",
                Typeface.MONOSPACE,
                gutter.typeface,
            )
        }
    }
}
