package com.drdisagree.teledrive.presentation

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.FrameMetrics
import android.view.Window

/** Debug-only aggregate frame timing sampler. */
internal class DebugFrameMetrics(private val window: Window) {
    private val handler = Handler(Looper.getMainLooper())
    private val durationsMs = LongArray(HISTOGRAM_BUCKETS)
    private var frameCount = 0L
    private var slowFrames = 0L
    private var verySlowFrames = 0L
    private var totalDurationNs = 0L
    private var layoutMeasureNs = 0L
    private var drawNs = 0L
    private var syncNs = 0L
    private var commandNs = 0L
    private var swapNs = 0L
    private var inputNs = 0L
    private var animationNs = 0L
    private var running = false

    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        if (total <= 0L) return@OnFrameMetricsAvailableListener
        frameCount++
        totalDurationNs += total
        if (total > FRAME_60HZ_NS) slowFrames++
        if (total > FRAME_30HZ_NS) verySlowFrames++
        durationsMs[(total / 1_000_000L).coerceIn(0, (HISTOGRAM_BUCKETS - 1).toLong()).toInt()]++
        layoutMeasureNs += metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)
        drawNs += metrics.getMetric(FrameMetrics.DRAW_DURATION)
        syncNs += metrics.getMetric(FrameMetrics.SYNC_DURATION)
        commandNs += metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION)
        swapNs += metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)
        inputNs += metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION)
        animationNs += metrics.getMetric(FrameMetrics.ANIMATION_DURATION)
    }

    private val report = object : Runnable {
        override fun run() {
            if (!running) return
            if (frameCount > 0L) {
                val frames = frameCount
                Log.i(
                    TAG,
                    "window=5s frames=$frames avg=${ms(totalDurationNs / frames)}ms " +
                        "p50=${percentile(50)}ms p90=${percentile(90)}ms p95=${percentile(95)}ms " +
                        "over16.7ms=$slowFrames over33.3ms=$verySlowFrames " +
                        "layout=${ms(layoutMeasureNs / frames)}ms draw=${ms(drawNs / frames)}ms " +
                        "sync=${ms(syncNs / frames)}ms command=${ms(commandNs / frames)}ms " +
                        "swap=${ms(swapNs / frames)}ms input=${ms(inputNs / frames)}ms " +
                        "animation=${ms(animationNs / frames)}ms"
                )
            } else {
                Log.i(TAG, "window=5s no frames")
            }
            reset()
            handler.postDelayed(this, REPORT_INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        reset()
        window.addOnFrameMetricsAvailableListener(listener, handler)
        handler.postDelayed(report, REPORT_INTERVAL_MS)
        Log.i(TAG, "Frame diagnostics started; reproduce scroll, tab switch and folder open")
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(report)
        window.removeOnFrameMetricsAvailableListener(listener)
        reset()
        Log.i(TAG, "Frame diagnostics stopped")
    }

    private fun percentile(percent: Int): Long {
        if (frameCount == 0L) return 0L
        val target = ((frameCount * percent) + 99L) / 100L
        var accumulated = 0L
        durationsMs.forEachIndexed { index, count ->
            accumulated += count
            if (accumulated >= target) return index.toLong()
        }
        return (HISTOGRAM_BUCKETS - 1).toLong()
    }

    private fun reset() {
        durationsMs.fill(0L)
        frameCount = 0L
        slowFrames = 0L
        verySlowFrames = 0L
        totalDurationNs = 0L
        layoutMeasureNs = 0L
        drawNs = 0L
        syncNs = 0L
        commandNs = 0L
        swapNs = 0L
        inputNs = 0L
        animationNs = 0L
    }

    private fun ms(nanoseconds: Long): String = "%.2f".format(nanoseconds / 1_000_000.0)

    private companion object {
        const val TAG = "TDFrameMetrics"
        const val REPORT_INTERVAL_MS = 5_000L
        const val FRAME_60HZ_NS = 16_666_667L
        const val FRAME_30HZ_NS = 33_333_334L
        const val HISTOGRAM_BUCKETS = 121
    }
}
