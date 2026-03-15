package com.ethran.notable.editor.utils

import android.graphics.Rect
import androidx.core.graphics.toRect
import com.ethran.notable.data.db.Annotation
import com.ethran.notable.data.db.AnnotationType
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.state.AnnotationMode
import com.onyx.android.sdk.api.device.epd.EpdController
import io.shipbook.shipbooksdk.ShipBook


private val log = ShipBook.getLogger("draw")

// touchpoints are in page coordinates
fun handleDraw(
    page: PageView,
    historyBucket: MutableList<String>,
    strokeSize: Float,
    color: Int,
    pen: Pen,
    touchPoints: List<StrokePoint>
) {
    try {
        val boundingBox = calculateBoundingBox(touchPoints) { Pair(it.x, it.y) }

        //move rectangle
        boundingBox.inset(-strokeSize, -strokeSize)

        val stroke = Stroke(
            size = strokeSize,
            pen = pen,
            pageId = page.currentPageId,
            top = boundingBox.top,
            bottom = boundingBox.bottom,
            left = boundingBox.left,
            right = boundingBox.right,
            points = touchPoints,
            color = color,
            maxPressure = EpdController.getMaxTouchPressure().toInt()
        )
        page.addStrokes(listOf(stroke))
        // this is causing lagging and crushing, neo pens are not good
        page.drawAreaPageCoordinates(strokeBounds(stroke).toRect())
        historyBucket.add(stroke.id)
    } catch (e: Exception) {
        log.e("Handle Draw: An error occurred while handling the drawing: ${e.message}")
    }
}

fun handleAnnotation(
    page: PageView,
    annotationMode: AnnotationMode,
    touchPoints: List<StrokePoint>
): String? {
    try {
        if (touchPoints.isEmpty() || annotationMode == AnnotationMode.None) return null

        val boundingBox = calculateBoundingBox(touchPoints) { Pair(it.x, it.y) }

        val type = when (annotationMode) {
            AnnotationMode.WikiLink -> AnnotationType.WIKILINK.name
            AnnotationMode.Tag -> AnnotationType.TAG.name
            else -> return null
        }

        val annotation = Annotation(
            type = type,
            x = boundingBox.left,
            y = boundingBox.top,
            width = boundingBox.right - boundingBox.left,
            height = boundingBox.bottom - boundingBox.top,
            pageId = page.currentPageId
        )

        page.addAnnotations(listOf(annotation))
        // Expand redraw area to include rendered bracket/hash glyphs
        // that extend beyond the annotation bounding box
        val boxHeight = (boundingBox.bottom - boundingBox.top)
        val extraH = (boxHeight * 1.2f).toInt().coerceAtLeast(60)
        val extraV = (boxHeight * 0.2f).toInt().coerceAtLeast(10)
        val expandedBounds = Rect(
            (boundingBox.left - extraH).toInt(),
            (boundingBox.top - extraV).toInt(),
            (boundingBox.right + extraH).toInt(),
            (boundingBox.bottom + extraV).toInt()
        )
        page.drawAreaPageCoordinates(expandedBounds)
        return annotation.id
    } catch (e: Exception) {
        log.e("Handle Annotation: An error occurred: ${e.message}")
        return null
    }
}
