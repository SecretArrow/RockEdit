package com.secretarrow.rockedit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.secretarrow.rockedit.core.App
import com.secretarrow.rockedit.core.RecentFile
import com.secretarrow.rockedit.databinding.ActivityMainBinding
import com.secretarrow.rockedit.ui.EditorActivity
import com.secretarrow.rockedit.ui.RecentFilesAdapter
import com.secretarrow.rockedit.ui.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main screen: recent files list + open/create entry points.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: RecentFilesAdapter

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                takePersistentPermission(uri)
                openEditor(uri)
            }
        }

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            App.settings(this).applyTheme()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val settings = App.settings(this)
        if (settings.isBlackTheme()) setTheme(R.style.Theme_RockEdit_Black)
        settings.applyTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        adapter =
            RecentFilesAdapter(
                onClick = { openEditor(Uri.parse(it.uri)) },
                onLongClick = { confirmRemove(it) },
            )
        binding.recentsList.layoutManager = LinearLayoutManager(this)
        binding.recentsList.adapter = adapter

        binding.fabNew.setOnClickListener { openEditor(null) }

        binding.toolbar.inflateMenu(R.menu.menu_main)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_open -> {
                    openDocument.launch(arrayOf("*/*"))
                    true
                }
                R.id.action_open_folder -> {
                    startActivity(Intent(this, com.secretarrow.rockedit.ui.FolderBrowserActivity::class.java))
                    true
                }
                R.id.action_storage_manager -> {
                    startActivity(Intent(this, com.secretarrow.rockedit.ui.StorageManagerActivity::class.java))
                    true
                }
                R.id.action_settings -> {
                    settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
                    true
                }
                R.id.action_help -> {
                    startActivity(Intent(this, com.secretarrow.rockedit.ui.HelpActivity::class.java))
                    true
                }
                R.id.action_about -> {
                    showAbout()
                    true
                }
                else -> false
            }
        }

        handleSharedText(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedText(intent)
    }

    override fun onResume() {
        super.onResume()
        App.settings(this).applyTheme()
        refreshRecents()
    }

    private fun handleSharedText(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val editor =
                Intent(this, EditorActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, text)
                }
            startActivity(editor)
        }
    }

    private fun takePersistentPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Provider did not offer persistable grants; file still opens for this session.
        }
    }

    private fun openEditor(uri: Uri?) {
        val intent = Intent(this, EditorActivity::class.java)
        if (uri != null) {
            intent.action = Intent.ACTION_EDIT
            intent.data = uri
        }
        startActivity(intent)
    }

    private fun confirmRemove(item: RecentFile) {
        androidx.appcompat.app.AlertDialog
            .Builder(this)
            .setTitle(R.string.remove_from_recents)
            .setMessage(item.name)
            .setPositiveButton(R.string.discard) { _, _ ->
                App.recents(this).remove(item.uri)
                refreshRecents()
                Toast.makeText(this, R.string.removed_from_recents, Toast.LENGTH_SHORT).show()
            }.setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAbout() {
        val body = getString(R.string.about_body, BuildConfig.VERSION_NAME)
        androidx.appcompat.app.AlertDialog
            .Builder(this)
            .setTitle(R.string.about_title)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.licenses_title) { _, _ ->
                startActivity(
                    Intent(this, com.secretarrow.rockedit.ui.LicensesActivity::class.java),
                )
            }.show()
    }

    private fun refreshRecents() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { App.recents(this@MainActivity).list() }
            adapter.submitList(items)
            val empty = items.isEmpty()
            binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
            binding.recentsTitle.visibility = if (empty) View.GONE else View.VISIBLE
        }
    }

    /** Resolves a friendly display name for a content URI. */
    fun queryDisplayName(uri: Uri): String {
        var name: String = uri.lastPathSegment ?: "file.txt"
        try {
            contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx)?.let { name = it }
                    }
                }
        } catch (_: Exception) {
            // Keep the path-derived fallback.
        }
        return name
    }
}
