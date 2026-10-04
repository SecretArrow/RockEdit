package com.secretarrow.rockedit.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.secretarrow.rockedit.R
import com.secretarrow.rockedit.core.App
import com.secretarrow.rockedit.core.EncodingDetector
import com.secretarrow.rockedit.core.FileNames
import com.secretarrow.rockedit.databinding.ActivitySplitEditorBinding
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Split view (v0.13.0): two editable panes side by side (or stacked) for
 * comparing and copying between documents. Mirrors [DiffActivity] patterns:
 * view binding, theme handling, up navigation.
 *
 * Defensive contract:
 * - Pane A receives the editor buffer through the process-local
 *   [Companion.pendingPaneA] handoff, read once in onCreate and then cleared.
 *   It carries unsaved text only (no uri), so a killed process simply loses
 *   the handoff and pane A starts empty — never stale or crashing.
 * - Pane B files load through SAF: takePersistableUriPermission failures are
 *   ignored (session grant is enough), the stream is read with a
 *   [MAX_FILE_BYTES] byte ceiling, and the decoded text is capped at
 *   [MAX_PANE_CHARS]. Oversize input is refused with a banner
 *   (split_too_large); read/decode failures (IOException, SecurityException,
 *   dead documents) map to split_read_failed. Neither path can crash.
 * - Saving pane B writes UTF-8 off the main thread; a failed write maps to
 *   split_save_failed and the binding stays alive for a retry.
 * - Back/up navigation with a dirty pane asks for confirmation before
 *   discarding; swapping panes also swaps names and dirty flags so dirty
 *   content can never appear clean.
 * - Loading into pane B resets its dirty flag (the loaded text is committed);
 *   a successful save does the same.
 *
 * Documented assumptions:
 * - Panes are plain multi-line EditTexts: undo is the native IME/EditText
 *   undo; there is no custom undo stack in v1.
 * - Decoding follows the editor default (UTF-8 lenient via
 *   [EncodingDetector]); no per-pane encoding switching in v1.
 */
class SplitEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplitEditorBinding
    private var paneAName: String = ""
    private var paneBName: String = ""
    private var dirtyA = false
    private var dirtyB = false
    private var loading = true

    private val loadBLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            loadIntoPaneB(uri)
        }

    private val saveBLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            savePaneB(uri)
        }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (dirtyA || dirtyB) {
                showDiscardDialog()
            } else {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val settings = App.settings(this)
        if (settings.isBlackTheme()) setTheme(R.style.Theme_RockEdit_Black)
        settings.applyTheme()
        super.onCreate(savedInstanceState)
        binding = ActivitySplitEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.splitToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // Up navigation goes through the dispatcher so the discard dialog
        // applies to it exactly like the system back button.
        binding.splitToolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        onBackPressedDispatcher.addCallback(this, backCallback)
        supportActionBar?.title = getString(R.string.split_title)

        val untitled = getString(R.string.split_untitled_pane)
        paneAName = untitled
        paneBName = untitled
        binding.paneA.addTextChangedListener(
            PaneWatcher { dirty ->
                dirtyA = dirty
                updateStatusA()
            }
        )
        binding.paneB.addTextChangedListener(
            PaneWatcher { dirty ->
                dirtyB = dirty
                updateStatusB()
            }
        )

        // Consume the process-local handoff exactly once; null -> empty pane.
        val pending = pendingPaneA
        pendingPaneA = null
        binding.paneA.setText(pending.orEmpty())

        loading = false
        updateStatusA()
        updateStatusB()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_split, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_load_b -> {
                openLoadPicker()
                return true
            }
            R.id.action_save_b -> {
                saveBLauncher.launch(FileNames.sanitize(paneBName))
                return true
            }
            R.id.action_toggle_orientation -> {
                toggleOrientation()
                return true
            }
            R.id.action_swap_panes -> {
                swapPanes()
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    // --------------------------------------------------------------- panes

    /**
     * Loads the picked document into pane B. The uri is validated first
     * (null picker result -> split_no_uri); oversize and unreadable input
     * are refused with a banner, never crash.
     */
    private fun loadIntoPaneB(uri: Uri?) {
        if (uri == null) {
            toast(getString(R.string.split_no_uri))
            return
        }
        takePersistentPermission(uri)
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { readPaneFile(uri) }
            when {
                outcome == null -> toast(getString(R.string.split_read_failed))
                outcome.tooLarge -> toast(getString(R.string.split_too_large))
                outcome.text == null -> toast(getString(R.string.split_read_failed))
                else -> {
                    loading = true
                    binding.paneB.setText(outcome.text)
                    loading = false
                    dirtyB = false
                    paneBName = resolveName(uri)
                    updateStatusB()
                    toast(getString(R.string.split_switched))
                }
            }
        }
    }

    /**
     * Writes pane B to the picked location as UTF-8. Null picker result maps
     * to split_no_uri; a failed write maps to split_save_failed and keeps
     * the screen (binding) for a retry.
     */
    private fun savePaneB(uri: Uri?) {
        if (uri == null) {
            toast(getString(R.string.split_no_uri))
            return
        }
        takePersistentPermission(uri)
        val text = binding.paneB.text?.toString().orEmpty()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { writePane(uri, text) }
            if (ok) {
                dirtyB = false
                val name = DisplayNames.resolve(this@SplitEditorActivity, uri)
                if (name.isBlank()) {
                    paneBName = getString(R.string.split_untitled_pane)
                    toast(getString(R.string.split_saved))
                } else {
                    paneBName = name
                    toast(getString(R.string.split_saved_to, name))
                }
                updateStatusB()
            } else {
                toast(getString(R.string.split_save_failed))
            }
        }
    }

    /** Flips the pane container between side-by-side and stacked. */
    private fun toggleOrientation() {
        val toVertical = binding.splitPanes.orientation == LinearLayout.HORIZONTAL
        binding.splitPanes.orientation =
            if (toVertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        applyPaneGeometry(toVertical)
        toast(getString(R.string.split_orientation))
    }

    /**
     * Rewrites the pane and divider geometry so the weights follow the new
     * orientation (LinearLayout weights only apply along its main axis).
     */
    private fun applyPaneGeometry(vertical: Boolean) {
        if (vertical) {
            binding.splitPaneA.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            binding.splitPaneB.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            binding.splitDivider.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            )
        } else {
            binding.splitPaneA.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
            )
            binding.splitPaneB.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
            )
            binding.splitDivider.layoutParams = LinearLayout.LayoutParams(
                dp(1), LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
    }

    /**
     * Exchanges texts, names, and dirty flags between the panes so dirty
     * content can never appear clean after the swap.
     */
    private fun swapPanes() {
        val textA = binding.paneA.text?.toString().orEmpty()
        val textB = binding.paneB.text?.toString().orEmpty()
        loading = true
        binding.paneA.setText(textB)
        binding.paneB.setText(textA)
        loading = false
        val name = paneAName
        paneAName = paneBName
        paneBName = name
        val dirty = dirtyA
        dirtyA = dirtyB
        dirtyB = dirty
        updateStatusA()
        updateStatusB()
        toast(getString(R.string.split_swapped))
    }

    private fun updateStatusA() {
        binding.statusA.text = statusText(paneAName, dirtyA)
    }

    private fun updateStatusB() {
        binding.statusB.text = statusText(paneBName, dirtyB)
    }

    /** "● name" when dirty, plain name otherwise. */
    private fun statusText(name: String, dirty: Boolean): String =
        if (dirty) "$DIRTY_DOT $name" else name

    private fun showDiscardDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.split_discard_title)
            .setMessage(R.string.split_discard_body)
            .setPositiveButton(R.string.discard) { _, _ -> finish() }
            .setNegativeButton(R.string.cancel) { _, _ ->
                // Stay on the screen and keep both panes editable.
            }
            .show()
    }

    private fun openLoadPicker() {
        try {
            loadBLauncher.launch(
                arrayOf(
                    "text/*",
                    "application/json",
                    "application/xml",
                    "application/javascript",
                    "application/x-yaml"
                )
            )
        } catch (e: Exception) {
            // No file picker on the device (or the resolver failed).
            toast(getString(R.string.split_read_failed))
        }
    }

    // -------------------------------------------------------------- io

    /** Friendly file name; DisplayNames falls back to the last URI segment. */
    private fun resolveName(uri: Uri): String =
        DisplayNames.resolve(this, uri).ifBlank { getString(R.string.split_untitled_pane) }

    /** Outcome of reading pane B's file: unreadable (null) / too large / text. */
    private class PaneRead(val text: String?, val tooLarge: Boolean)

    /**
     * Reads the document with a [MAX_FILE_BYTES] byte ceiling; a file bigger
     * than the ceiling, or one decoding to more than [MAX_PANE_CHARS]
     * characters, maps to `tooLarge`. Any I/O or provider failure returns
     * null (mapped to split_read_failed by the caller).
     */
    private fun readPaneFile(uri: Uri): PaneRead? = try {
        val stream = contentResolver.openInputStream(uri) ?: return null
        stream.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            var read = input.read(chunk)
            var overflow = false
            while (read >= 0) {
                if (buffer.size() >= MAX_FILE_BYTES) {
                    overflow = true
                    break
                }
                buffer.write(chunk, 0, minOf(read, MAX_FILE_BYTES - buffer.size()))
                read = input.read(chunk)
            }
            if (overflow) {
                PaneRead(null, true)
            } else {
                val decoded =
                    EncodingDetector.decode(buffer.toByteArray(), EncodingDetector.DEFAULT_CHARSET)
                if (decoded.length > MAX_PANE_CHARS) {
                    PaneRead(null, true)
                } else {
                    PaneRead(decoded, false)
                }
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun writePane(uri: Uri, text: String): Boolean {
        return try {
            val stream = try {
                contentResolver.openOutputStream(uri, "wt")
            } catch (_: IllegalArgumentException) {
                null
            } ?: contentResolver.openOutputStream(uri) ?: return false
            stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun takePersistentPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Non-persistable grant: still valid for this session.
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** Marks a pane dirty on any edit; ignored while text is set programmatically. */
    private inner class PaneWatcher(private val onDirty: (Boolean) -> Unit) : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            // Not needed: dirty state is derived from afterTextChanged only.
        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            // Not needed.
        }

        override fun afterTextChanged(s: Editable?) {
            if (loading) return
            onDirty(true)
        }
    }

    companion object {
        private const val DIRTY_DOT = "●"
        private const val MAX_PANE_CHARS = 1_000_000

        /** Byte ceiling (4 MiB) above which a file is refused before decoding. */
        private const val MAX_FILE_BYTES = 4_194_304

        /**
         * Process-local handoff for the editor's unsaved buffer:
         * EditorActivity assigns it right before startActivity and
         * [onCreate] reads it once and clears it. Null (or a killed process)
         * means pane A starts empty; no cross-process or persisted transfer
         * is attempted in v1.
         */
        @Volatile
        var pendingPaneA: String? = null
    }
}
