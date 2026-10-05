package com.secretarrow.rockedit.ui

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.secretarrow.rockedit.R
import com.secretarrow.rockedit.core.App
import com.secretarrow.rockedit.databinding.ActivitySettingsBinding

/**
 * In-app help / manual (roadmap: dokumentasi pengguna). Static content,
 * available offline, no network access.
 */
class HelpActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val settings = App.settings(this)
        if (settings.isBlackTheme()) setTheme(R.style.Theme_RockEdit_Black)
        settings.applyTheme()
        super.onCreate(savedInstanceState)
        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.install(this, binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.help_title)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.settingsContainer.removeAllViews()
        val body =
            TextView(this).apply {
                text = buildHelpText()
                textSize = 15f
                setPadding(dp(20), dp(16), dp(20), dp(24))
                setTextIsSelectable(true)
            }
        val scroll =
            ScrollView(this).apply {
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

    private fun buildHelpText(): String =
        buildString {
            append(getString(R.string.help_intro)).append("\n\n")
            for ((question, answer) in FAQ) {
                append("• ").append(question).append('\n')
                append(answer).append("\n\n")
            }
            append(getString(R.string.help_privacy))
        }

    /** Frequently asked questions, drawn from the product manual. */
    val FAQ: List<Pair<Int, Int>> =
        listOf(
            R.string.help_q1 to R.string.help_a1,
            R.string.help_q2 to R.string.help_a2,
            R.string.help_q3 to R.string.help_a3,
            R.string.help_q4 to R.string.help_a4,
            R.string.help_q5 to R.string.help_a5,
            R.string.help_q6 to R.string.help_a6,
            R.string.help_q7 to R.string.help_a7,
            R.string.help_q8 to R.string.help_a8,
        )
}
