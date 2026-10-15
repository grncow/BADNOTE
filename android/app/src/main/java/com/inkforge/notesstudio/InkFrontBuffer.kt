package com.inkforge.notesstudio

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Picture
import android.graphics.Paint
import android.hardware.HardwareBuffer
import android.os.Build
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer
import androidx.graphics.surface.SurfaceControlCompat
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executor
import org.json.JSONObject

/** API-neutral editor contract. Only API 31+ constructs the SurfaceView implementation. */
internal data class InkFrontFrame(val stroke: JSONObject, val x: Float, val y: Float, val scale: Float,
                                  val pageWidth: Float, val pageHeight: Float,
                                  val points: List<InkPoint>? = null) {
    constructor(stroke: String, x: Float, y: Float, scale: Float, pageWidth: Float, pageHeight: Float) :
        this(JSONObject(stroke), x, y, scale, pageWidth, pageHeight)
}

internal interface InkFrontSurface {
    val presented: Boolean
    fun hideForLegacy()
    fun renderScene(picture: Picture, width: Int, height: Int, commitFront: Boolean)
    fun renderFront(frame: InkFrontFrame): Boolean
    fun cancelFront()
}

/** One renderer per drawing thread; does not access SurfaceView or editor state. */
@RequiresApi(Build.VERSION_CODES.S)
internal class InkFrontFrameRenderer {
    private val brush = NoteRenderer()
    private val replacePaint = Paint().apply { blendMode = BlendMode.SRC }
    private val pending = AtomicReference<InkFrontFrame?>()

    fun submit(frame: InkFrontFrame) { pending.set(frame) }
    fun clear() { pending.set(null) }

    fun drawLatest(canvas: Canvas, bufferWidth: Int, bufferHeight: Int): Boolean {
        val frame = pending.getAndSet(null) ?: return false
        draw(canvas, bufferWidth, bufferHeight, frame)
        return true
    }

    fun draw(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, frame: InkFrontFrame) {
        // Replace transparent pixels too, without exposing CLEAR on the displayed front.
        // Full replay preserves taper and alpha, which depend on the complete stroke.
        val saved = canvas.saveLayer(0f, 0f, bufferWidth.toFloat(), bufferHeight.toFloat(), replacePaint)
        try {
            canvas.translate(frame.x, frame.y)
            canvas.scale(frame.scale, frame.scale)
            canvas.clipRect(0f, 0f, frame.pageWidth, frame.pageHeight)
            val points = frame.points
            if (points == null) brush.draw(canvas, frame.stroke, frame.scale.toDouble())
            else brush.stroke(canvas, frame.stroke, points, frame.scale.toDouble())
        } finally { canvas.restoreToCount(saved) }
    }
}

/** Owns the editor's final page image after its multi-buffer transaction is committed. */
@RequiresApi(Build.VERSION_CODES.S)
internal class InkFrontBuffer(context: Context, private val onPresented: (Boolean) -> Unit,
                              private val onRecreated: () -> Unit,
                              private val callbackExecutor: Executor = context.mainExecutor) : SurfaceView(context),
    CanvasFrontBufferedRenderer.Callback<InkFrontFrame>, InkFrontSurface {
    private data class Scene(val version: Long, val picture: Picture, val width: Int, val height: Int)

    private var renderer: CanvasFrontBufferedRenderer<InkFrontFrame>? = null
    @Volatile private var scene: Scene? = null
    private val renderedVersions = ArrayDeque<Long>() // Renderer callbacks share its worker thread.
    private val frontPainter = InkFrontFrameRenderer()
    private var nextVersion = 0L
    override var presented = false
        private set
    val frontFrames = AtomicInteger()
    val multiFrames = AtomicInteger()
    val multiCompletions = AtomicInteger()
    val cancelledFrames = AtomicInteger()
    val releasedRenderers = AtomicInteger()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (renderer == null) renderer = CanvasFrontBufferedRenderer(this, this, HardwareBuffer.RGBA_8888)
        onRecreated()
    }

    override fun onDetachedFromWindow() {
        hideForLegacy()
        renderer?.release(true)
        renderer = null
        scene = null
        synchronized(renderedVersions) { renderedVersions.clear() }
        releasedRenderers.incrementAndGet()
        super.onDetachedFromWindow()
    }

    override fun hideForLegacy() {
        // A previously rendered transaction may commit after a page or tool switch.
        frontPainter.clear()
        ++nextVersion
        if (presented) { presented = false; onPresented(false) }
    }

    override fun renderScene(picture: Picture, width: Int, height: Int, commitFront: Boolean) {
        // A previous commit must never hide a newly admitted front stroke. The View owns
        // presentation until this scene's transaction has actually committed.
        frontPainter.clear()
        if (presented) { presented = false; onPresented(false) }
        scene = Scene(++nextVersion, picture, width, height)
        val current = renderer ?: return
        if (commitFront) current.commit() else current.renderMultiBufferedLayer(emptyList())
    }

    override fun renderFront(frame: InkFrontFrame): Boolean {
        val current = renderer ?: return false
        if (!presented || !current.isValid()) return false
        frontPainter.submit(frame)
        current.renderFrontBufferedLayer(frame)
        return true
    }

    override fun cancelFront() {
        // Cancelling transient ink keeps the already committed scene reusable.
        frontPainter.clear()
        cancelledFrames.incrementAndGet()
        renderer?.cancel()
    }

    override fun onDrawFrontBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, param: InkFrontFrame) {
        // A merged callback may carry an old parameter. Always draw the newest available
        // snapshot once, so continuous input cannot starve behind superseded callbacks.
        if (frontPainter.drawLatest(canvas, bufferWidth, bufferHeight)) frontFrames.incrementAndGet()
    }

    override fun onDrawMultiBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int,
                                           params: Collection<InkFrontFrame>) {
        val snapshot = scene
        canvas.drawColor(Color.BLACK, BlendMode.CLEAR)
        if (snapshot != null && snapshot.width == bufferWidth && snapshot.height == bufferHeight)
            canvas.drawPicture(snapshot.picture)
        synchronized(renderedVersions) { renderedVersions.addLast(snapshot?.version ?: 0L) }
        multiFrames.incrementAndGet()
    }

    override fun onMultiBufferedLayerRenderComplete(
        frontBufferedLayerSurfaceControl: SurfaceControlCompat,
        multiBufferedLayerSurfaceControl: SurfaceControlCompat,
        transaction: SurfaceControlCompat.Transaction
    ) {
        val version = synchronized(renderedVersions) { if (renderedVersions.isEmpty()) 0L else renderedVersions.removeFirst() }
        multiCompletions.incrementAndGet()
        transaction.addTransactionCommittedListener(callbackExecutor,
            object : SurfaceControlCompat.TransactionCommittedListener {
                override fun onTransactionCommitted() {
                    val latest = scene
                    if (isAttachedToWindow && version != 0L && version == nextVersion &&
                        version == latest?.version &&
                        width == latest.width && height == latest.height && !presented) {
                        presented = true
                        onPresented(true)
                    }
                }
            }
        )
    }
}
