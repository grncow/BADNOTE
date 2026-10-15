package com.inkforge.notesstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executor

/** Delays actual SurfaceControl commit callbacks to deterministically exercise stroke overlap. */
object PenPresentationChecks {
    private class CommitGate(private val main: Executor) : Executor {
        @Volatile var held = false
        private val pending = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            val queued = synchronized(pending) { if (held) { pending.addLast(command); true } else false }
            if (!queued) main.execute(command)
        }
        fun release() {
            val commands = synchronized(pending) { held = false; pending.toList().also { pending.clear() } }
            commands.forEach { main.execute(it) }
        }
    }

    fun run(activity: MainActivity, instrumentation: android.app.Instrumentation,
            ui: (() -> Unit) -> Unit, result: Bundle) {
        check(android.os.Build.VERSION.SDK_INT >= 31)
        val document = activity.repository.create("pen-presentation", "root", "blank")
        val page = requireNotNull(activity.repository.page(activity.repository.pageIds(document.id).first()))
        val gate = CommitGate(activity.mainExecutor)
        lateinit var host: FrameLayout
        lateinit var view: InkCanvasView
        lateinit var front: InkFrontBuffer
        val screens = File(activity.filesDir, "pen-presentation").apply { mkdirs() }
        fun await(label: String, condition: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 30_000
            while (SystemClock.uptimeMillis() < end) { if (condition()) return; SystemClock.sleep(25) }
            error("Timeout: $label")
        }
        fun presented(): Boolean { var value = false; ui { value = front.presented }; return value }
        fun pixel(x: Float, y: Float): Int {
            var px = 0; var py = 0
            ui { val location = IntArray(2); view.getLocationOnScreen(location)
                val point = view.screenPoint(x, y); px = location[0] + point.x.toInt(); py = location[1] + point.y.toInt() }
            val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            return try { image.getPixel(px, py) } finally { image.recycle() }
        }
        fun dark(x: Float, y: Float): Boolean { val p = pixel(x, y)
            return Color.red(p) < 100 && Color.green(p) < 130 && Color.blue(p) < 170 }
        fun screen(name: String) { val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { File(screens, "$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { image.recycle() } }
        var down = 0L
        fun send(action: Int, x: Float, y: Float) = ui {
            val now = SystemClock.uptimeMillis(); if (action == MotionEvent.ACTION_DOWN) down = now
            val point = view.screenPoint(x, y)
            val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 9; toolType = MotionEvent.TOOL_TYPE_STYLUS })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = point.x; this.y = point.y; pressure = .6f })
            val event = MotionEvent.obtain(down, now, action, 1, properties, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
            try { check(view.onTouchEvent(event)) } finally { event.recycle() }
        }
        run {
            // Exact software-raster comparison with the previous CLEAR + replay result.
            val painter = InkFrontFrameRenderer()
            val expected = Bitmap.createBitmap(384, 256, Bitmap.Config.ARGB_8888)
            val actual = Bitmap.createBitmap(384, 256, Bitmap.Config.ARGB_8888)
            val reference = NoteRenderer()
            var cases = 0
            try {
                for (brush in listOf("fountain", "ballpoint", "gel", "brush", "pencil", "fineliner", "highlighter")) {
                    for (count in listOf(1, 2, 31)) {
                        val samples = List(count) { n -> InkPoint(30f + n * 8,
                            80f + kotlin.math.sin(n.toFloat()) * 15, .2f + n % 7 * .1f) }
                        val stroke = json("id" to "stable-seed", "type" to "stroke", "brush" to brush,
                            "color" to "#172033", "width" to 10, "opacity" to .35,
                            "points" to JSONArray(samples.map { it.json() }))
                        val frame = InkFrontFrame(stroke, 6f, 12f, .9f, 360f, 250f, samples)
                        expected.eraseColor(Color.TRANSPARENT)
                        val canvas = Canvas(expected); val saved = canvas.save()
                        canvas.translate(frame.x, frame.y); canvas.scale(frame.scale, frame.scale)
                        canvas.clipRect(0f, 0f, frame.pageWidth, frame.pageHeight)
                        reference.draw(canvas, stroke, frame.scale.toDouble()); canvas.restoreToCount(saved)
                        for (snapshot in listOf(frame, frame.copy(points = null))) {
                            actual.eraseColor(Color.MAGENTA)
                            repeat(3) {
                                // Check both immutable samples and serialized compatibility against reference pixels.
                                painter.draw(Canvas(actual), 384, 256, snapshot)
                                check(actual.sameAs(expected)) { "Front replacement changed raster/alpha: $brush/$count/$it" }
                            }
                            cases++
                        }
                    }
                }
                // Queue many snapshots before the worker runs. Its oldest notification
                // must still draw the newest snapshot on its first callback.
                var newest: InkFrontFrame? = null
                repeat(128) { n ->
                    val samples = listOf(InkPoint(20f, 180f, .6f), InkPoint(40f + n * 2, 180f, .6f))
                    val stroke = json("id" to "queued", "type" to "stroke", "brush" to "ballpoint",
                        "color" to "#172033", "width" to 10, "opacity" to 1,
                        "points" to JSONArray(samples.map { it.json() }))
                    newest = InkFrontFrame(stroke, 0f, 0f, 1f, 384f, 256f, samples)
                    painter.submit(requireNotNull(newest))
                }
                expected.eraseColor(Color.TRANSPARENT)
                reference.draw(Canvas(expected), requireNotNull(newest).stroke, 1.0)
                actual.eraseColor(Color.MAGENTA)
                check(painter.drawLatest(Canvas(actual), 384, 256)) { "Queued input starved the first callback" }
                check(actual.sameAs(expected)) { "Queued input did not draw the newest complete snapshot" }
                check(!painter.drawLatest(Canvas(actual), 384, 256)) { "Replayed a snapshot twice" }
                painter.submit(requireNotNull(newest))
                check(painter.drawLatest(Canvas(actual), 384, 256)) { "Explicit repeat was dropped" }
                painter.submit(requireNotNull(newest))
                painter.clear()
                check(!painter.drawLatest(Canvas(actual), 384, 256)) { "Cancelled frame was replayed" }
                painter.submit(requireNotNull(newest))
                check(painter.drawLatest(Canvas(actual), 384, 256)) { "Frame was lost after clearing the surface" }
                result.putInt("penCoalescedSnapshots", 128)
            } finally { expected.recycle(); actual.recycle() }
            result.putInt("penReplacementRasterCases", cases)
        }
        ui {
            view = InkCanvasView(activity, activity.repository).apply {
                drawHold = false; scribbleErase = false; inkWidth = 14f
                open(listOf(page.id), listOf(page.meta)); replacePage(page)
            }
            host = FrameLayout(activity)
            front = InkFrontBuffer(activity, { view.frontBufferPresented(it) }, { view.frontBufferRecreated() }, gate)
            view.attachFrontBuffer(front)
            host.addView(front, FrameLayout.LayoutParams(-1, -1)); host.addView(view, FrameLayout.LayoutParams(-1, -1))
            activity.findViewById<ViewGroup>(android.R.id.content).addView(host, ViewGroup.LayoutParams(-1, -1))
        }
        try {
            await("initial canonical scene", ::presented)
            send(MotionEvent.ACTION_DOWN, 200f, 300f); send(MotionEvent.ACTION_MOVE, 500f, 300f)
            await("first active stroke") { dark(350f, 300f) }
            gate.held = true
            send(MotionEvent.ACTION_UP, 520f, 300f)
            check(!presented()) { "New front input admitted before prior commit completed" }
            send(MotionEvent.ACTION_DOWN, 200f, 450f); send(MotionEvent.ACTION_MOVE, 500f, 450f)
            await("next stroke visible while commit callback held") { dark(350f, 450f) }
            screen("01-next-stroke-during-commit")
            gate.release()
            repeat(5) { check(dark(350f, 450f)) { "Prior commit erased next active stroke" }; SystemClock.sleep(20) }
            send(MotionEvent.ACTION_UP, 520f, 450f)
            await("both consecutive strokes committed") { presented() && dark(350f, 300f) && dark(350f, 450f) }
            repeat(4) { n ->
                val y = 600f + n * 60
                send(MotionEvent.ACTION_DOWN, 200f, y); send(MotionEvent.ACTION_MOVE, 500f, y); send(MotionEvent.ACTION_UP, 520f, y)
            }
            await("rapid strokes retained") { presented() && (0..3).all { dark(350f, 600f + it * 60) } }
            screen("02-rapid-strokes-committed")
            send(MotionEvent.ACTION_DOWN, 200f, 900f); send(MotionEvent.ACTION_MOVE, 500f, 900f)
            await("cancel preview visible") { dark(350f, 900f) }
            send(MotionEvent.ACTION_CANCEL, 500f, 900f)
            await("cancelled preview gone") { presented() && !dark(350f, 900f) }
            ui {
                // open() loads the canonical page asynchronously; inspect the view's loaded instance.
                val committed = requireNotNull(view.currentPage)
                check(committed.objects.size == 6) { "Cancel changed committed strokes: ${committed.objects.size}" }
                view.undo()
                check(committed.objects.size == 5) { "Undo did not remove one stroke: ${committed.objects.size}" }
                view.redo()
                check(committed.objects.size == 6) { "Redo did not restore one stroke: ${committed.objects.size}" }
            }
            await("undo redo canonical scene", ::presented)
            screen("03-cancel-undo-redo")
            result.putString("penPresentation", "pass: exact repeated-alpha raster, held-commit next stroke, rapid strokes, cancel, undo/redo")
            result.putString("penPresentationScreens", screens.absolutePath)
        } finally { gate.release(); ui { view.cancelContact(); (host.parent as? ViewGroup)?.removeView(host) } }
    }
}
