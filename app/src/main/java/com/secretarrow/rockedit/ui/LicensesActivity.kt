package com.secretarrow.rockedit.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.secretarrow.rockedit.R
import com.secretarrow.rockedit.core.App
import com.secretarrow.rockedit.databinding.ActivitySettingsBinding

/**
 * Open-source licenses screen (FOSS attribution obligation). Lists every
 * third-party library shipped with Rock Edit together with its license.
 */
class LicensesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val settings = App.settings(this)
        if (settings.isBlackTheme()) setTheme(R.style.Theme_RockEdit_Black)
        settings.applyTheme()
        super.onCreate(savedInstanceState)
        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.licenses_title)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.settingsContainer.removeAllViews()
        val body =
            TextView(this).apply {
                text = buildLicensesText()
                textSize = 14f
                setPadding(dp(20), dp(16), dp(20), dp(24))
                setTextIsSelectable(true)
            }
        val scroll =
            android.widget.ScrollView(this).apply {
                layoutParams =
                    android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                addView(body)
            }
        binding.settingsContainer.addView(scroll)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildLicensesText(): String =
        buildString {
            append(getString(R.string.licenses_intro)).append("\n\n")
            for ((name, license, url) in LIBRARIES) {
                append("• ").append(name).append('\n')
                append("  ")
                    .append(license)
                    .append(" — ")
                    .append(url)
                    .append("\n\n")
            }
            append(getString(R.string.licenses_thanks))
        }

    companion object {
        /** Third-party libraries and their permissive licenses. */
        val LIBRARIES: List<Triple<String, String, String>> =
            listOf(
                Triple(
                    "Jetpack (core-ktx, appcompat, RecyclerView, Preference, Activity, Lifecycle, DocumentFile)",
                    "Apache License 2.0",
                    "https://developer.android.com/jetpack",
                ),
                Triple(
                    "Material Components for Android",
                    "Apache License 2.0",
                    "https://github.com/material-components/material-components-android",
                ),
                Triple("Kotlin & Coroutines", "Apache License 2.0", "https://github.com/JetBrains/kotlin"),
                Triple("juniversalchardet", "MPL 1.1 / GPL (ALv2 compatible)", "https://github.com/albfernandez/juniversalchardet"),
            )
    }
}
