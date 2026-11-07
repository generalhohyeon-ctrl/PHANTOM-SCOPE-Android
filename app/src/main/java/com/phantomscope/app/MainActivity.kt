        values.clear()
        values.addAll(data.takeLast(60))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawRoundRect(
            0f, 0f, w, h, 16f, 16f, backgroundPaint
        )

        val left = 42f
        val right = w - 12f
        val top = 20f
        val bottom = h - 30f

        // 그래프 격자
        for (i in 0..4) {
            val y = top + (bottom - top) * i / 4f

            canvas.drawLine(
                left, y, right, y, gridPaint
            )
        }

        canvas.drawLine(
            left, top, left, bottom, gridPaint
        )

        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("60 SAMPLES", left, 14f, textPaint)

        if (values.isEmpty()) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                "WAITING FOR SENSOR DATA",
                w / 2f,
                h / 2f,
                textPaint
            )
            return
        }

        val minimum = (values.minOrNull() ?: 0f) - 2f
        val maximum = (values.maxOrNull() ?: 100f) + 2f
        val range = (maximum - minimum).coerceAtLeast(4f)

        textPaint.textAlign = Paint.Align.RIGHT

        canvas.drawText(
            String.format(Locale.US, "%.1f", maximum),
            left - 6f, top + 4f, textPaint
        )

        canvas.drawText(
            String.format(Locale.US, "%.1f", minimum),
            left - 6f, bottom, textPaint
        )

        val path = Path()

        values.forEachIndexed { index, value ->
            val x = if (values.size == 1) {
                left
            } else {
                left + (right - left) *
                        index / (values.size - 1).toFloat()
            }

            val y = bottom -
                    ((value - minimum) / range) * (bottom - top)

            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        canvas.drawPath(path, linePaint)

        val lastValue = values.last()
        val lastX = if (values.size == 1) {
            left
        } else {
            right
        }

        val lastY = bottom -
                ((lastValue - minimum) / range) * (bottom - top)

        val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 65, 75)
            style = Paint.Style.FILL
        }

        canvas.drawCircle(lastX, lastY, 5f, pointPaint)

        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("RECENT MAGNETIC FIELD", w / 2f, h - 7f, textPaint)
    }
}
