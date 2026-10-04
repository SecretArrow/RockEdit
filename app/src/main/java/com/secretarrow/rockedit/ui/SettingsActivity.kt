package com.secretarrow.rockedit.ui

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.secretarrow.rockedit.R
import com.secretarrow.rockedit.core.App
import com.secretarrow.rockedit.core.BackupRestore
import com.secretarrow.rockedit.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings screen backed by androidx.preference. */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val settings = App.settings(this)
        if (settings.isBlackTheme()) setTheme(R.style.Theme_RockEdit_Black)
        settings.applyTheme()
        super.onCreate(savedInstanceState)
        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private val backupLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                if (uri != null) exportTo(uri)
            }

        private val restoreLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) restoreFrom(uri)
            }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            findPreference<Preference>("backup_data")?.setOnPreferenceClickListener {
                backupLauncher.launch("rockedit-backup.json")
                true
            }
            findPreference<Preference>("restore_data")?.setOnPreferenceClickListener {
                restoreLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                true
            }
        }

        override fun onResume() {
            super.onResume()
            // Apply theme changes immediately when returning to the caller.
            App.settings(requireContext()).applyTheme()
        }

        private fun exportTo(uri: Uri) {
            val context = requireContext()
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        val kv = App.keyValueStore(context)
                        val json = BackupRestore(kv).export(BackupRestore.defaultKeys())
                        context.contentResolver.openOutputStream(uri, "wt")?.use {
                            it.write(json.toByteArray(Charsets.UTF_8))
                        } != null
                    } catch (_: Exception) {
                        false
                    }
                }
                toast(
                    if (ok) getString(R.string.backup_done)
                    else getString(R.string.backup_failed)
                )
            }
        }

        private fun restoreFrom(uri: Uri) {
            val context = requireContext()
            lifecycleScope.launch {
                val message = withContext(Dispatchers.IO) {
                    try {
                        val kv = App.keyValueStore(context)
                        val json = context.contentResolver.openInputStream(uri)
                            ?.use { it.readBytes().toString(Charsets.UTF_8) }
                        if (json == null) {
                            getString(R.string.restore_failed)
                        } else {
                            val allowed = BackupRestore.defaultKeys().map { it.key }.toSet()
                            val result = BackupRestore(kv).restore(json, allowed)
                            getString(R.string.restore_done, result.applied, result.skipped)
                        }
                    } catch (_: Exception) {
                        getString(R.string.restore_failed)
                    }
                }
                toast(message)
            }
        }

        private fun toast(message: String) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }
}
