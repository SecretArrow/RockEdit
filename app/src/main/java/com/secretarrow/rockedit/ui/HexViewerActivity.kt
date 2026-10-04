package com.secretarrow.rockedit.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.secretarrow.rockedit.R
import com.secretarrow.rockedit.core.App
import com.secretarrow.rockedit.core.HexDump
import com.secretarrow.rockedit.databinding.ActivityHexViewerBinding
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hex viewer (v0.13.0): read-only byte dump of a document, one monospace line
 * per [HexDump] row (offset + hex + ASCII), rendered through a RecyclerView
 * in the same style as [GrepActivity].
 *
 * Defensive contract:
 * - A missing, blank or invalid [EXTRA_URI] extra shows the error banner and
 *   keeps the empty state; a malformed intent never crashes the activity.
 * - Reading is capped at [HexDump.MAX_BYTES] bytes. Anything larger is
 *   rejected with a "file too large" banner. Documented decision: the dump is
 *   NOT truncated to the cap, because a partial view would silently lie about
 *   the file contents.
 * - SecurityException, FileNotFoundException, IOException, a rejected URI
 *   (NullPointerException from the provider) and a null stream each map to
 *   their own banner message (what failed + why).
 * - Dumping runs off the main thread; the UI thread only renders results.
 * - An empty file renders the "0 byte" state instead of an empty list.
 * - Copying re-uses the already-rendered dump text; a missing clipboard
 *   service or a failed copy surfaces the failure banner, never a crash.
 */
class HexViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHexViewerBinding
    private lateinit var adapter: HexLinesAdapter

    /** Fully rendered dump text for the clipboard; empty while nothing is loaded. */
    private var dumpText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        val settings = App.settings(this)
        if (settings.isBlackTheme()) setTheme(R.style.Theme_RockEdit_Black)
        settings.applyTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityHexViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.hexToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.hexToolbar.setNavigationOnClickListener { finish() }
        supportActionBar?.title = getString(R.string.hex_title)

        adapter = HexLinesAdapter()
        binding.hexLines.layoutManager = LinearLayoutManager(this)
        binding.hexLines.adapter = adapter

        val uri = parseUri(intent.getStringExtra(EXTRA_URI))
        if (uri == null) {
            showBanner(getString(R.string.hex_uri_invalid))
            showEmptyState()
            return
        }
        val name = displayName(uri)
        supportActionBar?.subtitle = name
        lifecycleScope.launch {
            when (val loaded = withContext(Dispatchers.IO) { readBytes(uri) }) {
                is LoadResult.Failed -> {
                    showBanner(getString(R.string.hex_read_failed, loaded.reason))
                    showEmptyState()
                }
                is LoadResult.TooLarge -> {
                    showBanner(getString(R.string.hex_too_large, loaded.size, HexDump.MAX_BYTES))
                    showEmptyState()
                }
                is LoadResult.Success -> render(name, loaded.bytes)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_hex_viewer, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_copy_dump -> {
            copyDump()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    // ------------------------------------------------------------ rendering

    private fun render(name: String, bytes: ByteArray) {
        if (bytes.isEmpty()) {
            supportActionBar?.subtitle = "$name · ${getString(R.string.hex_bytes, 0)}"
            showEmptyState()
            return
        }
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.Default) {
                val lines = HexDump.toDumpLines(bytes)
                val text = if (lines is HexDump.ResultLines.Success) {
                    HexDump.toDumpText(bytes)
                } else {
                    ""
                }
                Rendered(lines, text)
            }
            when (val result = outcome.result) {
                is HexDump.ResultLines.Failure -> {
                    // Cannot normally happen: size is already capped and the
                    // default options are valid. Surfaced, never swallowed.
                    showBanner(getString(R.string.hex_read_failed, result.message))
                    showEmptyState()
                }
                is HexDump.ResultLines.Success -> {
                    binding.hexError.visibility = View.GONE
                    binding.hexEmpty.visibility = View.GONE
                    binding.hexLines.visibility = View.VISIBLE
                    adapter.submitList(result.lines)
                    dumpText = outcome.text
                    val lastOffset = (bytes.size - 1).toString()
                    supportActionBar?.subtitle = name + " · " +
                        getString(R.string.hex_bytes, bytes.size) + " · " +
                        getString(R.string.hex_range, "0", lastOffset)
                }
            }
        }
    }

    private fun showBanner(message: String) {
        binding.hexError.text = message
        binding.hexError.visibility = View.VISIBLE
        binding.hexLines.visibility = View.GONE
        dumpText = ""
    }

    private fun showEmptyState() {
        binding.hexEmpty.visibility = View.VISIBLE
        binding.hexLines.visibility = View.GONE
        dumpText = ""
    }

    // -------------------------------------------------------------- reading

    private sealed interface LoadResult {
        data class Success(val bytes: ByteArray) : LoadResult
        data class TooLarge(val size: Int) : LoadResult
        data class Failed(val reason: String) : LoadResult
    }

    private data class Rendered(
        val result: HexDump.ResultLines,
        val text: String
    )

    /**
     * Parses the intent extra. Accepts only `content://` and `file://` URIs;
     * anything else (null, blank, unparsable, other scheme) is rejected.
     */
    private fun parseUri(raw: String?): Uri? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return try {
            val uri = Uri.parse(raw)
            if (uri.scheme == "content" || uri.scheme == "file") uri else null
        } catch (_: Exception) {
            null
        }
    }

    /** Cosmetic display name; failures degrade to the last path segment. */
    private fun displayName(uri: Uri): String = try {
        DisplayNames.resolve(this, uri)
    } catch (_: Exception) {
        uri.lastPathSegment ?: "file"
    }

    /**
     * Reads up to [HexDump.MAX_BYTES] + 1 bytes so an oversized file is
     * detected without reading it entirely. Every failure mode is mapped to
     * [LoadResult.Failed] with a specific reason; no path returns null.
     */
    private fun readBytes(uri: Uri): LoadResult {
        val stream = try {
            contentResolver.openInputStream(uri)
        } catch (e: SecurityException) {
            return LoadResult.Failed("access denied (SecurityException): ${e.message}")
        } catch (e: FileNotFoundException) {
            return LoadResult.Failed("file not found (FileNotFoundException): ${e.message}")
        } catch (e: IOException) {
            return LoadResult.Failed("I/O error while opening (IOException): ${e.message}")
        } catch (e: NullPointerException) {
            return LoadResult.Failed(
                "provider rejected the URI (NullPointerException): ${e.message}"
            )
        } catch (e: Exception) {
            return LoadResult.Failed("unexpected ${e.javaClass.simpleName}: ${e.message}")
        }
        if (stream == null) {
            return LoadResult.Failed("content provider returned no stream for $uri")
        }
        return try {
            stream.use { input ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                var read = input.read(chunk)
                while (read >= 0) {
                    buffer.write(chunk, 0, read)
                    if (buffer.size() > HexDump.MAX_BYTES) {
                        return LoadResult.TooLarge(buffer.size())
                    }
                    read = input.read(chunk)
                }
                LoadResult.Success(buffer.toByteArray())
            }
        } catch (e: SecurityException) {
            LoadResult.Failed("access revoked while reading (SecurityException): ${e.message}")
        } catch (e: IOException) {
            LoadResult.Failed("I/O error while reading (IOException): ${e.message}")
        } catch (e: Exception) {
            LoadResult.Failed("unexpected ${e.javaClass.simpleName} while reading: ${e.message}")
        }
    }

    // ------------------------------------------------------------ clipboard

    private fun copyDump() {
        if (dumpText.isEmpty()) {
            Toast.makeText(this, R.string.hex_empty_file, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            showBanner(getString(R.string.hex_read_failed, "clipboard service unavailable"))
            return
        }
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("hex dump", dumpText))
            Toast.makeText(this, R.string.hex_copied, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            showBanner(getString(R.string.hex_read_failed, "copy failed: ${e.message}"))
        }
    }

    companion object {
        private const val EXTRA_URI = "rockedit.extra.HEX_URI"

        fun createIntent(context: Context, uri: Uri): Intent =
            Intent(context, HexViewerActivity::class.java)
                .putExtra(EXTRA_URI, uri.toString())
    }
}

/**
 * Flat list of dump rows; mirrors the GrepActivity adapter style with a
 * DiffUtil-backed ListAdapter so re-submits stay cheap for 65k+ lines.
 */
class HexLinesAdapter : ListAdapter<HexDump.DumpLine, HexLinesAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_hex_line, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {

        private val text: TextView = view.findViewById(R.id.hex_line_text)

        fun bind(line: HexDump.DumpLine) {
            text.text = line.offset.toString(16).padStart(8, '0') + "  " +
                line.hexText + "  " + line.asciiText
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<HexDump.DumpLine>() {
            override fun areItemsTheSame(
                oldItem: HexDump.DumpLine,
                newItem: HexDump.DumpLine
            ): Boolean = oldItem.offset == newItem.offset

            override fun areContentsTheSame(
                oldItem: HexDump.DumpLine,
                newItem: HexDump.DumpLine
            ): Boolean = oldItem == newItem
        }
    }
}
