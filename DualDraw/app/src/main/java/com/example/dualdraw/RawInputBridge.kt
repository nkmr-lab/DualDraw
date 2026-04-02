package com.example.dualdraw

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.InputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * /dev/input/event3 (touch) と event6 (EMR stylus) を
 * TCP ソケット経由で直接受信し、Android InputDispatcher を完全バイパスする。
 *
 * SELinux の制約上、shell→app の接続は拒否されるため、
 * app がクライアントとして shell 側の nc サーバーに接続する。
 *
 * 準備（adb shell から実行）:
 *   nc -l 9003 < /dev/input/event3 &
 *   nc -l 9006 < /dev/input/event6 &
 *
 * アプリが自動的に接続を試みる。nc が切れたら上記コマンドを再実行。
 *
 * 座標マッピング:
 *   event3: X 0-1440, Y 0-2200 (portrait) → landscape view 座標
 *   event6: X 0-15928, Y 0-24334 (portrait) → landscape view 座標
 *   描画位置がずれる場合は USE_ROTATION_90 を false に変更して再ビルド
 */
class RawInputBridge(private val view: DualDrawView) {

    companion object {
        private const val TAG = "RawBridge"

        const val PORT_TOUCH  = 9003  // event3: NVTCapacitiveTouchScreen
        const val PORT_STYLUS = 9006  // event6: hid-over-i2c (EMR)

        private const val TOUCH_X_MAX = 1440f
        private const val TOUCH_Y_MAX = 2200f
        private const val TOUCH_P_MAX = 1000f

        private const val STYLUS_X_MAX = 15928f
        private const val STYLUS_Y_MAX = 24334f
        private const val STYLUS_P_MAX = 8191f

        // Linux input_event: timeval(8+8) + type(2) + code(2) + value(4) = 24 bytes (64-bit)
        private const val EVENT_SIZE = 24

        private const val EV_SYN = 0x00
        private const val EV_KEY = 0x01
        private const val EV_ABS = 0x03

        private const val ABS_MT_SLOT        = 0x2f
        private const val ABS_MT_POSITION_X  = 0x35
        private const val ABS_MT_POSITION_Y  = 0x36
        private const val ABS_MT_TRACKING_ID = 0x39
        private const val ABS_MT_PRESSURE    = 0x3a

        private const val ABS_X        = 0x00
        private const val ABS_Y        = 0x01
        private const val ABS_PRESSURE = 0x18
        private const val BTN_TOUCH    = 0x14a
        private const val SYN_REPORT   = 0x00

        // portrait → landscape 変換方向
        // 描画位置がずれる場合は false に変更
        private const val USE_ROTATION_90 = false

        private const val RETRY_INTERVAL_MS = 2000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile var touchConnected  = false; private set
    @Volatile var stylusConnected = false; private set
    @Volatile private var running = false

    var onStatusChanged: ((touch: Boolean, stylus: Boolean) -> Unit)? = null

    fun start() {
        running = true
        Log.w(TAG, "start() called — connecting to ports $PORT_TOUCH/$PORT_STYLUS")
        launchThread("RawTouch")  { clientLoop(PORT_TOUCH,  isStylus = false) }
        launchThread("RawStylus") { clientLoop(PORT_STYLUS, isStylus = true)  }
    }

    fun stop() {
        running = false
    }

    private fun launchThread(name: String, block: () -> Unit) =
        Thread(block, name).apply { isDaemon = true; start() }

    // ---- クライアントループ: nc サーバーに接続を試み続ける ----

    private fun clientLoop(port: Int, isStylus: Boolean) {
        while (running) {
            Log.d(TAG, "Trying to connect to ::1:$port ...")
            val sock = runCatching { Socket("::1", port) }
                .onFailure { Log.d(TAG, "port=$port not ready yet (::1): ${it.message}") }
                .getOrNull()
                ?: runCatching { Socket("127.0.0.1", port) }
                    .onFailure { Log.d(TAG, "port=$port not ready yet (127.0.0.1): ${it.message}") }
                    .getOrNull()

            if (sock != null) {
                Log.w(TAG, "Connected to port=$port isStylus=$isStylus")
                setStatus(isStylus, true)
                runCatching {
                    if (isStylus) processStylus(sock.getInputStream())
                    else          processTouch(sock.getInputStream())
                }.onFailure { Log.d(TAG, "port=$port disconnected: ${it.message}") }
                sock.runCatching { close() }
                setStatus(isStylus, false)
                Log.w(TAG, "Disconnected from port=$port, retrying in ${RETRY_INTERVAL_MS}ms")
            }

            if (running) Thread.sleep(RETRY_INTERVAL_MS)
        }
    }

    private fun setStatus(isStylus: Boolean, v: Boolean) {
        if (isStylus) stylusConnected = v else touchConnected = v
        mainHandler.post { onStatusChanged?.invoke(touchConnected, stylusConnected) }
    }

    // ---- Touch: Multi-Touch Protocol B ----

    private data class SlotState(
        val slot: Int,
        var trackingId: Int = -1,
        var prevId: Int = -1,
        var x: Int = 0,
        var y: Int = 0,
        var pressure: Int = 500,
        var dirty: Boolean = false
    )

    private fun processTouch(inp: InputStream) {
        val buf = ByteArray(EVENT_SIZE)
        val bb  = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val slots = Array(10) { SlotState(it) }
        var cur = 0

        while (readFully(inp, buf)) {
            bb.rewind(); bb.getLong(); bb.getLong()
            val type  = bb.short.toInt() and 0xFFFF
            val code  = bb.short.toInt() and 0xFFFF
            val value = bb.int

            when (type) {
                EV_ABS -> slots[cur].let { s ->
                    when (code) {
                        ABS_MT_SLOT        -> cur = value.coerceIn(0, 9)
                        ABS_MT_TRACKING_ID -> { s.trackingId = value; s.dirty = true }
                        ABS_MT_POSITION_X  -> { s.x = value;          s.dirty = true }
                        ABS_MT_POSITION_Y  -> { s.y = value;          s.dirty = true }
                        ABS_MT_PRESSURE    -> { s.pressure = value;   s.dirty = true }
                    }
                }
                EV_SYN -> if (code == SYN_REPORT) {
                    data class Ev(val key: DualDrawView.DevicePointerKey,
                                  val x: Float, val y: Float, val p: Float,
                                  val was: Boolean, val now: Boolean)
                    val events = slots.filter { it.dirty }.map { s ->
                        val (vx, vy) = mapTouch(s.x.toFloat(), s.y.toFloat())
                        val ev = Ev(
                            key = DualDrawView.DevicePointerKey(1000, s.slot),
                            x = vx, y = vy,
                            p = (s.pressure / TOUCH_P_MAX).coerceIn(0f, 1f),
                            was = s.prevId >= 0,
                            now = s.trackingId >= 0
                        )
                        s.dirty = false; s.prevId = s.trackingId
                        ev
                    }
                    if (events.isNotEmpty()) mainHandler.post {
                        events.forEach { e ->
                            when {
                                !e.was && e.now -> view.rawBegin(e.key, e.x, e.y, e.p, isStylus = false)
                                e.was && !e.now -> view.rawEnd(e.key, isStylus = false)
                                e.was && e.now  -> view.rawMove(e.key, e.x, e.y, e.p, isStylus = false)
                            }
                        }
                        view.invalidate()
                    }
                }
            }
        }
    }

    // ---- Stylus (EMR, event6) ----

    private fun processStylus(inp: InputStream) {
        val buf = ByteArray(EVENT_SIZE)
        val bb  = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val key = DualDrawView.DevicePointerKey(2000, 0)
        var rawX = 0f; var rawY = 0f; var rawP = 0f
        var isDown = false; var prevDown = false

        while (readFully(inp, buf)) {
            bb.rewind(); bb.getLong(); bb.getLong()
            val type  = bb.short.toInt() and 0xFFFF
            val code  = bb.short.toInt() and 0xFFFF
            val value = bb.int

            when (type) {
                EV_ABS -> when (code) {
                    ABS_X        -> rawX = value.toFloat()
                    ABS_Y        -> rawY = value.toFloat()
                    ABS_PRESSURE -> rawP = value.toFloat()
                }
                EV_KEY -> if (code == BTN_TOUCH) isDown = (value != 0)
                EV_SYN -> if (code == SYN_REPORT) {
                    val (vx, vy) = mapStylus(rawX, rawY)
                    val p = (rawP / STYLUS_P_MAX).coerceIn(0f, 1f)
                    val dn = isDown; val pd = prevDown
                    prevDown = isDown
                    mainHandler.post {
                        when {
                            !pd && dn -> view.rawBegin(key, vx, vy, p, isStylus = true)
                            pd && !dn -> view.rawEnd(key,             isStylus = true)
                            pd && dn  -> view.rawMove(key, vx, vy, p, isStylus = true)
                        }
                        view.invalidate()
                    }
                }
            }
        }
    }

    // ---- 座標変換 ----

    private fun mapTouch(rawX: Float, rawY: Float): Pair<Float, Float> {
        val w = view.width.toFloat().takeIf  { it > 0f } ?: return 0f to 0f
        val h = view.height.toFloat().takeIf { it > 0f } ?: return 0f to 0f
        return if (USE_ROTATION_90)
            (1f - rawY / TOUCH_Y_MAX) * w to rawX / TOUCH_X_MAX * h
        else
            rawY / TOUCH_Y_MAX * w to (1f - rawX / TOUCH_X_MAX) * h
    }

    private fun mapStylus(rawX: Float, rawY: Float): Pair<Float, Float> {
        val w = view.width.toFloat().takeIf  { it > 0f } ?: return 0f to 0f
        val h = view.height.toFloat().takeIf { it > 0f } ?: return 0f to 0f
        return if (USE_ROTATION_90)
            (1f - rawY / STYLUS_Y_MAX) * w to rawX / STYLUS_X_MAX * h
        else
            rawY / STYLUS_Y_MAX * w to (1f - rawX / STYLUS_X_MAX) * h
    }

    private fun readFully(inp: InputStream, buf: ByteArray): Boolean {
        var n = 0
        while (n < buf.size) {
            val r = inp.read(buf, n, buf.size - n)
            if (r < 0) return false
            n += r
        }
        return true
    }
}
