package com.ethran.notable.editor.drawing

import androidx.compose.ui.geometry.Offset
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke as InkStroke
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.offsetStroke

/**
 * Singleton renderer — thread-safe, reuse across draw calls.
 */
val inkRenderer: CanvasStrokeRenderer by lazy { CanvasStrokeRenderer.create() }

// Cache brush families (they're lazy-computed internally, but let's not re-invoke every stroke)
private val markerFamily by lazy { StockBrushes.marker() }
private val pressurePenFamily by lazy { StockBrushes.pressurePen() }
private val highlighterFamily by lazy { StockBrushes.highlighter() }
private val dashedLineFamily by lazy { StockBrushes.dashedLine() }

/**
 * Map our Pen types to Ink API BrushFamily + create a Brush with the stroke's color/size.
 */
fun brushForStroke(stroke: Stroke): Brush {
    val family = when (stroke.pen) {
        Pen.BALLPEN, Pen.REDBALLPEN, Pen.GREENBALLPEN, Pen.BLUEBALLPEN ->
            markerFamily
        Pen.FOUNTAIN ->
            pressurePenFamily
        Pen.BRUSH ->
            pressurePenFamily
        Pen.PENCIL ->
            markerFamily
        Pen.MARKER ->
            highlighterFamily
        Pen.DASHED ->
            dashedLineFamily
    }
    return Brush.createWithColorIntArgb(
        family = family,
        colorIntArgb = stroke.color,
        size = stroke.size,
        epsilon = 0.1f
    )
}

// Spacing between synthetic timestamps when dt is missing (ms per point).
// 5ms ≈ 200Hz stylus sample rate — realistic enough for Ink API's stroke smoothing.
private const val SYNTHETIC_DT_MS = 5L

/**
 * Convert our Stroke data model to an Ink API Stroke for rendering.
 */
fun Stroke.toInkStroke(offset: Offset = Offset.Zero): InkStroke {
    val src = if (offset != Offset.Zero) offsetStroke(this, offset) else this
    val batch = MutableStrokeInputBatch()
    val points = src.points
    if (points.isEmpty()) {
        return InkStroke(brushForStroke(this), batch.toImmutable())
    }

    for (i in points.indices) {
        val pt = points[i]
        // Use real dt if available, otherwise synthesize realistic timing
        val elapsedMs = pt.dt?.toLong() ?: (i * SYNTHETIC_DT_MS)
        val pressure = pt.pressure?.let { it / maxPressure.toFloat() } ?: 0.5f
        // tiltRadians must be in [0, π/2] or -1 (unset). Our tiltX is degrees [-90, 90].
        val tiltRad = pt.tiltX?.let {
            Math.toRadians(it.toDouble()).toFloat().coerceIn(0f, Math.PI.toFloat() / 2f)
        } ?: -1f

        batch.add(
            type = InputToolType.STYLUS,
            x = pt.x,
            y = pt.y,
            elapsedTimeMillis = elapsedMs,
            pressure = pressure.coerceIn(0f, 1f),
            tiltRadians = tiltRad,
            orientationRadians = -1f  // not captured by our hardware
        )
    }
    return InkStroke(brushForStroke(this), batch)
}
