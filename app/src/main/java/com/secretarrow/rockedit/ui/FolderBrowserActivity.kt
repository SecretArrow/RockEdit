package com.secretarrow.rockedit.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.secretarrow.rockedit.R
import com.secretarrow.rockedit.core.App
import com.secretarrow.rockedit.core.FileNames
import com.secretarrow.rockedit.core.FileOps
import com.secretarrow.rockedit.core.FolderSort
import com.secretarrow.rockedit.databinding.ActivityFolderBrowserBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SAF folder browser (roadmap: open files from a folder tree without leaving
 * the app). The picked tree URI is persisted; navigation uses [DocumentFile].
 * Tapping a file hands it to the editor, which opens it in a new tab.
 *
 * v0.21.0: full file management — toolbar "New file"/"New folder", and
 * long-press an entry for Open/Rename/Delete. All name rules live in
 * [FileOps] (pure JVM, fully unit-tested); this activity only performs the
 * SAF calls on [Dispatchers.IO] and maps each outcome to localized feedback.
 *
 * Scenario -> handling table (defensive rule 7, full matrix in docs §4.20):
 * - null/blank/illegal/reserved/oversized name -> validation toast, no SAF call;
 * - rename to the same name -> dialog closes silently (no-op, no error);
 * - name already taken in this folder -> "already exists" toast;
 * - entry vanished between listing and op (findFile null) -> generic failure;
 * - provider throws (revoked grant, stale tree, FAT case collision) -> caught
 *   by every op, mapped to the generic failure toast — never a crash, never a
 *   silent failure;
 * - dialog shown while activity is finishing -> guarded, returns null.
 */
class FolderBrowserActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFolderBrowserBinding
    private lateinit var adapter: FolderEntryAdapter
    private var rootTree: DocumentFile? = null
    private var path: List<String> = emptyList()

    /** Outcome of a management op, produced on IO and mapped to UI on main. */
    private enum class Op {
        CREATED,
        RENAMED,
        DELETED,
        COLLISION,
        FAILED,
    }

    private val pickTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) {
                if (rootTree == null) finish()
                return@registerForActivityResult
            }
            takePersistentPermission(uri)
            App.settings(this).lastFolderUri = uri.toString()
            openTree(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val settings = App.settings(this)
        if (settings.isBlackTheme()) setTheme(R.style.Theme_RockEdit_Black)
        settings.applyTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityFolderBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.install(this, binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter =
            FolderEntryAdapter(
                onClick = { entry -> onEntryClicked(entry) },
                onLongClick = { entry -> showOpsDialog(entry) },
            )
        binding.entries.layoutManager = LinearLayoutManager(this)
        binding.entries.adapter = adapter

        binding.btnPickFolder.setOnClickListener { pickTree.launch(null) }

        val saved = settings.lastFolderUri
        val savedUri = if (saved.isNullOrBlank()) null else Uri.parse(saved)
        if (savedUri == null) {
            pickTree.launch(null)
        } else {
            openTree(savedUri)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_PATH, ArrayList(path))
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val saved = savedInstanceState.getStringArrayList(STATE_PATH).orEmpty()
        if (saved.isNotEmpty() && rootTree != null) {
            path = saved
            refresh()
        }
    }

    private fun takePersistentPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Provider did not offer persistable grants; browsing still works now.
        }
    }

    private fun openTree(uri: Uri) {
        try {
            rootTree = DocumentFile.fromTreeUri(this, uri)
        } catch (_: Exception) {
            rootTree = null
        }
        if (rootTree == null) {
            // Persisted grant may have been revoked: ask for a fresh pick.
            App.settings(this).lastFolderUri = ""
            pickTree.launch(null)
            return
        }
        path = emptyList()
        refresh()
    }

    private fun onEntryClicked(entry: FolderSort.Entry) {
        val current = currentDir() ?: return
        if (entry.isFolder) {
            path = FolderSort.descend(path, entry.name)
            refresh()
        } else {
            val child = current.findFile(entry.name)
            val uri = child?.uri
            if (uri != null) {
                startActivity(EditorActivity.createIntent(this, uri))
            }
        }
    }

    private fun currentDir(): DocumentFile? {
        var dir = rootTree ?: return null
        for (segment in path) {
            val next = dir.findFile(segment) ?: return null
            dir = next
        }
        return dir
    }

    private fun goUp() {
        if (path.isEmpty()) return
        path = FolderSort.parentPath(path)
        refresh()
    }

    // ---- v0.21.0: file management -----------------------------------------

    /**
     * Long-press action sheet for an entry: Open (files only), Rename,
     * Delete. Built from resource ids (not strings) so the action mapping
     * cannot break if a translation changes; guarded against finishing
     * activities like every other dialog in this file.
     */
    internal fun showOpsDialog(entry: FolderSort.Entry): AlertDialog? {
        if (isFinishing || isDestroyed) return null
        val actions =
            buildList {
                if (!entry.isFolder) add(R.string.ops_open)
                add(R.string.ops_rename)
                add(R.string.ops_delete)
            }
        return try {
            AlertDialog
                .Builder(this)
                .setTitle(entry.name)
                .setItems(Array(actions.size) { getString(actions[it]) }) { _, which ->
                    when (actions[which]) {
                        R.string.ops_open -> onEntryClicked(entry)
                        R.string.ops_rename -> showRenameDialog(entry)
                        R.string.ops_delete -> showDeleteDialog(entry)
                    }
                }.setNegativeButton(R.string.cancel, null)
                .show()
        } catch (e: Exception) {
            // show() throws BadTokenException when the activity dies between
            // the guard above and the call; fall back to a toast so the
            // failure is never silent.
            toast(getString(R.string.err_op_failed))
            null
        }
    }

    /**
     * Create-file / create-folder dialog. Returns the dialog for tests;
     * null only when the activity is finishing or the dialog failed to show.
     */
    internal fun showCreateDialog(isFolder: Boolean): AlertDialog? {
        if (isFinishing || isDestroyed) return null
        val input = layoutInflater.inflate(R.layout.dialog_name_input, null) as EditText
        input.setHint(if (isFolder) R.string.name_hint_folder else R.string.name_hint_file)
        return try {
            AlertDialog
                .Builder(this)
                .setTitle(getString(if (isFolder) R.string.new_folder else R.string.new_file))
                .setView(input)
                .setPositiveButton(R.string.create) { _, _ ->
                    performCreate(input.text.toString(), isFolder)
                }.setNegativeButton(R.string.cancel, null)
                .show()
        } catch (e: Exception) {
            // Dialog show() failed (activity died mid-call); surface feedback.
            toast(getString(R.string.err_op_failed))
            null
        }
    }

    /** Rename dialog, prefilled with the current name and cursor at the end. */
    internal fun showRenameDialog(entry: FolderSort.Entry): AlertDialog? {
        if (isFinishing || isDestroyed) return null
        val input = layoutInflater.inflate(R.layout.dialog_name_input, null) as EditText
        input.setText(entry.name)
        input.setSelection(entry.name.length)
        return try {
            AlertDialog
                .Builder(this)
                .setTitle(R.string.ops_rename)
                .setView(input)
                .setPositiveButton(R.string.ops_rename) { _, _ ->
                    performRename(entry, input.text.toString())
                }.setNegativeButton(R.string.cancel, null)
                .show()
        } catch (e: Exception) {
            toast(getString(R.string.err_op_failed))
            null
        }
    }

    /**
     * Delete confirmation. SAF deletion is permanent (no trash can), so the
     * message says so explicitly before the destructive call happens.
     */
    internal fun showDeleteDialog(entry: FolderSort.Entry): AlertDialog? {
        if (isFinishing || isDestroyed) return null
        return try {
            AlertDialog
                .Builder(this)
                .setTitle(R.string.delete_confirm_title)
                .setMessage(getString(R.string.delete_confirm_msg, entry.name))
                .setPositiveButton(R.string.ops_delete) { _, _ -> performDelete(entry) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } catch (e: Exception) {
            toast(getString(R.string.err_op_failed))
            null
        }
    }

    private fun performCreate(
        rawName: String,
        isFolder: Boolean,
    ) {
        if (rootTree == null) {
            // Tree was closed/revoked between listing and the tap.
            toast(getString(R.string.err_op_failed))
            return
        }
        val name =
            when (val result = FileOps.validateName(rawName)) {
                is FileOps.NameResult.Invalid -> {
                    toast(nameErrorText(result.error))
                    return
                }
                is FileOps.NameResult.Valid -> result.name
            }
        lifecycleScope.launch {
            val op =
                withContext(Dispatchers.IO) {
                    val dir = currentDir() ?: return@withContext Op.FAILED
                    try {
                        if (FileOps.checkCollision(name, siblingNames(dir))) {
                            return@withContext Op.COLLISION
                        }
                        val created =
                            if (isFolder) {
                                dir.createDirectory(name)
                            } else {
                                dir.createFile(FileOps.deriveMime(name), name)
                            }
                        if (created != null) Op.CREATED else Op.FAILED
                    } catch (e: Exception) {
                        // SecurityException (revoked grant), stale document,
                        // provider-specific rejections: report, never crash.
                        Op.FAILED
                    }
                }
            when (op) {
                Op.CREATED -> {
                    toast(getString(if (isFolder) R.string.msg_folder_created else R.string.msg_file_created))
                    refresh()
                }
                Op.COLLISION -> toast(getString(R.string.err_name_exists))
                else -> toast(getString(R.string.err_op_failed))
            }
        }
    }

    private fun performRename(
        entry: FolderSort.Entry,
        rawName: String,
    ) {
        if (FileOps.renameIsNoOp(entry.name, rawName)) return
        if (rootTree == null) {
            toast(getString(R.string.err_op_failed))
            return
        }
        val name =
            when (val result = FileOps.validateName(rawName)) {
                is FileOps.NameResult.Invalid -> {
                    toast(nameErrorText(result.error))
                    return
                }
                is FileOps.NameResult.Valid -> result.name
            }
        lifecycleScope.launch {
            val op =
                withContext(Dispatchers.IO) {
                    val dir = currentDir() ?: return@withContext Op.FAILED
                    try {
                        if (FileOps.checkCollision(name, siblingNames(dir))) {
                            return@withContext Op.COLLISION
                        }
                        val target = dir.findFile(entry.name) ?: return@withContext Op.FAILED
                        if (target.renameTo(name)) Op.RENAMED else Op.FAILED
                    } catch (e: Exception) {
                        Op.FAILED
                    }
                }
            when (op) {
                Op.RENAMED -> {
                    toast(getString(R.string.msg_renamed))
                    refresh()
                }
                Op.COLLISION -> toast(getString(R.string.err_name_exists))
                else -> toast(getString(R.string.err_op_failed))
            }
        }
    }

    private fun performDelete(entry: FolderSort.Entry) {
        if (rootTree == null) {
            toast(getString(R.string.err_op_failed))
            return
        }
        lifecycleScope.launch {
            val op =
                withContext(Dispatchers.IO) {
                    val dir = currentDir() ?: return@withContext Op.FAILED
                    try {
                        val target = dir.findFile(entry.name) ?: return@withContext Op.FAILED
                        if (target.delete()) Op.DELETED else Op.FAILED
                    } catch (e: Exception) {
                        Op.FAILED
                    }
                }
            when (op) {
                Op.DELETED -> {
                    toast(getString(R.string.msg_deleted))
                    refresh()
                }
                else -> toast(getString(R.string.err_op_failed))
            }
        }
    }

    /** Names of the entries in [dir]; throws on provider errors (caller catches). */
    private fun siblingNames(dir: DocumentFile): List<String> = dir.listFiles().mapNotNull { it.name }

    /** Maps each [FileOps.NameError] to its localized message. */
    private fun nameErrorText(error: FileOps.NameError): String =
        getString(
            when (error) {
                FileOps.NameError.EMPTY -> R.string.err_name_empty
                FileOps.NameError.INVALID_CHARS -> R.string.err_name_chars
                FileOps.NameError.DOT_NAME -> R.string.err_name_dots
                FileOps.NameError.TOO_LONG -> R.string.err_name_long
            },
        )

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun refresh() {
        val root = rootTree ?: return
        val settings = App.settings(this)
        val title = FolderSort.breadcrumb(root.name ?: getString(R.string.folder_root), path)
        binding.breadcrumb.text = title
        supportActionBar?.title = getString(R.string.open_folder)
        lifecycleScope.launch {
            val entries =
                withContext(Dispatchers.IO) {
                    val dir = currentDir()
                    if (dir == null) {
                        emptyList()
                    } else {
                        try {
                            dir.listFiles().mapNotNull { child ->
                                val name = child.name ?: return@mapNotNull null
                                FolderSort.Entry(
                                    name = name,
                                    isFolder = child.isDirectory,
                                    size = child.length(),
                                    lastModified = child.lastModified(),
                                )
                            }
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }
            val visible =
                FolderSort.sort(
                    FolderSort.filterHidden(entries, settings.showHiddenFiles),
                    settings.sortFoldersFirst,
                )
            adapter.submitList(visible)
            val empty = visible.isEmpty()
            binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
            binding.entries.visibility = if (empty) View.GONE else View.VISIBLE
            binding.toolbar.menu.clear()
            // v0.11.0: grep the whole tree (stays available at any depth).
            binding.toolbar.menu.add(getString(R.string.grep_in_folder)).apply {
                setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                setOnMenuItemClickListener {
                    rootTree?.let { tree ->
                        startActivity(GrepActivity.createIntent(this@FolderBrowserActivity, tree.uri))
                    }
                    true
                }
            }
            // v0.21.0: create entries from the toolbar.
            binding.toolbar.menu.add(getString(R.string.new_file)).apply {
                setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                setOnMenuItemClickListener {
                    showCreateDialog(isFolder = false)
                    true
                }
            }
            binding.toolbar.menu.add(getString(R.string.new_folder)).apply {
                setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                setOnMenuItemClickListener {
                    showCreateDialog(isFolder = true)
                    true
                }
            }
            binding.toolbar.menu.add(getString(R.string.up)).apply {
                setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                setOnMenuItemClickListener {
                    goUp()
                    true
                }
            }
        }
    }

    companion object {
        private const val STATE_PATH = "state.path"
    }
}

/** List adapter for folder entries. */
class FolderEntryAdapter(
    private val onClick: (FolderSort.Entry) -> Unit,
    private val onLongClick: (FolderSort.Entry) -> Unit = {},
) : ListAdapter<FolderSort.Entry, FolderEntryAdapter.ViewHolder>(DIFF) {
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder {
        val view =
            LayoutInflater
                .from(parent.context)
                .inflate(R.layout.item_folder_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        view: View,
    ) : RecyclerView.ViewHolder(view) {
        private val icon: TextView = view.findViewById(R.id.entry_icon)
        private val name: TextView = view.findViewById(R.id.entry_name)
        private val meta: TextView = view.findViewById(R.id.entry_meta)

        fun bind(entry: FolderSort.Entry) {
            val context = itemView.context
            icon.text = if (entry.isFolder) context.getString(R.string.folder_icon) else ""
            name.text = entry.name
            meta.text =
                if (entry.isFolder) {
                    context.getString(R.string.folder_kind)
                } else {
                    val ext = FileNames.split(entry.name).second
                    val size = formatSize(entry.size)
                    if (ext.isEmpty()) size else context.getString(R.string.file_kind, ext, size)
                }
            itemView.setOnClickListener { onClick(entry) }
            itemView.setOnLongClickListener {
                onLongClick(entry)
                true
            }
        }

        private fun formatSize(bytes: Long): String {
            if (bytes < 0) return ""
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            return when {
                mb >= 1 -> String.format("%.1f MB", mb)
                kb >= 1 -> String.format("%.0f KB", kb)
                else -> "$bytes B"
            }
        }
    }

    companion object {
        val DIFF =
            object : DiffUtil.ItemCallback<FolderSort.Entry>() {
                override fun areItemsTheSame(
                    oldItem: FolderSort.Entry,
                    newItem: FolderSort.Entry,
                ) = oldItem.name == newItem.name && oldItem.isFolder == newItem.isFolder

                override fun areContentsTheSame(
                    oldItem: FolderSort.Entry,
                    newItem: FolderSort.Entry,
                ) = oldItem == newItem
            }
    }
}
