package com.cnumed.mlms.ui.settings.color

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.EditText
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.cnumed.mlms.R

/**
 * HSV 그라디언트 색상 피커 다이얼로그.
 * 채도·명도 사각형 + 색조 슬라이더 + HEX 입력 + 미리보기가 서로 실시간 연동된다.
 */
object ColorPickerDialog {

    fun show(context: Context, initialColor: Int, onPicked: (Int) -> Unit) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_color_picker, null)
        val svView   = view.findViewById<SaturationValueView>(R.id.sv_view)
        val hueView  = view.findViewById<HueSliderView>(R.id.hue_view)
        val etHex    = view.findViewById<EditText>(R.id.et_hex)
        val preview  = view.findViewById<View>(R.id.color_preview)

        val hsv = FloatArray(3)
        Color.colorToHSV(initialColor, hsv)
        var h = hsv[0]; var s = hsv[1]; var v = hsv[2]

        fun current(): Int = Color.HSVToColor(floatArrayOf(h, s, v))
        fun hexOf(c: Int): String = String.format("#%06X", 0xFFFFFF and c)

        var suppressHexWatcher = false
        fun paintPreview() {
            val bg = preview.background
            if (bg is GradientDrawable) { bg.mutate(); bg.setColor(current()) }
            else preview.setBackgroundColor(current())
        }
        fun syncHexText() {
            suppressHexWatcher = true
            etHex.setText(hexOf(current()))
            etHex.setSelection(etHex.text.length)
            suppressHexWatcher = false
        }

        // 초기 상태 반영
        svView.setHue(h); svView.setSatVal(s, v)
        hueView.setHue(h)
        paintPreview(); syncHexText()

        svView.onChanged = { ns, nv -> s = ns; v = nv; paintPreview(); syncHexText() }
        hueView.onChanged = { nh -> h = nh; svView.setHue(nh); paintPreview(); syncHexText() }

        etHex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(cs: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(cs: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                if (suppressHexWatcher) return
                var t = e?.toString()?.trim() ?: return
                if (!t.startsWith("#")) t = "#$t"
                if (t.length != 7) return
                val parsed = try { Color.parseColor(t) } catch (_: Exception) { return }
                val out = FloatArray(3); Color.colorToHSV(parsed, out)
                h = out[0]; s = out[1]; v = out[2]
                svView.setHue(h); svView.setSatVal(s, v); hueView.setHue(h)
                paintPreview()
            }
        })

        AlertDialog.Builder(context)
            .setTitle("색상 선택")
            .setView(view)
            .setPositiveButton("확인") { _, _ -> onPicked(current()) }
            .setNegativeButton("취소", null)
            .show()
    }
}
