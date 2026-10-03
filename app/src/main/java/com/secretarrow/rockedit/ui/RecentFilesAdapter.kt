package com.secretarrow.rockedit.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.secretarrow.rockedit.R
import com.secretarrow.rockedit.core.RecentFile
import com.secretarrow.rockedit.core.FileNames
import com.secretarrow.rockedit.databinding.ItemFileBinding
import java.text.DateFormat
import java.util.Date

/** Adapter for the recent files list on the main screen. */
class RecentFilesAdapter(
    private val onClick: (RecentFile) -> Unit,
    private val onLongClick: (RecentFile) -> Unit
) : ListAdapter<RecentFile, RecentFilesAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemFileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RecentFile) {
            val context = binding.root.context
            binding.fileName.text = item.name
            binding.fileMeta.text = context.getString(
                R.string.file_meta,
                FileNames.split(item.name).second.ifEmpty { context.getString(R.string.file_no_ext) },
                DateFormat.getDateTimeInstance().format(Date(item.lastOpened))
            )
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<RecentFile>() {
            override fun areItemsTheSame(oldItem: RecentFile, newItem: RecentFile) =
                oldItem.uri == newItem.uri

            override fun areContentsTheSame(oldItem: RecentFile, newItem: RecentFile) =
                oldItem == newItem
        }
    }
}
