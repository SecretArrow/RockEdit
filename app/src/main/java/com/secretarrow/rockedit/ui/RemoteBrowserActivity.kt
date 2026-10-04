package com.secretarrow.rockedit.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.secretarrow.rockedit.R
import com.secretarrow.rockedit.core.App
import com.secretarrow.rockedit.core.FolderSort
import com.secretarrow.rockedit.core.RemoteClientFactory
import com.secretarrow.rockedit.core.RemoteFile
import com.secretarrow.rockedit.core.RemotePath
import com.secretarrow.rockedit.databinding.ActivityRemoteBrowserBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Browses a saved remote connection's file tree. Files open in the editor
 * through [com.secretarrow.rockedit.remote.RemoteContentProvider], so tabs,
 * recents and session restore work exactly like local files.
 */
class RemoteBrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRemoteBrowserBinding
    private lateinit var adapter: FolderEntryAdapter

    private var connectionId: Long = -1
    private var path: String = "/"

    override fun onCreate(savedInstanceState: Bundle?) {
        val settings = App.settings(this)
        if (settings.isBlackTheme()) setTheme(R.style.Theme_RockEdit_Black)
        settings.applyTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityRemoteBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        connectionId = intent.getLongExtra(EXTRA_CONNECTION_ID, -1L)
        val connection = App.remoteConnections(this).find(connectionId)
        if (connection == null) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        supportActionBar?.title = connection.name
        path = RemotePath.normalize(
            savedInstanceState?.getString(STATE_PATH) ?: connection.initialPath
        )

        adapter = FolderEntryAdapter(
            onClick = { entry -> onEntryClicked(entry) }
        )
        binding.entries.layoutManager = LinearLayoutManager(this)
        binding.entries.adapter = adapter

        binding.btnUp.setOnClickListener { goUp() }
        binding.btnNewFolder.setOnClickListener { showMkdirDialog() }
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PATH, path)
    }

    private fun onEntryClicked(entry: FolderSort.Entry) {
        if (entry.isFolder) {
            path = RemotePath.normalize(RemotePath.child(path, entry.name))
            refresh()
        } else {
            val target = RemotePath.child(path, entry.name)
            val uri = Uri.Builder()
                .scheme("content")
                .authority("com.secretarrow.rockedit.remote")
                .appendPath(connectionId.toString())
                .appendEncodedPath(target.trimStart('/'))
                .build()
            startActivity(EditorActivity.createIntent(this, uri))
        }
    }

    private fun goUp() {
        if (RemotePath.parent(path) == path) return
        path = RemotePath.parent(path)
        refresh()
    }

    private fun showMkdirDialog() {
        val input = EditText(this).apply { hint = getString(R.string.storage_folder_name) }
        AlertDialog.Builder(this)
            .setTitle(R.string.storage_new_folder)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val target = RemotePath.child(path, name)
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        try {
                            val c = client() ?: return@withContext false
                            c.use { it.mkdir(target) }
                            true
                        } catch (_: Exception) {
                            false
                        }
                    }
                    toast(
                        if (ok) getString(R.string.storage_folder_created)
                        else getString(R.string.storage_operation_failed)
                    )
                    if (ok) refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private suspend fun client(): com.secretarrow.rockedit.core.RemoteClient? {
        val connection = App.remoteConnections(this).find(connectionId)
            ?: return null
        return withContext(Dispatchers.IO) { RemoteClientFactory.create(connection) }
    }

    private fun refresh() {
        binding.breadcrumb.text = RemotePath.normalize(path)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val c = client() ?: return@withContext null
                    c.use { it.list(path) }
                } catch (e: Exception) {
                    null
                }
            }
            if (result == null) {
                toast(getString(R.string.storage_operation_failed))
                return@launch
            }
            val settings = App.settings(this@RemoteBrowserActivity)
            val visible = FolderSort.sort(
                result.map { FolderSort.Entry(it.name, it.isFolder, it.size, it.lastModified) },
                settings.sortFoldersFirst
            )
            adapter.submitList(visible)
            val empty = visible.isEmpty()
            binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
            binding.entries.visibility = if (empty) View.GONE else View.VISIBLE
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_CONNECTION_ID = "connection_id"
        private const val STATE_PATH = "state.path"
    }
}
