package com.secretarrow.rockedit.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.secretarrow.rockedit.R
import com.secretarrow.rockedit.core.App
import com.secretarrow.rockedit.core.EncodingDetector
import com.secretarrow.rockedit.core.FolderGrep
import com.secretarrow.rockedit.databinding.ActivityGrepBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Multi-file search over a SAF folder tree (v0.11.0 "grep in folder").
 *
 * Defensive contract:
 * - Missing/invalid tree URI finishes gracefully with a toast, never a crash.
 * - Blank queries are rejected at the button with a localized toast.
 * - Traversal is bounded: [MAX_DEPTH] levels, [MAX_FILES] documents, and
 *   [MAX_FILE_BYTES] read per file; over-budget content is skipped safely.
 * - The whole pipeline runs off the main thread; UI only renders the result.
 * - Traversal/provider failures (SecurityException, revoked grants, dead
 *   documents) are absorbed per file: a broken child never aborts the search.
 *
 * Documented assumptions:
 * - Files are decoded as UTF-8 lenient (invalid bytes become replacement
 *   chars); searching non-UTF-8 documents byte-exactly is out of scope v1.
 * - Files larger than [MAX_FILE_BYTES] are searched in their first
 *   [MAX_FILE_BYTES] only — noted in the summary when it happens.
 */
class GrepActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGrepBinding
    private lateinit var resultsAdapter: GrepResultsAdapter
    private var treeUri: Uri? = null
    private var searching = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val settings = App.settings(this)
        if (settings.isBlackTheme()) setTheme(R.style.Theme_RockEdit_Black)
        settings.applyTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityGrepBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.grepToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.grepToolbar.setNavigationOnClickListener { finish() }
        supportActionBar?.title = getString(R.string.grep_title)

        treeUri = intent.getStringExtra(EXTRA_TREE_URI)?.let {
            try { Uri.parse(it) } catch (_: Exception) { null }
        }
        if (treeUri == null) {
            Toast.makeText(this, R.string.grep_no_folder, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        resultsAdapter = GrepResultsAdapter { hit ->
            openHit(hit)
        }
        binding.grepResults.layoutManager = LinearLayoutManager(this)
        binding.grepResults.adapter = resultsAdapter

        binding.grepSearch.setOnClickListener { runSearch() }
        binding.grepQuery.requestFocus()
    }

    private fun runSearch() {
        if (searching) return
        val uri = treeUri ?: return
        val query = binding.grepQuery.text?.toString().orEmpty()
        if (query.isBlank()) {
            Toast.makeText(this, R.string.grep_empty_query, Toast.LENGTH_SHORT).show()
            return
        }
        val options = FolderGrep.GrepOptions(
            isRegex = binding.grepRegex.isChecked,
            ignoreCase = binding.grepCase.isChecked
        )
        searching = true
        binding.grepProgress.visibility = View.VISIBLE
        binding.grepEmpty.visibility = View.GONE
        binding.grepSummary.text = ""
        resultsAdapter.submitList(emptyList())

        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.Default) {
                val files = collectFiles(uri)
                FolderGrep.run(files, query, options)
            }
            searching = false
            binding.grepProgress.visibility = View.GONE
            render(outcome)
        }
    }

    private fun render(outcome: FolderGrep.GrepOutcome) {
        when (outcome) {
            is FolderGrep.GrepOutcome.Failure -> {
                Toast.makeText(
                    this,
                    getString(R.string.grep_error, outcome.error.message),
                    Toast.LENGTH_LONG
                ).show()
            }
            is FolderGrep.GrepOutcome.Done -> {
                val summary = outcome.summary
                resultsAdapter.submitList(summary.hits)
                binding.grepSummary.text = getString(
                    R.string.grep_summary,
                    summary.hits.size,
                    summary.filesWithHits,
                    summary.filesScanned,
                    summary.skippedBinary
                ) + if (summary.truncatedMatches) " " + getString(R.string.grep_truncated) else ""
                val empty = summary.hits.isEmpty()
                binding.grepEmpty.visibility = if (empty) View.VISIBLE else View.GONE
                binding.grepResults.visibility = if (empty) View.GONE else View.VISIBLE
            }
        }
    }

    private fun openHit(hit: FolderGrep.GrepHit) {
        val root = treeUri ?: return
        val target = resolveDocument(root, hit.path)
        if (target == null) {
            Toast.makeText(this, R.string.grep_file_gone, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(EditorActivity.createIntent(this, target))
    }

    // ------------------------------------------------------------- traversal

    private suspend fun collectFiles(root: Uri): List<FolderGrep.GrepFile> =
        withContext(Dispatchers.IO) {
            collected = 0
            collectDir(root, "", depth = 0)
        }

    private fun collectDir(root: Uri, relative: String, depth: Int): List<FolderGrep.GrepFile> {
        if (depth > MAX_DEPTH || collected >= MAX_FILES) return emptyList()
        val dir = try {
            DocumentFile.fromTreeUri(this, root) ?: return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
        val out = ArrayList<FolderGrep.GrepFile>()
        val children = try {
            dir.listFiles()
        } catch (_: Exception) {
            return emptyList()
        }
        for (child in children) {
            if (collected >= MAX_FILES) break
            val name = child.name ?: continue
            if (child.isDirectory) {
                out.addAll(collectDir(child.uri, joinPath(relative, name), depth + 1))
            } else {
                collected++
                val content = readText(child.uri) ?: continue
                out.add(FolderGrep.GrepFile(joinPath(relative, name), content))
            }
        }
        return out
    }

    /** Resolves a stored relative path back to a document URI under the tree. */
    private fun resolveDocument(root: Uri, relativePath: String): Uri? {
        return try {
            var dir = DocumentFile.fromTreeUri(this, root) ?: return null
            val segments = relativePath.split('/').filter { it.isNotEmpty() }
            if (segments.isEmpty()) return null
            for (i in 0 until segments.size - 1) {
                dir = dir.findFile(segments[i]) ?: return null
            }
            dir.findFile(segments.last())?.uri
        } catch (_: Exception) {
            null
        }
    }

    private fun readText(uri: Uri): String? = try {
        contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            var read = stream.read(chunk)
            while (read >= 0 && buffer.size() < MAX_FILE_BYTES) {
                buffer.write(chunk, 0, read)
                read = stream.read(chunk)
            }
            EncodingDetector.decode(buffer.toByteArray(), EncodingDetector.DEFAULT_CHARSET)
        }
    } catch (_: Exception) {
        null // unreadable document: skip silently, counted nowhere on purpose
    }

    private fun joinPath(parent: String, name: String): String =
        if (parent.isEmpty()) name else "$parent/$name"

    private var collected = 0

    companion object {
        private const val EXTRA_TREE_URI = "rockedit.extra.TREE_URI"
        private const val MAX_DEPTH = 8
        private const val MAX_FILES = 400
        private const val MAX_FILE_BYTES = 1_048_576

        fun createIntent(context: Context, treeUri: Uri): Intent =
            Intent(context, GrepActivity::class.java)
                .putExtra(EXTRA_TREE_URI, treeUri.toString())
    }
}

/** Flat list of hits: "path:line:col" title + trimmed line preview. */
class GrepResultsAdapter(
    private val onClick: (FolderGrep.GrepHit) -> Unit
) : ListAdapter<FolderGrep.GrepHit, GrepResultsAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_grep_result, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.grep_hit_title)
        private val preview: TextView = view.findViewById(R.id.grep_hit_preview)

        fun bind(hit: FolderGrep.GrepHit) {
            title.text = "${hit.path}:${hit.lineNumber}:${hit.column}"
            preview.text = hit.preview
            itemView.setOnClickListener { onClick(hit) }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<FolderGrep.GrepHit>() {
            override fun areItemsTheSame(oldItem: FolderGrep.GrepHit, newItem: FolderGrep.GrepHit) =
                oldItem.path == newItem.path && oldItem.lineNumber == newItem.lineNumber &&
                    oldItem.column == newItem.column

            override fun areContentsTheSame(oldItem: FolderGrep.GrepHit, newItem: FolderGrep.GrepHit) =
                oldItem == newItem
        }
    }
}
