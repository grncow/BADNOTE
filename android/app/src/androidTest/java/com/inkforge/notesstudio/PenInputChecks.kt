package com.inkforge.notesstudio

import android.graphics.Picture
import android.os.Bundle
import android.os.Debug
import android.view.InputDevice
import android.view.MotionEvent
import org.json.JSONArray

/** Production MotionEvent ingestion with a synchronous surface sink, excluding display/GPU latency. */
object PenInputChecks {
    private class Surface : InkFrontSurface {
        override val presented = true
        var requests = 0
        var first: InkFrontFrame? = null
        var last: InkFrontFrame? = null
        override fun hideForLegacy() = Unit
        override fun renderScene(picture: Picture, width: Int, height: Int, commitFront: Boolean) = Unit
        override fun renderFront(frame: InkFrontFrame): Boolean {
            if (requests == 0) first = frame
            last = frame; requests++; return true
        }
        override fun cancelFront() = Unit
    }

    fun run(activity: MainActivity, ui: (() -> Unit) -> Unit, result: Bundle) {
        val trials = JSONArray()
        val document = activity.repository.create("pen-performance", "root", "blank")
        val page = requireNotNull(activity.repository.page(activity.repository.pageIds(document.id).first()))
        ui {
            val view = InkCanvasView(activity, activity.repository)
            view.layout(0, 0, 1080, 1600)
            view.drawHold = false; view.scribbleErase = false
            view.open(listOf(page.id), listOf(page.meta))
            view.replacePage(page)
            val surface = Surface(); view.attachFrontBuffer(surface)
            val properties = arrayOf(MotionEvent.PointerProperties().apply {
                id = 7; toolType = MotionEvent.TOOL_TYPE_STYLUS
            })
            fun coords(sample: Int) = arrayOf(MotionEvent.PointerCoords().apply {
                val point = view.screenPoint(180f + sample * .55f, 500f + kotlin.math.sin(sample * .05f) * 25f)
                x = point.x; y = point.y; pressure = .25f + (sample % 17) * .04f
                setAxisValue(MotionEvent.AXIS_TILT, .3f)
                setAxisValue(MotionEvent.AXIS_ORIENTATION, .4f)
            })
            fun event(action: Int, first: Int, count: Int = 1): MotionEvent {
                val time = 10_000L + first * 4
                return MotionEvent.obtain(10_000, time, action, 1, properties, coords(first),
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0).apply {
                    for (sample in first + 1 until first + count) addBatch(10_000L + sample * 4, coords(sample), 0)
                }
            }
            repeat(4) { trial ->
                page.objects.clear(); view.replacePage(page)
                surface.requests = 0
                event(MotionEvent.ACTION_DOWN, 0).let { try { view.onTouchEvent(it) } finally { it.recycle() } }
                val wall = mutableListOf<Long>(); val cpu = mutableListOf<Long>()
                var sample = 1
                while (sample < 1023) {
                    val count = minOf(9, 1023 - sample)
                    val input = event(MotionEvent.ACTION_MOVE, sample, count)
                    val start = System.nanoTime(); val cpuStart = Debug.threadCpuTimeNanos()
                    try { view.onTouchEvent(input) } finally { input.recycle() }
                    cpu += Debug.threadCpuTimeNanos() - cpuStart; wall += System.nanoTime() - start
                    sample += count
                }
                event(MotionEvent.ACTION_UP, 1023).let { try { view.onTouchEvent(it) } finally { it.recycle() } }
                val stored = page.objects.single().getJSONArray("points")
                check(surface.first?.points?.size == 1 && surface.last?.points?.size == 1023) {
                    "Previously submitted samples changed after finishing the contact"
                }
                check(surface.first?.stroke?.has("points") == false) { "Finishing mutated submitted style" }
                check(stored.length() == 1024) { "Dropped historical samples: ${stored.length()}" }
                for (index in 0 until stored.length()) {
                    val p = stored.getJSONObject(index)
                    check(p.getLong("t") == 10_000L + index * 4 && p.getInt("pointerId") == 7)
                    check(kotlin.math.abs(p.getDouble("p") - (.25f + (index % 17) * .04f)) < .00001)
                    check(kotlin.math.abs(p.getDouble("tilt") - .3) < .00001)
                    check(kotlin.math.abs(p.getDouble("orientation") - .4) < .00001)
                }
                fun percentile(values: List<Long>, fraction: Double) =
                    values.sorted()[(kotlin.math.ceil(values.size * fraction).toInt() - 1).coerceAtLeast(0)] / 1e6
                if (trial > 0) trials.put(json("wallP95Ms" to percentile(wall, .95),
                    "cpuP95Ms" to percentile(cpu, .95), "wallMedianMs" to percentile(wall, .5),
                    "maxMs" to wall.max() / 1e6, "frontRequests" to surface.requests,
                    "moveEvents" to wall.size, "storedSamples" to stored.length()))
            }
            view.cancelContact()
        }
        result.putString("penInputTrials", trials.toString())
        result.putString("penInputScope", "production_MotionEvent_ingestion_synchronous_surface_sink_no_display_latency")
    }
}
