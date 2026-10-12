package com.appsventory.digiapps.launchpad4.ui.files

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.appsventory.digiapps.launchpad4.R
import java.io.File

class FileAdapter(
    private var files: List<File>,
    private var viewMode: String,
    private var filterActive: Boolean,
    private val onFileClick: (File) -> Unit
) : RecyclerView.Adapter<FileAdapter.FileViewHolder>() {

    companion object {
        const val VIEW_LIST = "list"
        const val VIEW_GRID = "grid"
    }

    fun updateData(newFiles: List<File>, newViewMode: String, newFilterActive: Boolean) {
        files = newFiles
        viewMode = newViewMode
        filterActive = newFilterActive
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return if (viewMode == VIEW_GRID) 1 else 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val layout = if (viewType == 1) R.layout.item_file_grid else R.layout.item_file_list
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val file = files[position]
        holder.bind(file, filterActive, onFileClick)
    }

    override fun getItemCount(): Int = files.size

    class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: TextView = view.findViewById(R.id.fileIcon)
        private val name: TextView = view.findViewById(R.id.fileName)
        private val meta: TextView = view.findViewById(R.id.fileMeta)

        fun bind(file: File, filterActive: Boolean, onClick: (File) -> Unit) {
            name.text = file.name
            
            icon.text = when {
                file.isDirectory -> "📁"
                file.extension.equals("apk", true) -> "📦"
                else -> "📄"
            }

            if (file.isDirectory) {
                meta.text = "Folder"
            } else {
                val size = formatFileSize(file.length())
                val type = file.extension.uppercase().ifBlank { "File" }
                val parent = if (filterActive) " · ${file.parentFile?.name ?: ""}" else ""
                meta.text = "$type · $size$parent"
            }
            meta.visibility = View.VISIBLE

            itemView.setOnClickListener { onClick(file) }
        }

        private fun formatFileSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return String.format("%.1f KB", kb)
            val mb = kb / 1024.0
            if (mb < 1024) return String.format("%.1f MB", mb)
            return String.format("%.2f GB", mb / 1024.0)
        }
    }
}
