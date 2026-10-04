package com.secretarrow.rockedit.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.secretarrow.rockedit.R
import com.secretarrow.rockedit.core.App
import com.secretarrow.rockedit.core.RemoteConnection
import com.secretarrow.rockedit.core.RemoteType
import com.secretarrow.rockedit.core.RemotePath
import com.secretarrow.rockedit.databinding.ActivityStorageManagerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Storage Manager (roadmap: konektivitas): lists saved FTP/FTPS/SFTP/WebDAV
 * connections, lets the user add/edit/delete them, and opens the remote
 * browser. Passwords are stored encrypted (Android Keystore).
 */
class StorageManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStorageManagerBinding
    private lateinit var adapter: RemoteConnectionAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        val settings = App.settings(this)
        if (settings.isBlackTheme()) setTheme(R.style.Theme_RockEdit_Black)
        settings.applyTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityStorageManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = RemoteConnectionAdapter(
            onClick = { connection -> openBrowser(connection) },
            onLongClick = { connection -> confirmDelete(connection) }
        )
        binding.connections.layoutManager = LinearLayoutManager(this)
        binding.connections.adapter = adapter

        binding.btnAddConnection.setOnClickListener { showEditorDialog(null) }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                App.remoteConnections(this@StorageManagerActivity).list()
            }
            adapter.submitList(items)
            val empty = items.isEmpty()
            binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
            binding.connections.visibility = if (empty) View.GONE else View.VISIBLE
        }
    }

    private fun openBrowser(connection: RemoteConnection) {
        val intent = Intent(this, RemoteBrowserActivity::class.java)
            .putExtra(RemoteBrowserActivity.EXTRA_CONNECTION_ID, connection.id)
        startActivity(intent)
    }

    private fun confirmDelete(connection: RemoteConnection) {
        AlertDialog.Builder(this)
            .setTitle(R.string.storage_delete)
            .setMessage(getString(R.string.storage_delete_confirm, connection.name))
            .setPositiveButton(R.string.discard) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        App.remoteConnections(this@StorageManagerActivity).remove(connection.id)
                    }
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Add/edit dialog: type, host, port, user, password, initial path. */
    private fun showEditorDialog(existing: RemoteConnection?) {
        val view = layoutInflater.inflate(R.layout.dialog_connection, null)
        val nameInput = view.findViewById<EditText>(R.id.input_name)
        val hostInput = view.findViewById<EditText>(R.id.input_host)
        val portInput = view.findViewById<EditText>(R.id.input_port)
        val userInput = view.findViewById<EditText>(R.id.input_user)
        val passwordInput = view.findViewById<EditText>(R.id.input_password)
        val pathInput = view.findViewById<EditText>(R.id.input_path)
        val typeContainer = view.findViewById<LinearLayout>(R.id.type_buttons)

        val types = RemoteType.entries
        var selectedType = existing?.type ?: RemoteType.FTP
        val typeButtons = types.map { type ->
            TextView(this).apply {
                text = type.displayName
                setPadding(dp(12), dp(10), dp(12), dp(10))
                isClickable = true
                isFocusable = true
                typeContainer.addView(this)
            }
        }
        fun selectType(type: RemoteType) {
            selectedType = type
            val index = types.indexOf(type)
            typeButtons.forEachIndexed { i, button ->
                button.setBackgroundColor(
                    getColor(if (i == index) R.color.tab_chip_active else R.color.tab_chip_inactive)
                )
            }
            if (portInput.text.isNullOrEmpty()) {
                portInput.hint = type.defaultPort.toString()
            }
        }
        typeButtons.forEachIndexed { index, button ->
            button.setOnClickListener { selectType(types[index]) }
        }

        existing?.let { c ->
            nameInput.setText(c.name)
            hostInput.setText(c.host)
            portInput.setText(c.port.toString())
            userInput.setText(c.user)
            pathInput.setText(c.initialPath)
            selectType(c.type)
        } ?: selectType(RemoteType.FTP)

        AlertDialog.Builder(this)
            .setTitle(
                if (existing == null) R.string.storage_add else R.string.storage_edit
            )
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val host = hostInput.text.toString().trim()
                if (host.isEmpty()) {
                    toast(getString(R.string.storage_host_required))
                    return@setPositiveButton
                }
                val connection = RemoteConnection(
                    id = existing?.id ?: RemoteConnection.newId(),
                    name = nameInput.text.toString().trim().ifEmpty { host },
                    type = selectedType,
                    host = host,
                    port = portInput.text.toString().toIntOrNull() ?: 0,
                    user = userInput.text.toString(),
                    password = passwordInput.text.toString(),
                    initialPath = RemotePath.normalize(pathInput.text.toString())
                )
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        App.remoteConnections(this@StorageManagerActivity).save(connection)
                    }
                    refresh()
                    toast(getString(R.string.storage_saved))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

class RemoteConnectionAdapter(
    private val onClick: (RemoteConnection) -> Unit,
    private val onLongClick: (RemoteConnection) -> Unit
) : ListAdapter<RemoteConnection, RemoteConnectionAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_connection, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.conn_name)
        private val meta: TextView = view.findViewById(R.id.conn_meta)

        fun bind(connection: RemoteConnection) {
            name.text = connection.name
            meta.text = "${connection.type.displayName} · ${connection.user.ifEmpty { "anonymous" }}@${connection.host}:${connection.port}"
            itemView.setOnClickListener { onClick(connection) }
            itemView.setOnLongClickListener {
                onLongClick(connection)
                true
            }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<RemoteConnection>() {
            override fun areItemsTheSame(oldItem: RemoteConnection, newItem: RemoteConnection) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: RemoteConnection, newItem: RemoteConnection) =
                oldItem == newItem
        }
    }
}
