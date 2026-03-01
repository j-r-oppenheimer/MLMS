package com.cnumed.mlms.ui.notice

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cnumed.mlms.databinding.ItemNoticeBinding
import com.cnumed.mlms.domain.model.Notice

class NoticeAdapter(
    private val onClick: (Notice) -> Unit
) : ListAdapter<Notice, NoticeAdapter.ViewHolder>(DiffCallback()) {

    inner class ViewHolder(val binding: ItemNoticeBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemNoticeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val notice = getItem(position)
        holder.binding.apply {
            tvTitle.text = notice.title
            tvDate.text = notice.date.toString()
            // 조회수 TextView 숨김 처리
            tvViewCount.visibility = View.GONE
            tvTitle.alpha = if (notice.isRead) 0.5f else 1.0f
            root.setOnClickListener { onClick(notice) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Notice>() {
        override fun areItemsTheSame(a: Notice, b: Notice) = a.id == b.id
        override fun areContentsTheSame(a: Notice, b: Notice) = a == b
    }
}