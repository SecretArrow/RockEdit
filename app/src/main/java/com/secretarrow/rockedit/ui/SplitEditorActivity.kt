package com.secretarrow.rockedit.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
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
import com.secretarrow.rockedit.core.SplitPaneState
import com.secretarrow.rockedit.core.SplitSessionCodec
import com.secretarrow.rockedit.databinding.ActivitySplitEditorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * Split view (v0.13.0, full-app panes v0.17.0): two fully active document
 * panes side by side (or stacked) for comparing and editing documents. Each
 * pane opens its own document through SAF and saves it with a chosen
 * charset; the whole split session survives process death and rotation.
 * Mirrors [DiffActivity] patterns: view binding, theme handling, up
 * navigation.
 *
 * Defensive contract (new failure paths of the v0.17.0 upgrade):
 *
 * | Failure path                            | Handling                                       |
 * |------------------------------------------|-----------------------------------------------|
 * | SAF read failure (IOException,          | split_read_failed toast; pane untouched        |
 * | SecurityException, dead document)       |                                                |
 * | File over [MAX_FILE_BYTES] bytes or     | split_too_large toast; pane untouched          |
 * | text over [MAX_PANE_CHARS] characters   |                                                |
 * | No file picker (resolver failure)       | split_read_failed toast; pane untouched        |
 * | Picker cancelled (null uri)             | split_no_uri toast; pane untouched             |
 * | Opening into a dirty pane               | replace confirmation first (no silent loss)    |
 * | Save charset cannot encode the text     | strict encoder refuses BEFORE writing;         |
 * | (unmappable / unsupported name)         | split_encode_failed toast; charset dialog      |
 * |                                         | stays open or is re-opened; nothing written    |
 * | Write/IO failure on save                | split_save_failed toast; binding stays for a   |
 * |                                         | retry                                          |
 * | Corrupt persisted session               | decode yields the all-empty session; panes     |
 * |                                         | start empty, never crash                       |
 * | Session over the combined cap           | encode null -> skip persisting;                |
 * |                                         | split_session_too_large toast (onStop only)    |
 * | Restored pane text over the pane cap    | decode sanitize truncates with a documented    |
 * |                                         | marker (last-resort fail-safe)                 |
 * | Snapshot put/remove fails (storage)     | caught with comment; the session is simply     |
 * |                                         | not kept, never breaks the lifecycle path      |
 *
 * Session model (snapshot on stop):
 * - Restore precedence (documented): savedInstanceState (rotation or
 *   recreation inside the same process) > process-local handoff
 *   ([Companion.pendingPaneA], set by EditorActivity for an explicitly
 *   fresh split) > persisted session ([SESSION_KEY] in KeyValueStore,
 *   process-death recovery) > empty panes. The handoff can therefore never
 *   overwrite an already restored session.
 * - [onStop] persists the CURRENT live text of both panes plus names, uris,
 *   charsets, and dirty flags via [SplitSessionCodec]. onStop (not onPause)
 *   is used because onPause fires for every transient overlay - including
 *   the SAF picker of the save flow itself - which would persist mid-flow
 *   snapshots on every pick, while onStop still always precedes a process
 *   kill of a backgrounded activity.
 * - [onSaveInstanceState] carries the same snapshot so rotation restores
 *   without touching disk; oversize sessions are skipped silently there
 *   (the onStop path already warned the user, and toasting inside an
 *   ongoing state transaction would be disruptive).
 * - A dirty pane restored from the snapshot keeps its persisted dirty flag:
 *   its last saved baseline is unknowable after process death, so there is
 *   nothing meaningful to compare the live text against (the persisted text
 *   IS the live snapshot - snapshot-on-stop model).
 * - Intentional exits ([onDestroy] with isFinishing) clear the persisted
 *   snapshot so a discarded session can never reappear; rotation and
 *   process death keep it.
 *
 * Charset model:
 * - Each pane carries its own charset (default UTF-8) used for saving.
 * - Opening a file resets the pane charset to UTF-8 before decoding (fresh
 *   document semantics), so the decode path stays byte-identical to
 *   v0.13.0; the chosen save charset persists in the pane state until the
 *   next open or save.
 *
 * Documented assumptions:
 * - Panes are plain multi-line EditTexts: undo is the native IME/EditText
 *   undo; there is no custom undo stack in v1.
 * - Restored URIs are metadata only: no automatic reload (the snapshot
 *   already carries the text), and saving still goes through SAF because a
 *   persisted grant cannot be relied on after process death.
 * - The orientation toggle is not part of the session (v0.13.0 behavior
 *   kept).
 */
class SplitEditorActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySplitEditorBinding
    private var paneAName: String = ""
    private var paneBName: String = ""
    private var paneAUri: String? = null
    private var paneBUri: String? = null
    private var charsetA: String = EncodingDetector.DEFAULT_CHARSET
    private var charsetB: String = EncodingDetector.DEFAULT_CHARSET
    private var dirtyA = false
    private var dirtyB = false
    private var loading = true

    private val paneAOpenLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            loadIntoPane(uri, isPaneA = true)
        }

    private val paneBOpenLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            loadIntoPane(uri, isPaneA = false)
        }

    private val saveALauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            savePaneA(uri)
        }

    private val saveBLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            savePaneB(uri)
        }

    private val backCallback =
        object : OnBackPressedCallback(true) {
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
            },
        )
        binding.paneB.addTextChangedListener(
            PaneWatcher { dirty ->
                dirtyB = dirty
                updateStatusB()
            },
        )

        restoreOrStart(savedInstanceState)

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
            R.id.action_open_a -> {
                confirmThenOpen(isPaneA = true)
                return true
            }
            R.id.action_open_b -> {
                confirmThenOpen(isPaneA = false)
                return true
            }
            R.id.action_save_a -> {
                showSaveCharsetDialog(isPaneA = true)
                return true
            }
            R.id.action_save_b -> {
                showSaveCharsetDialog(isPaneA = false)
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
     * Opens the SAF document picker for one pane. A dirty pane asks for
     * confirmation first because an open REPLACES the pane content - no
     * silent loss.
     */
    private fun confirmThenOpen(isPaneA: Boolean) {
        val dirty = if (isPaneA) dirtyA else dirtyB
        if (!dirty) {
            launchOpenPicker(isPaneA)
            return
        }
        AlertDialog
            .Builder(this)
            .setTitle(R.string.split_discard_title)
            .setMessage(R.string.split_replace_confirm)
            .setPositiveButton(R.string.split_replace) { _, _ -> launchOpenPicker(isPaneA) }
            .setNegativeButton(R.string.cancel) { _, _ ->
                // Stay on the screen and keep the pane content.
            }.show()
    }

    private fun launchOpenPicker(isPaneA: Boolean) {
        try {
            val launcher = if (isPaneA) paneAOpenLauncher else paneBOpenLauncher
            launcher.launch(OPEN_MIME_TYPES)
        } catch (_: Exception) {
            // No file picker on the device (or the resolver failed).
            toast(getString(R.string.split_read_failed))
        }
    }

    /**
     * Loads the picked document into the pane. The uri is validated first
     * (null picker result -> split_no_uri); oversize and unreadable input
     * are refused with a toast, never crash. Opening resets the pane charset
     * to UTF-8 (fresh document semantics, see the class KDoc), so decoding
     * with the pane's charset keeps the v0.13.0
     * [EncodingDetector.DEFAULT_CHARSET] decode.
     */
    private fun loadIntoPane(
        uri: Uri?,
        isPaneA: Boolean,
    ) {
        if (uri == null) {
            toast(getString(R.string.split_no_uri))
            return
        }
        takePersistentPermission(uri)
        val charsetName = EncodingDetector.DEFAULT_CHARSET
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { readPaneFile(uri, charsetName) }
            when {
                outcome == null -> toast(getString(R.string.split_read_failed))
                outcome.tooLarge -> toast(getString(R.string.split_too_large))
                outcome.text == null -> toast(getString(R.string.split_read_failed))
                else -> applyLoaded(uri, outcome.text, charsetName, isPaneA)
            }
        }
    }

    /** Applies a successfully read document to one pane (commit point). */
    private fun applyLoaded(
        uri: Uri,
        text: String,
        charsetName: String,
        isPaneA: Boolean,
    ) {
        loading = true
        if (isPaneA) {
            binding.paneA.setText(text)
        } else {
            binding.paneB.setText(text)
        }
        loading = false
        val name = resolveName(uri)
        if (isPaneA) {
            dirtyA = false
            paneAUri = uri.toString()
            charsetA = charsetName
            paneAName = name
            updateStatusA()
            toast(getString(R.string.split_loaded_a))
        } else {
            dirtyB = false
            paneBUri = uri.toString()
            charsetB = charsetName
            paneBName = name
            updateStatusB()
            toast(getString(R.string.split_switched))
        }
    }

    /**
     * Writes pane A to the picked location with the pane's charset. See
     * [savePaneInto] for the failure mapping.
     */
    private fun savePaneA(uri: Uri?) {
        savePaneInto(uri, isPaneA = true)
    }

    /**
     * Writes pane B to the picked location with the pane's charset. See
     * [savePaneInto] for the failure mapping.
     */
    private fun savePaneB(uri: Uri?) {
        savePaneInto(uri, isPaneA = false)
    }

    /**
     * Writes one pane to the picked location. Null picker result maps to
     * split_no_uri; an unmappable charset refuses the write (nothing is
     * written - no silent loss) and re-opens the charset dialog; a failed
     * write maps to split_save_failed (or split_save_failed_a) and keeps
     * the screen (binding) for a retry.
     */
    private fun savePaneInto(
        uri: Uri?,
        isPaneA: Boolean,
    ) {
        if (uri == null) {
            toast(getString(R.string.split_no_uri))
            return
        }
        takePersistentPermission(uri)
        val text = paneText(if (isPaneA) binding.paneA else binding.paneB)
        val charsetName = if (isPaneA) charsetA else charsetB
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { writePane(uri, text, charsetName) }
            when {
                outcome.unmappable -> {
                    toast(getString(R.string.split_encode_failed, charsetName))
                    showSaveCharsetDialog(isPaneA)
                }
                outcome.written -> {
                    val name = DisplayNames.resolve(this@SplitEditorActivity, uri)
                    if (isPaneA) {
                        dirtyA = false
                        paneAUri = uri.toString()
                        paneAName =
                            if (name.isBlank()) getString(R.string.split_untitled_pane) else name
                        updateStatusA()
                        if (name.isBlank()) {
                            toast(getString(R.string.split_saved_a))
                        } else {
                            toast(getString(R.string.split_saved_to, name))
                        }
                    } else {
                        dirtyB = false
                        paneBUri = uri.toString()
                        paneBName =
                            if (name.isBlank()) getString(R.string.split_untitled_pane) else name
                        updateStatusB()
                        if (name.isBlank()) {
                            toast(getString(R.string.split_saved))
                        } else {
                            toast(getString(R.string.split_saved_to, name))
                        }
                    }
                }
                else -> {
                    val failed =
                        if (isPaneA) R.string.split_save_failed_a else R.string.split_save_failed
                    toast(getString(failed))
                }
            }
        }
    }

    /**
     * Single-choice charset dialog for the pane's save
     * ([EncodingDetector.COMMON_CHARSETS], default UTF-8). Selecting a
     * charset that cannot encode the current pane text (unmappable
     * characters, or an unsupported name) keeps the dialog open with an
     * informative toast - the save is simply not started; a valid selection
     * proceeds to the SAF create picker and is remembered in the pane state.
     */
    private fun showSaveCharsetDialog(isPaneA: Boolean) {
        val names = EncodingDetector.COMMON_CHARSETS.toTypedArray()
        val current = names.indexOf(if (isPaneA) charsetA else charsetB)
        val text = paneText(if (isPaneA) binding.paneA else binding.paneB)
        AlertDialog
            .Builder(this)
            .setTitle(R.string.split_charset_title)
            .setSingleChoiceItems(names, current) { dialog, which ->
                val chosen = names[which]
                if (!canEncode(text, chosen)) {
                    // Unmappable characters for this charset: keep the save
                    // dialog open so another charset can be picked; nothing
                    // is written (no silent loss).
                    toast(getString(R.string.split_encode_failed, chosen))
                    return@setSingleChoiceItems
                }
                dialog.dismiss()
                if (isPaneA) {
                    charsetA = chosen
                    saveALauncher.launch(FileNames.sanitize(paneAName))
                } else {
                    charsetB = chosen
                    saveBLauncher.launch(FileNames.sanitize(paneBName))
                }
            }.setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** True when [charsetName] is a supported charset that can encode [text]. */
    private fun canEncode(
        text: String,
        charsetName: String,
    ): Boolean =
        try {
            charset(charsetName).newEncoder().canEncode(text)
        } catch (_: Exception) {
            // Illegal or unsupported charset name: treat as unencodable so
            // the save flow never proceeds with a broken charset.
            false
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
            binding.splitPaneA.layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                )
            binding.splitPaneB.layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                )
            binding.splitDivider.layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(1),
                )
        } else {
            binding.splitPaneA.layoutParams =
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1f,
                )
            binding.splitPaneB.layoutParams =
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1f,
                )
            binding.splitDivider.layoutParams =
                LinearLayout.LayoutParams(
                    dp(1),
                    LinearLayout.LayoutParams.MATCH_PARENT,
                )
        }
    }

    /**
     * Exchanges texts, names, uris, charsets, and dirty flags between the
     * panes so dirty content can never appear clean after the swap.
     */
    private fun swapPanes() {
        val textA = paneText(binding.paneA)
        val textB = paneText(binding.paneB)
        loading = true
        binding.paneA.setText(textB)
        binding.paneB.setText(textA)
        loading = false
        val name = paneAName
        paneAName = paneBName
        paneBName = name
        val uri = paneAUri
        paneAUri = paneBUri
        paneBUri = uri
        val cs = charsetA
        charsetA = charsetB
        charsetB = cs
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
    private fun statusText(
        name: String,
        dirty: Boolean,
    ): String = if (dirty) "$DIRTY_DOT $name" else name

    private fun showDiscardDialog() {
        AlertDialog
            .Builder(this)
            .setTitle(R.string.split_discard_title)
            .setMessage(R.string.split_discard_body)
            .setPositiveButton(R.string.discard) { _, _ -> finish() }
            .setNegativeButton(R.string.cancel) { _, _ ->
                // Stay on the screen and keep both panes editable.
            }.show()
    }

    // -------------------------------------------------------------- session

    /**
     * Rebuilds the panes from the highest-priority source. Precedence (see
     * the class KDoc): savedInstanceState > process-local handoff
     * ([Companion.pendingPaneA]) > persisted session > empty panes. The
     * bundle path is silent (rotation is not a recovery event); only the
     * persisted-session path announces itself with split_session_restored.
     */
    private fun restoreOrStart(savedInstanceState: Bundle?) {
        val fromBundle = savedInstanceState?.getString(STATE_SESSION)
        val handoff = pendingPaneA
        pendingPaneA = null
        when {
            fromBundle != null -> {
                // Rotation/recreation inside the same process: the bundle
                // mirrors the newest snapshot and must never be overwritten
                // by a stale handoff or an older persisted session.
                restoreSession(SplitSessionCodec.decode(fromBundle), announce = false)
            }
            handoff != null -> {
                // Explicitly requested fresh split seeded with the editor
                // buffer (v0.13.0 behavior, pane B stays empty).
                loading = true
                binding.paneA.setText(handoff)
                loading = false
            }
            else -> {
                val stored = App.keyValueStore(this).getString(SESSION_KEY, null)
                if (stored != null) {
                    restoreSession(SplitSessionCodec.decode(stored), announce = true)
                }
                // No stored key (first run, or the previous exit was
                // intentional): panes stay empty and untitled.
            }
        }
    }

    private fun restoreSession(
        session: Pair<SplitPaneState, SplitPaneState>,
        announce: Boolean,
    ) {
        val (a, b) = session
        loading = true
        binding.paneA.setText(a.savedText)
        binding.paneB.setText(b.savedText)
        loading = false
        paneAName = a.name
        paneBName = b.name
        paneAUri = a.uri
        paneBUri = b.uri
        charsetA = a.charsetName
        charsetB = b.charsetName
        // Snapshot-on-stop model: the persisted text is the pane's live text
        // at stop time, so the persisted dirty flag is the truth (a dirty
        // pane's saved baseline is unknowable after process death).
        dirtyA = a.wasDirty
        dirtyB = b.wasDirty
        if (announce && sessionHasContent(a, b)) {
            toast(getString(R.string.split_session_restored))
        }
    }

    private fun sessionHasContent(
        a: SplitPaneState,
        b: SplitPaneState,
    ): Boolean =
        a.savedText.isNotEmpty() || b.savedText.isNotEmpty() ||
            a.uri != null || b.uri != null

    private fun paneStateA(): SplitPaneState =
        SplitPaneState(
            uri = paneAUri,
            name = paneAName,
            charsetName = charsetA,
            savedText = paneText(binding.paneA),
            wasDirty = dirtyA,
        )

    private fun paneStateB(): SplitPaneState =
        SplitPaneState(
            uri = paneBUri,
            name = paneBName,
            charsetName = charsetB,
            savedText = paneText(binding.paneB),
            wasDirty = dirtyB,
        )

    override fun onStop() {
        super.onStop()
        persistSession()
    }

    /**
     * Snapshot on stop (the class KDoc documents why onStop and not
     * onPause). An oversize session (encode null) is skipped with an
     * informative toast; a storage failure is caught with a comment because
     * losing the recovery snapshot must never break the stop path.
     */
    private fun persistSession() {
        val json =
            SplitSessionCodec
                .encode(paneStateA(), paneStateB()) ?: run {
                toast(getString(R.string.split_session_too_large))
                return
            }
        try {
            App.keyValueStore(this).putString(SESSION_KEY, json)
        } catch (_: Exception) {
            // Storage failure (read-only disk, exotic provider): non-fatal,
            // the snapshot is simply not restored after a process death.
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Same snapshot as onStop; oversize sessions are skipped silently
        // here (the onStop path already warned the user, and toasting inside
        // an ongoing state transaction would be disruptive).
        SplitSessionCodec
            .encode(paneStateA(), paneStateB())
            ?.let { outState.putString(STATE_SESSION, it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Intentional exits (back, up, discard) clear the recovery snapshot
        // so a discarded session can never reappear; rotation and process
        // death keep it (isFinishing false, or no destroy callback at all).
        if (isFinishing) {
            App.keyValueStore(this).remove(SESSION_KEY)
        }
    }

    // ---------------------------------------------------------------- io

    /** Live text of a pane, never null (empty when the editable is null). */
    private fun paneText(pane: EditText): String = pane.text?.toString().orEmpty()

    /** Friendly file name; DisplayNames falls back to the last URI segment. */
    private fun resolveName(uri: Uri): String =
        DisplayNames.resolve(this, uri).ifBlank { getString(R.string.split_untitled_pane) }

    /** Outcome of reading a pane's file: unreadable (null) / too large / text. */
    private class PaneRead(
        val text: String?,
        val tooLarge: Boolean,
    )

    /**
     * Reads the document with a [MAX_FILE_BYTES] byte ceiling; a file bigger
     * than the ceiling, or one decoding to more than [MAX_PANE_CHARS]
     * characters, maps to `tooLarge`. Any I/O or provider failure returns
     * null (mapped to split_read_failed by the caller).
     */
    private fun readPaneFile(
        uri: Uri,
        charsetName: String,
    ): PaneRead? =
        try {
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
                    val decoded = EncodingDetector.decode(buffer.toByteArray(), charsetName)
                    if (decoded.length > MAX_PANE_CHARS) {
                        PaneRead(null, true)
                    } else {
                        PaneRead(decoded, false)
                    }
                }
            }
        } catch (_: Exception) {
            // Provider or stream failure (dead document, unmounted storage):
            // mapped to split_read_failed by the caller, never a crash.
            null
        }

    /** Outcome of writing a pane: written / refused (unmappable) / IO failure. */
    private class PaneWrite(
        val written: Boolean,
        val unmappable: Boolean,
    )

    /**
     * Encodes [text] strictly for [charsetName] (REPORT for malformed and
     * unmappable input) and writes the bytes to [uri]. Nothing is written
     * when the encoding fails: [PaneWrite.unmappable] lets the caller inform
     * the user instead of silently substituting bytes. I/O failures also
     * return written=false; the caller distinguishes them via the flag.
     */
    private fun writePane(
        uri: Uri,
        text: String,
        charsetName: String,
    ): PaneWrite {
        val bytes = encodeStrict(text, charsetName) ?: return PaneWrite(false, true)
        return try {
            val stream =
                try {
                    contentResolver.openOutputStream(uri, "wt")
                } catch (_: IllegalArgumentException) {
                    // Invalid mode or URI: retry with the plain overload.
                    null
                } ?: contentResolver.openOutputStream(uri) ?: return PaneWrite(false, false)
            stream.use { it.write(bytes) }
            PaneWrite(true, false)
        } catch (_: Exception) {
            // Stream or write failure: mapped to split_save_failed.
            PaneWrite(false, false)
        }
    }

    /** Strict encode; null when unmappable/malformed or the name is bogus. */
    private fun encodeStrict(
        text: String,
        charsetName: String,
    ): ByteArray? =
        try {
            val encoder = charset(charsetName).newEncoder()
            encoder.onMalformedInput(CodingErrorAction.REPORT)
            encoder.onUnmappableCharacter(CodingErrorAction.REPORT)
            val out = encoder.encode(CharBuffer.wrap(text))
            val bytes = ByteArray(out.remaining())
            out.get(bytes)
            bytes
        } catch (_: Exception) {
            // CharacterCodingException (unmappable or malformed input under
            // REPORT actions) or an illegal/unsupported charset name.
            null
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** Marks a pane dirty on any edit; ignored while text is set programmatically. */
    private inner class PaneWatcher(
        private val onDirty: (Boolean) -> Unit,
    ) : TextWatcher {
        override fun beforeTextChanged(
            s: CharSequence?,
            start: Int,
            count: Int,
            after: Int,
        ) {
            // Not needed: dirty state is derived from afterTextChanged only.
        }

        override fun onTextChanged(
            s: CharSequence?,
            start: Int,
            before: Int,
            count: Int,
        ) {
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

        /** KeyValueStore key of the split session snapshot (process death). */
        private const val SESSION_KEY = "split_session_v1"

        /** Bundle key of the snapshot (restore precedence 1: rotation). */
        private const val STATE_SESSION = "split_session_bundle"

        /** MIME filters for the pane document pickers (v0.13.0 list kept). */
        private val OPEN_MIME_TYPES =
            arrayOf(
                "text/*",
                "application/json",
                "application/xml",
                "application/javascript",
                "application/x-yaml",
            )

        /**
         * Process-local handoff for the editor's unsaved buffer:
         * EditorActivity assigns it right before startActivity and
         * [onCreate] reads it once and clears it. Null (or a killed process)
         * means the handoff is absent and the restore precedence falls
         * through to the persisted session (see the class KDoc). No
         * cross-process or persisted transfer happens through this field.
         */
        @Volatile
        var pendingPaneA: String? = null
    }
}
