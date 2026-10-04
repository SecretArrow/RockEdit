package com.secretarrow.rockedit.ui

import android.content.res.Configuration
import android.os.Bundle
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.secretarrow.rockedit.R
import com.secretarrow.rockedit.core.App
import com.secretarrow.rockedit.core.FileNames
import com.secretarrow.rockedit.core.MarkdownRenderer
import com.secretarrow.rockedit.core.SettingsRepository
import com.secretarrow.rockedit.databinding.ActivitySettingsBinding

/**
 * Live preview for HTML and Markdown documents (roadmap: preview). The
 * rendered content never leaves the device; JavaScript is left enabled so
 * the author's HTML behaves like in a browser.
 */
class PreviewActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val settings = App.settings(this)
        if (settings.isBlackTheme()) setTheme(R.style.Theme_RockEdit_Black)
        settings.applyTheme()
        super.onCreate(savedInstanceState)
        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        supportActionBar?.title = getString(R.string.preview)

        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.preview)
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME)

        binding.settingsContainer.removeAllViews()
        val webView =
            WebView(this).apply {
                layoutParams =
                    android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                this.settings.javaScriptEnabled = true
            }
        binding.settingsContainer.addView(webView)

        val html =
            when {
                fileName != null && looksLikeMarkdown(fileName) ->
                    MarkdownRenderer.renderDocument(text, dark = isDarkTheme(settings), title = title)
                fileName != null && looksLikeHtml(fileName) -> text
                else ->
                    "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/></head><body><pre>" +
                        escapeForPre(text) + "</pre></body></html>"
            }
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    private fun isDarkTheme(settings: SettingsRepository): Boolean =
        when (settings.theme) {
            SettingsRepository.THEME_DARK, SettingsRepository.THEME_BLACK -> true
            SettingsRepository.THEME_LIGHT -> false
            else -> {
                val mask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                mask == Configuration.UI_MODE_NIGHT_YES
            }
        }

    private fun looksLikeMarkdown(name: String): Boolean = FileNames.split(name).second.lowercase() in setOf("md", "markdown")

    private fun looksLikeHtml(name: String): Boolean = FileNames.split(name).second.lowercase() in setOf("html", "htm")

    private fun escapeForPre(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")

    companion object {
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_FILE_NAME = "extra_file_name"
    }
}
