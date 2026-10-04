package com.secretarrow.rockedit

import android.content.Intent
import android.text.style.ForegroundColorSpan
import android.widget.EditText
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.secretarrow.rockedit.ui.EditorActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Syntax highlighting e2e: opening a real .kt file must produce colored
 * spans on the editor text once the async load finishes.
 */
@RunWith(AndroidJUnit4::class)
class SyntaxHighlightE2eTest {
    private fun newKotlinFileIntent(): Intent {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.filesDir, "e2e_syntax.kt")
        file.writeText("fun main() {\n    // comment line\n    val n = 42\n    print(n)\n}\n")
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setClass(context, EditorActivity::class.java)
            setDataAndType(uri, "text/plain")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    @Test
    fun kotlinFileGetsSyntaxSpans() {
        val intent = newKotlinFileIntent()
        val scenario = ActivityScenario.launch<EditorActivity>(intent)

        // The load is asynchronous: poll until at least one span of each
        // important kind (keyword/comment/number) shows up.
        val deadline = System.currentTimeMillis() + 5000
        var spans = emptyList<ForegroundColorSpan>()
        while (System.currentTimeMillis() < deadline && spans.size < 3) {
            scenario.onActivity { activity ->
                val editor = activity.findViewById<EditText>(R.id.editor)
                spans =
                    editor.editableText
                        .getSpans(0, editor.length(), ForegroundColorSpan::class.java)
                        .toList()
            }
            if (spans.size < 3) Thread.sleep(100)
        }

        assertTrue("expected >= 3 syntax spans, found ${spans.size}", spans.size >= 3)
        // All spans must use distinct colors for distinct token kinds here:
        // the file contains a keyword, a comment and a number.
        assertTrue("expected distinct span colors", spans.map { it.foregroundColor }.toSet().size >= 3)
        scenario.close()
    }
}
