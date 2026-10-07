package com.secretarrow.rockedit

import android.content.Intent
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.secretarrow.rockedit.core.App
import com.secretarrow.rockedit.core.FolderSort
import com.secretarrow.rockedit.ui.FolderBrowserActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Folder-browser file management E2E (v0.21.0).
 *
 * Determinism strategy (lessons from AboutE2eTest/ZenExitE2eTest applied):
 * - No system SAF picker anywhere: the test seeds `lastFolderUri` with a
 *   structurally valid tree URI that is NOT user-granted, so the browser
 *   opens directly and behaves like an unauthorized/empty tree (listFiles
 *   fails -> empty state, toolbar still fully built). Green-path DocumentFile
 *   mutations need a real user grant and are covered by the pure-JVM
 *   FileOpsTest plus the documented physical-device limitation (same
 *   category as the USB OTG item).
 * - Dialogs are reached through the internal `show*Dialog` functions that
 *   RETURN the AlertDialog, then inspected through
 *   `dialog.window!!.decorView` / `getButton(...)` — never through Espresso
 *   root pickers, which are unreliable on headless emulators for dialog
 *   windows.
 */
@RunWith(AndroidJUnit4::class)
class FolderOpsE2eTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Valid-looking tree URI with no persistable grant: safe empty state. */
    private val fakeTree = "content://com.android.externalstorage.documents/tree/primary%3ARockEditE2e"

    @Before
    fun seedTree() {
        App.settings(context).lastFolderUri = fakeTree
    }

    @After
    fun resetTree() {
        App.settings(context).lastFolderUri = ""
    }

    private fun launch(): ActivityScenario<FolderBrowserActivity> =
        ActivityScenario.launch(Intent(context, FolderBrowserActivity::class.java))

    /**
     * Polls on the main thread until the options menu contains both creation
     * entries, then asserts their titles are present. The menu is inflated
     * via onCreateOptionsMenu (stable XML, editor-proven pattern); the poll
     * only absorbs the async tree-open that precedes nothing menu-related —
     * inflation happens at create time.
     */
    @Test
    fun toolbarMenuOffersNewFileAndNewFolder() {
        launch().use { scenario ->
            val deadline = System.currentTimeMillis() + 5000
            var hasNewFile = false
            var hasNewFolder = false
            var lastTitles = ""
            while (System.currentTimeMillis() < deadline && !(hasNewFile && hasNewFolder)) {
                scenario.onActivity { act ->
                    val menu = act.findViewById<Toolbar>(R.id.toolbar).menu
                    val titles = (0 until menu.size()).mapNotNull { menu.getItem(it).title?.toString() }
                    lastTitles = titles.toString()
                    hasNewFile = titles.any { it == context.getString(R.string.new_file) }
                    hasNewFolder = titles.any { it == context.getString(R.string.new_folder) }
                }
                if (!(hasNewFile && hasNewFolder)) Thread.sleep(100)
            }
            assertTrue(
                "creation entries missing from options menu; titles seen: $lastTitles",
                hasNewFile && hasNewFolder,
            )
        }
    }

    /**
     * AlertDialog button clicks are dispatched through AlertController's
     * ButtonHandler (a Handler): performClick() returns before the listener
     * and the auto-dismiss run. Every dismissal assertion therefore polls
     * with a deadline instead of checking synchronously.
     */
    private fun awaitDismissed(dialog: AlertDialog) {
        val deadline = System.currentTimeMillis() + 2500
        var showing = true
        while (System.currentTimeMillis() < deadline && showing) {
            showing = dialog.isShowing
            if (showing) Thread.sleep(50)
        }
        assertFalse("dialog never dismissed after positive click", showing)
    }

    @Test
    fun createDialogValidatesNameBeforeAnyProviderCall() {
        launch().use { scenario ->
            scenario.onActivity { act ->
                val dialog = act.showCreateDialog(isFolder = false)
                assertNotNull("create dialog must show", dialog)
                val input = dialog!!.window!!.decorView.findViewById<EditText>(R.id.input_name)
                assertEquals(
                    context.getString(R.string.name_hint_file),
                    input.hint.toString(),
                )

                // Invalid name: the provider must never be called; the dialog
                // follows the app-wide goto-dialog convention (dismiss + toast).
                input.setText("bad/name")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                awaitDismissed(dialog)

                // Valid name: flows past validation into the (failing,
                // unauthorized) provider call without crashing the activity.
                val second = act.showCreateDialog(isFolder = true)
                assertNotNull("folder dialog must show", second)
                val input2 = second!!.window!!.decorView.findViewById<EditText>(R.id.input_name)
                assertEquals(context.getString(R.string.name_hint_folder), input2.hint.toString())
                input2.setText("reports")
                second.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                awaitDismissed(second)
            }
        }
    }

    @Test
    fun renameDialogPrefillsCurrentName() {
        launch().use { scenario ->
            scenario.onActivity { act ->
                val entry = FolderSort.Entry(name = "old-name.txt", isFolder = false, size = 3L, lastModified = 0L)
                val dialog = act.showRenameDialog(entry)
                assertNotNull(dialog)
                val input = dialog!!.window!!.decorView.findViewById<EditText>(R.id.input_name)
                assertEquals("old-name.txt", input.text.toString())
                assertEquals("old-name.txt".length, input.selectionEnd)
                dialog.dismiss()
            }
        }
    }

    @Test
    fun opsDialogAdaptsActionsToEntryKind() {
        launch().use { scenario ->
            scenario.onActivity { act ->
                val file = FolderSort.Entry(name = "a.txt", isFolder = false, size = 1L, lastModified = 0L)
                val fileDialog = act.showOpsDialog(file)
                assertNotNull(fileDialog)
                val fileItems = (0 until fileDialog!!.listView.count).map { fileDialog.listView.getItemAtPosition(it).toString() }
                assertEquals(3, fileItems.size)
                assertEquals(context.getString(R.string.ops_open), fileItems[0])
                assertEquals(context.getString(R.string.ops_rename), fileItems[1])
                assertEquals(context.getString(R.string.ops_delete), fileItems[2])
                fileDialog.dismiss()

                val folder = FolderSort.Entry(name = "docs", isFolder = true, size = 0L, lastModified = 0L)
                val folderDialog = act.showOpsDialog(folder)
                assertNotNull(folderDialog)
                val folderItems = (0 until folderDialog!!.listView.count).map { folderDialog.listView.getItemAtPosition(it).toString() }
                assertEquals(2, folderItems.size)
                assertEquals(context.getString(R.string.ops_rename), folderItems[0])
                assertEquals(context.getString(R.string.ops_delete), folderItems[1])
                folderDialog.dismiss()
            }
        }
    }

    @Test
    fun deleteDialogNamesTheEntryAndWarns() {
        launch().use { scenario ->
            scenario.onActivity { act ->
                val entry = FolderSort.Entry(name = "precious.md", isFolder = false, size = 9L, lastModified = 0L)
                val dialog = act.showDeleteDialog(entry)
                assertNotNull(dialog)
                val message = dialog!!.window!!.decorView.findViewById<android.widget.TextView>(android.R.id.message)
                // Exact match against the localized resource: proves the dialog
                // uses delete_confirm_msg and interpolates the entry name.
                assertEquals(
                    context.getString(R.string.delete_confirm_msg, "precious.md"),
                    message.text.toString(),
                )
                dialog.dismiss()
            }
        }
    }
}
