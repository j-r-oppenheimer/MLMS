package com.cnumed.mlms.ui.timetable

import android.os.Bundle
import android.text.SpannableString
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cnumed.mlms.databinding.FragmentTimetableBinding
import com.cnumed.mlms.domain.model.ClassItem
import com.cnumed.mlms.domain.model.LessonFile
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
            showClassDetailDialog(cls)
        }
    }

    private fun showClassDetailDialog(cls: ClassItem) {
        val density = resources.displayMetrics.density
        val pad = (24 * density).toInt()
        val padSmall = (8 * density).toInt()
        val textSize = 16f

        // 타이틀 (볼드)
        val titleSpannable = SpannableString(cls.title)
        titleSpannable.setSpan(
            StyleSpan(Typeface.BOLD), 0, cls.title.length,
            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        val titleView = TextView(requireContext()).apply {
            text = titleSpannable
            this.textSize = 20f
            maxLines = Int.MAX_VALUE
            setPadding(pad, (22 * density).toInt(), pad, padSmall)
            val ta = requireContext().obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
            setTextColor(ta.getColor(0, 0xFF000000.toInt()))
            ta.recycle()
        }

        // 컨텐츠
        val contentLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, padSmall, pad, (4 * density).toInt())
        }

        val hasSeq = cls.lpSeq != null && cls.currSeq != null && cls.acaSeq != null

        // 과목 (서버에서 로드)
        val subjectView = TextView(requireContext()).apply { this.textSize = textSize }
        // 교수
        val professorView = if (cls.professor.isNotEmpty()) {
            TextView(requireContext()).apply {
                text = "교수: ${cls.professor}"
                this.textSize = textSize
            }
        } else null
        // 시간
        val timeView = TextView(requireContext()).apply {
            text = "시간: ${cls.startTime} ~ ${cls.endTime}"
            this.textSize = textSize
        }
        // 강의실 (서버에서 로드)
        val roomView = TextView(requireContext()).apply { this.textSize = textSize }

        if (hasSeq) {
            subjectView.text = "과목: 로딩 중..."
            roomView.text = "강의실: 로딩 중..."
        } else {
            subjectView.text = "과목: ${cls.title}"
            roomView.text = "강의실: -"
        }

        contentLayout.addView(subjectView)
        professorView?.let { contentLayout.addView(it) }
        contentLayout.addView(timeView)
        contentLayout.addView(roomView)

        // 파일 목록 컨테이너 (로딩 후 채움)
        val filesContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        contentLayout.addView(filesContainer)

        if (hasSeq) {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val result = kotlinx.coroutines.withTimeoutOrNull(15_000) {
                        viewModel.fetchLessonDetail(cls.lpSeq!!, cls.currSeq!!, cls.acaSeq!!)
                    }
                    val detail = result?.getOrNull()
                    subjectView.text = if (!detail?.subject.isNullOrEmpty()) "과목: ${detail!!.subject}" else "과목: ${cls.title}"
                    roomView.text = if (!detail?.room.isNullOrEmpty()) "강의실: ${detail!!.room}" else "강의실: -"

                    if (detail != null && detail.files.isNotEmpty()) {
                        // 구분선
                        filesContainer.addView(View(requireContext()).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()
                            ).apply { topMargin = (10 * density).toInt(); bottomMargin = (6 * density).toInt() }
                            setBackgroundColor(0x20000000)
                        })
                        for (file in detail.files) {
                            filesContainer.addView(TextView(requireContext()).apply {
                                text = file.fileName
                                this.textSize = textSize
                                setTextColor(0xFF1976D2.toInt())
                                setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
                                setOnClickListener { downloadFile(file) }
                            })
                        }
                    }
                } catch (_: Exception) {
                    subjectView.text = "과목: ${cls.title}"
                    roomView.text = "강의실: -"
                }
            }
        }

        MaterialAlertDialogBuilder(requireContext(), com.cnumed.mlms.R.style.RoundedDialog)
            .setCustomTitle(titleView)
            .setView(contentLayout)
            .setPositiveButton("닫기", null)
            .show()
    }

    private fun downloadFile(file: LessonFile) {
        Toast.makeText(requireContext(), "${file.fileName} 다운로드 중...", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = viewModel.downloadFile(file)
            if (result.isSuccess) {
                Toast.makeText(requireContext(), "${file.fileName} 다운로드 완료", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "다운로드 실패: ${result.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
            }
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