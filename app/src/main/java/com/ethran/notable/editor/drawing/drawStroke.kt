package com.ethran.notable.editor.drawing

import android.graphics.Canvas
import android.graphics.Matrix
import androidx.compose.ui.geometry.Offset
import com.ethran.notable.data.db.Stroke
import io.shipbook.shipbooksdk.ShipBook


private val strokeDrawingLogger = ShipBook.getLogger("drawStroke")

private val identityMatrix = Matrix()

fun drawStroke(canvas: Canvas, stroke: Stroke, offset: Offset) {
    if (stroke.points.isEmpty()) return

    try {
        val inkStroke = stroke.toInkStroke(offset)
        inkRenderer.draw(
            canvas = canvas,
            stroke = inkStroke,
            strokeToScreenTransform = identityMatrix
        )
    } catch (e: Exception) {
        strokeDrawingLogger.e(
            "Drawing stroke failed: id=${stroke.id} pen=${stroke.pen} " +
            "points=${stroke.points.size} size=${stroke.size}: ${e.message}"
        )
    }
}
