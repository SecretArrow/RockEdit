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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.secretarrow.rockedit.R
import com.secretarrow.rockedit.core.App
import com.secretarrow.rockedit.core.CursorNav
import com.secretarrow.rockedit.core.EditorTab
import com.secretarrow.rockedit.core.EncodingDetector
import com.secretarrow.rockedit.core.FileNames
import com.secretarrow.rockedit.core.FormatErrorCode
import com.secretarrow.rockedit.core.FormatError
import com.secretarrow.rockedit.core.FormatOptions
import com.secretarrow.rockedit.core.FormatRequest
import com.secretarrow.rockedit.core.FormatResult
import com.secretarrow.rockedit.core.FormatterRegistry
import com.secretarrow.rockedit.core.LineBreak
import com.secretarrow.rockedit.core.LineOps
import com.secretarrow.rockedit.core.PistonClient
import com.secretarrow.rockedit.core.SearchEngine
import com.secretarrow.rockedit.core.SettingsRepository
import com.secretarrow.rockedit.core.SyntaxRegistry
import com.secretarrow.rockedit.core.TabManager
import com.secretarrow.rockedit.core.TabPersistence
import com.secretarrow.rockedit.core.TextStats
import com.secretarrow.rockedit.databinding.ActivityEditorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject

/**
 * The editing screen. Opens content:// or file:// URIs (SAF) into tabs,
 * keeps a bounded undo/redo history per document, and writes files back
 * preserving their line break style.
 *
 * Multi-tab: the tab bar lives under the toolbar; each [EditorTab] owns its
 * text, undo stack, caret, scroll, encoding and read-only flag. Opening a
 * file while the editor is already on screen delivers it through
 * [onNewIntent] as a new tab (launchMode=singleTask).
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var settings: SettingsRepository
    private lateinit var tabPersistence: TabPersistence

    private val tabManager = TabManager()
    private var editorBoundTabId = -1
    private var untitledCounter = 0

    private var applyingUndoRedo = false
    private var loading = true
    private var wordWrapEnabled = false
    private var pendingCloseIndex = -1
    private var pendingFinishAfterSave = false
    private var searchStart = 0
    private var originalKeyListener: KeyListener? = null
    private lateinit var highlighter: SyntaxHighlighter
    private var syntaxOn = true
    private var tabSignature: String? = null

    private val saveAsLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) {
                val tab = tabManager.activeTab()
                if (tab != null) {
                    takePersistentPermission(uri)
                    tab.uri = uri.toString()
                    tab.name = DisplayNames.resolve(this, uri)
                    writeTo(tab)
                }
            }
        }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (hasDirtyTabs()) {
                showUnsavedDialog()
            } else {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = App.settings(this)
        if (settings.isBlackTheme()) setTheme(R.style.Theme_RockEdit_Black)
        settings.applyTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        tabPersistence = TabPersistence(App.keyValueStore(this))
        applyFullScreen()

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        onBackPressedDispatcher.addCallback(this, backCallback)

        originalKeyListener = binding.editor.keyListener
        highlighter = SyntaxHighlighter(binding.editor)

        binding.editor.addTextChangedListener(EditorWatcher())
        binding.editor.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            binding.gutter.scrollTo(0, scrollY)
        }

        val restored = restoreState(savedInstanceState)
        if (!restored) {
            if (!addTabFromIntent(intent)) {
                restorePersistedTabs()
            }
        }
        syntaxOn = settings.syntaxHighlight
        highlighter.setEnabled(syntaxOn)
        applyWordWrap(settings.wordWrap)
        val fontSp = settings.fontSizeSp
        binding.editor.textSize = fontSp.toFloat()
        binding.gutter.textSize = fontSp.toFloat()
        if (settings.lineNumbers) {
            updateGutter()
        } else {
            binding.gutter.visibility = View.GONE
        }
        loading = false
        updateUiState()
    }

    // Public so instrumentation tests can deliver intents the way the
    // system does for a singleTask instance that is already on screen.
    public override fun onNewIntent(passedIntent: Intent) {
        super.onNewIntent(passedIntent)
        setIntent(passedIntent)
        addTabFromIntent(passedIntent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        captureActiveState()
        val arr = JSONArray()
        var budget = SNAPSHOT_TOTAL_BUDGET
        for (tab in tabManager.tabs()) {
            val obj = JSONObject()
            obj.put(F_URI, tab.uri ?: "")
            obj.put(F_NAME, tab.name)
            obj.put(F_CHARSET, tab.charsetName)
            obj.put(F_LINE_BREAK, tab.lineBreak.name)
            obj.put(F_READ_ONLY, tab.readOnly)
            obj.put(F_LOADED, tab.loaded)
            obj.put(F_CARET, tab.caretStart)
            obj.put(F_CARET_END, tab.caretEnd)
            obj.put(F_SCROLL, tab.scrollY)
            var committed = if (budget > 0 && tab.lastCommitted.length <= SNAPSHOT_LIMIT)
                tab.lastCommitted else ""
            if (committed.length > budget) committed = ""
            budget -= committed.length
            val saved = if (tab.isDirty || tab.savedText != tab.lastCommitted) {
                var s = if (tab.savedText.length <= SNAPSHOT_LIMIT) tab.savedText else ""
                if (s.length > budget) s = ""
                s
            } else {
                committed
            }
            budget -= saved.length
            obj.put(F_SAVED, saved)
            obj.put(F_COMMITTED, committed)
            arr.put(obj)
        }
        outState.putString(STATE_TABS, arr.toString())
        outState.putInt(STATE_ACTIVE, tabManager.activeIndex())
    }

    // ------------------------------------------------------------------ intent

    /** Turns an incoming intent into a new (or existing) tab. Returns true if handled. */
    private fun addTabFromIntent(intent: Intent?): Boolean {
        val action = intent?.action
        val data: Uri? = intent?.data
        return when {
            (action == Intent.ACTION_VIEW || action == Intent.ACTION_EDIT) && data != null -> {
                val uriStr = data.toString()
                val existing = tabManager.indexOfUri(uriStr)
                if (existing >= 0) {
                    showTab(existing)
                } else {
                    if (tabManager.isFull()) {
                        toast(getString(R.string.tab_limit_reached, TabManager.MAX_TABS))
                        return true
                    }
                    takePersistentPermission(data)
                    val tab = EditorTab.pending(
                        uri = uriStr,
                        name = data.lastPathSegment ?: "…"
                    )
                    val index = tabManager.add(tab)
                    showTab(index)
                }
                true
            }
            action == Intent.ACTION_SEND -> {
                val shared = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                val tab = newUntitledTab()
                tab.lastCommitted = shared
                val index = tabManager.add(tab)
                showTab(index)
                true
            }
            else -> false
        }
    }

    // ------------------------------------------------------------- tab engine

    private fun newUntitledTab(): EditorTab {
        val n = untitledCounter++
        val name = if (n == 0) getString(R.string.untitled)
        else getString(R.string.untitled_n, n + 1)
        return EditorTab.untitled(name)
    }

    private fun restorePersistedTabs() {
        if (!settings.rememberTabs) {
            activateFreshTab(newUntitledTab())
            return
        }
        val saved = tabPersistence.load()
        if (saved == null || saved.tabs.isEmpty()) {
            activateFreshTab(newUntitledTab())
            return
        }
        for (st in saved.tabs) {
            val tab: EditorTab = if (st.uri == null) {
                EditorTab(id = EditorTab.newId(), uri = null, name = st.name, loaded = true)
            } else {
                EditorTab.pending(st.uri, st.name.ifEmpty { "…" })
            }
            tab.charsetName = st.charset
            val lb = LineBreak.entries.firstOrNull { it.name == st.lineBreak }
            if (lb != null) tab.lineBreak = lb
            tab.readOnly = st.readOnly
            tabManager.add(tab)
        }
        val idx = saved.activeIndex.coerceIn(0, tabManager.size() - 1)
        showTab(idx)
    }

    private fun activateFreshTab(tab: EditorTab) {
        val index = tabManager.add(tab)
        showTab(index)
    }

    private fun newTab() {
        if (tabManager.isFull()) {
            toast(getString(R.string.tab_limit_reached, TabManager.MAX_TABS))
            return
        }
        activateFreshTab(newUntitledTab())
    }

    /** Stores the visible editor state into the tab currently bound to it. */
    private fun captureActiveState() {
        val tab = tabManager.activeTab() ?: return
        if (tab.id != editorBoundTabId || !tab.loaded) return
        tab.lastCommitted = binding.editor.text?.toString() ?: ""
        val start = binding.editor.selectionStart
        val end = binding.editor.selectionEnd
        if (start >= 0 && end >= 0) {
            tab.caretStart = min(start, end)
            tab.caretEnd = max(start, end)
        }
        tab.scrollY = binding.editor.scrollY
    }

    /** Activates the tab at [index] (capturing the outgoing tab first). */
    private fun showTab(index: Int) {
        captureActiveState()
        val tab = tabManager.setActive(index) ?: return
        searchStart = 0
        if (tab.loaded) {
            renderTab(tab)
        } else {
            loadTab(tab)
        }
        updateUiState()
    }

    /** Puts [tab]'s content into the editor surface. */
    private fun renderTab(tab: EditorTab) {
        applyingUndoRedo = true
        binding.editor.setText(tab.lastCommitted)
        applyingUndoRedo = false
        val len = tab.lastCommitted.length
        val s = tab.caretStart.coerceIn(0, len)
        val e = tab.caretEnd.coerceIn(0, len)
        binding.editor.setSelection(min(s, e), max(s, e))
        if (tab.scrollY > 0) {
            binding.editor.post {
                if (tabManager.activeTab()?.id == tab.id) {
                    binding.editor.scrollTo(0, tab.scrollY)
                    binding.gutter.scrollTo(0, tab.scrollY)
                }
            }
        }
        binding.editor.keyListener = if (tab.readOnly) null else originalKeyListener
        editorBoundTabId = tab.id
        updateSyntaxLanguage()
        updateGutter()
        highlighter.rehighlightNow()
    }

    /** Loads a lazily-restored tab from its URI, then renders it when active. */
    private fun loadTab(tab: EditorTab) {
        val uriStr = tab.uri
        if (uriStr == null) {
            tab.loaded = true
            renderTab(tab)
            return
        }
        loading = true
        val uri = Uri.parse(uriStr)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { readBytes(uri) }
            if (result == null) {
                tab.loaded = true
                tab.savedText = ""
                tab.lastCommitted = ""
                toast(getString(R.string.open_failed, tab.name))
            } else {
                val (bytes, maybeBinary) = result
                tab.charsetName = EncodingDetector.detectName(bytes)
                val text = EncodingDetector.decode(bytes, tab.charsetName)
                tab.lineBreak = LineBreak.detect(text, LineBreak.LF)
                tab.name = DisplayNames.resolve(this@EditorActivity, uri)
                tab.savedText = text
                tab.lastCommitted = text
                tab.loaded = true
                val session = App.sessions(this@EditorActivity).loadCursor(uriStr)
                if (session != null) {
                    tab.caretStart = session.selStart
                    tab.caretEnd = session.selEnd
                    tab.scrollY = session.scrollY
                }
                showBinaryHintIfNeeded(maybeBinary)
            }
            loading = false
            if (tabManager.activeTab()?.id == tab.id) {
                renderTab(tab)
                updateUiState()
            }
        }
    }

    // -------------------------------------------------------------- tab bar UI

    /** Rebuilds the tab chips; cheap (max 10 tabs) and only on real changes. */
    private fun refreshTabs() {
        val bar = binding.tabBar
        bar.removeAllViews()
        val inflater = layoutInflater
        val tabs = tabManager.tabs()
        tabs.forEachIndexed { index, tab ->
            val chip = inflater.inflate(R.layout.item_tab, bar, false) as LinearLayout
            val name = chip.findViewById<TextView>(R.id.tab_name)
            val close = chip.findViewById<TextView>(R.id.tab_close)
            name.text = if (tab.isDirty) "\u2022 ${tab.name}" else tab.name
            val active = index == tabManager.activeIndex()
            chip.setBackgroundColor(
                getColor(
                    if (active) R.color.tab_chip_active else R.color.tab_chip_inactive
                )
            )
            chip.setOnClickListener { showTab(index) }
            chip.setOnLongClickListener {
                closeTab(index)
                true
            }
            close.setOnClickListener { closeTab(index) }
            bar.addView(chip)
        }
        val plus = TextView(this).apply {
            text = "+"
            contentDescription = getString(R.string.new_tab)
            textSize = 18f
            setPadding(24, 0, 24, 0)
            gravity = android.view.Gravity.CENTER
            setOnClickListener { newTab() }
        }
        bar.addView(plus)
        binding.tabScroll.post {
            val idx = tabManager.activeIndex()
            val activeChild = if (idx in tabs.indices) bar.getChildAt(idx) else plus
            if (activeChild != null) {
                binding.tabScroll.smoothScrollTo(activeChild.left, 0)
            }
        }
    }

    private fun tabStateSignature(): String = buildString {
        append(tabManager.activeIndex()).append('|')
        for (t in tabManager.tabs()) append(t.id).append(':').append(t.isDirty).append(':')
            .append(t.name).append('|')
    }

    private fun closeTab(index: Int) {
        val tab = tabManager.tabs().getOrNull(index) ?: return
        if (tab.isDirty && tab.loaded) {
            pendingCloseIndex = index
            AlertDialog.Builder(this)
                .setTitle(R.string.close_tab)
                .setMessage(getString(R.string.close_tab_confirm, tab.name))
                .setPositiveButton(R.string.save) { _, _ ->
                    if (tabManager.activeIndex() != index) showTab(index)
                    pendingFinishAfterSave = false
                    save { performClose(index) }
                }
                .setNegativeButton(R.string.discard) { _, _ -> performClose(index) }
                .setNeutralButton(R.string.cancel) { _, _ -> pendingCloseIndex = -1 }
                .show()
        } else {
            performClose(index)
        }
    }

    private fun performClose(index: Int) {
        pendingCloseIndex = -1
        tabManager.close(index)
        if (tabManager.size() == 0) {
            finish()
            return
        }
        showTab(tabManager.activeIndex())
    }

    private fun closeOthers(index: Int) {
        if (tabManager.size() <= 1) return
        AlertDialog.Builder(this)
            .setTitle(R.string.close_others)
            .setMessage(getString(R.string.close_others_confirm, tabManager.size() - 1))
            .setPositiveButton(R.string.close_others) { _, _ ->
                tabManager.closeOthers(index)
                showTab(0)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun hasDirtyTabs(): Boolean = tabManager.tabs().any { it.loaded && it.isDirty }

    // -------------------------------------------------------- session restore

    override fun onPause() {
        super.onPause()
        captureActiveState()
        for (tab in tabManager.tabs()) {
            val uri = tab.uri
            if (tab.loaded && uri != null && tab.caretStart >= 0) {
                App.sessions(this).saveCursor(
                    uri, tab.caretStart, tab.caretEnd, tab.scrollY
                )
            }
        }
        persistTabs()
        if (settings.autoSave) {
            for (tab in tabManager.dirtyFileTabs()) {
                writeTo(tab)
            }
        }
    }

    private fun persistTabs() {
        if (settings.rememberTabs && tabManager.size() > 0) {
            tabPersistence.save(tabManager.tabs(), tabManager.activeIndex())
        } else {
            tabPersistence.clear()
        }
    }

    /** Hides system bars when the full screen setting is enabled. */
    private fun applyFullScreen() {
        if (!settings.fullScreen) return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun showBinaryHintIfNeeded(maybeBinary: Boolean) {
        if (maybeBinary) {
            Toast.makeText(this, R.string.binary_warning, Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ text state

    private fun setTextInternal(newText: String) {
        val tab = tabManager.activeTab() ?: return
        applyingUndoRedo = true
        binding.editor.setText(newText)
        binding.editor.setSelection(newText.length)
        applyingUndoRedo = false
        tab.lastCommitted = newText
        tab.undoStack.clear()
        editorBoundTabId = tab.id
        updateGutter()
        dirtyChanged()
        highlighter.rehighlightNow()
    }

    private fun isDirty(): Boolean = tabManager.activeTab()?.isDirty == true

    private fun dirtyChanged() {
        updateUiState()
    }

    private inner class EditorWatcher : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

        override fun afterTextChanged(s: Editable?) {
            if (applyingUndoRedo || loading) return
            val tab = tabManager.activeTab() ?: return
            val text = s?.toString().orEmpty()
            if (text != tab.lastCommitted) {
                tab.undoStack.commit(tab.lastCommitted)
                tab.lastCommitted = text
                updateGutter()
                dirtyChanged()
                highlighter.scheduleHighlight()
            }
        }
    }

    // ------------------------------------------------------------------ save

    private fun saveLineBreakForFile(): LineBreak = when (settings.lineBreakDefault) {
        SettingsRepository.LINE_BREAK_LF -> LineBreak.LF
        SettingsRepository.LINE_BREAK_CRLF -> LineBreak.CRLF
        else -> tabManager.activeTab()?.lineBreak ?: LineBreak.LF
    }

    private fun save(afterSave: (() -> Unit)? = null) {
        val tab = tabManager.activeTab() ?: return
        if (tab.readOnly) {
            toast(getString(R.string.read_only_toast))
            afterSave?.invoke()
            return
        }
        val uri = tab.uri
        if (uri == null) {
            val suggested = FileNames.sanitize(tab.name)
            saveAsLauncher.launch(suggested)
        } else {
            writeTo(tab, afterSave)
        }
    }

    private fun writeTo(tab: EditorTab, afterSave: (() -> Unit)? = null) {
        val uriStr = tab.uri ?: return
        val target = saveLineBreakForFile()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                writeText(Uri.parse(uriStr), tab.lastCommitted, target, tab.charsetName)
            }
            if (ok) {
                tab.savedText = tab.lastCommitted
                tab.lineBreak = target
                val uri = Uri.parse(uriStr)
                if (tab.name.isEmpty() || tab.name == "…") {
                    tab.name = DisplayNames.resolve(this@EditorActivity, uri)
                }
                App.recents(this@EditorActivity).add(uriStr, tab.name)
                dirtyChanged()
                toast(getString(R.string.saved_toast))
            } else {
                toast(getString(R.string.save_failed, tab.name))
            }
            afterSave?.invoke()
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
        val dirtyCount = tabManager.tabs().count { it.loaded && it.isDirty }
        AlertDialog.Builder(this)
            .setTitle(R.string.discard_changes_title)
            .setMessage(
                if (dirtyCount > 1) getString(R.string.discard_changes_multi, dirtyCount)
                else getString(R.string.discard_changes_msg)
            )
            .setPositiveButton(R.string.save) { _, _ -> saveAllThenFinish() }
            .setNegativeButton(R.string.discard) { _, _ -> finish() }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun saveAllThenFinish() {
        val dirtyFiles = tabManager.tabs().filter { it.isDirty && it.uri != null && it.loaded }
        val untitledDirty = tabManager.tabs().count { it.isDirty && it.uri == null && it.loaded }
        if (untitledDirty > 0) {
            toast(getString(R.string.untitled_not_saved, untitledDirty))
        }
        if (dirtyFiles.isEmpty()) {
            finish()
            return
        }
        var remaining = dirtyFiles.size
        for (tab in dirtyFiles) {
            writeTo(tab) {
                remaining--
                if (remaining == 0) finish()
            }
        }
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
            getString(R.string.stats_encoding, tabManager.activeTab()?.charsetName ?: EncodingDetector.DEFAULT_CHARSET)
        ).joinToString("\n")
        AlertDialog.Builder(this)
            .setTitle(R.string.stats_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // -------------------------------------------------------- line operations

    /**
     * Applies a pure [LineOps] transform to the whole text. The edit goes
     * through the text watcher, so it automatically lands on the undo stack.
     */
    private fun applyLineOp(op: (String, Int, Int) -> LineOps.Result) {
        val editable = binding.editor.text ?: return
        val text = editable.toString()
        val start = binding.editor.selectionStart
        val end = binding.editor.selectionEnd
        if (start < 0 || end < start) return
        val result = op(text, start, end)
        if (result.text == text) return
        applyingUndoRedo = false
        editable.replace(0, text.length, result.text)
        binding.editor.setSelection(
            result.selStart.coerceIn(0, result.text.length),
            result.selEnd.coerceIn(0, result.text.length)
        )
        updateGutter()
        dirtyChanged()
    }

    // -------------------------------------------------------------- bookmarks

    private fun currentLine(): Int {
        val text = binding.editor.text?.toString().orEmpty()
        val sel = binding.editor.selectionStart.coerceAtLeast(0)
        return CursorNav.lineForOffset(text, sel)
    }

    private fun toggleBookmark() {
        val tab = tabManager.activeTab() ?: return
        val uri = tab.uri ?: return
        val line = currentLine()
        val text = binding.editor.text?.toString().orEmpty()
        val ls = LineOps.lineStart(text, binding.editor.selectionStart.coerceAtLeast(0))
        val le = LineOps.lineEnd(text, binding.editor.selectionStart.coerceAtLeast(0))
        val label = text.substring(ls, le).trim().take(60)
        val added = App.bookmarks(this).toggle(uri, line, label)
        toast(
            if (added) getString(R.string.bookmark_line) + " " + line
            else getString(R.string.bookmarks) + " " + line + " \u2717"
        )
    }

    private fun showBookmarksDialog() {
        val tab = tabManager.activeTab() ?: return
        val uri = tab.uri
        if (uri == null) {
            toast(getString(R.string.no_bookmarks))
            return
        }
        val items = App.bookmarks(this).list(uri)
        if (items.isEmpty()) {
            toast(getString(R.string.no_bookmarks))
            return
        }
        val text = binding.editor.text?.toString().orEmpty()
        val labels = items.map { b ->
            val lineText = if (text.isEmpty()) "" else {
                val ls = LineOps.lineStart(text, CursorNav.offsetForLine(text, b.line))
                val le = LineOps.lineEnd(text, CursorNav.offsetForLine(text, b.line))
                text.substring(ls, le).trim().take(40)
            }
            "${b.line}: ${b.label.ifEmpty { lineText }}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.bookmarks)
            .setItems(labels) { _, which ->
                val offset = CursorNav.offsetForLine(text, items[which].line)
                binding.editor.setSelection(offset)
                binding.editor.requestFocus()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun shareText() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, binding.editor.text?.toString().orEmpty())
        }
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    private fun printDocument() {
        val tab = tabManager.activeTab() ?: return
        val text = binding.editor.text?.toString().orEmpty()
        TextPrinter.print(this, FileNames.sanitize(tab.name), text)
    }

    private fun showPreview() {
        val tab = tabManager.activeTab() ?: return
        val intent = Intent(this, PreviewActivity::class.java)
            .putExtra(PreviewActivity.EXTRA_TEXT, binding.editor.text?.toString().orEmpty())
            .putExtra(PreviewActivity.EXTRA_TITLE, tab.name)
            .putExtra(PreviewActivity.EXTRA_FILE_NAME, tab.name)
        startActivity(intent)
    }

    private fun runCode() {
        val tab = tabManager.activeTab() ?: return
        val text = binding.editor.text?.toString().orEmpty()
        val languageId = SyntaxRegistry.languageForFileName(tab.name)?.id
        val pistonLanguage = languageId?.let { PistonClient.LANGUAGE_MAP[it] }
        if (pistonLanguage == null) {
            toast(getString(R.string.run_not_supported))
            return
        }
        if (!settings.onlineExecution) {
            AlertDialog.Builder(this)
                .setTitle(R.string.run_consent_title)
                .setMessage(R.string.run_consent_msg)
                .setPositiveButton(R.string.run_consent_yes) { _, _ ->
                    settings.onlineExecution = true
                    executeOnline(pistonLanguage, text)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        executeOnline(pistonLanguage, text)
    }

    private fun executeOnline(language: String, code: String) {
        toast(getString(R.string.run_done) + "…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    PistonClient.execute(language, code)
                } catch (e: Exception) {
                    PistonClient.ExecutionResult("", e.message ?: "network error", "", -1)
                }
            }
            AlertDialog.Builder(this@EditorActivity)
                .setTitle(R.string.run_output_title)
                .setMessage(result.summary)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
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
        val tab = tabManager.activeTab() ?: return
        tab.readOnly = enabled
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
        val tab = tabManager.activeTab()
        menu.findItem(R.id.action_undo)?.isEnabled = tab?.undoStack?.canUndo() == true
        menu.findItem(R.id.action_redo)?.isEnabled = tab?.undoStack?.canRedo() == true
        menu.findItem(R.id.action_save)?.isEnabled = isDirty() && tab?.readOnly != true
        menu.findItem(R.id.action_wrap)?.isChecked = wordWrapEnabled
        menu.findItem(R.id.action_line_numbers)?.isChecked = binding.gutter.visibility == View.VISIBLE
        menu.findItem(R.id.action_read_only)?.isChecked = tab?.readOnly == true
        menu.findItem(R.id.action_syntax)?.isChecked = syntaxOn
        menu.findItem(R.id.action_next_tab)?.isEnabled = tabManager.size() > 1
        menu.findItem(R.id.action_close_others)?.isEnabled = tabManager.size() > 1
        tab?.uri?.let { uri ->
            menu.findItem(R.id.action_bookmark_toggle)?.isChecked =
                App.bookmarks(this).has(uri, currentLine())
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_save -> save()
            R.id.action_save_as -> {
                val suggested = FileNames.sanitize(
                    tabManager.activeTab()?.name ?: "untitled.txt"
                )
                saveAsLauncher.launch(suggested)
            }
            R.id.action_new_tab -> newTab()
            R.id.action_next_tab -> {
                val next = tabManager.next()
                if (next != null) showTab(tabManager.activeIndex())
            }
            R.id.action_close_tab -> closeTab(tabManager.activeIndex())
            R.id.action_close_others -> closeOthers(tabManager.activeIndex())
            R.id.action_open_folder -> startActivity(
                Intent(this, FolderBrowserActivity::class.java)
            )
            R.id.action_undo -> performUndo()
            R.id.action_redo -> performRedo()
            R.id.action_format -> formatDocument()
            R.id.action_find -> showFindDialog()
            R.id.action_goto -> showGotoDialog()
            R.id.action_duplicate_line -> applyLineOp(LineOps::duplicateLine)
            R.id.action_delete_line -> applyLineOp(LineOps::deleteLine)
            R.id.action_move_line_up -> applyLineOp(LineOps::moveLineUp)
            R.id.action_move_line_down -> applyLineOp(LineOps::moveLineDown)
            R.id.action_bookmark_toggle -> {
                toggleBookmark()
                item.isChecked = tabManager.activeTab()?.uri?.let { uri ->
                    App.bookmarks(this).has(uri, currentLine())
                } == true
            }
            R.id.action_bookmarks -> showBookmarksDialog()
            R.id.action_stats -> showStatsDialog()
            R.id.action_print -> printDocument()
            R.id.action_preview -> showPreview()
            R.id.action_run -> runCode()
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
            R.id.action_read_only -> applyReadOnly(tabManager.activeTab()?.readOnly != true)
            R.id.action_syntax -> {
                syntaxOn = !syntaxOn
                highlighter.setEnabled(syntaxOn)
                item.isChecked = syntaxOn
            }
            R.id.action_reopen_encoding -> showReopenEncodingDialog()
            R.id.action_save_encoding -> showSaveEncodingDialog()
            R.id.action_insert_datetime -> insertDateTime()
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun performUndo() {
        val tab = tabManager.activeTab() ?: return
        val previous = tab.undoStack.undo(tab.lastCommitted) ?: return
        applyingUndoRedo = true
        binding.editor.setText(previous)
        binding.editor.setSelection(previous.length)
        tab.lastCommitted = previous
        applyingUndoRedo = false
        dirtyChanged()
        updateGutter()
        highlighter.rehighlightNow()
    }

    private fun performRedo() {
        val tab = tabManager.activeTab() ?: return
        val next = tab.undoStack.redo(tab.lastCommitted) ?: return
        applyingUndoRedo = true
        binding.editor.setText(next)
        binding.editor.setSelection(next.length)
        tab.lastCommitted = next
        applyingUndoRedo = false
        dirtyChanged()
        updateGutter()
        highlighter.rehighlightNow()
    }

    // ------------------------------------------------------------------ chrome

    private fun restoreState(state: Bundle?): Boolean {
        val raw = state?.getString(STATE_TABS) ?: return false
        return try {
            val arr = JSONArray(raw)
            if (arr.length() == 0) return false
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val uri = o.optString(F_URI).ifEmpty { null }
                val loaded = o.optBoolean(F_LOADED, true)
                val committed = o.optString(F_COMMITTED, "")
                val saved = o.optString(F_SAVED, "")
                val tab: EditorTab = if (loaded && (uri == null || committed.isNotEmpty())) {
                    EditorTab(id = EditorTab.newId(), uri = uri, name = o.optString(F_NAME))
                } else if (uri != null) {
                    EditorTab.pending(uri, o.optString(F_NAME).ifEmpty { "…" })
                } else {
                    EditorTab(id = EditorTab.newId(), uri = null, name = o.optString(F_NAME))
                }
                tab.charsetName = o.optString(F_CHARSET, EncodingDetector.DEFAULT_CHARSET)
                val lb = LineBreak.entries.firstOrNull { it.name == o.optString(F_LINE_BREAK) }
                if (lb != null) tab.lineBreak = lb
                tab.readOnly = o.optBoolean(F_READ_ONLY, false)
                tab.caretStart = o.optInt(F_CARET, 0)
                tab.caretEnd = o.optInt(F_CARET_END, 0)
                tab.scrollY = o.optInt(F_SCROLL, 0)
                tab.savedText = saved
                tab.lastCommitted = committed
                tab.loaded = loaded && (uri == null || committed.isNotEmpty())
                tabManager.add(tab)
            }
            showTab(state.getInt(STATE_ACTIVE, 0).coerceIn(0, tabManager.size() - 1))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun updateUiState() {
        val tab = tabManager.activeTab()
        if (tab == null) {
            binding.toolbar.title = getString(R.string.untitled)
            refreshTabs()
            invalidateOptionsMenu()
            return
        }
        val name = tab.name.ifEmpty { getString(R.string.untitled) }
        binding.toolbar.title = if (tab.isDirty) "$name \u2022" else name
        binding.toolbar.subtitle = if (tab.readOnly) {
            getString(R.string.read_only)
        } else {
            tab.charsetName
        }
        val sig = tabStateSignature()
        if (sig != tabSignature) {
            tabSignature = sig
            refreshTabs()
        }
        invalidateOptionsMenu()
    }

    /**
     * Formats the active document with the formatter matching its language
     * (JSON/XML/CSS get full formatters; any other type gets whitespace
     * normalization). Runs off the main thread; the result replaces the
     * document content and is pushed onto the undo stack so Format can be
     * undone like any edit. Every failure path shows a localized toast —
     * the editor is never left in a broken state.
     */
    private fun formatDocument() {
        val tab = tabManager.activeTab() ?: return
        val editable = binding.editor.text ?: return
        if (tab.readOnly) {
            toast(getString(R.string.read_only_toast))
            return
        }
        val text = editable.toString()
        if (text.isBlank()) {
            toast(getString(R.string.format_nothing))
            return
        }
        val languageId = SyntaxRegistry.languageForFileName(tab.name)?.id ?: "txt"
        toast(getString(R.string.format_running))
        lifecycleScope.launch {
            // Keep the file's own line break style (detected once, up front).
            val options = FormatOptions(lineBreak = LineBreak.detect(text, fallback = LineBreak.LF))
            val result = withContext(Dispatchers.Default) {
                FormatterRegistry.default().format(FormatRequest(text, languageId, options))
            }
            when (result) {
                is FormatResult.Success -> {
                    if (result.changed) {
                        applyingUndoRedo = false
                        editable.replace(0, text.length, result.formattedText)
                        binding.editor.setSelection(0)
                        updateGutter()
                        dirtyChanged()
                        toast(getString(R.string.format_done, result.durationMs))
                    } else {
                        toast(getString(R.string.format_unchanged))
                    }
                }
                is FormatResult.Skipped -> toast(getString(R.string.format_nothing))
                is FormatResult.Failure -> toast(formatErrorMessage(result.error))
            }
        }
    }

    /** Maps a [FormatError] to a user-facing message in the UI language. */
    private fun formatErrorMessage(error: FormatError): String {
        val line = error.line
        return when (error.code) {
            FormatErrorCode.INPUT_TOO_LARGE -> getString(R.string.format_error_too_large)
            FormatErrorCode.UNSUPPORTED_LANGUAGE -> getString(R.string.format_error_generic)
            FormatErrorCode.PARSE_ERROR ->
                if (line != null) getString(R.string.format_error_parse_line, line)
                else getString(R.string.format_error_parse_generic)
            FormatErrorCode.TIMEOUT -> getString(R.string.format_error_timeout)
            FormatErrorCode.INTERNAL_ERROR -> getString(R.string.format_error_generic)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ syntax & encoding

    /** Re-detects the syntax language from the active tab's file name. */
    private fun updateSyntaxLanguage() {
        val name = tabManager.activeTab()?.name
        highlighter.setLanguage(SyntaxRegistry.languageForFileName(name))
    }

    private fun showReopenEncodingDialog() {
        val tab = tabManager.activeTab() ?: return
        val uriStr = tab.uri
        if (uriStr == null) {
            toast(getString(R.string.reopen_needs_file))
            return
        }
        if (tab.isDirty) {
            AlertDialog.Builder(this)
                .setTitle(R.string.reopen_encoding)
                .setMessage(R.string.reopen_discard_msg)
                .setPositiveButton(R.string.continue_label) { _, _ ->
                    pickCharsetAndReopen(tab)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            pickCharsetAndReopen(tab)
        }
    }

    private fun pickCharsetAndReopen(tab: EditorTab) {
        val names = EncodingDetector.COMMON_CHARSETS.toTypedArray()
        val current = names.indexOf(tab.charsetName)
        AlertDialog.Builder(this)
            .setTitle(R.string.reopen_encoding)
            .setSingleChoiceItems(names, current) { dialog, which ->
                dialog.dismiss()
                reopenWith(tab, names[which])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun reopenWith(tab: EditorTab, charset: String) {
        val uriStr = tab.uri ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { readBytes(Uri.parse(uriStr)) }
            val bytes = result?.first
            if (bytes == null) {
                toast(getString(R.string.open_failed, tab.name))
                return@launch
            }
            val text = EncodingDetector.decode(bytes, charset)
            tab.charsetName = charset
            tab.savedText = text
            tab.lastCommitted = text
            if (tabManager.activeTab()?.id == tab.id) {
                setTextInternal(text)
                updateUiState()
            }
        }
    }

    private fun showSaveEncodingDialog() {
        val tab = tabManager.activeTab() ?: return
        val names = EncodingDetector.COMMON_CHARSETS.toTypedArray()
        val current = names.indexOf(tab.charsetName)
        AlertDialog.Builder(this)
            .setTitle(R.string.save_encoding)
            .setSingleChoiceItems(names, current) { dialog, which ->
                dialog.dismiss()
                tab.charsetName = names[which]
                updateUiState()
                save()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun insertDateTime() {
        val editable = binding.editor.text ?: return
        val tab = tabManager.activeTab()
        if (tab?.readOnly == true) {
            toast(getString(R.string.read_only_toast))
            return
        }
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(LocalDateTime.now())
        val start = binding.editor.selectionStart.coerceIn(0, editable.length)
        editable.replace(start, start, stamp)
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

    companion object {
        private const val STATE_TABS = "state.tabs"
        private const val STATE_ACTIVE = "state.active"
        private const val F_URI = "uri"
        private const val F_NAME = "name"
        private const val F_CHARSET = "charset"
        private const val F_LINE_BREAK = "line_break"
        private const val F_READ_ONLY = "read_only"
        private const val F_LOADED = "loaded"
        private const val F_CARET = "caret"
        private const val F_CARET_END = "caret_end"
        private const val F_SCROLL = "scroll"
        private const val F_SAVED = "saved"
        private const val F_COMMITTED = "committed"
        private const val SNAPSHOT_LIMIT = 200_000
        private const val SNAPSHOT_TOTAL_BUDGET = 500_000

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
