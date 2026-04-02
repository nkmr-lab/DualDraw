package com.example.dualdraw

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * DualDrawView
 *
 * EMRペン (MotionEvent.TOOL_TYPE_STYLUS) と
 * 指/タッチペン (TOOL_TYPE_FINGER / TOOL_TYPE_MOUSE) を
 * 独立したチャンネルとして扱い、別々の色で描画する。
 *
 * - ペンA (EMR):   青系  → stylusPaint
 * - ペンB (Touch): 赤系  → touchPaint
 *
 * 筆圧があればストローク幅に反映する。
 *
 * ストロークキーは DevicePointerKey(deviceId, pointerId) で管理し、
 * 異なる InputDevice からの同時入力を正しく分離する。
 */
class DualDrawView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ---- 設定 ----
    var penColor: Int = Color.parseColor("#1A6EBB")
    var touchColor: Int = Color.parseColor("#C0392B")
    var penMinWidth: Float = 2f
    var penMaxWidth: Float = 16f
    var touchMinWidth: Float = 3f
    var touchMaxWidth: Float = 18f
    var penAlpha: Int = 220
    var touchAlpha: Int = 180

    // ---- 内部状態 ----
    private var _penBitmap: Bitmap? = null
    private var _touchBitmap: Bitmap? = null
    private var penCanvas: Canvas? = null
    private var touchCanvas: Canvas? = null

    // deviceId と pointerId の組み合わせをキーにすることで、
    // 別デバイスから同じ pointerId が来ても衝突しない
    // RawInputBridge からも参照できるよう public にしている
    data class DevicePointerKey(val deviceId: Int, val pointerId: Int)

    private data class StrokeState(
        val path: Path,
        var lastX: Float,
        var lastY: Float,
        var lastPressure: Float
    )

    private val penStrokes = mutableMapOf<DevicePointerKey, StrokeState>()
    private val touchStrokes = mutableMapOf<DevicePointerKey, StrokeState>()

    private val stylusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val touchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bitmapPaint = Paint(Paint.DITHER_FLAG)

    var penPointCount = 0
        private set
    var touchPointCount = 0
        private set

    var onStatsChanged: ((penPts: Int, touchPts: Int) -> Unit)? = null

    // ---- ライフサイクル ----

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            _penBitmap?.recycle()
            _touchBitmap?.recycle()
            _penBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            _touchBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            penCanvas = Canvas(_penBitmap!!)
            touchCanvas = Canvas(_touchBitmap!!)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        _penBitmap?.let { canvas.drawBitmap(it, 0f, 0f, bitmapPaint) }
        _touchBitmap?.let { canvas.drawBitmap(it, 0f, 0f, bitmapPaint) }
    }

    // ---- タッチ処理 ----

    // Activity.dispatchTouchEvent() から直接呼ばれる公開エントリポイント。
    // deviceId を明示的に受け取ることで、デバイスごとにストリームを独立管理する。
    fun handleMotionEvent(event: MotionEvent): Boolean {
        val deviceId = event.deviceId
        val actionMasked = event.actionMasked
        val actionIndex = event.actionIndex

        when (actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerId = event.getPointerId(actionIndex)
                val key = DevicePointerKey(deviceId, pointerId)
                beginStroke(
                    key,
                    event.getX(actionIndex),
                    event.getY(actionIndex),
                    event.getPressure(actionIndex).coerceIn(0f, 1f),
                    event.getToolType(actionIndex)
                )
            }

            MotionEvent.ACTION_MOVE -> {
                for (pIdx in 0 until event.pointerCount) {
                    val key = DevicePointerKey(deviceId, event.getPointerId(pIdx))
                    val toolType = event.getToolType(pIdx)
                    for (h in 0 until event.historySize) {
                        moveStroke(
                            key,
                            event.getHistoricalX(pIdx, h),
                            event.getHistoricalY(pIdx, h),
                            event.getHistoricalPressure(pIdx, h).coerceIn(0f, 1f),
                            toolType
                        )
                    }
                    moveStroke(
                        key,
                        event.getX(pIdx),
                        event.getY(pIdx),
                        event.getPressure(pIdx).coerceIn(0f, 1f),
                        toolType
                    )
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                val key = DevicePointerKey(deviceId, event.getPointerId(actionIndex))
                endStroke(key, event.getToolType(actionIndex))
            }

            MotionEvent.ACTION_CANCEL -> {
                // このデバイスのストロークだけをキャンセル。他デバイスは継続させる。
                penStrokes.keys.filter { it.deviceId == deviceId }.forEach { penStrokes.remove(it) }
                touchStrokes.keys.filter { it.deviceId == deviceId }.forEach { touchStrokes.remove(it) }
            }
        }
        invalidate()
        return true
    }

    // View 経由の通常ディスパッチも受け付ける（フォールバック）
    override fun onTouchEvent(event: MotionEvent) = handleMotionEvent(event)

    // ---- RawInputBridge から呼ばれる raw 入力 API (メインスレッドから呼ぶこと) ----

    fun rawBegin(key: DevicePointerKey, x: Float, y: Float, pressure: Float, isStylus: Boolean) {
        beginStroke(key, x, y, pressure,
            if (isStylus) MotionEvent.TOOL_TYPE_STYLUS else MotionEvent.TOOL_TYPE_FINGER)
    }

    fun rawMove(key: DevicePointerKey, x: Float, y: Float, pressure: Float, isStylus: Boolean) {
        moveStroke(key, x, y, pressure,
            if (isStylus) MotionEvent.TOOL_TYPE_STYLUS else MotionEvent.TOOL_TYPE_FINGER)
    }

    fun rawEnd(key: DevicePointerKey, isStylus: Boolean) {
        endStroke(key,
            if (isStylus) MotionEvent.TOOL_TYPE_STYLUS else MotionEvent.TOOL_TYPE_FINGER)
    }

    // ---- ストローク操作 ----

    private fun isStylusType(toolType: Int) =
        toolType == MotionEvent.TOOL_TYPE_STYLUS ||
        toolType == MotionEvent.TOOL_TYPE_ERASER

    private fun beginStroke(
        key: DevicePointerKey, x: Float, y: Float,
        pressure: Float, toolType: Int
    ) {
        val state = StrokeState(Path().apply { moveTo(x, y) }, x, y, pressure)
        if (isStylusType(toolType)) penStrokes[key] = state else touchStrokes[key] = state
    }

    private fun moveStroke(
        key: DevicePointerKey, x: Float, y: Float,
        pressure: Float, toolType: Int
    ) {
        val isStylus = isStylusType(toolType)
        val strokes = if (isStylus) penStrokes else touchStrokes
        val targetCanvas = if (isStylus) penCanvas else touchCanvas
        val paint = if (isStylus) stylusPaint else touchPaint

        val state = strokes[key] ?: return
        val midX = (state.lastX + x) / 2f
        val midY = (state.lastY + y) / 2f
        val midP = (state.lastPressure + pressure) / 2f

        val strokeWidth = if (isStylus)
            lerp(penMinWidth, penMaxWidth, midP)
        else
            lerp(touchMinWidth, touchMaxWidth, midP.coerceAtLeast(0.3f))

        paint.color = if (isStylus) penColor else touchColor
        paint.alpha = if (isStylus) penAlpha else touchAlpha
        paint.strokeWidth = strokeWidth

        targetCanvas?.drawPath(Path().apply {
            moveTo(state.lastX, state.lastY)
            quadTo(state.lastX, state.lastY, midX, midY)
        }, paint)

        state.lastX = x
        state.lastY = y
        state.lastPressure = pressure

        if (isStylus) penPointCount++ else touchPointCount++
        onStatsChanged?.invoke(penPointCount, touchPointCount)
    }

    private fun endStroke(key: DevicePointerKey, toolType: Int) {
        if (isStylusType(toolType)) penStrokes.remove(key) else touchStrokes.remove(key)
    }

    // ---- ユーティリティ ----

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)

    fun clear() {
        penCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        touchCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        penStrokes.clear()
        touchStrokes.clear()
        penPointCount = 0
        touchPointCount = 0
        onStatsChanged?.invoke(0, 0)
        invalidate()
    }

    fun undoLastPen() {
        penCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        penPointCount = 0
        onStatsChanged?.invoke(penPointCount, touchPointCount)
        invalidate()
    }

    fun undoLastTouch() {
        touchCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        touchPointCount = 0
        onStatsChanged?.invoke(penPointCount, touchPointCount)
        invalidate()
    }
}
