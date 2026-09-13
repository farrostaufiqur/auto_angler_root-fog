package tf.fog.autoangler.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

enum class CalibStep {
    ROI_TOP_LEFT, ROI_BOTTOM_RIGHT,
    LEFT_BTN, RIGHT_BTN,
    DONE
}

@SuppressLint("ViewConstructor")
class CalibrationView(context: Context, private val onDone: (Result) -> Unit) : View(context) {

    data class Result(
        val roiRect: Rect,
        val leftBtnX: Int, val leftBtnY: Int,
        val rightBtnX: Int, val rightBtnY: Int
    )

    private var step = CalibStep.ROI_TOP_LEFT
    private var roiX1 = 0; private var roiY1 = 0
    private var roiX2 = 0; private var roiY2 = 0
    private var lbX = 0; private var lbY = 0
    private var rbX = 0; private var rbY = 0

    private val dotPaint = Paint().apply {
        color = Color.RED; style = Paint.Style.FILL; isAntiAlias = true
    }
    private val linePaint = Paint().apply {
        color = Color.parseColor("#66FF0000"); style = Paint.Style.STROKE
        strokeWidth = 2f; isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE; textSize = 42f; isAntiAlias = true
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }
    private val rectPaint = Paint().apply {
        color = Color.parseColor("#8800FF00"); style = Paint.Style.FILL; isAntiAlias = true
    }

    private val instruction get() = when (step) {
        CalibStep.ROI_TOP_LEFT    -> "TAP the TOP-LEFT corner of the fishing bar"
        CalibStep.ROI_BOTTOM_RIGHT -> "TAP the BOTTOM-RIGHT corner of the fishing bar"
        CalibStep.LEFT_BTN        -> "TAP the game's LEFT ARROW button"
        CalibStep.RIGHT_BTN       -> "TAP the game's RIGHT ARROW button"
        CalibStep.DONE            -> "Done!"
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true
        val x = event.x.toInt(); val y = event.y.toInt()
        when (step) {
            CalibStep.ROI_TOP_LEFT     -> { roiX1 = x; roiY1 = y; step = CalibStep.ROI_BOTTOM_RIGHT }
            CalibStep.ROI_BOTTOM_RIGHT -> { roiX2 = x; roiY2 = y; step = CalibStep.LEFT_BTN }
            CalibStep.LEFT_BTN         -> { lbX = x; lbY = y; step = CalibStep.RIGHT_BTN }
            CalibStep.RIGHT_BTN        -> {
                rbX = x; rbY = y; step = CalibStep.DONE
                onDone(Result(
                    roiRect = Rect(
                        minOf(roiX1, roiX2), minOf(roiY1, roiY2),
                        maxOf(roiX1, roiX2), maxOf(roiY1, roiY2)
                    ),
                    leftBtnX = lbX, leftBtnY = lbY,
                    rightBtnX = rbX, rightBtnY = rbY
                ))
            }
            CalibStep.DONE -> {}
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        // Latar semi-transparan gelap di area instruksi atas
        canvas.drawRect(0f, 0f, width.toFloat(), 120f,
            Paint().apply { color = Color.parseColor("#CC000000") })

        canvas.drawText(instruction, 24f, 80f, textPaint)

        // Gambar preview ROI kalau sudah ada 2 titik
        if (step.ordinal >= CalibStep.ROI_BOTTOM_RIGHT.ordinal && roiX2 != 0) {
            canvas.drawRect(
                minOf(roiX1, roiX2).toFloat(), minOf(roiY1, roiY2).toFloat(),
                maxOf(roiX1, roiX2).toFloat(), maxOf(roiY1, roiY2).toFloat(),
                rectPaint
            )
        }

        // Titik-titik yang sudah ditap
        if (step.ordinal > CalibStep.ROI_TOP_LEFT.ordinal)
            canvas.drawCircle(roiX1.toFloat(), roiY1.toFloat(), 12f, dotPaint)
        if (step.ordinal > CalibStep.ROI_BOTTOM_RIGHT.ordinal)
            canvas.drawCircle(roiX2.toFloat(), roiY2.toFloat(), 12f, dotPaint)
        if (step.ordinal > CalibStep.LEFT_BTN.ordinal)
            canvas.drawCircle(lbX.toFloat(), lbY.toFloat(), 18f,
                dotPaint.apply { color = Color.YELLOW })
        if (step == CalibStep.DONE)
            canvas.drawCircle(rbX.toFloat(), rbY.toFloat(), 18f,
                dotPaint.apply { color = Color.CYAN })
    }
}
