package com.secretarrow.rockedit.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import com.secretarrow.rockedit.R
import com.secretarrow.rockedit.core.App
import com.secretarrow.rockedit.databinding.ActivitySettingsBinding

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
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
        }

        override fun onResume() {
            super.onResume()
            // Apply theme changes immediately when returning to the caller.
            App.settings(requireContext()).applyTheme()
        }
    }
}
