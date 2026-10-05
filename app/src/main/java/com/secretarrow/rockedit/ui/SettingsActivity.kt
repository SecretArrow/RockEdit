package com.secretarrow.rockedit.ui

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.secretarrow.rockedit.R
import com.secretarrow.rockedit.core.App
import com.secretarrow.rockedit.core.BackupRestore
import com.secretarrow.rockedit.core.CustomGrammarStore
import com.secretarrow.rockedit.core.SyntaxRegistry
import com.secretarrow.rockedit.core.TmLanguageParser
import com.secretarrow.rockedit.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

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
            registerForActivityResult(
                ActivityResultContracts.CreateDocument("application/json"),
            ) { uri ->
                if (uri != null) exportTo(uri)
            }

        private val restoreLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) restoreFrom(uri)
            }

        /** v0.17.0: TextMate grammar import source picker (.tmLanguage JSON). */
        private val grammarImportLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) importGrammarFrom(uri)
            }

        override fun onCreatePreferences(
            savedInstanceState: Bundle?,
            rootKey: String?,
        ) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            findPreference<Preference>("backup_data")?.setOnPreferenceClickListener {
                backupLauncher.launch("rockedit-backup.json")
                true
            }
            findPreference<Preference>("restore_data")?.setOnPreferenceClickListener {
                restoreLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                true
            }
            findPreference<Preference>("grammar_import")?.setOnPreferenceClickListener {
                grammarImportLauncher.launch(arrayOf("*/*"))
                true
            }
            findPreference<Preference>("grammar_manage")?.setOnPreferenceClickListener {
                showGrammarManager()
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
                val ok =
                    withContext(Dispatchers.IO) {
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
                    if (ok) {
                        getString(R.string.backup_done)
                    } else {
                        getString(R.string.backup_failed)
                    },
                )
            }
        }

        private fun restoreFrom(uri: Uri) {
            val context = requireContext()
            lifecycleScope.launch {
                val message =
                    withContext(Dispatchers.IO) {
                        try {
                            val kv = App.keyValueStore(context)
                            val json =
                                context.contentResolver
                                    .openInputStream(uri)
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

        // --------------------------------------------- v0.17.0 custom grammars

        /**
         * Reads the picked grammar file (byte-capped), then asks for the
         * extension mapping before importing. All heavy work stays off the
         * main thread; every failure path ends in an informative toast.
         */
        private fun importGrammarFrom(uri: Uri) {
            val context = requireContext()
            lifecycleScope.launch {
                val json =
                    withContext(Dispatchers.IO) {
                        readGrammarCapped(context, uri)
                    }
                if (json == null) {
                    toast(
                        getString(R.string.grammar_import_failed) +
                            getString(R.string.grammar_read_failed),
                    )
                    return@launch
                }
                askGrammarDetails(json, displayName(context, uri))
            }
        }

        /**
         * Reads up to [TmLanguageParser.MAX_JSON_CHARS] + 1 bytes so an
         * oversized file is detected by the parser (TOO_LARGE) instead of
         * exhausting memory. Returns null on ANY read failure.
         */
        private fun readGrammarCapped(
            context: android.content.Context,
            uri: Uri,
        ): String? =
            try {
                context.contentResolver
                    .openInputStream(uri)
                    ?.use { stream ->
                        val budget = TmLanguageParser.MAX_JSON_CHARS + 1
                        val buffer = ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        var read = stream.read(chunk)
                        while (read > 0) {
                            buffer.write(chunk, 0, read)
                            if (buffer.size() > budget) break
                            read = stream.read(chunk)
                        }
                        buffer.toString("UTF-8")
                    }
            } catch (e: Exception) {
                // SAF read failure (dead document, revoked grant): reported
                // by the caller as a read failure, never a crash.
                null
            }

        private fun displayName(
            context: android.content.Context,
            uri: Uri,
        ): String =
            try {
                context.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
            } catch (e: Exception) {
                // Provider gone: the fallback name keeps the flow usable.
                null
            } ?: "grammar.tmLanguage"

        /** Dialog: grammar name + comma-separated extension list, then import. */
        private fun askGrammarDetails(
            json: String,
            suggestedName: String,
        ) {
            val context = requireContext()
            val pad = (16 * context.resources.displayMetrics.density).toInt()
            val layout =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(pad, pad / 2, pad, 0)
                }
            val nameInput =
                EditText(context).apply {
                    hint = getString(R.string.grammar_name_hint)
                    setText(suggestedName.substringBeforeLast('.'))
                }
            val extensionInput =
                EditText(context).apply {
                    hint = getString(R.string.grammar_extension_hint)
                }
            val extensionHint =
                TextView(context).apply {
                    text = getString(R.string.grammar_extension_hint)
                    textSize = 12f
                }
            layout.addView(nameInput)
            layout.addView(extensionHint)
            layout.addView(extensionInput)
            AlertDialog
                .Builder(context)
                .setTitle(R.string.grammar_import)
                .setView(layout)
                .setPositiveButton(R.string.grammar_save, null)
                .setNegativeButton(R.string.cancel, null)
                .create()
                .apply {
                    setOnShowListener {
                        getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            val name = nameInput.text.toString().trim()
                            val rawExtensions = extensionInput.text.toString()
                            val extensions =
                                rawExtensions
                                    .split(',')
                                    .map { it.trim().trimStart('.').lowercase() }
                                    .filter { it.isNotEmpty() }
                            if (name.isEmpty()) {
                                toast(getString(R.string.grammar_name_hint))
                                return@setOnClickListener
                            }
                            if (extensions.isEmpty()) {
                                toast(getString(R.string.grammar_extension_hint))
                                return@setOnClickListener
                            }
                            importGrammar(json, name, extensions)
                            dismiss()
                        }
                    }
                    show()
                }
        }

        /** Parses + stores the grammar off the main thread; reports any failure. */
        private fun importGrammar(
            json: String,
            name: String,
            extensions: List<String>,
        ) {
            val context = requireContext()
            lifecycleScope.launch {
                val outcome =
                    withContext(Dispatchers.IO) {
                        val store = CustomGrammarStore(App.keyValueStore(context))
                        val id = grammarIdFor(name)
                        when (val parsed = TmLanguageParser.parse(json, id, extensions)) {
                            is TmLanguageParser.ParseResult.Failure -> parsed.message
                            is TmLanguageParser.ParseResult.Success -> {
                                store.import(id, name, extensions, json)
                                store.loadIntoRegistry()
                                null
                            }
                        }
                    }
                if (outcome == null) {
                    toast(getString(R.string.grammar_imported, name))
                } else {
                    toast(getString(R.string.grammar_import_failed) + outcome)
                }
            }
        }

        /** Stable id derived from the display name (documented naming rule). */
        private fun grammarIdFor(name: String): String {
            val stem = name.substringBeforeLast('.')
            val cleaned = stem.replace(Regex("[^a-zA-Z0-9_]"), "_").lowercase()
            return TmLanguageParser.CUSTOM_ID_PREFIX + cleaned.ifEmpty { "grammar" }
        }

        /** Lists imported grammars; tapping one removes it after confirmation. */
        private fun showGrammarManager() {
            val context = requireContext()
            val store = CustomGrammarStore(App.keyValueStore(context))
            val entries = store.entries()
            if (entries.isEmpty()) {
                toast(getString(R.string.grammar_empty))
                return
            }
            val labels =
                entries.map { entry ->
                    "${entry.name}  (${entry.extensions.joinToString(", ")})"
                }
            AlertDialog
                .Builder(context)
                .setTitle(R.string.grammar_manage)
                .setItems(labels.toTypedArray()) { _, which ->
                    val entry = entries[which]
                    AlertDialog
                        .Builder(context)
                        .setTitle(R.string.grammar_remove_title)
                        .setMessage(entry.name)
                        .setPositiveButton(R.string.grammar_remove) { _, _ ->
                            store.remove(entry.id)
                            SyntaxRegistry.clearCustomLanguages()
                            store.loadIntoRegistry()
                            toast(getString(R.string.grammar_removed))
                        }.setNegativeButton(R.string.cancel, null)
                        .show()
                }.setNegativeButton(R.string.cancel, null)
                .show()
        }
    }
}
