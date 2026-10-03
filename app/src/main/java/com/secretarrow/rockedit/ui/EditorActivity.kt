package com.secretarrow.rockedit.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.method.KeyListener
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.secretarrow.rockedit.R
import com.secretarrow.rockedit.core.App
import com.secretarrow.rockedit.core.CursorNav
import com.secretarrow.rockedit.core.EncodingDetector
import com.secretarrow.rockedit.core.FileNames
import com.secretarrow.rockedit.core.LineBreak
import com.secretarrow.rockedit.core.SearchEngine
import com.secretarrow.rockedit.core.SettingsRepository
import com.secretarrow.rockedit.core.TextStats
import com.secretarrow.rockedit.core.UndoStack
import com.secretarrow.rockedit.databinding.ActivityEditorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * The editing screen. Opens content:// or file:// URIs (SAF), keeps a bounded
 * undo/redo history, and writes files back preserving their line break style.
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var settings: SettingsRepository

    private var fileUri: Uri? = null
    private var displayName: String? = null
    private var charsetName: String = EncodingDetector.DEFAULT_CHARSET
    private var fileLineBreak: LineBreak = LineBreak.LF
    private var savedText: String = ""
    private var lastCommitted: String = ""
    private var applyingUndoRedo = false
    private var loading = true
    private var readOnly = false
    private var wordWrapEnabled = false
    private var pendingFinishAfterSave = false
    private var searchStart = 0
    private var originalKeyListener: KeyListener? = null
    private val undoStack = UndoStack()

    private val saveAsLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) {
                fileUri = uri
                takePersistentPermission(uri)
                writeTo(uri)
            }
        }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (isDirty()) {
                showUnsavedDialog()
            } else {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = App.settings(this)
        settings.applyTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        onBackPressedDispatcher.addCallback(this, backCallback)

        originalKeyListener = binding.editor.keyListener

        binding.editor.addTextChangedListener(EditorWatcher())
        binding.editor.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            binding.gutter.scrollTo(0, scrollY)
        }

        val restored = restoreState(savedInstanceState)
        if (!restored) {
            loadFromIntent(intent, savedInstanceState == null)
        }
        applyWordWrap(settings.wordWrap)
        if (settings.lineNumbers) {
            updateGutter()
        } else {
            binding.gutter.visibility = View.GONE
        }
        loading = false
        updateUiState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val text = binding.editor.text?.toString() ?: ""
        if (text.length <= SNAPSHOT_LIMIT) {
            outState.putString(STATE_TEXT, text)
        }
        outState.putString(STATE_URI, fileUri?.toString())
        outState.putString(STATE_NAME, displayName)
        outState.putString(STATE_CHARSET, charsetName)
        outState.putString(STATE_LINE_BREAK, fileLineBreak.name)
        outState.putString(STATE_SAVED_TEXT, savedText)
        outState.putString(STATE_LAST_COMMITTED, lastCommitted)
    }

    // ------------------------------------------------------------------ intent

    private fun loadFromIntent(intent: Intent?, allowEmpty: Boolean) {
        val action = intent?.action
        val data: Uri? = intent?.data
        when {
            (action == Intent.ACTION_VIEW || action == Intent.ACTION_EDIT) && data != null -> {
                fileUri = data
                loadFrom(data)
            }
            action == Intent.ACTION_SEND -> {
                val shared = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                setTextInternal(shared)
                showBinaryHintIfNeeded(false)
            }
            allowEmpty -> setTextInternal("")
        }
    }

    private fun loadFrom(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { readBytes(uri) }
            if (result == null) {
                toast(getString(R.string.open_failed, uri.lastPathSegment ?: uri.toString()))
                finish()
                return@launch
            }
            val (bytes, maybeBinary) = result
            charsetName = EncodingDetector.detectName(bytes)
            val text = EncodingDetector.decode(bytes, charsetName)
            fileLineBreak = LineBreak.detect(text, LineBreak.LF)
            displayName = DisplayNames.resolve(this@EditorActivity, uri)
            savedText = text
            setTextInternal(text)
            showBinaryHintIfNeeded(maybeBinary)
            updateUiState()
        }
    }

    private fun readBytes(uri: Uri): Pair<ByteArray, Boolean>? {
        return try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            // Heuristic: NUL byte almost always means binary content.
            val binary = bytes.indexOf(0) >= 0
            bytes to binary
        } catch (_: Exception) {
            null
        }
    }

    private fun showBinaryHintIfNeeded(maybeBinary: Boolean) {
        if (maybeBinary) {
            Toast.makeText(this, R.string.binary_warning, Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ text state

    private fun setTextInternal(newText: String) {
        applyingUndoRedo = true
        binding.editor.setText(newText)
        binding.editor.setSelection(newText.length)
        lastCommitted = newText
        undoStack.clear()
        applyingUndoRedo = false
        dirtyChanged()
        updateGutter()
    }

    private fun isDirty(): Boolean = lastCommitted != savedText

    private fun dirtyChanged() {
        updateUiState()
    }

    private inner class EditorWatcher : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

        override fun afterTextChanged(s: Editable?) {
            if (applyingUndoRedo || loading) return
            val text = s?.toString().orEmpty()
            if (text != lastCommitted) {
                undoStack.commit(lastCommitted)
                lastCommitted = text
                updateGutter()
                dirtyChanged()
            }
        }
    }

    // ------------------------------------------------------------------ save

    private fun saveLineBreakForFile(): LineBreak = when (settings.lineBreakDefault) {
        SettingsRepository.LINE_BREAK_LF -> LineBreak.LF
        SettingsRepository.LINE_BREAK_CRLF -> LineBreak.CRLF
        else -> fileLineBreak
    }

    private fun save() {
        if (readOnly) {
            toast(getString(R.string.read_only_toast))
            return
        }
        val uri = fileUri
        if (uri == null) {
            val suggested = FileNames.sanitize(displayName ?: "untitled.txt")
            saveAsLauncher.launch(suggested)
        } else {
            writeTo(uri)
        }
    }

    private fun writeTo(uri: Uri) {
        val target = saveLineBreakForFile()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { writeText(uri, lastCommitted, target, charsetName) }
            if (ok) {
                savedText = lastCommitted
                fileLineBreak = target
                if (displayName == null) {
                    displayName = DisplayNames.resolve(this@EditorActivity, uri)
                }
                App.recents(this@EditorActivity).add(uri.toString(), displayName ?: uri.toString())
                dirtyChanged()
                toast(getString(R.string.saved_toast))
                if (pendingFinishAfterSave) finish()
            } else {
                pendingFinishAfterSave = false
                toast(getString(R.string.save_failed, uri.lastPathSegment ?: uri.toString()))
            }
        }
    }

    private fun writeText(uri: Uri, text: String, lineBreak: LineBreak, charset: String): Boolean {
        val payload = EncodingDetector.encode(LineBreak.normalize(text, lineBreak), charset)
        return try {
            val stream = try {
                contentResolver.openOutputStream(uri, "wt")
            } catch (_: IllegalArgumentException) {
                null
            } ?: contentResolver.openOutputStream(uri) ?: return false
            stream.use { it.write(payload) }
            true
        } catch (_: Exception) {
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

    // ------------------------------------------------------------------ dialogs

    private fun showUnsavedDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.discard_changes_title)
            .setMessage(R.string.discard_changes_msg)
            .setPositiveButton(R.string.save) { _, _ ->
                pendingFinishAfterSave = true
                save()
            }
            .setNegativeButton(R.string.discard) { _, _ -> finish() }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun showFindDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_find, null)
        val findInput = view.findViewById<EditText>(R.id.find_input)
        val replaceInput = view.findViewById<EditText>(R.id.replace_input)
        val caseBox = view.findViewById<android.widget.CheckBox>(R.id.case_sensitive)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.find)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .create()

        fun options() = SearchEngine.Options(caseSensitive = caseBox.isChecked)

        view.findViewById<View>(R.id.btn_next).setOnClickListener {
            val text = binding.editor.text?.toString().orEmpty()
            val query = findInput.text.toString()
            val idx = SearchEngine.indexOf(text, query, searchStart, options(), wrapAround = true)
            if (idx < 0 || query.isEmpty()) {
                toast(getString(R.string.not_found))
            } else {
                binding.editor.setSelection(idx, idx + query.length)
                searchStart = idx + max(1, query.length)
            }
        }
        view.findViewById<View>(R.id.btn_replace).setOnClickListener {
            val query = findInput.text.toString()
            val replacement = replaceInput.text.toString()
            val editable = binding.editor.text ?: return@setOnClickListener
            val selStart = binding.editor.selectionStart
            val selEnd = binding.editor.selectionEnd
            if (query.isEmpty() || selStart < 0 || selEnd < selStart) return@setOnClickListener
            val selected = editable.substring(selStart, selEnd)
            val matches = (if (caseBox.isChecked) selected == query else selected.equals(query, true))
            if (matches) {
                editable.replace(selStart, selEnd, replacement)
                searchStart = selStart + replacement.length
            }
            val text = binding.editor.text?.toString().orEmpty()
            val idx = SearchEngine.indexOf(text, query, searchStart, options(), wrapAround = true)
            if (idx < 0) {
                toast(getString(R.string.not_found))
            } else {
                binding.editor.setSelection(idx, idx + query.length)
                searchStart = idx + max(1, query.length)
            }
        }
        view.findViewById<View>(R.id.btn_replace_all).setOnClickListener {
            val query = findInput.text.toString()
            val replacement = replaceInput.text.toString()
            val text = binding.editor.text?.toString().orEmpty()
            val (newText, count) = SearchEngine.replaceAll(text, query, replacement, options())
            if (count > 0) {
                setTextPreservingHistory(newText)
            }
            toast(getString(R.string.replaced_count, count))
        }
        dialog.show()
    }

    private fun setTextPreservingHistory(newText: String) {
        // Full replace goes through the watcher so it lands on the undo stack.
        applyingUndoRedo = false
        val editable = binding.editor.text ?: return
        editable.replace(0, editable.length, newText)
        binding.editor.setSelection(newText.length)
    }

    private fun showGotoDialog() {
        val lineCount = TextStats.lineCount(binding.editor.text?.toString().orEmpty())
        val view = layoutInflater.inflate(R.layout.dialog_goto, null)
        val input = view.findViewById<EditText>(R.id.goto_input)
        input.hint = getString(R.string.goto_hint, lineCount)
        AlertDialog.Builder(this)
            .setTitle(R.string.goto_line)
            .setView(view)
            .setPositiveButton(R.string.go) { _, _ ->
                val line = input.text.toString().toIntOrNull()
                if (line == null || line < 1 || line > lineCount) {
                    toast(getString(R.string.invalid_line))
                } else {
                    val offset = CursorNav.offsetForLine(binding.editor.text?.toString().orEmpty(), line)
                    binding.editor.setSelection(offset)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showStatsDialog() {
        val text = binding.editor.text?.toString().orEmpty()
        val message = listOf(
            getString(R.string.stats_chars, TextStats.charCount(text)),
            getString(R.string.stats_words, TextStats.wordCount(text)),
            getString(R.string.stats_lines, TextStats.lineCount(text)),
            getString(R.string.stats_encoding, charsetName)
        ).joinToString("\n")
        AlertDialog.Builder(this)
            .setTitle(R.string.stats_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun shareText() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, binding.editor.text?.toString().orEmpty())
        }
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    // ------------------------------------------------------------------ toggles

    private fun applyWordWrap(enabled: Boolean) {
        wordWrapEnabled = enabled
        binding.editor.setHorizontallyScrolling(!enabled)
        if (enabled) {
            binding.gutter.visibility = View.GONE
        } else if (settings.lineNumbers) {
            binding.gutter.visibility = View.VISIBLE
            updateGutter()
        }
    }

    private fun applyReadOnly(enabled: Boolean) {
        readOnly = enabled
        binding.editor.keyListener = if (enabled) null else originalKeyListener
        if (enabled) toast(getString(R.string.read_only_toast))
        updateUiState()
    }

    private fun updateGutter() {
        if (binding.gutter.visibility != View.VISIBLE) return
        val lines = binding.editor.lineCount
        if (lines <= 0) {
            binding.gutter.text = "1"
            return
        }
        val sb = StringBuilder(lines * 4)
        for (i in 1..lines) {
            sb.append(i).append('\n')
        }
        binding.gutter.text = sb.toString().trimEnd('\n')
        binding.gutter.scrollTo(0, binding.editor.scrollY)
    }

    // ------------------------------------------------------------------ menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_editor, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_undo)?.isEnabled = undoStack.canUndo()
        menu.findItem(R.id.action_redo)?.isEnabled = undoStack.canRedo()
        menu.findItem(R.id.action_save)?.isEnabled = isDirty() && !readOnly
        menu.findItem(R.id.action_wrap)?.isChecked = wordWrapEnabled
        menu.findItem(R.id.action_line_numbers)?.isChecked = binding.gutter.visibility == View.VISIBLE
        menu.findItem(R.id.action_read_only)?.isChecked = readOnly
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_save -> save()
            R.id.action_save_as -> {
                val suggested = FileNames.sanitize(displayName ?: "untitled.txt")
                saveAsLauncher.launch(suggested)
            }
            R.id.action_undo -> performUndo()
            R.id.action_redo -> performRedo()
            R.id.action_find -> showFindDialog()
            R.id.action_goto -> showGotoDialog()
            R.id.action_stats -> showStatsDialog()
            R.id.action_share -> shareText()
            R.id.action_wrap -> {
                val enable = !wordWrapEnabled
                applyWordWrap(enable)
                item.isChecked = enable
            }
            R.id.action_line_numbers -> {
                val show = binding.gutter.visibility != View.VISIBLE
                binding.gutter.visibility = if (show) View.VISIBLE else View.GONE
                if (show) updateGutter()
                item.isChecked = show
            }
            R.id.action_read_only -> applyReadOnly(!readOnly)
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun performUndo() {
        val previous = undoStack.undo(lastCommitted) ?: return
        applyingUndoRedo = true
        binding.editor.setText(previous)
        binding.editor.setSelection(previous.length)
        lastCommitted = previous
        applyingUndoRedo = false
        dirtyChanged()
        updateGutter()
    }

    private fun performRedo() {
        val next = undoStack.redo(lastCommitted) ?: return
        applyingUndoRedo = true
        binding.editor.setText(next)
        binding.editor.setSelection(next.length)
        lastCommitted = next
        applyingUndoRedo = false
        dirtyChanged()
        updateGutter()
    }

    // ------------------------------------------------------------------ chrome

    private fun restoreState(state: Bundle?): Boolean {
        if (state == null) return false
        if (!state.containsKey(STATE_LAST_COMMITTED)) return false
        fileUri = state.getString(STATE_URI)?.let(Uri::parse)
        displayName = state.getString(STATE_NAME)
        charsetName = state.getString(STATE_CHARSET) ?: EncodingDetector.DEFAULT_CHARSET
        fileLineBreak = state.getString(STATE_LINE_BREAK)?.let { name ->
            LineBreak.entries.firstOrNull { it.name == name }
        } ?: LineBreak.LF
        savedText = state.getString(STATE_SAVED_TEXT).orEmpty()
        val text = state.getString(STATE_TEXT)
        lastCommitted = state.getString(STATE_LAST_COMMITTED).orEmpty()
        applyingUndoRedo = true
        binding.editor.setText(text ?: lastCommitted)
        binding.editor.setSelection(binding.editor.text?.length ?: 0)
        applyingUndoRedo = false
        updateGutter()
        return true
    }

    private fun updateUiState() {
        val name = displayName ?: getString(R.string.untitled)
        binding.toolbar.title = if (isDirty()) "$name •" else name
        val subtitle = if (readOnly) getString(R.string.read_only) else charsetName
        binding.toolbar.subtitle = subtitle
        invalidateOptionsMenu()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val STATE_TEXT = "state.text"
        private const val STATE_URI = "state.uri"
        private const val STATE_NAME = "state.name"
        private const val STATE_CHARSET = "state.charset"
        private const val STATE_LINE_BREAK = "state.linebreak"
        private const val STATE_SAVED_TEXT = "state.saved_text"
        private const val STATE_LAST_COMMITTED = "state.last_committed"
        private const val SNAPSHOT_LIMIT = 300_000

        /** Convenience starter used by MainActivity and tests. */
        fun createIntent(context: android.content.Context, uri: Uri?): Intent =
            Intent(context, EditorActivity::class.java).apply {
                if (uri != null) {
                    action = Intent.ACTION_EDIT
                    data = uri
                }
            }

        fun openText(context: android.content.Context, text: String): Intent =
            Intent(context, EditorActivity::class.java).apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, text)
            }
    }
}
