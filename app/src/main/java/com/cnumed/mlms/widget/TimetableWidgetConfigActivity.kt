package com.cnumed.mlms.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton
import com.cnumed.mlms.R

class TimetableWidgetConfigActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContentView(R.layout.activity_widget_config)

        val prefs       = getSharedPreferences(TimetableWidget.PREFS_WIDGET, Context.MODE_PRIVATE)
        val savedAlpha  = prefs.getInt(TimetableWidget.KEY_BG_ALPHA, 255)
        val savedBlockAlpha = prefs.getInt(TimetableWidget.KEY_BLOCK_ALPHA, 255) // 박스 투명도 불러오기
        val savedDark   = prefs.getBoolean(TimetableWidget.KEY_DARK_MODE, false)
        val savedTextSize = prefs.getInt(TimetableWidget.KEY_TEXT_SIZE, 100)

        val seekbarAlpha  = findViewById<SeekBar>(R.id.seekbar_alpha)
        val tvAlphaValue  = findViewById<TextView>(R.id.tv_alpha_value)

        val seekbarBlockAlpha = findViewById<SeekBar>(R.id.seekbar_block_alpha)
        val tvBlockAlphaValue = findViewById<TextView>(R.id.tv_block_alpha_value)

        val seekbarTextSize = findViewById<SeekBar>(R.id.seekbar_text_size)
        val tvTextSizeValue = findViewById<TextView>(R.id.tv_text_size_value)
        val switchDark    = findViewById<SwitchCompat>(R.id.switch_dark_mode)
        val btnApply      = findViewById<MaterialButton>(R.id.btn_apply)

        seekbarAlpha.max      = 255
        seekbarAlpha.progress = savedAlpha
        tvAlphaValue.text     = "${savedAlpha * 100 / 255}%"

        seekbarBlockAlpha.max = 255
        seekbarBlockAlpha.progress = savedBlockAlpha
        tvBlockAlphaValue.text = "${savedBlockAlpha * 100 / 255}%"

        seekbarTextSize.max   = 100
        seekbarTextSize.progress = savedTextSize - 50
        tvTextSizeValue.text  = "$savedTextSize%"

        switchDark.isChecked  = savedDark

        seekbarAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                tvAlphaValue.text = "${progress * 100 / 255}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        seekbarBlockAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                tvBlockAlphaValue.text = "${progress * 100 / 255}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        seekbarTextSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                tvTextSizeValue.text = "${progress + 50}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        btnApply.setOnClickListener {
            val alpha = seekbarAlpha.progress
            val blockAlpha = seekbarBlockAlpha.progress
            val dark  = switchDark.isChecked
            val textSizeValue = seekbarTextSize.progress + 50

            prefs.edit()
                .putInt(TimetableWidget.KEY_BG_ALPHA, alpha)
                .putInt(TimetableWidget.KEY_BLOCK_ALPHA, blockAlpha) // 박스 투명도 저장
                .putBoolean(TimetableWidget.KEY_DARK_MODE, dark)
                .putInt(TimetableWidget.KEY_TEXT_SIZE, textSizeValue)
                .apply()

            val awm = AppWidgetManager.getInstance(this)
            TimetableWidget.updateWidget(this, awm, appWidgetId)

            val resultIntent = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(RESULT_OK, resultIntent)
            finish()
        }
    }
}