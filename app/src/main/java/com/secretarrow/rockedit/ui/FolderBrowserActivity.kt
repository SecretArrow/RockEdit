package com.secretarrow.rockedit.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.secretarrow.rockedit.R
import com.secretarrow.rockedit.core.App
import com.secretarrow.rockedit.core.FolderSort
import com.secretarrow.rockedit.databinding.ActivityFolderBrowserBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.secretarrow.rockedit.core.FileNames

/**
 * SAF folder browser (roadmap: open files from a folder tree without leaving
 * the app). The picked tree URI is persisted; navigation uses [DocumentFile].
 * Tapping a file hands it to the editor, which opens it in a new tab.
 */
class FolderBrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFolderBrowserBinding
    private lateinit var adapter: FolderEntryAdapter
    private var rootTree: DocumentFile? = null
    private var path: List<String> = emptyList()

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

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = FolderEntryAdapter(
            onClick = { entry -> onEntryClicked(entry) }
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

    override fun onRestoreInstanceState(savedInstanceState: Bundle?) {
        super.onRestoreInstanceState(savedInstanceState)
        val saved = savedInstanceState?.getStringArrayList(STATE_PATH).orEmpty()
        if (saved.isNotEmpty() && rootTree != null) {
            path = saved
            refresh()
        }
    }

    private fun takePersistentPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
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

    private fun refresh() {
        val root = rootTree ?: return
        val settings = App.settings(this)
        val title = FolderSort.breadcrumb(root.name ?: getString(R.string.folder_root), path)
        binding.breadcrumb.text = title
        supportActionBar?.title = getString(R.string.open_folder)
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                val dir = currentDir()
                if (dir == null) emptyList()
                else try {
                    dir.listFiles().mapNotNull { child ->
                        val name = child.name ?: return@mapNotNull null
                        FolderSort.Entry(
                            name = name,
                            isFolder = child.isDirectory,
                            size = child.length(),
                            lastModified = child.lastModified()
                        )
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }
            val visible = FolderSort.sort(
                FolderSort.filterHidden(entries, settings.showHiddenFiles),
                settings.sortFoldersFirst
            )
            adapter.submitList(visible)
            val empty = visible.isEmpty()
            binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
            binding.entries.visibility = if (empty) View.GONE else View.VISIBLE
            binding.toolbar.menu.clear()
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
    private val onClick: (FolderSort.Entry) -> Unit
) : ListAdapter<FolderSort.Entry, FolderEntryAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_folder_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: TextView = view.findViewById(R.id.entry_icon)
        private val name: TextView = view.findViewById(R.id.entry_name)
        private val meta: TextView = view.findViewById(R.id.entry_meta)

        fun bind(entry: FolderSort.Entry) {
            val context = itemView.context
            icon.text = if (entry.isFolder) context.getString(R.string.folder_icon) else ""
            name.text = entry.name
            meta.text = if (entry.isFolder) {
                context.getString(R.string.folder_kind)
            } else {
                val ext = FileNames.split(entry.name).second
                val size = formatSize(entry.size)
                if (ext.isEmpty()) size else context.getString(R.string.file_kind, ext, size)
            }
            itemView.setOnClickListener { onClick(entry) }
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
        val DIFF = object : DiffUtil.ItemCallback<FolderSort.Entry>() {
            override fun areItemsTheSame(oldItem: FolderSort.Entry, newItem: FolderSort.Entry) =
                oldItem.name == newItem.name && oldItem.isFolder == newItem.isFolder

            override fun areContentsTheSame(oldItem: FolderSort.Entry, newItem: FolderSort.Entry) =
                oldItem == newItem
        }
    }
}
