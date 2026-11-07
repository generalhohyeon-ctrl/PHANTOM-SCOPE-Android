
package com.phantomscope.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var content: LinearLayout
    private lateinit var tabButtons: List<Button>

    private val backgroundColor = Color.rgb(10, 14, 20)
    private val panelColor = Color.rgb(20, 27, 36)
    private val accentColor = Color.rgb(70, 220, 150)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor
        window.decorView.systemUiVisibility = 0

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
            setPadding(20, 20, 20, 12)
        }

        val title = TextView(this).apply {
            text = "PHANTOM SCOPE"
            textSize = 25f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        root.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 55
            )
        )

        val subtitle = TextView(this).apply {
            text = "PARANORMAL INVESTIGATION SYSTEM"
            textSize = 10f
            setTextColor(accentColor)
            gravity = Gravity.CENTER
        }

        root.addView(subtitle)

        root.addView(createSpectrum())

        val tabNames = listOf("레이더", "사운드", "카메라", "기록")

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 15)
        }

        tabButtons = tabNames.mapIndexed { index, name ->
            Button(this).apply {
                text = name
                textSize = 11f
                isAllCaps = false
                setTextColor(Color.WHITE)
                setOnClickListener {
                    showPage(index)
                }

                tabRow.addView(
                    this,
                    LinearLayout.LayoutParams(0, 48, 1f).apply {
                        setMargins(3, 0, 3, 0)
                    }
                )
            }
        }

        root.addView(tabRow)

        val scrollView = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        scrollView.addView(content)

        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
        showPage(0)
    }

    private fun createSpectrum(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(15, 15, 15, 15)
            background = roundedBackground(panelColor, 14)
        }

        val label = TextView(this).apply {
            text = "MAGNETIC FIELD SPECTRUM"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }

        panel.addView(label)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 15, 0, 10)
        }

        val colors = listOf(
            Color.rgb(40, 210, 120),
            Color.rgb(160, 220, 50),
            Color.rgb(255, 190, 40),
            Color.rgb(255, 100, 40),
            Color.rgb(230, 45, 55)
        )

        colors.forEach { color ->
            val segment = View(this).apply {
                background = roundedBackground(color, 5)
            }

            bar.addView(
                segment,
                LinearLayout.LayoutParams(0, 13, 1f).apply {
                    setMargins(2, 0, 2, 0)
                }
            )
        }

        panel.addView(bar)

        val status = TextView(this).apply {
            text = "SYSTEM STANDBY  |  SENSOR NOT CONNECTED"
            textSize = 10f
            setTextColor(Color.LTGRAY)
        }

        panel.addView(status)

        return panel
    }

    private fun showPage(index: Int) {
        content.removeAllViews()

        tabButtons.forEachIndexed { i, button ->
            button.setBackgroundColor(
                if (i == index) accentColor else panelColor
            )
            button.setTextColor(
                if (i == index) Color.BLACK else Color.WHITE
            )
        }

        when (index) {
            0 -> showRadar()
            1 -> showSound()
            2 -> showCamera()
            3 -> showHistory()
        }
    }

    private fun showRadar() {
        addHeading("RADAR SYSTEM")
        addPanelText(
            "탐사 레이더 대기 중",
            "현재 레이더 센서가 연결되지 않았습니다.\n\n" +
            "향후 위치 및 움직임 감지 기능이 추가될 예정입니다."
        )
    }

    private fun showSound() {
        addHeading("AUDIO DETECTION")
        addPanelText(
            "사운드 감지 시스템",
            "마이크 기능은 아직 연결되지 않았습니다.\n\n" +
            "향후 주변 소리 감지 기능을 추가할 예정입니다."
        )
    }

    private fun showCamera() {
        addHeading("CAMERA SYSTEM")
        addPanelText(
            "카메라 대기 중",
            "카메라 기능은 아직 연결되지 않았습니다.\n\n" +
            "현재 자동 촬영 및 플래시 기능은 사용하지 않습니다."
        )
    }

    private fun showHistory() {
        addHeading("INVESTIGATION LOG")
        addPanelText(
            "탐사 기록 없음",
            "아직 저장된 탐사 기록이 없습니다."
        )
    }

    private fun addHeading(text: String) {
        val heading = TextView(this).apply {
            this.text = text
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentColor)
            setPadding(5, 15, 5, 15)
        }

        content.addView(heading)
    }

    private fun addPanelText(title: String, description: String) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 25, 20, 25)
            background = roundedBackground(panelColor, 14)
        }

        val heading = TextView(this).apply {
            text = title
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }

        val body = TextView(this).apply {
            text = description
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setLineSpacing(8f, 1f)
            setPadding(0, 15, 0, 0)
        }

        panel.addView(heading)
        panel.addView(body)

        content.addView(
            panel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 5, 0, 15)
            }
        )
    }

    private fun roundedBackground(
        color: Int,
        radius: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }
    }
}
