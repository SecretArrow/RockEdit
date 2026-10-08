package com.secretarrow.rockedit.ui

import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.text.method.KeyListener
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.secretarrow.rockedit.R
import com.secretarrow.rockedit.core.App
import com.secretarrow.rockedit.core.BraceMatcher
import com.secretarrow.rockedit.core.CharsetLab
import com.secretarrow.rockedit.core.ClipboardHistoryStore
import com.secretarrow.rockedit.core.CodeFolding
import com.secretarrow.rockedit.core.CodeStatistics
import com.secretarrow.rockedit.core.ColorExtractor
import com.secretarrow.rockedit.core.CommentProfiles
import com.secretarrow.rockedit.core.CursorNav
import com.secretarrow.rockedit.core.EditorConfigParser
import com.secretarrow.rockedit.core.EditorTab
import com.secretarrow.rockedit.core.EncodingDetector
import com.secretarrow.rockedit.core.FileNames
import com.secretarrow.rockedit.core.FoldErrorCode
import com.secretarrow.rockedit.core.FoldResult
import com.secretarrow.rockedit.core.FolderGrep
import com.secretarrow.rockedit.core.FormatError
import com.secretarrow.rockedit.core.FormatErrorCode
import com.secretarrow.rockedit.core.FormatOptions
import com.secretarrow.rockedit.core.FormatRequest
import com.secretarrow.rockedit.core.FormatResult
import com.secretarrow.rockedit.core.FormatterRegistry
import com.secretarrow.rockedit.core.ImageExportErrorCode
import com.secretarrow.rockedit.core.ImageExportOptions
import com.secretarrow.rockedit.core.ImageExportPlanner
import com.secretarrow.rockedit.core.ImagePlanResult
import com.secretarrow.rockedit.core.LineBreak
import com.secretarrow.rockedit.core.LineNumbering
import com.secretarrow.rockedit.core.LineOps
import com.secretarrow.rockedit.core.PdfExportErrorCode
import com.secretarrow.rockedit.core.PdfExportOptions
import com.secretarrow.rockedit.core.PdfExportPlanner
import com.secretarrow.rockedit.core.PdfPlanResult
import com.secretarrow.rockedit.core.PistonClient
import com.secretarrow.rockedit.core.RegexTester
import com.secretarrow.rockedit.core.SearchEngine
import com.secretarrow.rockedit.core.SettingsRepository
import com.secretarrow.rockedit.core.SnippetStore
import com.secretarrow.rockedit.core.SyntaxRegistry
import com.secretarrow.rockedit.core.SyntaxTokenizer
import com.secretarrow.rockedit.core.TabManager
import com.secretarrow.rockedit.core.TabPersistence
import com.secretarrow.rockedit.core.TextStats
import com.secretarrow.rockedit.core.TextUtilities
import com.secretarrow.rockedit.core.TodoScanner
import com.secretarrow.rockedit.core.ZenActive
import com.secretarrow.rockedit.core.ZenMode
import com.secretarrow.rockedit.core.ZenResult
import com.secretarrow.rockedit.core.ZenSnapshot
import com.secretarrow.rockedit.databinding.ActivityEditorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

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

    /** Clipboard history (v0.13.0): captured on resume, inserted on demand. */
    private val clipboardHistory by lazy { ClipboardHistoryStore(App.keyValueStore(this)) }

    /**
     * v0.14.0 code folding engine. Stateful (remembers folded bodies for
     * unfolding), so it is kept per language id; switching the active tab to
     * a different language rebuilds it (documented v1 limitation: archives
     * of the previous language are dropped, stale placeholders then surface
     * as a localized fold error instead of corrupting the document).
     */
    private var foldEngine: CodeFolding? = null
    private var foldEngineLanguage: String? = null

    /** v0.14.0 zen mode session; survives rotation, not app restarts. */
    private var zenActive: ZenActive? = null

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

    private val diffFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                startDiffWithFile(uri)
            }
        }

    /**
     * v0.18.0 "Open file": SAF picker that adds the picked document as a new
     * tab (or focuses the tab that already has it). Reuses the intent path so
     * every defensive branch of [addTabFromIntent] (tab limit, duplicate
     * detection, persistable-permission fallback, deferred load) applies
     * unchanged; a cancelled picker (null uri) simply does nothing.
     */
    private val openFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                addTabFromIntent(Intent(Intent.ACTION_EDIT).setData(uri))
            }
        }

    /** v0.14.0: colored PDF export target picker. */
    private val pdfExportLauncher =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/pdf"),
        ) { uri ->
            if (uri != null) {
                writePdfTo(uri)
            }
        }

    /** v0.16.0: code screenshot PNG export target picker. */
    private val imageExportLauncher =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("image/png"),
        ) { uri ->
            if (uri != null) {
                writeImageTo(uri)
            }
        }

    private val backCallback =
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (zenActive != null) {
                    // Zen mode owns Back first: one press leaves zen, only a
                    // second press (or dirty dialog) leaves the activity.
                    exitZenMode()
                    return
                }
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
        SystemBars.install(this, binding.root)
        tabPersistence = TabPersistence(App.keyValueStore(this))
        // v0.14.0: warm up the prettier WebView engine for the formatter.
        WasmFormatterHost.init(applicationContext)
        applyFullScreen()

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        onBackPressedDispatcher.addCallback(this, backCallback)
        // v0.20.0: visible exit path for zen mode. The FAB only exists as a
        // click target while zen is active (visibility is owned by
        // applyZenUi/restoreFromZen); exitZenMode() itself is idempotent and
        // no-ops when zen is already inactive.
        binding.zenExit.setOnClickListener { exitZenMode() }

        originalKeyListener = binding.editor.keyListener
        // v0.17.0: register user-imported TextMate grammars BEFORE any
        // highlighting; failures are skipped by the store (kept for review).
        App.customGrammars(this).loadIntoRegistry()
        highlighter = SyntaxHighlighter(binding.editor)

        binding.editor.addTextChangedListener(EditorWatcher())
        binding.editor.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            binding.gutter.scrollTo(0, scrollY)
        }
        // v0.24.0: relative/hybrid gutter labels must follow the caret even
        // when the text is unchanged (arrows, taps, selection drags).
        // Absolute mode and a hidden gutter skip the work entirely.
        binding.editor.onSelectionMoved = { _, _ ->
            if (binding.gutter.visibility == View.VISIBLE &&
                settings.resolveLineNumbering() != LineNumbering.Mode.ABSOLUTE
            ) {
                updateGutter()
            }
        }

        val restored = restoreState(savedInstanceState)
        if (!restored) {
            if (!addTabFromIntent(intent)) {
                restorePersistedTabs()
            }
        }
        syntaxOn = settings.syntaxHighlight
        highlighter.setEnabled(syntaxOn)
        highlighter.setBracketColors(settings.bracketPairColors)
        applyWordWrap(settings.wordWrap)
        applyEditorFont()
        if (settings.lineNumbers) {
            updateGutter()
        } else {
            binding.gutter.visibility = View.GONE
        }
        loading = false
        updateUiState()
        if (savedInstanceState?.getBoolean(STATE_ZEN_ACTIVE, false) == true) {
            enterZenMode()
        }
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
        outState.putBoolean(STATE_ZEN_ACTIVE, zenActive != null)
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
            var committed =
                if (budget > 0 && tab.lastCommitted.length <= SNAPSHOT_LIMIT) {
                    tab.lastCommitted
                } else {
                    ""
                }
            if (committed.length > budget) committed = ""
            budget -= committed.length
            val saved =
                if (tab.isDirty || tab.savedText != tab.lastCommitted) {
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
                    val tab =
                        EditorTab.pending(
                            uri = uriStr,
                            name = data.lastPathSegment ?: "…",
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
        val name =
            if (n == 0) {
                getString(R.string.untitled)
            } else {
                getString(R.string.untitled_n, n + 1)
            }
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
            val tab: EditorTab =
                if (st.uri == null) {
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
                    if (active) R.color.tab_chip_active else R.color.tab_chip_inactive,
                ),
            )
            chip.setOnClickListener { showTab(index) }
            chip.setOnLongClickListener {
                closeTab(index)
                true
            }
            close.setOnClickListener { closeTab(index) }
            bar.addView(chip)
        }
        val plus =
            TextView(this).apply {
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

    private fun tabStateSignature(): String =
        buildString {
            append(tabManager.activeIndex()).append('|')
            for (t in tabManager.tabs()) {
                append(t.id)
                    .append(':')
                    .append(t.isDirty)
                    .append(':')
                    .append(t.name)
                    .append('|')
            }
        }

    private fun closeTab(index: Int) {
        val tab = tabManager.tabs().getOrNull(index) ?: return
        if (tab.isDirty && tab.loaded) {
            pendingCloseIndex = index
            AlertDialog
                .Builder(this)
                .setTitle(R.string.close_tab)
                .setMessage(getString(R.string.close_tab_confirm, tab.name))
                .setPositiveButton(R.string.save) { _, _ ->
                    if (tabManager.activeIndex() != index) showTab(index)
                    pendingFinishAfterSave = false
                    save { performClose(index) }
                }.setNegativeButton(R.string.discard) { _, _ -> performClose(index) }
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
        AlertDialog
            .Builder(this)
            .setTitle(R.string.close_others)
            .setMessage(getString(R.string.close_others_confirm, tabManager.size() - 1))
            .setPositiveButton(R.string.close_others) { _, _ ->
                tabManager.closeOthers(index)
                showTab(0)
            }.setNegativeButton(R.string.cancel, null)
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
                    uri,
                    tab.caretStart,
                    tab.caretEnd,
                    tab.scrollY,
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
        override fun beforeTextChanged(
            s: CharSequence?,
            start: Int,
            count: Int,
            after: Int,
        ) = Unit

        override fun onTextChanged(
            s: CharSequence?,
            start: Int,
            before: Int,
            count: Int,
        ) = Unit

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

    private fun saveLineBreakForFile(): LineBreak =
        when (settings.lineBreakDefault) {
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

    private fun saveAllDirtyTabs() {
        val dirty = tabManager.dirtyFileTabs()
        if (dirty.isEmpty()) {
            toast(getString(R.string.nothing_to_save))
            return
        }
        // writeTo is defensive per tab (null uri guard, IO failure toast),
        // so one failing tab never blocks the others.
        for (tab in dirty) writeTo(tab)
    }

    /**
     * v0.18.0 "Open recent": picks a recently opened document into a new
     * tab. Failure paths: store read error -> treated as an empty list
     * (toast, no crash); stale entry whose provider is gone -> the normal
     * tab load path surfaces a localized open-failed error; empty pick ->
     * ignored. The list dialog needs names only; duplicates are impossible
     * because the store de-duplicates by uri on add.
     */
    private fun showOpenRecentDialog() {
        lifecycleScope.launch {
            val items =
                withContext(Dispatchers.Default) {
                    runCatching { App.recents(this@EditorActivity).list() }
                        .getOrElse { emptyList() }
                }
            if (items.isEmpty()) {
                toast(getString(R.string.no_recent_files))
                return@launch
            }
            AlertDialog
                .Builder(this@EditorActivity)
                .setTitle(R.string.open_recent)
                .setItems(items.map { it.name }.toTypedArray()) { _, which ->
                    val picked = items.getOrNull(which) ?: return@setItems
                    addTabFromIntent(Intent(Intent.ACTION_EDIT).setData(Uri.parse(picked.uri)))
                }.setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun writeTo(
        tab: EditorTab,
        afterSave: (() -> Unit)? = null,
    ) {
        val uriStr = tab.uri ?: return
        val target = saveLineBreakForFile()
        lifecycleScope.launch {
            // v0.11.0: opt-in format-on-save runs BEFORE the bytes are written.
            maybeFormatOnSave(tab)
            val ok =
                withContext(Dispatchers.IO) {
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

    private fun writeText(
        uri: Uri,
        text: String,
        lineBreak: LineBreak,
        charset: String,
    ): Boolean {
        val payload = EncodingDetector.encode(LineBreak.normalize(text, lineBreak), charset)
        return try {
            val stream =
                try {
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
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Non-persistable grant: still valid for this session.
        }
    }

    // ------------------------------------------------------------------ dialogs

    private fun showUnsavedDialog() {
        val dirtyCount = tabManager.tabs().count { it.loaded && it.isDirty }
        AlertDialog
            .Builder(this)
            .setTitle(R.string.discard_changes_title)
            .setMessage(
                if (dirtyCount > 1) {
                    getString(R.string.discard_changes_multi, dirtyCount)
                } else {
                    getString(R.string.discard_changes_msg)
                },
            ).setPositiveButton(R.string.save) { _, _ -> saveAllThenFinish() }
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

    /**
     * Marker background span for Find highlights (v0.22.0). A dedicated
     * subclass — instead of a raw BackgroundColorSpan — means cleanup removes
     * ONLY spans this dialog added, never other background colors the editor
     * may carry (selection tints, future features).
     */
    private class MatchSpan(
        color: Int,
    ) : BackgroundColorSpan(color)

    /** Last Find dialog created; exposed for deterministic E2E automation. */
    internal var activeFindDialog: AlertDialog? = null
        private set

    private fun showFindDialog(): AlertDialog? {
        val view = layoutInflater.inflate(R.layout.dialog_find, null)
        val findInput = view.findViewById<EditText>(R.id.find_input)
        val replaceInput = view.findViewById<EditText>(R.id.replace_input)
        val caseBox = view.findViewById<android.widget.CheckBox>(R.id.case_sensitive)
        val counterView = view.findViewById<TextView>(R.id.find_counter)
        val highlightColor = ContextCompat.getColor(this, R.color.find_highlight_bg)
        val dialog =
            AlertDialog
                .Builder(this)
                .setTitle(R.string.find)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .create()

        fun options() = SearchEngine.Options(caseSensitive = caseBox.isChecked)

        fun currentText(): String =
            binding.editor.text
                ?.toString()
                .orEmpty()

        fun clearHighlights() {
            val editable = binding.editor.text ?: return
            for (span in editable.getSpans(0, editable.length, MatchSpan::class.java)) {
                editable.removeSpan(span)
            }
        }

        fun applyHighlights(list: SearchEngine.MatchList) {
            clearHighlights()
            val editable = binding.editor.text ?: return
            val length = editable.length
            for (r in list.ranges) {
                // Stale-guard: the buffer can change between listing and
                // painting (replace buttons edit it); an out-of-range span
                // would throw IndexOutOfBoundsException — skip instead.
                val end = r.last + 1
                if (r.first < 0 || end > length || r.first >= end) continue
                editable.setSpan(MatchSpan(highlightColor), r.first, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        fun updateFindUi() {
            val query = findInput.text.toString()
            val list =
                if (query.isEmpty()) {
                    SearchEngine.MatchList.EMPTY
                } else {
                    SearchEngine.findAllMatches(currentText(), query, options())
                }
            applyHighlights(list)
            if (query.isEmpty()) {
                counterView.visibility = View.GONE
            } else {
                counterView.visibility = View.VISIBLE
                // Non-empty query with no hits renders an explicit "0" — a
                // blank counter would look like a rendering bug. Caret sits
                // on a match -> "k/N", otherwise the bare total ("N"/"N+").
                counterView.text =
                    if (list.ranges.isEmpty()) {
                        "0"
                    } else {
                        SearchEngine.counterLabel(list, max(0, binding.editor.selectionStart))
                    }
            }
        }

        fun selectMatch(
            idx: Int,
            query: String,
        ) {
            binding.editor.setSelection(idx, idx + query.length)
            searchStart = idx + max(1, query.length)
            val list = SearchEngine.findAllMatches(currentText(), query, options())
            applyHighlights(list)
            counterView.visibility = View.VISIBLE
            counterView.text = SearchEngine.counterLabel(list, idx)
        }

        // Live feedback while typing; a changed query restarts the search at
        // the caret instead of a stale searchStart left by the previous term.
        findInput.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) {}

                override fun afterTextChanged(s: Editable?) {
                    searchStart = max(0, binding.editor.selectionStart)
                    updateFindUi()
                }
            },
        )
        caseBox.setOnCheckedChangeListener { _, _ -> updateFindUi() }

        view.findViewById<View>(R.id.btn_prev).setOnClickListener {
            val text = currentText()
            val query = findInput.text.toString()
            val idx =
                SearchEngine
                    .indexOfPrev(
                        text,
                        query,
                        binding.editor.selectionStart,
                        options(),
                        wrapAround = true,
                    )
            if (idx < 0 || query.isEmpty()) {
                toast(getString(R.string.not_found))
            } else {
                selectMatch(idx, query)
            }
        }
        view.findViewById<View>(R.id.btn_next).setOnClickListener {
            val text = currentText()
            val query = findInput.text.toString()
            val idx = SearchEngine.indexOf(text, query, searchStart, options(), wrapAround = true)
            if (idx < 0 || query.isEmpty()) {
                toast(getString(R.string.not_found))
            } else {
                selectMatch(idx, query)
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
            val idx = SearchEngine.indexOf(currentText(), query, searchStart, options(), wrapAround = true)
            if (idx < 0) {
                toast(getString(R.string.not_found))
                // Buffer changed (replace happened) — keep highlights honest.
                updateFindUi()
            } else {
                selectMatch(idx, query)
            }
        }
        view.findViewById<View>(R.id.btn_replace_all).setOnClickListener {
            val query = findInput.text.toString()
            val replacement = replaceInput.text.toString()
            val text = currentText()
            val (newText, count) = SearchEngine.replaceAll(text, query, replacement, options())
            if (count > 0) {
                setTextPreservingHistory(newText)
            }
            toast(getString(R.string.replaced_count, count))
            updateFindUi()
        }
        dialog.setOnDismissListener {
            // The editor must return exactly as it was: no leftover spans, no
            // stale search position for the next session of the dialog.
            searchStart = 0
            clearHighlights()
        }
        activeFindDialog = dialog
        dialog.show()
        return dialog
    }

    private fun setTextPreservingHistory(newText: String) {
        // Full replace goes through the watcher so it lands on the undo stack.
        applyingUndoRedo = false
        val editable = binding.editor.text ?: return
        editable.replace(0, editable.length, newText)
        binding.editor.setSelection(newText.length)
    }

    private fun showGotoDialog() {
        val lineCount =
            TextStats.lineCount(
                binding.editor.text
                    ?.toString()
                    .orEmpty(),
            )
        val view = layoutInflater.inflate(R.layout.dialog_goto, null)
        val input = view.findViewById<EditText>(R.id.goto_input)
        input.hint = getString(R.string.goto_hint, lineCount)
        AlertDialog
            .Builder(this)
            .setTitle(R.string.goto_line)
            .setView(view)
            .setPositiveButton(R.string.go) { _, _ ->
                val line = input.text.toString().toIntOrNull()
                if (line == null || line < 1 || line > lineCount) {
                    toast(getString(R.string.invalid_line))
                } else {
                    val offset =
                        CursorNav.offsetForLine(
                            binding.editor.text
                                ?.toString()
                                .orEmpty(),
                            line,
                        )
                    binding.editor.setSelection(offset)
                }
            }.setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showStatsDialog() {
        val text =
            binding.editor.text
                ?.toString()
                .orEmpty()
        val tab = tabManager.activeTab()
        val profile = CommentProfiles.forFileName(tab?.name)
        lifecycleScope.launch {
            val stats =
                withContext(Dispatchers.Default) {
                    CodeStatistics.analyze(text, profile)
                }
            val indentWidth =
                if (stats.indentation.commonSpaceWidth < 0) {
                    getString(R.string.stats_indent_width_none)
                } else {
                    stats.indentation.commonSpaceWidth.toString()
                }
            val message =
                listOf(
                    getString(R.string.stats_chars, stats.charCount),
                    getString(R.string.stats_words, stats.wordCount),
                    getString(R.string.stats_lines, stats.lineCount),
                    getString(R.string.stats_code_lines, stats.codeLines),
                    getString(R.string.stats_blank_lines, stats.blankLines),
                    getString(R.string.stats_comment_lines, stats.commentOnlyLines),
                    getString(R.string.stats_longest_line, stats.longestLineLength),
                    getString(R.string.stats_avg_line, stats.averageLineLength),
                    getString(
                        R.string.stats_endings,
                        stats.lineEndings.crlf,
                        stats.lineEndings.lf,
                        stats.lineEndings.cr,
                    ),
                    getString(
                        R.string.stats_indent,
                        stats.indentation.tabIndentedLines,
                        stats.indentation.spaceIndentedLines,
                        indentWidth,
                    ),
                    getString(R.string.stats_todos, stats.todos.total()),
                    getString(R.string.stats_encoding, tab?.charsetName ?: EncodingDetector.DEFAULT_CHARSET),
                ).joinToString("\n")
            AlertDialog
                .Builder(this@EditorActivity)
                .setTitle(R.string.stats_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    /**
     * v0.16.0 charset lab: byte size, unmappable characters and round-trip
     * status for every common charset, computed from the CURRENT text (the
     * editor never keeps the original raw bytes — documented v1 assumption).
     */
    private fun showCharsetLabDialog() {
        val text =
            binding.editor.text
                ?.toString()
                .orEmpty()
        if (text.isEmpty()) {
            toast(getString(R.string.charsetlab_empty))
            return
        }
        lifecycleScope.launch {
            val reports =
                withContext(Dispatchers.Default) {
                    CharsetLab.encodeReports(text, EncodingDetector.COMMON_CHARSETS)
                }
            val message =
                buildString {
                    append(getString(R.string.charsetlab_header, text.length))
                    for (report in reports) {
                        append("\n")
                        if (!report.supported) {
                            append(getString(R.string.charsetlab_unsupported, report.charsetName))
                        } else {
                            val roundTrip =
                                getString(
                                    if (report.roundTripOk) R.string.charsetlab_ok else R.string.charsetlab_lossy,
                                )
                            append(
                                getString(
                                    R.string.charsetlab_line,
                                    report.charsetName,
                                    report.byteCount,
                                    report.unmappableCount,
                                    roundTrip,
                                ),
                            )
                        }
                    }
                }
            AlertDialog
                .Builder(this@EditorActivity)
                .setTitle(R.string.charsetlab_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
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
            result.selEnd.coerceIn(0, result.text.length),
        )
        updateGutter()
        dirtyChanged()
    }

    // -------------------------------------------------------------- bookmarks

    private fun currentLine(): Int {
        val text =
            binding.editor.text
                ?.toString()
                .orEmpty()
        val sel = binding.editor.selectionStart.coerceAtLeast(0)
        return CursorNav.lineForOffset(text, sel)
    }

    private fun toggleBookmark() {
        val tab = tabManager.activeTab() ?: return
        val uri = tab.uri ?: return
        val line = currentLine()
        val text =
            binding.editor.text
                ?.toString()
                .orEmpty()
        val ls = LineOps.lineStart(text, binding.editor.selectionStart.coerceAtLeast(0))
        val le = LineOps.lineEnd(text, binding.editor.selectionStart.coerceAtLeast(0))
        val label = text.substring(ls, le).trim().take(60)
        val added = App.bookmarks(this).toggle(uri, line, label)
        toast(
            if (added) {
                getString(R.string.bookmark_line) + " " + line
            } else {
                getString(R.string.bookmarks) + " " + line + " \u2717"
            },
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
        val text =
            binding.editor.text
                ?.toString()
                .orEmpty()
        val labels =
            items
                .map { b ->
                    val lineText =
                        if (text.isEmpty()) {
                            ""
                        } else {
                            val ls = LineOps.lineStart(text, CursorNav.offsetForLine(text, b.line))
                            val le = LineOps.lineEnd(text, CursorNav.offsetForLine(text, b.line))
                            text.substring(ls, le).trim().take(40)
                        }
                    "${b.line}: ${b.label.ifEmpty { lineText }}"
                }.toTypedArray()
        AlertDialog
            .Builder(this)
            .setTitle(R.string.bookmarks)
            .setItems(labels) { _, which ->
                val offset = CursorNav.offsetForLine(text, items[which].line)
                binding.editor.setSelection(offset)
                binding.editor.requestFocus()
            }.setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------ v0.13.0 tool menu

    override fun onResume() {
        super.onResume()
        // v0.23.0: re-apply font settings so changes made in Settings apply
        // on return, without reopening the file. Skipped during zen: the
        // zen snapshot owns the font size until exit restores it.
        applyEditorFont()
        // v0.24.0: the numbering mode may have changed in Settings; the
        // rebuild is idempotent and early-returns when the gutter is hidden.
        updateGutter()
        captureClipboard()
    }

    /**
     * v0.23.0: applies the stored font size + family to the editor.
     *
     * The gutter ALWAYS stays monospace: its numerals must keep aligning
     * column-per-line regardless of the text face, so a proportional font
     * for the editor must never leak into the gutter. Invalid stored family
     * values are normalized by [SettingsRepository.fontFamily] (monospace
     * fallback), so the when below has no dead path.
     */
    private fun applyEditorFont() {
        if (zenActive != null) return
        val fontSp = settings.fontSizeSp
        binding.editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp.toFloat())
        binding.editor.typeface =
            when (settings.fontFamily) {
                SettingsRepository.FONT_SANS -> Typeface.SANS_SERIF
                SettingsRepository.FONT_SERIF -> Typeface.SERIF
                else -> Typeface.MONOSPACE
            }
        binding.gutter.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp.toFloat())
        binding.gutter.typeface = Typeface.MONOSPACE
    }

    /**
     * Best-effort clipboard capture: on Android 10+ an app may read the
     * clipboard only while focused, which is exactly the case here, so no
     * extra permission is involved. Failures are non-actionable by design
     * (restricted builds throw SecurityException; a malformed clip can throw
     * while reading items) and are swallowed deliberately - capture is an
     * optimization for the history feature, never a user-visible action, so
     * an error toast on every resume would be worse than the failure itself.
     */
    private fun captureClipboard() {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            val text =
                if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0).coerceToText(this)?.toString()
                } else {
                    null
                }
            if (!text.isNullOrBlank()) {
                clipboardHistory.add(text)
            }
        } catch (e: Exception) {
            // Documented best-effort ignore: see the KDoc above.
        }
    }

    /** Hex viewer reads the saved file's raw bytes; unsaved buffers refuse. */
    private fun openHexView() {
        val uriStr = tabManager.activeTab()?.uri
        if (uriStr == null) {
            toast(getString(R.string.hex_needs_file))
            return
        }
        startActivity(HexViewerActivity.createIntent(this, Uri.parse(uriStr)))
    }

    /** Hands the live buffer to the split screen (process-local, capped). */
    private fun openSplitView() {
        val text =
            binding.editor.text
                ?.toString()
                .orEmpty()
        if (text.length > SPLIT_HANDOFF_MAX_CHARS) {
            toast(getString(R.string.split_too_large))
            return
        }
        SplitEditorActivity.pendingPaneA = text
        startActivity(Intent(this, SplitEditorActivity::class.java))
    }

    /**
     * Selects from the cursor bracket to its partner. Tries the character
     * under the caret first, then the one right before it - standard editor
     * behaviour for a caret sitting just after a closing bracket.
     */
    private fun matchBrace() {
        val text =
            binding.editor.text
                ?.toString()
                .orEmpty()
        if (text.isEmpty()) {
            toast(getString(R.string.brace_not_found))
            return
        }
        var index = binding.editor.selectionStart.coerceIn(0, text.lastIndex)
        var result = BraceMatcher.matchAt(text, index)
        if (result is BraceMatcher.MatchResult.NoBracketAtCursor && index > 0) {
            index -= 1
            result = BraceMatcher.matchAt(text, index)
        }
        when (result) {
            is BraceMatcher.MatchResult.Matched -> {
                binding.editor.setSelection(
                    min(index, result.partnerIndex),
                    max(index, result.partnerIndex) + 1,
                )
                binding.editor.requestFocus()
            }
            is BraceMatcher.MatchResult.NoBracketAtCursor ->
                toast(getString(R.string.brace_not_found))
            is BraceMatcher.MatchResult.Unmatched ->
                toast(getString(R.string.brace_unmatched))
        }
    }

    /** Lists TODO/FIXME/HACK/XXX/BUG/NOTE markers; tapping jumps to the line. */
    private fun scanTodos() {
        val text =
            binding.editor.text
                ?.toString()
                .orEmpty()
        when (val result = TodoScanner.scan(text)) {
            is TodoScanner.ScanResult.Failure ->
                toast(getString(R.string.todo_too_large))
            is TodoScanner.ScanResult.Success -> {
                if (result.items.isEmpty()) {
                    toast(getString(R.string.todo_none))
                    return
                }
                val labels =
                    result.items
                        .map { item ->
                            getString(
                                R.string.todo_item_row,
                                item.lineNumber,
                                item.marker,
                                item.message.ifEmpty { "-" },
                            )
                        }.toTypedArray()
                val builder =
                    AlertDialog
                        .Builder(this)
                        .setTitle(getString(R.string.todo_dialog_title, result.items.size))
                if (result.truncated) {
                    builder.setMessage(R.string.todo_truncated)
                }
                builder
                    .setItems(labels) { _, which ->
                        val offset =
                            CursorNav.offsetForLine(
                                text,
                                result.items[which].lineNumber,
                            )
                        binding.editor.setSelection(offset)
                        binding.editor.requestFocus()
                    }.setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    /** Clipboard history: tap inserts at the caret, neutral button clears. */
    private fun showClipboardHistoryDialog() {
        val entries = clipboardHistory.list()
        if (entries.isEmpty()) {
            toast(getString(R.string.clip_empty))
            return
        }
        val labels =
            entries
                .map { entry ->
                    val preview = entry.text.replace('\n', ' ').take(60)
                    (if (entry.pinned) "\u2605 " else "") + preview
                }.toTypedArray()
        AlertDialog
            .Builder(this)
            .setTitle(R.string.clip_dialog_title)
            .setItems(labels) { _, which ->
                insertAtCursor(entries[which].text)
            }.setNeutralButton(R.string.clip_clear) { _, _ ->
                clipboardHistory.clear()
                toast(getString(R.string.clip_cleared))
            }.setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Inserts [text] at the caret, replacing the selection if one exists.
     * Mirrors [insertSnippet]: respects read-only mode and records into the
     * undo stack through the shared editable watcher.
     */
    private fun insertAtCursor(text: String) {
        val editable = binding.editor.text ?: return
        val tab = tabManager.activeTab()
        if (tab?.readOnly == true) {
            toast(getString(R.string.read_only_toast))
            return
        }
        val length = editable.length
        val start = binding.editor.selectionStart.coerceIn(0, length)
        val end = binding.editor.selectionEnd.coerceIn(start, length)
        applyingUndoRedo = false
        editable.replace(start, end, text)
        binding.editor.setSelection(start + text.length)
        updateGutter()
        dirtyChanged()
    }

    private fun shareText() {
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(
                    Intent.EXTRA_TEXT,
                    binding.editor.text
                        ?.toString()
                        .orEmpty(),
                )
            }
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    private fun printDocument() {
        val tab = tabManager.activeTab() ?: return
        val text =
            binding.editor.text
                ?.toString()
                .orEmpty()
        TextPrinter.print(this, FileNames.sanitize(tab.name), text)
    }

    private fun showPreview() {
        val tab = tabManager.activeTab() ?: return
        val intent =
            Intent(this, PreviewActivity::class.java)
                .putExtra(
                    PreviewActivity.EXTRA_TEXT,
                    binding.editor.text
                        ?.toString()
                        .orEmpty(),
                ).putExtra(PreviewActivity.EXTRA_TITLE, tab.name)
                .putExtra(PreviewActivity.EXTRA_FILE_NAME, tab.name)
        startActivity(intent)
    }

    private fun runCode() {
        val tab = tabManager.activeTab() ?: return
        val text =
            binding.editor.text
                ?.toString()
                .orEmpty()
        val languageId = SyntaxRegistry.languageForFileName(tab.name)?.id
        val pistonLanguage = languageId?.let { PistonClient.LANGUAGE_MAP[it] }
        if (pistonLanguage == null) {
            toast(getString(R.string.run_not_supported))
            return
        }
        if (!settings.onlineExecution) {
            AlertDialog
                .Builder(this)
                .setTitle(R.string.run_consent_title)
                .setMessage(R.string.run_consent_msg)
                .setPositiveButton(R.string.run_consent_yes) { _, _ ->
                    settings.onlineExecution = true
                    executeOnline(pistonLanguage, text)
                }.setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        executeOnline(pistonLanguage, text)
    }

    private fun executeOnline(
        language: String,
        code: String,
    ) {
        toast(getString(R.string.run_done) + "…")
        lifecycleScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    try {
                        PistonClient.execute(language, code)
                    } catch (e: Exception) {
                        PistonClient.ExecutionResult("", e.message ?: "network error", "", -1)
                    }
                }
            AlertDialog
                .Builder(this@EditorActivity)
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

    /**
     * Rebuilds the gutter text. Labels come from the pure-JVM
     * [LineNumbering] decision layer; the caret's line is only resolved
     * for caret-dependent modes (relative/hybrid) — absolute mode stays
     * O(lines) with no text access at all.
     */
    private fun updateGutter() {
        if (binding.gutter.visibility != View.VISIBLE) return
        val lines = binding.editor.lineCount
        if (lines <= 0) {
            binding.gutter.text = "1"
            return
        }
        val mode = settings.resolveLineNumbering()
        val caretLine =
            if (mode == LineNumbering.Mode.ABSOLUTE) {
                1
            } else {
                val text =
                    binding.editor.text
                        ?.toString()
                        .orEmpty()
                val offset = binding.editor.selectionStart
                CursorNav.lineForOffset(text, if (offset < 0) 0 else offset)
            }
        binding.gutter.text = LineNumbering.labels(lines, caretLine, mode).joinToString("\n")
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
        menu.findItem(R.id.action_zen_mode)?.isChecked = zenActive != null
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
                val suggested =
                    FileNames.sanitize(
                        tabManager.activeTab()?.name ?: "untitled.txt",
                    )
                saveAsLauncher.launch(suggested)
            }
            R.id.action_open_file -> openFileLauncher.launch(arrayOf("*/*"))
            R.id.action_open_recent -> showOpenRecentDialog()
            R.id.action_save_all -> saveAllDirtyTabs()
            R.id.action_new_tab -> newTab()
            R.id.action_next_tab -> {
                val next = tabManager.next()
                if (next != null) showTab(tabManager.activeIndex())
            }
            R.id.action_close_tab -> closeTab(tabManager.activeIndex())
            R.id.action_close_others -> closeOthers(tabManager.activeIndex())
            R.id.action_open_folder ->
                startActivity(
                    Intent(this, FolderBrowserActivity::class.java),
                )
            R.id.action_undo -> performUndo()
            R.id.action_redo -> performRedo()
            R.id.action_format -> formatDocument()
            R.id.action_format_selection -> formatSelection()
            R.id.action_text_tools -> showTextToolsDialog()
            R.id.action_regex_test -> showRegexTesterDialog()
            R.id.action_colors -> showColorsDialog()
            R.id.action_snippets -> showSnippetsDialog()
            R.id.action_diff_with_file -> pickDiffFile()
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
            R.id.action_charset_lab -> showCharsetLabDialog()
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
            R.id.action_hex_view -> openHexView()
            R.id.action_match_brace -> matchBrace()
            R.id.action_todo_scan -> scanTodos()
            R.id.action_clipboard_history -> showClipboardHistoryDialog()
            R.id.action_split_view -> openSplitView()
            R.id.action_fold_all -> foldAll()
            R.id.action_unfold_all -> unfoldAll()
            R.id.action_fold_toggle -> foldToggleAtCursor()
            R.id.action_export_pdf -> exportPdf()
            R.id.action_export_image -> exportImage()
            R.id.action_zen_mode -> toggleZenMode(item)
            R.id.action_about -> AboutDialog.show(this)
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
                val tab: EditorTab =
                    if (loaded && (uri == null || committed.isNotEmpty())) {
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
        binding.toolbar.subtitle =
            if (tab.readOnly) {
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
            // Keep the file's own line break style; indentation comes from
            // the user's settings (v0.23.0) — .editorconfig merges on top.
            val options =
                FormatOptions(
                    indentStyle = settings.resolveIndentStyle(),
                    indentSize = settings.indentSize,
                    lineBreak = LineBreak.detect(text, fallback = LineBreak.LF),
                )
            val result =
                withContext(Dispatchers.Default) {
                    formatterRegistry().format(FormatRequest(text, languageId, options))
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
                if (line != null) {
                    getString(R.string.format_error_parse_line, line)
                } else {
                    getString(R.string.format_error_parse_generic)
                }
            FormatErrorCode.TIMEOUT -> getString(R.string.format_error_timeout)
            // The core message carries only the reason (documented contract).
            FormatErrorCode.ENGINE_UNAVAILABLE ->
                getString(
                    R.string.wasm_engine_unavailable,
                    error.message.removePrefix("prettier engine unavailable: "),
                )
            FormatErrorCode.INTERNAL_ERROR -> getString(R.string.format_error_generic)
        }
    }

    /**
     * Editor registry: the native engines plus the prettier WASM/WebView
     * engine for the tier-2 languages (JavaScript/TypeScript/HTML/Markdown/
     * GraphQL). The WASM formatter degrades to its native fallback by
     * itself when the WebView engine cannot run (documented contract).
     */
    private fun formatterRegistry(): FormatterRegistry =
        FormatterRegistry.withWasm { payload, budgetMs ->
            WasmFormatterHost.launch(payload, budgetMs)
        }

    // ------------------------------------------------- v0.14.0 code folding

    private fun foldEngineFor(languageId: String): CodeFolding {
        val existing = foldEngine
        if (existing != null && foldEngineLanguage == languageId) return existing
        val fresh = CodeFolding(languageId)
        foldEngine = fresh
        foldEngineLanguage = languageId
        return fresh
    }

    private fun foldAll() {
        applyFoldOperation { engine, text -> engine.foldAll(text) }
    }

    private fun unfoldAll() {
        applyFoldOperation { engine, text -> engine.unfoldAll(text) }
    }

    /** Folds the region at the cursor line, or unfolds when it sits on a placeholder. */
    private fun foldToggleAtCursor() {
        val editable = binding.editor.text ?: return
        val layout = binding.editor.layout
        if (layout == null) {
            // Not laid out yet (transient) — nothing safe to act on.
            toast(getString(R.string.fold_none))
            return
        }
        val offset = binding.editor.selectionStart.coerceIn(0, editable.length)
        val line = layout.getLineForOffset(offset)
        val lineText = editable.toString().split('\n').getOrNull(line) ?: ""
        if (CodeFolding.isPlaceholderLine(lineText)) {
            applyFoldOperation { engine, text -> engine.unfoldAtLine(text, line) }
        } else {
            applyFoldOperation { engine, text -> engine.foldAtLine(text, line) }
        }
    }

    /**
     * Shared folding pipeline: read-only/blank guards, engine selection by
     * active language, compute off the main thread, apply through the same
     * replace path as Format (so undo, dirty flag, gutter and highlighter
     * stay consistent), and a localized toast for every outcome.
     */
    private fun applyFoldOperation(op: (CodeFolding, String) -> FoldResult) {
        val tab = tabManager.activeTab() ?: return
        val editable = binding.editor.text ?: return
        if (tab.readOnly) {
            toast(getString(R.string.read_only_toast))
            return
        }
        val text = editable.toString()
        if (text.isBlank()) {
            toast(getString(R.string.fold_none))
            return
        }
        val engine = foldEngineFor(SyntaxRegistry.languageForFileName(tab.name)?.id ?: "txt")
        lifecycleScope.launch {
            val result =
                withContext(Dispatchers.Default) { op(engine, text) }
            when (result) {
                is FoldResult.Done -> {
                    if (result.text == text) {
                        // Nothing folded/unfolded (e.g. unfold on plain text).
                        toast(getString(R.string.fold_none))
                    } else {
                        applyingUndoRedo = false
                        editable.replace(0, text.length, result.text)
                        binding.editor.setSelection(0)
                        updateGutter()
                        dirtyChanged()
                        toast(getString(R.string.fold_done, result.hiddenLines))
                    }
                }
                is FoldResult.Failure -> toast(foldErrorMessage(result.code))
            }
        }
    }

    private fun foldErrorMessage(code: FoldErrorCode): String =
        when (code) {
            FoldErrorCode.INPUT_TOO_LARGE -> getString(R.string.fold_too_large)
            FoldErrorCode.NO_FOLD_RANGE -> getString(R.string.fold_none)
            FoldErrorCode.REGION_CONTAINS_PLACEHOLDER -> getString(R.string.fold_nested)
            FoldErrorCode.NOT_A_PLACEHOLDER -> getString(R.string.fold_not_placeholder)
            FoldErrorCode.TOO_MANY_FOLDS -> getString(R.string.fold_too_many)
            FoldErrorCode.PLACEHOLDER_AMBIGUOUS -> getString(R.string.fold_ambiguous)
            FoldErrorCode.REGION_GONE -> getString(R.string.fold_region_gone)
        }

    // ------------------------------------------------------ v0.14.0 PDF export

    /** Asks for a target file, then renders the document as a colored PDF. */
    private fun exportPdf() {
        val tab = tabManager.activeTab() ?: return
        val text = binding.editor.text?.toString() ?: return
        if (text.isBlank()) {
            toast(getString(R.string.pdf_empty))
            return
        }
        val base = FileNames.sanitize(tab.name.ifBlank { "document" })
        val suggested = base.substringBeforeLast('.', base) + ".pdf"
        pdfExportLauncher.launch(suggested)
    }

    /** Renders and writes the PDF for a chosen target (SAF callback). */
    private fun writePdfTo(target: Uri) {
        val tab = tabManager.activeTab() ?: return
        val editable = binding.editor.text ?: return
        val text = editable.toString()
        if (text.isBlank()) {
            toast(getString(R.string.pdf_empty))
            return
        }
        toast(getString(R.string.pdf_running))
        val jobName = tab.name.ifBlank { getString(R.string.untitled) }
        val language = SyntaxRegistry.languageForFileName(tab.name)
        lifecycleScope.launch {
            val planResult =
                withContext(Dispatchers.Default) {
                    val pre = PdfExportPlanner.preprocess(text)
                    val tokens =
                        if (language != null) {
                            SyntaxTokenizer.tokenize(pre, language)
                        } else {
                            emptyList()
                        }
                    PdfExportPlanner.plan(pre, tokens, PdfExportOptions())
                }
            when (planResult) {
                is PdfPlanResult.Failure -> {
                    val message =
                        when (planResult.code) {
                            PdfExportErrorCode.TOO_LARGE ->
                                getString(
                                    R.string.pdf_too_large,
                                    planResult.actualLines ?: 0,
                                    PdfExportPlanner.MAX_EXPORT_LINES,
                                )
                            // Cannot happen when planner and tokenizer see the
                            // same text; still reported instead of crashing.
                            PdfExportErrorCode.TOKEN_MISMATCH ->
                                getString(R.string.pdf_failed, planResult.message)
                        }
                    toast(message)
                }
                is PdfPlanResult.Success -> {
                    val outcome =
                        withContext(Dispatchers.IO) {
                            PdfExporter.export(this@EditorActivity, planResult.plan, jobName, target)
                        }
                    when (outcome) {
                        is PdfExportResult.Success -> toast(getString(R.string.pdf_done, outcome.pages))
                        is PdfExportResult.Failure -> toast(getString(R.string.pdf_failed, outcome.message))
                    }
                }
            }
        }
    }

    // ------------------------------------------------- v0.16.0 image export

    /** Opens the SAF picker for a PNG code screenshot. */
    private fun exportImage() {
        val tab = tabManager.activeTab() ?: return
        val text =
            binding.editor.text
                ?.toString()
                .orEmpty()
        if (text.isBlank()) {
            toast(getString(R.string.image_empty))
            return
        }
        val base = FileNames.sanitize(tab.name.ifBlank { "document" })
        val suggested = base.substringBeforeLast('.', base) + ".png"
        imageExportLauncher.launch(suggested)
    }

    /**
     * Plans, renders and writes the PNG for a chosen target (SAF callback).
     * Palette follows the current UI night mode; the planner failure codes
     * map to localized, actionable toasts exactly like the PDF path.
     */
    private fun writeImageTo(target: Uri) {
        val tab = tabManager.activeTab() ?: return
        val editable = binding.editor.text ?: return
        val text = editable.toString()
        if (text.isBlank()) {
            toast(getString(R.string.image_empty))
            return
        }
        toast(getString(R.string.image_running))
        val jobName = tab.name.ifBlank { getString(R.string.untitled) }
        val language = SyntaxRegistry.languageForFileName(tab.name)
        val darkTheme =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        lifecycleScope.launch {
            val planResult =
                withContext(Dispatchers.Default) {
                    val pre = ImageExportPlanner.preprocess(text)
                    val tokens =
                        if (language != null) {
                            SyntaxTokenizer.tokenize(pre, language)
                        } else {
                            emptyList()
                        }
                    ImageExportPlanner.plan(
                        pre,
                        tokens,
                        ImageExportOptions(darkTheme = darkTheme, title = jobName),
                    )
                }
            when (planResult) {
                is ImagePlanResult.Failure -> {
                    val message =
                        when (planResult.code) {
                            ImageExportErrorCode.TOO_LARGE ->
                                getString(R.string.image_too_large, planResult.actualLines)
                            // Already refused by exportImage; kept for the
                            // SAF-callback path where text could have changed.
                            ImageExportErrorCode.EMPTY -> getString(R.string.image_empty)
                            // Cannot happen when planner and tokenizer see the
                            // same text; still reported instead of crashing.
                            ImageExportErrorCode.TOKEN_MISMATCH ->
                                getString(R.string.image_failed, planResult.message)
                        }
                    toast(message)
                }
                is ImagePlanResult.Success -> {
                    val bytes =
                        withContext(Dispatchers.Default) {
                            val density = resources.displayMetrics.density
                            val bitmap = ImageExporter.render(planResult.plan, density)
                            if (bitmap != null) ImageExporter.toPngBytes(bitmap) else null
                        }
                    when {
                        bytes == null -> toast(getString(R.string.image_too_large_pixels))
                        bytes.isEmpty() ->
                            toast(getString(R.string.image_failed, "PNG encoding"))
                        else -> {
                            val written =
                                withContext(Dispatchers.IO) {
                                    writeBytesTo(target, bytes)
                                }
                            if (written) {
                                toast(getString(R.string.image_done))
                            } else {
                                toast(getString(R.string.image_failed, "writing the file"))
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Writes PNG bytes through SAF. Returns false on ANY failure (missing
     * stream, IO error) so the caller can show one informative message; the
     * exception is swallowed deliberately because the user-facing outcome is
     * the same regardless of the low-level cause and Rock Edit is log-free.
     */
    private fun writeBytesTo(
        target: Uri,
        bytes: ByteArray,
    ): Boolean =
        try {
            contentResolver
                .openOutputStream(target)
                ?.use { stream ->
                    stream.write(bytes)
                    stream.flush()
                } != null
        } catch (t: Throwable) {
            // SAF write failure (provider gone, disk full, revoked grant):
            // reported to the user as a generic write failure, never a crash.
            false
        }

    // ------------------------------------------------------ v0.14.0 zen mode

    private fun toggleZenMode(item: MenuItem) {
        if (zenActive == null) {
            enterZenMode()
        } else {
            exitZenMode()
        }
        item.isChecked = zenActive != null
    }

    /** Enters zen (idempotent); the pre-zen UI state is captured for exit. */
    private fun enterZenMode() {
        val snapshot =
            ZenMode.sanitizeSnapshot(
                binding.toolbar.visibility == View.VISIBLE,
                binding.tabScroll.visibility == View.VISIBLE,
                settings.fontSizeSp.toFloat(),
            )
        when (val result = ZenMode.enter(snapshot, zenActive)) {
            is ZenResult.Entered -> {
                zenActive = result.state
                applyZenUi(result.state)
            }
            // Rotation re-entry: the state already exists, re-assert the UI.
            ZenResult.AlreadyActive -> zenActive?.let { applyZenUi(it) }
            else -> Unit // NotActive cannot occur on enter; nothing to do.
        }
    }

    private fun applyZenUi(state: ZenActive) {
        binding.toolbar.visibility = View.GONE
        binding.tabScroll.visibility = View.GONE
        binding.zenExit.visibility = View.VISIBLE // v0.20.0: visible way out
        // Editor and gutter must scale together to keep line numbers aligned.
        binding.editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, state.zenFontSizeSp)
        binding.gutter.setTextSize(TypedValue.COMPLEX_UNIT_SP, state.zenFontSizeSp)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun exitZenMode() {
        val active = zenActive ?: return
        when (ZenMode.exit(active)) {
            ZenResult.Exited -> {
                zenActive = null
                restoreFromZen(active.snapshot)
            }
            else -> zenActive = null // NotActive: nothing was active; clear defensively.
        }
    }

    private fun restoreFromZen(snapshot: ZenSnapshot) {
        binding.toolbar.visibility = if (snapshot.toolbarVisible) View.VISIBLE else View.GONE
        binding.tabScroll.visibility = if (snapshot.tabsVisible) View.VISIBLE else View.GONE
        binding.zenExit.visibility = View.GONE // v0.20.0: hide the exit FAB
        binding.editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, snapshot.fontSizeSp)
        binding.gutter.setTextSize(TypedValue.COMPLEX_UNIT_SP, snapshot.fontSizeSp)
        if (settings.fullScreen) {
            applyFullScreen()
        } else {
            // v0.18.0: stay on the edge-to-edge baseline installed by
            // SystemBars; showing the bars is enough for the insets to be
            // re-dispatched and the root padding restored.
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, binding.root)
                .show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ------------------------------------------------------ v0.11.0 tools

    /**
     * Formats only the selected block (lenient mode: fragments often contain
     * unbalanced braces). Re-indent rule (documented in rockedit.md §4.11):
     * when the formatter stripped a non-empty first-line indent, every line
     * of the output is prefixed with that original indent (uniform shift).
     */
    private fun formatSelection() {
        val tab = tabManager.activeTab() ?: return
        val editable = binding.editor.text ?: return
        if (tab.readOnly) {
            toast(getString(R.string.read_only_toast))
            return
        }
        val text = editable.toString()
        val selStart = binding.editor.selectionStart
        val selEnd = binding.editor.selectionEnd
        if (selStart < 0 || selEnd <= selStart) {
            toast(getString(R.string.format_selection_empty))
            return
        }
        val start = selStart.coerceIn(0, text.length)
        val end = selEnd.coerceIn(0, text.length)
        if (start >= end) {
            toast(getString(R.string.format_selection_empty))
            return
        }
        val fragment = text.substring(start, end)
        val languageId = SyntaxRegistry.languageForFileName(tab.name)?.id ?: "txt"
        val originalIndent = fragment.takeWhile { it == ' ' || it == '\t' }
        toast(getString(R.string.format_running))
        lifecycleScope.launch {
            val baseOptions =
                FormatOptions(
                    indentStyle = settings.resolveIndentStyle(),
                    indentSize = settings.indentSize,
                    lineBreak = LineBreak.detect(fragment, fallback = LineBreak.LF),
                    lenient = true,
                    // A selection never gains a final newline of its own.
                    insertFinalNewline = false,
                )
            val options =
                editorConfigOptionsFor(tab, baseOptions)
                    .copy(lenient = true, insertFinalNewline = false)
            val result =
                withContext(Dispatchers.Default) {
                    formatterRegistry().format(FormatRequest(fragment, languageId, options))
                }
            when (result) {
                is FormatResult.Success -> {
                    if (!result.changed) {
                        toast(getString(R.string.format_unchanged))
                        return@launch
                    }
                    var formatted = result.formattedText
                    if (originalIndent.isNotEmpty() && !formatted.startsWith(originalIndent)) {
                        val breakValue = LineBreak.detect(formatted, LineBreak.LF).value
                        formatted =
                            formatted
                                .split(Regex("\r\n|\n|\r"))
                                .joinToString(breakValue) { originalIndent + it }
                    }
                    applyingUndoRedo = false
                    editable.replace(start, end, formatted)
                    binding.editor.setSelection(
                        (start + formatted.length).coerceAtMost(text.length),
                        (start + formatted.length).coerceAtMost(text.length),
                    )
                    updateGutter()
                    dirtyChanged()
                    toast(getString(R.string.format_done, result.durationMs))
                }
                is FormatResult.Skipped -> toast(getString(R.string.format_nothing))
                is FormatResult.Failure -> toast(formatErrorMessage(result.error))
            }
        }
    }

    /**
     * Text tools dialog: 18 pure transformations on selection or document.
     * Built as a plain ScrollView of rows (not AlertDialog.setItems): every
     * row is always laid out, which keeps the dialog testable on tiny
     * screens where a virtualized ListView materializes nothing.
     */
    private fun showTextToolsDialog() {
        val opLabels = resources.getStringArray(R.array.text_tools_ops)
        val ops = TextUtilities.Op.values()
        // Defensive: label table and enum must agree; fall back to enum names.
        val labels: Array<String> = if (opLabels.size == ops.size) opLabels else ops.map { it.name }.toTypedArray()
        val selStart = binding.editor.selectionStart
        val selEnd = binding.editor.selectionEnd
        val hasSelection = selStart >= 0 && selEnd > selStart
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_text_tools, null)
        val container = view.findViewById<LinearLayout>(R.id.text_tools_container)
        val scope = view.findViewById<TextView>(R.id.text_tools_scope)
        scope.text =
            getString(
                if (hasSelection) {
                    R.string.text_tools_scope_selection
                } else {
                    R.string.text_tools_scope_document
                },
            )
        val ripple = android.util.TypedValue()
        val hasRipple =
            theme.resolveAttribute(
                android.R.attr.selectableItemBackground,
                ripple,
                true,
            )
        val rowParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        var dialog: AlertDialog? = null
        for ((index, op) in ops.withIndex()) {
            val row = TextView(this)
            row.text = labels[index]
            row.textSize = 16f
            row.isClickable = true
            row.isFocusable = true
            row.setPadding(dp(20), dp(14), dp(20), dp(14))
            if (hasRipple) row.setBackgroundResource(ripple.resourceId)
            row.setOnClickListener {
                dialog?.dismiss()
                applyTextTool(op)
            }
            container.addView(row, rowParams)
        }
        dialog =
            AlertDialog
                .Builder(this)
                .setTitle(R.string.text_tools)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun applyTextTool(op: TextUtilities.Op) {
        val tab = tabManager.activeTab() ?: return
        val editable = binding.editor.text ?: return
        if (tab.readOnly) {
            toast(getString(R.string.read_only_toast))
            return
        }
        val text = editable.toString()
        val selStart = binding.editor.selectionStart
        val selEnd = binding.editor.selectionEnd
        val hasSelection = selStart >= 0 && selEnd > selStart && selEnd <= text.length
        val input = if (hasSelection) text.substring(selStart, selEnd) else text
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) { TextUtilities.run(op, input) }
            when (result) {
                is TextUtilities.TextResult.Success -> {
                    if (!result.changed) {
                        toast(getString(R.string.text_tools_unchanged))
                        return@launch
                    }
                    applyingUndoRedo = false
                    if (hasSelection) {
                        editable.replace(selStart, selEnd, result.text)
                        val newEnd = (selStart + result.text.length).coerceAtMost(text.length)
                        binding.editor.setSelection(selStart.coerceAtMost(newEnd), newEnd)
                    } else {
                        editable.replace(0, text.length, result.text)
                        binding.editor.setSelection(0)
                    }
                    updateGutter()
                    dirtyChanged()
                    toast(getString(R.string.text_tools_done))
                }
                is TextUtilities.TextResult.Skipped -> toast(getString(R.string.format_nothing))
                is TextUtilities.TextResult.Failure ->
                    toast(getString(R.string.text_tools_error, result.error.message))
            }
        }
    }

    /** Interactive regex tester: live matches + capture groups, read-only. */
    private fun showRegexTesterDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_regex_test, null)
        val patternInput = view.findViewById<EditText>(R.id.regex_pattern)
        val textInput = view.findViewById<EditText>(R.id.regex_test_text)
        val ignoreCase = view.findViewById<CheckBox>(R.id.regex_ignore_case)
        val multiline = view.findViewById<CheckBox>(R.id.regex_multiline)
        val dotAll = view.findViewById<CheckBox>(R.id.regex_dotall)
        val results = view.findViewById<TextView>(R.id.regex_results)
        AlertDialog
            .Builder(this)
            .setTitle(R.string.regex_tester)
            .setView(view)
            .setPositiveButton(R.string.close, null)
            .show()
        var pending: Job? = null
        val schedule = {
            pending?.cancel()
            pending =
                lifecycleScope.launch {
                    delay(250) // debounce: one run per pause in typing
                    runRegexTest(
                        patternInput.text?.toString().orEmpty(),
                        textInput.text?.toString().orEmpty(),
                        RegexTester.Flags(ignoreCase.isChecked, multiline.isChecked, dotAll.isChecked),
                        results,
                    )
                }
        }
        val watcher =
            object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = schedule()

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) = Unit
            }
        patternInput.addTextChangedListener(watcher)
        textInput.addTextChangedListener(watcher)
        ignoreCase.setOnClickListener { schedule() }
        multiline.setOnClickListener { schedule() }
        dotAll.setOnClickListener { schedule() }
    }

    private fun runRegexTest(
        pattern: String,
        text: String,
        flags: RegexTester.Flags,
        resultsView: TextView,
    ) {
        lifecycleScope.launch {
            val outcome =
                try {
                    withTimeout(3_000) {
                        withContext(Dispatchers.Default) { RegexTester.run(pattern, text, flags) }
                    }
                } catch (e: TimeoutCancellationException) {
                    // The engine is not interruptible, but the dialog stops
                    // waiting: documented trade-off for pathological patterns.
                    resultsView.text = getString(R.string.regex_timeout)
                    return@launch
                }
            when (outcome) {
                is RegexTester.RegexOutcome.Failure ->
                    resultsView.text = getString(R.string.regex_error, outcome.error.message)
                is RegexTester.RegexOutcome.Found -> {
                    val found = outcome.result
                    if (found.matches.isEmpty()) {
                        resultsView.text = getString(R.string.regex_results_none)
                    } else {
                        val shown = found.matches.take(20)
                        val suffix =
                            if (found.matchesTruncated || found.matches.size > shown.size) {
                                getString(R.string.regex_truncated_more, shown.size)
                            } else {
                                ""
                            }
                        val header = getString(R.string.regex_results_header, found.matches.size, suffix)
                        val body =
                            shown
                                .mapIndexed { i, match ->
                                    val groups =
                                        match.groups
                                            .drop(1)
                                            .joinToString(" ") { g -> "[${g.text ?: "—"}]" }
                                    "#${i + 1} [${match.start}, ${match.end}) \"${text.substring(
                                        match.start.coerceIn(0, text.length),
                                        match.end.coerceIn(0, text.length),
                                    )}\" $groups"
                                }.joinToString("\n")
                        resultsView.text = header + "\n" + body
                    }
                }
            }
        }
    }

    /** Lists every color literal in the document; tapping jumps to it. */
    private fun showColorsDialog() {
        val text =
            binding.editor.text
                ?.toString()
                .orEmpty()
        lifecycleScope.launch {
            val summary = withContext(Dispatchers.Default) { ColorExtractor.extract(text) }
            if (summary.colors.isEmpty()) {
                toast(getString(R.string.color_none))
                return@launch
            }
            val shown = summary.colors.take(100)
            val title =
                getString(
                    R.string.color_count,
                    summary.colors.size,
                    if (summary.colors.size > shown.size) {
                        getString(R.string.color_truncated_more, shown.size)
                    } else {
                        ""
                    },
                )
            val adapter =
                object : android.widget.ArrayAdapter<ColorExtractor.ColorOccurrence>(
                    this@EditorActivity,
                    R.layout.item_color,
                    shown,
                ) {
                    override fun getView(
                        position: Int,
                        convertView: View?,
                        parent: ViewGroup,
                    ): View {
                        val row =
                            convertView ?: LayoutInflater
                                .from(context)
                                .inflate(R.layout.item_color, parent, false)
                        val swatch = row.findViewById<View>(R.id.grep_swatch)
                        val label = row.findViewById<TextView>(R.id.grep_color_text)
                        val occurrence = shown[position]
                        swatch.setBackgroundColor(occurrence.argb.toInt())
                        label.text = "${occurrence.source}  ·  ${getString(
                            R.string.color_line,
                            CursorNav.lineForOffset(text, occurrence.start),
                        )}"
                        return row
                    }
                }
            AlertDialog
                .Builder(this@EditorActivity)
                .setTitle(title)
                .setAdapter(adapter) { _, which ->
                    val target = shown[which].start.coerceIn(0, text.length)
                    binding.editor.setSelection(target)
                    binding.editor.requestFocus()
                }.setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /**
     * Merges a sibling .editorconfig (same SAF folder) into [base]. Any
     * problem — non-SAF URI, revoked grant, broken config — silently keeps
     * [base]: config support must never block formatting or saving.
     */
    private fun editorConfigOptionsFor(
        tab: EditorTab,
        base: FormatOptions,
    ): FormatOptions {
        val uriStr = tab.uri ?: return base
        return try {
            val content = readSiblingEditorConfig(Uri.parse(uriStr)) ?: return base
            val config = EditorConfigParser.parse(content)
            val resolved = EditorConfigParser.resolve(config, tab.name)
            EditorConfigParser.toFormatOptions(resolved, base)
        } catch (e: Exception) {
            base
        }
    }

    private fun readSiblingEditorConfig(fileUri: Uri): String? {
        return try {
            val docId = DocumentsContract.getDocumentId(fileUri)
            val parentId = docId.substringBeforeLast('/')
            if (parentId.isEmpty() || parentId == docId) return null
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(fileUri, parentId)
            var configUri: Uri? = null
            contentResolver
                .query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    ),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) == ".editorconfig") {
                            configUri =
                                DocumentsContract.buildDocumentUriUsingTree(
                                    fileUri,
                                    cursor.getString(0),
                                )
                            break
                        }
                    }
                }
            val found = configUri ?: return null
            contentResolver.openInputStream(found)?.bufferedReader()?.readText()
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------ v0.12.0 diff & snippets

    /** Opens the SAF picker for the "other" side of a comparison. */
    private fun pickDiffFile() {
        try {
            diffFileLauncher.launch(
                arrayOf(
                    "text/*",
                    "application/json",
                    "application/xml",
                    "application/javascript",
                    "application/x-yaml",
                ),
            )
        } catch (e: Exception) {
            // No file picker on the device (or the resolver failed).
            toast(getString(R.string.diff_error_picker))
        }
    }

    /**
     * Prepares the comparison: both sides go through private cache files
     * because intent extras are size-limited. Any failure here is a localized
     * toast — a broken comparison never mutates the document.
     */
    private fun startDiffWithFile(otherUri: Uri) {
        val current =
            binding.editor.text
                ?.toString()
                .orEmpty()
        if (current.length > MAX_DIFF_CHARS) {
            toast(getString(R.string.diff_too_large))
            return
        }
        toast(getString(R.string.diff_running))
        lifecycleScope.launch {
            val picked = withContext(Dispatchers.IO) { readDiffSource(otherUri) }
            when {
                picked == null -> toast(getString(R.string.diff_error_read))
                picked.binary -> toast(getString(R.string.diff_error_binary))
                picked.content == null || picked.content.length > MAX_DIFF_CHARS ->
                    toast(getString(R.string.diff_too_large))
                else -> {
                    val docFile = File(cacheDir, "diff_doc_${System.nanoTime()}.txt")
                    val otherFile = File(cacheDir, "diff_other_${System.nanoTime()}.txt")
                    try {
                        withContext(Dispatchers.IO) {
                            docFile.writeText(current, Charsets.UTF_8)
                            otherFile.writeText(picked.content, Charsets.UTF_8)
                        }
                        startActivity(
                            DiffActivity.createIntent(
                                this@EditorActivity,
                                docFile.absolutePath,
                                otherFile.absolutePath,
                            ),
                        )
                    } catch (e: Exception) {
                        toast(getString(R.string.diff_error_generic, e.message ?: "?"))
                        try {
                            docFile.delete()
                            otherFile.delete()
                        } catch (_: Exception) {
                            // Cache cleanup is best-effort; the dir is private.
                        }
                    }
                }
            }
        }
    }

    /** Outcome of reading the picked side: unreadable / binary / content. */
    private class DiffRead(
        val content: String?,
        val binary: Boolean,
    )

    private fun readDiffSource(uri: Uri): DiffRead? =
        try {
            val stream = contentResolver.openInputStream(uri) ?: return null
            stream.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                var read = input.read(chunk)
                var sniffed = 0
                var binary = false
                while (read >= 0) {
                    if (sniffed < FolderGrep.BINARY_SNIFF_BYTES) {
                        val limit = minOf(read, FolderGrep.BINARY_SNIFF_BYTES - sniffed)
                        for (i in 0 until limit) {
                            if (chunk[i] == 0.toByte()) {
                                binary = true
                                break
                            }
                        }
                        sniffed += limit
                    }
                    if (binary) return DiffRead(null, true)
                    if (buffer.size() >= MAX_DIFF_BYTES) break
                    val toWrite = minOf(read, MAX_DIFF_BYTES - buffer.size())
                    buffer.write(chunk, 0, toWrite)
                    if (toWrite < read) break
                    read = input.read(chunk)
                }
                if (binary) {
                    DiffRead(null, true)
                } else {
                    DiffRead(
                        EncodingDetector.decode(buffer.toByteArray(), EncodingDetector.DEFAULT_CHARSET),
                        false,
                    )
                }
            }
        } catch (e: Exception) {
            null
        }

    /** Lists insertable snippets (current language + wildcard) as rows. */
    private fun showSnippetsDialog() {
        val store = SnippetStore(App.keyValueStore(this))
        val tab = tabManager.activeTab()
        val languageId = tab?.let { SyntaxRegistry.languageForFileName(it.name)?.id } ?: "txt"
        val available = store.list(languageId)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_snippet_list, null)
        val container = view.findViewById<LinearLayout>(R.id.snippet_container)
        val ripple = android.util.TypedValue()
        val hasRipple =
            theme.resolveAttribute(
                android.R.attr.selectableItemBackground,
                ripple,
                true,
            )
        val rowParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        var dialog: AlertDialog? = null

        fun addRow(
            label: String,
            onClick: () -> Unit,
        ) {
            val row = TextView(this)
            row.text = label
            row.textSize = 16f
            row.isClickable = true
            row.isFocusable = true
            row.setPadding(dp(20), dp(14), dp(20), dp(14))
            if (hasRipple) row.setBackgroundResource(ripple.resourceId)
            row.setOnClickListener {
                dialog?.dismiss()
                onClick()
            }
            container.addView(row, rowParams)
        }

        for (snippet in available) {
            addRow("${snippet.name}  (${snippet.language})") { insertSnippet(store, snippet) }
        }
        if (available.isEmpty()) {
            addRow(getString(R.string.snippet_none, languageId)) {
                showNewSnippetDialog(store, languageId)
            }
        }
        addRow(getString(R.string.snippet_new)) { showNewSnippetDialog(store, languageId) }
        addRow(getString(R.string.snippet_manage)) { showDeleteSnippetDialog(store) }

        dialog =
            AlertDialog
                .Builder(this)
                .setTitle(R.string.snippets)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .show()
    }

    /**
     * Inserts the expanded snippet at the caret, replacing the selection if
     * one exists. Respects read-only mode and the undo stack; the caret lands
     * on the body's `$0` stop (or the end of the inserted text).
     */
    private fun insertSnippet(
        store: SnippetStore,
        snippet: SnippetStore.Snippet,
    ) {
        val editable = binding.editor.text ?: return
        val tab = tabManager.activeTab() ?: return
        if (tab.readOnly) {
            toast(getString(R.string.read_only_toast))
            return
        }
        when (val result = SnippetStore.Insert.expand(snippet.body)) {
            is SnippetStore.Insert.InsertResult.Failure ->
                toast(getString(R.string.snippet_error, result.message))
            is SnippetStore.Insert.InsertResult.Success -> {
                val length = editable.length
                val start = binding.editor.selectionStart.coerceIn(0, length)
                val end = binding.editor.selectionEnd.coerceIn(start, length)
                applyingUndoRedo = false
                editable.replace(start, end, result.text)
                val caret =
                    (start + SnippetStore.Insert.finalCaret(result))
                        .coerceIn(0, editable.length)
                binding.editor.setSelection(caret)
                updateGutter()
                dirtyChanged()
                store.touch(snippet.id)
                toast(getString(R.string.snippet_inserted))
            }
        }
    }

    /** Create form; stays open on validation failure so the user can retry. */
    private fun showNewSnippetDialog(
        store: SnippetStore,
        languageId: String,
    ) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_snippet_form, null)
        val nameInput = view.findViewById<EditText>(R.id.snippet_name)
        val languageInput = view.findViewById<EditText>(R.id.snippet_language)
        val bodyInput = view.findViewById<EditText>(R.id.snippet_body)
        languageInput.setText(languageId)
        val dialog =
            AlertDialog
                .Builder(this)
                .setTitle(R.string.snippet_new)
                .setView(view)
                .setPositiveButton(R.string.snippet_save, null)
                .setNegativeButton(R.string.cancel, null)
                .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = nameInput.text?.toString().orEmpty()
            val language = languageInput.text?.toString().orEmpty()
            val body = bodyInput.text?.toString().orEmpty()
            when (val result = store.create(name, language, body)) {
                is SnippetStore.MutateResult.Failure ->
                    toast(getString(R.string.snippet_error, result.message))
                is SnippetStore.MutateResult.Success -> {
                    toast(getString(R.string.snippet_saved))
                    dialog.dismiss()
                }
            }
        }
    }

    /** Delete list; every tap removes exactly one snippet (defensive no-op if already gone). */
    private fun showDeleteSnippetDialog(store: SnippetStore) {
        val all = store.list()
        if (all.isEmpty()) {
            toast(getString(R.string.snippet_none, SnippetStore.LANG_ALL))
            return
        }
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_snippet_list, null)
        val container = view.findViewById<LinearLayout>(R.id.snippet_container)
        val hint = view.findViewById<TextView>(R.id.snippet_hint)
        hint.setText(R.string.snippet_delete_hint)
        val ripple = android.util.TypedValue()
        val hasRipple =
            theme.resolveAttribute(
                android.R.attr.selectableItemBackground,
                ripple,
                true,
            )
        val rowParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        var dialog: AlertDialog? = null
        for (snippet in all) {
            val row = TextView(this)
            row.text = "${snippet.name}  (${snippet.language})"
            row.textSize = 16f
            row.isClickable = true
            row.isFocusable = true
            row.setPadding(dp(20), dp(14), dp(20), dp(14))
            if (hasRipple) row.setBackgroundResource(ripple.resourceId)
            row.setOnClickListener {
                dialog?.dismiss()
                if (store.delete(snippet.id)) {
                    toast(getString(R.string.snippet_deleted))
                } else {
                    toast(getString(R.string.snippet_delete_missing))
                }
            }
            container.addView(row, rowParams)
        }
        dialog =
            AlertDialog
                .Builder(this)
                .setTitle(R.string.snippet_manage)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .show()
    }

    /**
     * v0.11.0 format-on-save: opt-in, .editorconfig-aware, strictly
     * fail-safe — any formatter or config problem leaves the document
     * unchanged and never blocks the save itself.
     */
    private suspend fun maybeFormatOnSave(tab: EditorTab) {
        if (!App.settings(this).formatOnSave) return
        if (tab.readOnly) return
        val text = tab.lastCommitted
        if (text.isBlank()) return
        try {
            val languageId = SyntaxRegistry.languageForFileName(tab.name)?.id ?: "txt"
            val baseOptions =
                FormatOptions(
                    indentStyle = settings.resolveIndentStyle(),
                    indentSize = settings.indentSize,
                    lineBreak = LineBreak.detect(text, fallback = LineBreak.LF),
                )
            val options = editorConfigOptionsFor(tab, baseOptions)
            val result =
                withContext(Dispatchers.Default) {
                    formatterRegistry().format(FormatRequest(text, languageId, options))
                }
            if (result is FormatResult.Success && result.changed) {
                val editable = binding.editor.text
                if (editable != null) {
                    applyingUndoRedo = false
                    editable.replace(0, text.length, result.formattedText)
                    binding.editor.setSelection(0)
                    updateGutter()
                    dirtyChanged()
                } else {
                    // Editor view already torn down: keep the payload in sync.
                    tab.lastCommitted = result.formattedText
                }
            }
        } catch (e: Exception) {
            toast(getString(R.string.format_on_save_failed))
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
            AlertDialog
                .Builder(this)
                .setTitle(R.string.reopen_encoding)
                .setMessage(R.string.reopen_discard_msg)
                .setPositiveButton(R.string.continue_label) { _, _ ->
                    pickCharsetAndReopen(tab)
                }.setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            pickCharsetAndReopen(tab)
        }
    }

    private fun pickCharsetAndReopen(tab: EditorTab) {
        val names = EncodingDetector.COMMON_CHARSETS.toTypedArray()
        val current = names.indexOf(tab.charsetName)
        AlertDialog
            .Builder(this)
            .setTitle(R.string.reopen_encoding)
            .setSingleChoiceItems(names, current) { dialog, which ->
                dialog.dismiss()
                reopenWith(tab, names[which])
            }.setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun reopenWith(
        tab: EditorTab,
        charset: String,
    ) {
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
        AlertDialog
            .Builder(this)
            .setTitle(R.string.save_encoding)
            .setSingleChoiceItems(names, current) { dialog, which ->
                dialog.dismiss()
                tab.charsetName = names[which]
                updateUiState()
                save()
            }.setNegativeButton(R.string.cancel, null)
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
        private const val MAX_DIFF_CHARS = 2_000_000

        /** Split pane A handoff cap; SplitEditorActivity enforces the same. */
        private const val SPLIT_HANDOFF_MAX_CHARS = 1_000_000
        private const val STATE_ZEN_ACTIVE = "state.zen_active"
        private const val MAX_DIFF_BYTES = 4_000_000

        /** Convenience starter used by MainActivity and tests. */
        fun createIntent(
            context: android.content.Context,
            uri: Uri?,
        ): Intent =
            Intent(context, EditorActivity::class.java).apply {
                if (uri != null) {
                    action = Intent.ACTION_EDIT
                    data = uri
                }
            }

        fun openText(
            context: android.content.Context,
            text: String,
        ): Intent =
            Intent(context, EditorActivity::class.java).apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, text)
            }
    }
}
