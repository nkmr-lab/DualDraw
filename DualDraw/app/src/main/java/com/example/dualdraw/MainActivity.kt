package com.example.dualdraw

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var drawingView: DualDrawView
    private lateinit var tvPenStats: TextView
    private lateinit var tvTouchStats: TextView
    private lateinit var tvInfo: TextView
    private var bridge: RawInputBridge? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        setContentView(R.layout.activity_main)

        drawingView = findViewById(R.id.drawingView)
        tvPenStats = findViewById(R.id.tvPenStats)
        tvTouchStats = findViewById(R.id.tvTouchStats)
        tvInfo = findViewById(R.id.tvInfo)

        drawingView.onStatsChanged = { penPts, touchPts ->
            runOnUiThread {
                tvPenStats.text = "ペン: $penPts pt"
                tvTouchStats.text = "タッチ: $touchPts pt"
            }
        }

        // /dev/input 直読みブリッジ起動
        bridge = RawInputBridge(drawingView)
        val bridge = bridge!!
        bridge.onStatusChanged = { touch, stylus ->
            val t = if (touch)  "✓" else "…"
            val s = if (stylus) "✓" else "…"
            tvInfo.text = "RAW touch=$t stylus=$s"
        }
        bridge.start()
        tvInfo.text = "待機中 port=${RawInputBridge.PORT_TOUCH}/${RawInputBridge.PORT_STYLUS}"

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            drawingView.clear()
            tvInfo.text = "クリアしました"
        }
        findViewById<Button>(R.id.btnClearPen).setOnClickListener {
            drawingView.undoLastPen()
            tvInfo.text = "ペンレイヤーをクリア"
        }
        findViewById<Button>(R.id.btnClearTouch).setOnClickListener {
            drawingView.undoLastTouch()
            tvInfo.text = "タッチレイヤーをクリア"
        }

        val penColors = listOf(
            "青" to Color.parseColor("#1A6EBB"),
            "紺" to Color.parseColor("#0D3B6E"),
            "緑" to Color.parseColor("#1A7A4A"),
            "黒" to Color.parseColor("#1A1A1A"),
        )
        val penSpinner = findViewById<Spinner>(R.id.spinnerPenColor)
        penSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            penColors.map { it.first })
        penSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                drawingView.penColor = penColors[pos].second
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val touchColors = listOf(
            "赤" to Color.parseColor("#C0392B"),
            "橙" to Color.parseColor("#D35400"),
            "紫" to Color.parseColor("#7D3C98"),
            "茶" to Color.parseColor("#6E2C00"),
        )
        val touchSpinner = findViewById<Spinner>(R.id.spinnerTouchColor)
        touchSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            touchColors.map { it.first })
        touchSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                drawingView.touchColor = touchColors[pos].second
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        findViewById<SeekBar>(R.id.seekPenWidth).apply {
            max = 28
            progress = 14
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) {
                    drawingView.penMinWidth = (v + 2).toFloat()
                    drawingView.penMaxWidth = (v + 16).toFloat()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        findViewById<SeekBar>(R.id.seekTouchWidth).apply {
            max = 28
            progress = 14
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) {
                    drawingView.touchMinWidth = (v + 3).toFloat()
                    drawingView.touchMaxWidth = (v + 18).toFloat()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
    }

    /**
     * Activity レベルで全 MotionEvent を横取りして DualDrawView に直接渡す。
     *
     * 通常の View ディスパッチチェーンは「ある View がタッチを消費中に別デバイスの
     * ACTION_DOWN が来ると先行デバイスに ACTION_CANCEL を送る」という挙動をする。
     * これを回避するため、drawingView の描画エリア内のイベントをここで捕まえ、
     * super への伝播より先に handleMotionEvent() へ届ける。
     * それ以外（コントロールパネル操作等）は super に委譲して通常動作させる。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        logEvent("TOUCH", ev)
        if (::drawingView.isInitialized) {
            val loc = IntArray(2)
            drawingView.getLocationOnScreen(loc)
            val relX = ev.rawX - loc[0]
            val relY = ev.rawY - loc[1]
            if (relX >= 0 && relY >= 0 &&
                relX <= drawingView.width && relY <= drawingView.height
            ) {
                // Bridge 接続中は raw 入力側で処理済みなので消費だけして二重描画を防ぐ
                val b = bridge
                if (b != null && (b.touchConnected || b.stylusConnected)) return true
                val offsetEvent = MotionEvent.obtain(ev).also {
                    it.offsetLocation(-loc[0].toFloat(), -loc[1].toFloat())
                }
                val handled = drawingView.handleMotionEvent(offsetEvent)
                offsetEvent.recycle()
                if (handled) return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // EMRペンが SOURCE_STYLUS として来る場合はこちらを通る
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        logEvent("GENERIC", ev)
        if (ev.source and InputDevice.SOURCE_CLASS_POINTER != 0 &&
            ::drawingView.isInitialized
        ) {
            val loc = IntArray(2)
            drawingView.getLocationOnScreen(loc)
            val relX = ev.rawX - loc[0]
            val relY = ev.rawY - loc[1]
            if (relX >= 0 && relY >= 0 &&
                relX <= drawingView.width && relY <= drawingView.height
            ) {
                // Bridge 接続中は raw 入力側で処理済みなので消費だけして二重描画を防ぐ
                val b = bridge
                if (b != null && (b.touchConnected || b.stylusConnected)) return true
                val offsetEvent = MotionEvent.obtain(ev).also {
                    it.offsetLocation(-loc[0].toFloat(), -loc[1].toFloat())
                }
                val handled = drawingView.handleMotionEvent(offsetEvent)
                offsetEvent.recycle()
                if (handled) return true
            }
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun logEvent(tag: String, ev: MotionEvent) {
        val actionStr = when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN          -> "DOWN"
            MotionEvent.ACTION_POINTER_DOWN  -> "PTR_DOWN"
            MotionEvent.ACTION_MOVE          -> "MOVE"
            MotionEvent.ACTION_UP            -> "UP"
            MotionEvent.ACTION_POINTER_UP    -> "PTR_UP"
            MotionEvent.ACTION_CANCEL        -> "CANCEL"
            MotionEvent.ACTION_HOVER_MOVE    -> "HOVER"
            else -> "act=${ev.actionMasked}"
        }
        val toolStr = when (ev.getToolType(0)) {
            MotionEvent.TOOL_TYPE_STYLUS  -> "STYLUS"
            MotionEvent.TOOL_TYPE_FINGER  -> "FINGER"
            MotionEvent.TOOL_TYPE_MOUSE   -> "MOUSE"
            MotionEvent.TOOL_TYPE_ERASER  -> "ERASER"
            else -> "tool=${ev.getToolType(0)}"
        }
        val srcHex = "0x%08x".format(ev.source)
        Log.d("DualDraw", "[$tag] dev=${ev.deviceId} src=$srcHex tool=$toolStr action=$actionStr ptrs=${ev.pointerCount}")
    }
}
