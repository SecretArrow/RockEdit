package com.secretarrow.rockedit.ui

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.secretarrow.rockedit.R
import com.secretarrow.rockedit.core.App
import com.secretarrow.rockedit.core.CloudAuthUrls
import com.secretarrow.rockedit.core.OAuthExchangeResult
import com.secretarrow.rockedit.core.OAuthTokenExchanger
import com.secretarrow.rockedit.core.OAuthTokenStore
import com.secretarrow.rockedit.core.KeystoreEncryptor
import com.secretarrow.rockedit.core.RemoteConnection
import com.secretarrow.rockedit.core.RemotePath
import com.secretarrow.rockedit.core.RemoteType
import com.secretarrow.rockedit.core.UsbOtgLogic
import com.secretarrow.rockedit.core.isCloud
import com.secretarrow.rockedit.core.cloudProvider
import com.secretarrow.rockedit.databinding.ActivityStorageManagerBinding
import com.secretarrow.rockedit.remote.CloudRuntime
import com.secretarrow.rockedit.remote.UsbOtgSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Storage Manager (roadmap: konektivitas): lists saved FTP/FTPS/SFTP/WebDAV/
 * Git/OAuth-cloud connections, lets the user add/edit/delete them, opens the
 * remote browser, and exposes attached USB OTG devices. Secrets (passwords,
 * client secrets, OAuth tokens) are stored encrypted (Android Keystore).
 *
 * v0.15.0 additions (backlog: Cloud OAuth + USB OTG):
 *  - cloud types reuse the host field as the user's OAuth client id and add
 *    an authorization-URL + code-paste flow; save keeps the dialog open on
 *    every validation/exchange failure so nothing is lost;
 *  - an attached USB mass-storage device shows a tap-to-open section; the
 *    browser then runs through the sentinel connection id.
 */
class StorageManagerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityStorageManagerBinding
    private lateinit var adapter: RemoteConnectionAdapter
    private var pendingUsbDevice: UsbDevice? = null

    private val usbPermissionReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action != ACTION_USB_PERMISSION) return
                val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                if (device == null) {
                    toast(getString(R.string.storage_usb_open_failed))
                    return
                }
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    openUsbDevice(device)
                } else {
                    toast(getString(R.string.storage_usb_permission_denied))
                }
            }
        }

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

        adapter =
            RemoteConnectionAdapter(
                onClick = { connection -> openBrowser(connection) },
                onLongClick = { connection -> confirmDelete(connection) },
            )
        binding.connections.layoutManager = LinearLayoutManager(this)
        binding.connections.adapter = adapter

        binding.btnAddConnection.setOnClickListener { showEditorDialog(null) }
        binding.usbSection.setOnClickListener { onUsbSectionClicked() }
        ContextCompat.registerReceiver(
            this,
            usbPermissionReceiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        refreshUsbSection()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbPermissionReceiver)
    }

    private fun refresh() {
        lifecycleScope.launch {
            val items =
                withContext(Dispatchers.IO) {
                    App.remoteConnections(this@StorageManagerActivity).list()
                }
            adapter.submitList(items)
            val empty = items.isEmpty()
            binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
            binding.connections.visibility = if (empty) View.GONE else View.VISIBLE
        }
    }

    // ------------------------------------------------------------ USB OTG

    private fun refreshUsbSection() {
        val devices = UsbOtgSupport.attachedDevices(this)
        if (devices.isEmpty()) {
            binding.usbSection.visibility = View.GONE
            pendingUsbDevice = null
            return
        }
        val device = devices.first()
        pendingUsbDevice = device
        binding.usbSection.text =
            getString(
                R.string.storage_usb_section,
                UsbOtgLogic.displayName(device.manufacturerName, device.productName, getString(R.string.storage_usb_fallback)),
            )
        binding.usbSection.visibility = View.VISIBLE
    }

    private fun onUsbSectionClicked() {
        val device = pendingUsbDevice ?: return
        if (UsbOtgSupport.hasPermission(this, device)) {
            openUsbDevice(device)
        } else {
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
            val pendingIntent =
                PendingIntent.getBroadcast(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
            try {
                (getSystemService(Context.USB_SERVICE) as? UsbManager)?.requestPermission(device, pendingIntent)
            } catch (e: Exception) {
                toast(getString(R.string.storage_usb_open_failed) + UsbOtgLogic.describeError(e))
            }
        }
    }

    private fun openUsbDevice(device: UsbDevice) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { UsbOtgSupport.open(this@StorageManagerActivity, device) }
                startActivity(
                    Intent(this@StorageManagerActivity, RemoteBrowserActivity::class.java)
                        .putExtra(RemoteBrowserActivity.EXTRA_CONNECTION_ID, UsbOtgLogic.SENTINEL_CONNECTION_ID),
                )
            } catch (e: Exception) {
                toast(getString(R.string.storage_usb_open_failed) + UsbOtgLogic.describeError(e))
            }
        }
    }

    // ---------------------------------------------------------- browsing

    private fun openBrowser(connection: RemoteConnection) {
        val intent =
            Intent(this, RemoteBrowserActivity::class.java)
                .putExtra(RemoteBrowserActivity.EXTRA_CONNECTION_ID, connection.id)
        startActivity(intent)
    }

    private fun confirmDelete(connection: RemoteConnection) {
        AlertDialog
            .Builder(this)
            .setTitle(R.string.storage_delete)
            .setMessage(getString(R.string.storage_delete_confirm, connection.name))
            .setPositiveButton(R.string.discard) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        App.remoteConnections(this@StorageManagerActivity).remove(connection.id)
                        OAuthTokenStore(App.keyValueStore(this@StorageManagerActivity), KeystoreEncryptor)
                            .removeTokens(connection.id)
                    }
                    refresh()
                }
            }.setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Add/edit dialog: type, host (or client id), port, user, secret, path, OAuth box. */
    private fun showEditorDialog(existing: RemoteConnection?) {
        @SuppressLint("InflateParams")
        val view = layoutInflater.inflate(R.layout.dialog_connection, null)
        val nameInput = view.findViewById<EditText>(R.id.input_name)
        val hostInput = view.findViewById<EditText>(R.id.input_host)
        val portInput = view.findViewById<EditText>(R.id.input_port)
        val userInput = view.findViewById<EditText>(R.id.input_user)
        val passwordInput = view.findViewById<EditText>(R.id.input_password)
        val pathInput = view.findViewById<EditText>(R.id.input_path)
        val typeContainer = view.findViewById<LinearLayout>(R.id.type_buttons)
        val oauthBox = view.findViewById<LinearLayout>(R.id.cloud_oauth_box)

        val oauthHelp =
            TextView(this).apply {
                text = getString(R.string.storage_oauth_help)
                setPadding(0, dp(4), 0, dp(4))
            }
        val oauthCopyButton =
            Button(this).apply {
                text = getString(R.string.storage_oauth_copy_url)
            }
        val oauthCodeInput =
            EditText(this).apply {
                hint = getString(R.string.storage_oauth_code_hint)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
        oauthBox.addView(oauthHelp)
        oauthBox.addView(oauthCopyButton)
        oauthBox.addView(oauthCodeInput)

        val types = RemoteType.entries
        var selectedType = existing?.type ?: RemoteType.FTP
        val typeButtons =
            types.map { type ->
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
                    getColor(if (i == index) R.color.tab_chip_active else R.color.tab_chip_inactive),
                )
            }
            val cloud = type.isCloud
            oauthBox.visibility = if (cloud) View.VISIBLE else View.GONE
            if (portInput.text.isNullOrEmpty()) {
                portInput.hint = type.defaultPort.toString()
            }
            pathInput.hint =
                when {
                    type == RemoteType.GITHUB || type == RemoteType.GITLAB -> getString(R.string.storage_git_path)
                    cloud -> getString(R.string.storage_cloud_path_hint)
                    else -> getString(R.string.storage_initial_path)
                }
            hostInput.hint =
                when {
                    cloud -> getString(R.string.storage_client_id)
                    type == RemoteType.GITLAB -> getString(R.string.storage_gitlab_host)
                    else -> getString(R.string.storage_host)
                }
            passwordInput.hint = if (cloud) getString(R.string.storage_client_secret) else getString(R.string.storage_password)
        }
        typeButtons.forEachIndexed { index, button ->
            button.setOnClickListener { selectType(types[index]) }
        }

        oauthCopyButton.setOnClickListener {
            val provider = selectedType.cloudProvider()
            val clientId = hostInput.text.toString().trim()
            if (provider == null) {
                toast(getString(R.string.storage_oauth_failed) + "not a cloud connection type")
                return@setOnClickListener
            }
            if (clientId.isEmpty()) {
                toast(getString(R.string.storage_oauth_need_client_id))
                return@setOnClickListener
            }
            val url = CloudAuthUrls.build(provider, clientId)
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard == null) {
                toast(getString(R.string.storage_oauth_failed) + "no clipboard")
                return@setOnClickListener
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("RockEdit OAuth", url))
            toast(getString(R.string.storage_oauth_copied))
        }

        existing?.let { c ->
            nameInput.setText(c.name)
            if (c.type.isCloud) {
                hostInput.setText(c.clientId)
                passwordInput.setText(c.clientSecret)
            } else {
                hostInput.setText(c.host)
                passwordInput.setText(c.password)
            }
            portInput.setText(c.port.toString())
            userInput.setText(c.user)
            pathInput.setText(c.initialPath)
            selectType(c.type)
        } ?: selectType(RemoteType.FTP)

        val dialog =
            AlertDialog
                .Builder(this)
                .setTitle(
                    if (existing == null) R.string.storage_add else R.string.storage_edit,
                ).setView(view)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create()

        // Positive button is wired manually so failed validation keeps the
        // dialog open (no input is ever lost on an error path).
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val isCloud = selectedType.isCloud
                val hostOrClientId = hostInput.text.toString().trim()
                if (!isCloud && hostOrClientId.isEmpty()) {
                    toast(getString(R.string.storage_host_required))
                    return@setOnClickListener
                }
                if (isCloud && hostOrClientId.isEmpty()) {
                    toast(getString(R.string.storage_oauth_need_client_id))
                    return@setOnClickListener
                }
                val secret = passwordInput.text.toString()
                val connection =
                    RemoteConnection(
                        id = existing?.id ?: RemoteConnection.newId(),
                        name =
                            nameInput.text
                                .toString()
                                .trim()
                                .ifEmpty { hostOrClientId },
                        type = selectedType,
                        host = if (isCloud) "" else hostOrClientId,
                        port = portInput.text.toString().toIntOrNull() ?: 0,
                        user = userInput.text.toString(),
                        password = if (isCloud) "" else secret,
                        initialPath = RemotePath.normalize(pathInput.text.toString()),
                        clientId = if (isCloud) hostOrClientId else existing?.clientId.orEmpty(),
                        clientSecret = if (isCloud) secret else existing?.clientSecret.orEmpty(),
                    )
                val pastedCode = oauthCodeInput.text.toString().trim()
                if (isCloud && pastedCode.isNotEmpty()) {
                    authorizeAndSave(dialog, connection, pastedCode)
                } else {
                    saveConnection(dialog, connection)
                }
            }
        }
        dialog.show()
    }

    /** Persists a connection; dismisses the dialog only on success. */
    private fun saveConnection(
        dialog: AlertDialog,
        connection: RemoteConnection,
    ) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                App.remoteConnections(this@StorageManagerActivity).save(connection)
            }
            refresh()
            toast(getString(R.string.storage_saved))
            dialog.dismiss()
        }
    }

    /**
     * Exchanges the pasted value (authorization code first, refresh token as
     * fallback) into tokens, saves both connection and tokens; keeps the
     * dialog open with an informative message on any failure branch.
     */
    private fun authorizeAndSave(
        dialog: AlertDialog,
        connection: RemoteConnection,
        pastedCode: String,
    ) {
        val provider = connection.type.cloudProvider() ?: return
        toast(getString(R.string.storage_oauth_connecting))
        lifecycleScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    val exchanger = OAuthTokenExchanger(CloudRuntime.http)
                    val direct =
                        exchanger.exchangeCode(
                            provider,
                            connection.clientId,
                            connection.clientSecret,
                            pastedCode,
                            provider.defaultRedirectUri,
                        )
                    if (direct is OAuthExchangeResult.Success) {
                        direct
                    } else {
                        // The pasted value may already be a refresh token from
                        // an earlier session (e.g. a provider console tool).
                        exchanger.refreshTokens(provider, connection.clientId, connection.clientSecret, pastedCode)
                    }
                }
            when (result) {
                is OAuthExchangeResult.Success -> {
                    withContext(Dispatchers.IO) {
                        App.remoteConnections(this@StorageManagerActivity).save(connection)
                        OAuthTokenStore(App.keyValueStore(this@StorageManagerActivity), KeystoreEncryptor)
                            .saveTokens(connection.id, result.tokens)
                    }
                    refresh()
                    toast(getString(R.string.storage_oauth_saved, provider.displayName))
                    dialog.dismiss()
                }
                is OAuthExchangeResult.Failure -> {
                    toast(getString(R.string.storage_oauth_failed) + result.reason)
                }
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.secretarrow.rockedit.USB_PERMISSION"
    }
}

class RemoteConnectionAdapter(
    private val onClick: (RemoteConnection) -> Unit,
    private val onLongClick: (RemoteConnection) -> Unit,
) : ListAdapter<RemoteConnection, RemoteConnectionAdapter.ViewHolder>(DIFF) {
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder {
        val view =
            LayoutInflater
                .from(parent.context)
                .inflate(R.layout.item_connection, parent, false)
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
        private val name: TextView = view.findViewById(R.id.conn_name)
        private val meta: TextView = view.findViewById(R.id.conn_meta)

        fun bind(connection: RemoteConnection) {
            name.text = connection.name
            meta.text =
                if (connection.type.isCloud) {
                    "${connection.type.displayName} · ${connection.user.ifEmpty { "OAuth" }}"
                } else {
                    "${connection.type.displayName} · ${connection.user.ifEmpty { "anonymous" }}@${connection.host}:${connection.port}"
                }
            itemView.setOnClickListener { onClick(connection) }
            itemView.setOnLongClickListener {
                onLongClick(connection)
                true
            }
        }
    }

    companion object {
        val DIFF =
            object : DiffUtil.ItemCallback<RemoteConnection>() {
                override fun areItemsTheSame(
                    oldItem: RemoteConnection,
                    newItem: RemoteConnection,
                ) = oldItem.id == newItem.id

                override fun areContentsTheSame(
                    oldItem: RemoteConnection,
                    newItem: RemoteConnection,
                ) = oldItem == newItem
            }
    }
}
