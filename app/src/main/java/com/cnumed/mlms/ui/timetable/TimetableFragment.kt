package com.cnumed.mlms.ui.timetable

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.style.StyleSpan
import android.graphics.Rect
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import com.cnumed.mlms.util.ClassColorPrefs
import com.cnumed.mlms.util.DateUtils
import com.cnumed.mlms.util.ThemeColorManager
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
        applyThemeColor()
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
        val dotTouch = (28 * density).toInt()   // 터치 영역
        val dotSize = (12 * density).toInt()    // 눈에 보이는 원 크기
        val dotInset = (dotTouch - dotSize) / 2
        val titleTopPad = (22 * density).toInt()

        val titleView = TextView(requireContext()).apply {
            text = titleSpannable
            this.textSize = 20f
            maxLines = Int.MAX_VALUE
            // 오른쪽은 색 원(카드 여백 + 원 + 간격)만큼 비워 제목이 원 밑으로 들어가지 않게 한다.
            setPadding(pad, titleTopPad, pad + dotSize + padSmall, padSmall)
            val ta = requireContext().obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
            setTextColor(ta.getColor(0, 0xFF000000.toInt()))
            ta.recycle()
        }

        // 카드 우상단 모서리의 작은 색 원. 평소엔 테마 색, 누르면 보색으로 바뀌면서
        // 이 수업 칸(그 회차 하나)만 보색으로 칠해진다.
        val themeColor = ThemeColorManager.getColor(requireContext())
        val invertedColor = ClassColorPrefs.complementary(themeColor)
        var inverted = ClassColorPrefs.isInverted(
            requireContext(), cls.title, cls.date, cls.startTime
        )

        fun dotDrawable(color: Int): Drawable = InsetDrawable(
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            },
            dotInset
        )

        // 원 중심을 제목 첫 줄의 "글자" 중앙에 맞춘다.
        // textSize/2로 잡으면 글자 위 어센더 여백까지 반으로 나누는 셈이라 약 0.08em(20sp에서 ~2dp) 위로 뜬다.
        // 제목 자체를 재면 디센더('y', 괄호 등) 유무에 따라 팝업마다 원 높이가 달라지므로,
        // 한글·라틴 대문자 기준 글자("가H")의 잉크 높이로 고정한다.
        val fm = titleView.paint.fontMetrics
        val firstBaseline = titleTopPad - fm.top          // includeFontPadding 기본값(true) 기준
        val ink = Rect().also { titleView.paint.getTextBounds("가H", 0, 2, it) }
        val glyphCenter = firstBaseline + (ink.top + ink.bottom) / 2f

        val colorDot = View(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(dotTouch, dotTouch).apply {
                gravity = Gravity.TOP or Gravity.END
                // 터치 영역이 원보다 크므로 그 여백(dotInset)을 빼서
                // "보이는 원"이 카드 컨텐츠 여백선·제목 첫 줄 글자 중앙에 맞도록 한다.
                topMargin = (glyphCenter - dotTouch / 2f).toInt().coerceAtLeast(0)
                marginEnd = pad - dotInset
            }
            background = dotDrawable(if (inverted) invertedColor else themeColor)
            contentDescription = "이 수업 칸 색 반전"
            setOnClickListener {
                inverted = !inverted
                background = dotDrawable(if (inverted) invertedColor else themeColor)
                ClassColorPrefs.setInverted(
                    requireContext(), cls.title, cls.date, cls.startTime, inverted
                )
                _binding?.timetableView?.refreshColors()
                viewModel.refreshWidgetColors()
            }
        }

        val titleBar = FrameLayout(requireContext()).apply {
            addView(titleView)
            addView(colorDot)
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
            .setCustomTitle(titleBar)
            .setView(contentLayout)
            .setPositiveButton("닫기", null)
            .show()
    }

    private fun downloadFile(file: LessonFile) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = viewModel.downloadFile(file)
            if (result.isSuccess) {
                Toast.makeText(requireContext(), result.getOrNull(), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "다운로드 실패: ${result.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyThemeColor() {
        ThemeColorManager.tintHeader(
            binding.headerBar,
            binding.tvWeekRange, binding.btnPrevWeek, binding.btnNextWeek
        )
        ThemeColorManager.applySpinnerColor(binding.swipeRefresh)
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