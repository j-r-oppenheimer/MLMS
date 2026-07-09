package com.cnumed.mlms.util

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import android.widget.CompoundButton
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton

/**
 * 사용자가 설정에서 고른 테마 색(`class_settings`/`block_color`)을 앱 전체 UI에 적용하는 유틸.
 * XML 테마의 colorPrimary 등은 컴파일 타임 고정이라 런타임에 바꿀 수 없으므로,
 * 상단 바·상태바·하단 네비게이션·버튼·스위치 등을 코드에서 직접 틴팅한다.
 */
object ThemeColorManager {

    private const val PREFS = "class_settings"
    private const val KEY = "block_color"
    const val DEFAULT = "#7787A1"

    private const val UNSELECTED = "#9E9E9E"

    /** 현재 테마 색을 반환한다(파싱 실패 시 기본색). */
    fun getColor(context: Context): Int = try {
        Color.parseColor(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, DEFAULT) ?: DEFAULT
        )
    } catch (e: Exception) {
        Color.parseColor(DEFAULT)
    }

    /** 배경색 위에 얹을 콘텐츠(글자·아이콘) 색을 명암 대비에 맞춰 검정/흰색으로 고른다. */
    fun onColorFor(color: Int): Int =
        if (ColorUtils.calculateLuminance(color) > 0.5) Color.parseColor("#212121") else Color.WHITE

    /** 상태바 색을 상단 바(테마 색)와 동일하게 맞추고, 밝기에 따라 아이콘 명암을 조정한다. */
    fun applyStatusBar(activity: Activity, color: Int = getColor(activity)) {
        activity.window.statusBarColor = color
        val lightIcons = ColorUtils.calculateLuminance(color) > 0.5
        WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            .isAppearanceLightStatusBars = lightIcons
    }

    /**
     * 상단 바 배경을 테마 색으로 칠하고, 함께 넘긴 글자/아이콘의 색을 대비에 맞춰 조정한다.
     * @param background 상단 바 컨테이너(또는 배경을 가진 뷰)
     * @param content 상단 바 안의 TextView / ImageView 들
     */
    fun tintHeader(background: View, vararg content: View, color: Int = getColor(background.context)) {
        background.setBackgroundColor(color)
        val onColor = onColorFor(color)
        for (v in content) {
            when (v) {
                is TextView -> v.setTextColor(onColor)
                is ImageView -> v.imageTintList = ColorStateList.valueOf(onColor)
            }
        }
    }

    /** 하단 네비게이션의 선택 항목 색을 테마 색으로 지정한다. */
    fun tintBottomNav(nav: BottomNavigationView, color: Int = getColor(nav.context)) {
        val csl = checkedStateList(color, Color.parseColor(UNSELECTED))
        nav.itemIconTintList = csl
        nav.itemTextColor = csl
    }

    /** 채워진(Filled) 버튼: 배경을 테마 색으로, 글자색은 대비에 맞춰 조정. */
    fun tintFilledButton(btn: MaterialButton, color: Int = getColor(btn.context)) {
        btn.backgroundTintList = ColorStateList.valueOf(color)
        btn.setTextColor(onColorFor(color))
        btn.iconTint = ColorStateList.valueOf(onColorFor(color))
    }

    /** 외곽선(Outlined) 버튼: 글자·외곽선·아이콘을 테마 색으로. */
    fun tintOutlinedButton(btn: MaterialButton, color: Int = getColor(btn.context)) {
        btn.setTextColor(color)
        btn.strokeColor = ColorStateList.valueOf(color)
        btn.iconTint = ColorStateList.valueOf(color)
    }

    /** 라디오 버튼·체크박스 틴트. */
    fun tintCompoundButton(cb: CompoundButton, color: Int = getColor(cb.context)) {
        cb.buttonTintList = checkedStateList(color, Color.parseColor(UNSELECTED))
    }

    /** 스위치(SwitchCompat) 썸/트랙 틴트. */
    fun tintSwitch(sw: SwitchCompat, color: Int = getColor(sw.context)) {
        sw.thumbTintList = checkedStateList(color, Color.parseColor("#FAFAFA"))
        sw.trackTintList = checkedStateList(
            ColorUtils.setAlphaComponent(color, 0x80),
            Color.parseColor("#61000000")
        )
    }

    /** SwipeRefresh 로딩 스피너 색. */
    fun applySpinnerColor(refresh: androidx.swiperefreshlayout.widget.SwipeRefreshLayout,
                          color: Int = getColor(refresh.context)) {
        refresh.setColorSchemeColors(color)
    }

    private fun checkedStateList(checked: Int, unchecked: Int): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(checked, unchecked)
    )
}
