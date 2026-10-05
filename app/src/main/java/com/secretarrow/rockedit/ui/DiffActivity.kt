package com.secretarrow.rockedit.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.secretarrow.rockedit.R
import com.secretarrow.rockedit.core.App
import com.secretarrow.rockedit.core.DiffEngine
import com.secretarrow.rockedit.databinding.ActivityDiffBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Compare screen (v0.12.0): current document vs a picked file, rendered as a
 * line diff with delete/insert highlighting.
 *
 * Defensive contract:
 * - Missing/short-lived cache files finish gracefully with a toast, never a
 *   crash; both files are always deleted after reading (finally).
 * - The diff runs off the main thread; the UI only renders results.
 * - Engine failures (INPUT_TOO_LARGE) map to a localized toast + finish.
 * - Rendering is capped at [MAX_ROWS] rows with a truncation note, and runs
 *   of unchanged lines collapse to a "N unchanged lines" gap row so huge
 *   similar files stay cheap to display.
 *
 * Documented assumptions:
 * - The document side is passed through the same cache mechanism the editor
 *   already trusts; the picked side was decoded by the editor (UTF-8
 *   lenient) and binary content was rejected before this screen starts.
 */
class DiffActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDiffBinding
    private lateinit var adapter: DiffRowsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        val settings = App.settings(this)
        if (settings.isBlackTheme()) setTheme(R.style.Theme_RockEdit_Black)
        settings.applyTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityDiffBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.install(this, binding.root)

        setSupportActionBar(binding.diffToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.diffToolbar.setNavigationOnClickListener { finish() }
        supportActionBar?.title = getString(R.string.diff_title)

        adapter = DiffRowsAdapter()
        binding.diffResults.layoutManager = LinearLayoutManager(this)
        binding.diffResults.adapter = adapter

        val oldPath = intent.getStringExtra(EXTRA_OLD_PATH)
        val newPath = intent.getStringExtra(EXTRA_NEW_PATH)
        if (oldPath.isNullOrBlank() || newPath.isNullOrBlank()) {
            Toast.makeText(this, R.string.diff_error_read, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        binding.diffSummary.text = getString(R.string.diff_running)
        lifecycleScope.launch {
            val sides = withContext(Dispatchers.IO) { readSides(oldPath, newPath) }
            if (sides == null) {
                Toast.makeText(this@DiffActivity, R.string.diff_error_read, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            runDiff(sides.first, sides.second)
        }
    }

    private suspend fun runDiff(
        oldText: String,
        newText: String,
    ) {
        val outcome = withContext(Dispatchers.Default) { DiffEngine.diff(oldText, newText) }
        when (outcome) {
            is DiffEngine.DiffOutcome.Failure -> {
                val message =
                    if (outcome.code == DiffEngine.DiffErrorCode.INPUT_TOO_LARGE) {
                        getString(R.string.diff_too_large)
                    } else {
                        getString(R.string.diff_error_generic, outcome.message)
                    }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                finish()
            }
            is DiffEngine.DiffOutcome.Done -> render(outcome)
        }
    }

    private fun render(outcome: DiffEngine.DiffOutcome.Done) {
        val stats = outcome.stats
        val summary =
            getString(R.string.diff_summary, stats.addedLines, stats.removedLines, stats.unchangedLines) +
                if (outcome.fellBack) getString(R.string.diff_fell_back) else ""
        binding.diffSummary.text = summary
        if (!stats.hasChanges) {
            binding.diffResults.visibility = View.GONE
            binding.diffEmpty.visibility = View.VISIBLE
            return
        }
        val (rows, truncated) = buildRows(outcome.ops)
        adapter.submitList(rows)
        if (truncated) {
            binding.diffSummary.text = summary + getString(R.string.diff_truncated, MAX_ROWS)
        }
    }

    // -------------------------------------------------------------- rows

    private fun buildRows(ops: List<DiffEngine.DiffOp>): Pair<List<DiffRow>, Boolean> {
        val rows = ArrayList<DiffRow>()
        var truncated = false
        for (op in ops) {
            val lines = op.text.split('\n')
            when (op.kind) {
                DiffEngine.DiffKind.EQUAL -> {
                    if (lines.size > CONTEXT * 2 + 2) {
                        for (i in 0 until CONTEXT) {
                            rows.add(DiffRow.Change(op.oldStart + i + 1, " " + lines[i]))
                        }
                        rows.add(DiffRow.Gap(lines.size - CONTEXT * 2))
                        val tailStart = lines.size - CONTEXT
                        for (i in tailStart until lines.size) {
                            rows.add(DiffRow.Change(op.oldStart + i + 1, " " + lines[i]))
                        }
                    } else {
                        for ((i, line) in lines.withIndex()) {
                            rows.add(DiffRow.Change(op.oldStart + i + 1, " " + line))
                        }
                    }
                }
                DiffEngine.DiffKind.DELETE ->
                    for ((i, line) in lines.withIndex()) {
                        rows.add(DiffRow.Change(op.oldStart + i + 1, "-" + line))
                    }
                DiffEngine.DiffKind.INSERT ->
                    for ((i, line) in lines.withIndex()) {
                        rows.add(DiffRow.Change(op.newStart + i + 1, "+" + line))
                    }
            }
            if (rows.size >= MAX_ROWS) {
                truncated = true
                break
            }
        }
        return Pair(rows, truncated)
    }

    /** Reads both cache sides and always cleans them up afterwards. */
    private fun readSides(
        oldPath: String,
        newPath: String,
    ): Pair<String, String>? {
        val oldFile = File(oldPath)
        val newFile = File(newPath)
        return try {
            Pair(oldFile.readText(Charsets.UTF_8), newFile.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        } finally {
            try {
                oldFile.delete()
            } catch (_: Exception) {
                // Best-effort cleanup; the cache dir is app-private anyway.
            }
            try {
                newFile.delete()
            } catch (_: Exception) {
                // Best-effort cleanup.
            }
        }
    }

    companion object {
        private const val EXTRA_OLD_PATH = "rockedit.extra.DIFF_OLD_PATH"
        private const val EXTRA_NEW_PATH = "rockedit.extra.DIFF_NEW_PATH"
        private const val CONTEXT = 3
        private const val MAX_ROWS = 2000

        fun createIntent(
            context: Context,
            oldPath: String,
            newPath: String,
        ): Intent =
            Intent(context, DiffActivity::class.java)
                .putExtra(EXTRA_OLD_PATH, oldPath)
                .putExtra(EXTRA_NEW_PATH, newPath)
    }
}

/** One renderable row: a numbered change line or an unchanged-lines gap. */
sealed interface DiffRow {
    data class Change(
        val number: Int,
        val text: String,
    ) : DiffRow

    data class Gap(
        val count: Int,
    ) : DiffRow
}

class DiffRowsAdapter : ListAdapter<DiffRow, DiffRowViewHolder>(DIFF) {
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): DiffRowViewHolder {
        val view =
            LayoutInflater
                .from(parent.context)
                .inflate(R.layout.item_diff_row, parent, false)
        return DiffRowViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: DiffRowViewHolder,
        position: Int,
    ) {
        holder.bind(getItem(position))
    }

    companion object {
        val DIFF =
            object : DiffUtil.ItemCallback<DiffRow>() {
                override fun areItemsTheSame(
                    oldItem: DiffRow,
                    newItem: DiffRow,
                ): Boolean = oldItem == newItem

                override fun areContentsTheSame(
                    oldItem: DiffRow,
                    newItem: DiffRow,
                ): Boolean = oldItem == newItem
            }
    }
}

class DiffRowViewHolder(
    view: View,
) : RecyclerView.ViewHolder(view) {
    private val number: TextView = view.findViewById(R.id.diff_row_number)
    private val text: TextView = view.findViewById(R.id.diff_row_text)

    fun bind(row: DiffRow) {
        val context = itemView.context
        when (row) {
            is DiffRow.Change -> {
                number.text = row.number.toString()
                text.text = row.text
                number.setTextColor(ContextCompat.getColor(context, R.color.diff_row_text))
                text.setTextColor(ContextCompat.getColor(context, R.color.diff_row_text))
                val background =
                    if (row.text.startsWith("+")) {
                        R.color.diff_insert_bg
                    } else if (row.text.startsWith("-")) {
                        R.color.diff_delete_bg
                    } else {
                        android.R.color.transparent
                    }
                itemView.setBackgroundColor(ContextCompat.getColor(context, background))
            }
            is DiffRow.Gap -> {
                number.text = "⋯"
                text.text = context.getString(R.string.diff_gap_rows, row.count)
                text.setTextColor(ContextCompat.getColor(context, R.color.diff_gap_fg))
                itemView.setBackgroundColor(ContextCompat.getColor(context, android.R.color.transparent))
            }
        }
    }
}
