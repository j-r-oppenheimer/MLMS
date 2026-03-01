package com.cnumed.mlms.ui.timetable

import android.os.Bundle
import android.text.SpannableString
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cnumed.mlms.databinding.FragmentTimetableBinding
import com.cnumed.mlms.util.DateUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class TimetableFragment : Fragment() {

    private var _binding: FragmentTimetableBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TimetableViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTimetableBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupNavigation()
        observeState()
        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }
        binding.timetableView.setOnClassClickListener { cls ->
            val msg = buildString {
                if (cls.professor.isNotEmpty()) appendLine("교수: ${cls.professor}")
                append("시간: ${cls.startTime} ~ ${cls.endTime}")
            }

            // 과목명을 볼드로 만들기
            val titleSpannable = SpannableString(cls.title)
            titleSpannable.setSpan(
                StyleSpan(Typeface.BOLD),
                0,
                cls.title.length,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            // 제목이 길어도 잘리지 않도록 커스텀 타이틀 뷰 사용
            val density = resources.displayMetrics.density
            val titleView = android.widget.TextView(requireContext()).apply {
                text = titleSpannable
                textSize = 20f
                maxLines = Int.MAX_VALUE
                setPadding(
                    (24 * density).toInt(),
                    (22 * density).toInt(),
                    (24 * density).toInt(),
                    (8 * density).toInt()
                )
                val ta = requireContext().obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
                setTextColor(ta.getColor(0, 0xFF000000.toInt()))
                ta.recycle()
            }

            MaterialAlertDialogBuilder(requireContext())
                .setCustomTitle(titleView)
                .setMessage(msg)
                .setPositiveButton("닫기", null)
                .show()
        }
    }

    private fun setupNavigation() {
        binding.btnPrevWeek.setOnClickListener { viewModel.previousWeek() }
        binding.btnNextWeek.setOnClickListener { viewModel.nextWeek() }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.tvWeekRange.text = DateUtils.formatWeekRange(state.weekStart)
                    binding.swipeRefresh.isRefreshing = state.isLoading
                    binding.tvOfflineBadge.visibility =
                        if (state.isOffline) View.VISIBLE else View.GONE
                    binding.timetableView.setData(state.classes, state.weekStart)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refilter()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}