package com.ethran.notable.io

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.io.OnyxHWREngine.HwrWord
import io.shipbook.shipbooksdk.ShipBook
import java.io.File
import java.io.FileOutputStream

private val log = ShipBook.getLogger("PDFGenerator")

/**
 * Generates searchable PDF files with handwritten strokes and invisible text layer.
 */
object SearchablePdfGenerator {
    
    // Default dimensions in points (72 DPI), portrait A4.
    private const val DEFAULT_PAGE_WIDTH = 595
    private const val DEFAULT_PAGE_HEIGHT = 842
    const val A5_PAGE_WIDTH = 420
    const val A5_PAGE_HEIGHT = 595
    private const val DEBUG_DRAW_LAYOUT_BOXES = false
    
    data class PdfGenerationResult(
        val success: Boolean,
        val pdfFile: File? = null,
        val errorMessage: String? = null
    )

    data class PdfPageContent(
        val strokes: List<Stroke>,
        val recognizedText: String,
        val recognizedWords: List<HwrWord> = emptyList(),
        val pageWidth: Float,
        val pageHeight: Float
    )

    private data class Box(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val centerY: Float get() = (top + bottom) * 0.5f
    }
    
    /**
     * Generate a searchable PDF with strokes and invisible text overlay.
     * 
     * @param strokes List of strokes to render
     * @param recognizedText Full recognized text from HWR
     * @param outputFile Output PDF file
     * @param pageWidth Original page width (for scaling)
     * @param pageHeight Original page height (for scaling)
     */
    fun generateSearchablePdf(
        strokes: List<Stroke>,
        recognizedText: String,
        outputFile: File,
        pageWidth: Float = 1404f,
        pageHeight: Float = 1872f
    ): PdfGenerationResult {
        val singlePage = PdfPageContent(
            strokes = strokes,
            recognizedText = recognizedText,
            recognizedWords = emptyList(),
            pageWidth = pageWidth,
            pageHeight = pageHeight
        )
        return generateSearchablePdfForPages(listOf(singlePage), outputFile)
    }

    /**
     * Generate a searchable PDF for multiple logical note pages.
     */
    fun generateSearchablePdfForPages(
        pages: List<PdfPageContent>,
        outputFile: File,
        outputPageWidth: Int = DEFAULT_PAGE_WIDTH,
        outputPageHeight: Int = DEFAULT_PAGE_HEIGHT
    ): PdfGenerationResult {
        try {
            if (pages.isEmpty()) {
                return PdfGenerationResult(
                    success = false,
                    errorMessage = "No pages to render"
                )
            }

            log.i("Generating searchable PDF: ${outputFile.name}, pages=${pages.size}")
            android.util.Log.i("PDFGenerator", "Creating PDF with ${pages.size} page(s)")
            
            // Create PDF document
            val document = PdfDocument()
            pages.forEachIndexed { idx, pageContent ->
                val pageInfo = PdfDocument.PageInfo.Builder(outputPageWidth, outputPageHeight, idx + 1).create()
                val page = document.startPage(pageInfo)
                val canvas = page.canvas

                val safeWidth = pageContent.pageWidth.coerceAtLeast(1f)
                val safeHeight = pageContent.pageHeight.coerceAtLeast(1f)
                // Preserve aspect ratio to avoid x/y distortion.
                val scale = minOf(
                    outputPageWidth.toFloat() / safeWidth,
                    outputPageHeight.toFloat() / safeHeight
                )
                val drawnWidth = safeWidth * scale
                val drawnHeight = safeHeight * scale
                val offsetX = (outputPageWidth.toFloat() - drawnWidth) * 0.5f
                val offsetY = (outputPageHeight.toFloat() - drawnHeight) * 0.5f

                renderStrokes(canvas, pageContent.strokes, scale, scale, offsetX, offsetY)

                if (pageContent.recognizedText.isNotBlank()) {
                    addInvisibleTextLayer(
                        canvas = canvas,
                        text = pageContent.recognizedText,
                        recognizedWords = pageContent.recognizedWords,
                        strokes = pageContent.strokes,
                        scaleX = scale,
                        scaleY = scale,
                        offsetX = offsetX,
                        offsetY = offsetY,
                        outputPageHeight = outputPageHeight
                    )
                }

                document.finishPage(page)
            }
            
            // Write to file
            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { outputStream ->
                document.writeTo(outputStream)
            }
            
            document.close()
            
            log.i("PDF generated successfully: ${outputFile.absolutePath}")
            android.util.Log.i("PDFGenerator", "PDF file size: ${outputFile.length()} bytes")
            
            return PdfGenerationResult(
                success = true,
                pdfFile = outputFile
            )
            
        } catch (e: Exception) {
            log.e("Failed to generate PDF: ${e.message}")
            android.util.Log.e("PDFGenerator", "Error generating PDF", e)
            return PdfGenerationResult(
                success = false,
                errorMessage = e.message
            )
        }
    }
    
    /**
     * Render strokes onto the PDF canvas.
     */
    private fun renderStrokes(
        canvas: Canvas,
        strokes: List<Stroke>,
        scaleX: Float,
        scaleY: Float,
        offsetX: Float,
        offsetY: Float
    ) {
        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        
        android.util.Log.d("PDFGenerator", "Rendering ${strokes.size} strokes")
        
        for (stroke in strokes) {
            if (stroke.points.isEmpty()) continue
            
            // Set stroke properties
            paint.color = stroke.color
            paint.strokeWidth = stroke.size * minOf(scaleX, scaleY)
            
            // Create a fresh path for this stroke
            val path = Path()
            
            // Draw points, skipping where pressure is zero (pen lifted)
            var pathStarted = false
            for ((i, point) in stroke.points.withIndex()) {
                val x = point.x * scaleX
                val y = point.y * scaleY
                val translatedX = x + offsetX
                val translatedY = y + offsetY
                val pressure = point.pressure ?: 1.0f  // Treat null as pen down
                
                // Skip points with zero pressure (pen not touching)
                if (pressure < 0.01f) {
                    pathStarted = false
                    continue
                }
                
                // Start or continue path
                if (!pathStarted) {
                    path.moveTo(translatedX, translatedY)
                    pathStarted = true
                } else {
                    path.lineTo(translatedX, translatedY)
                }
            }
            
            // Draw the complete path for this stroke
            canvas.drawPath(path, paint)
        }
        
        android.util.Log.d("PDFGenerator", "Finished rendering ${strokes.size} strokes")
    }
    
    /**
     * Add invisible text layer for searchability.
     * Text is rendered with very low alpha to be invisible but searchable.
     */
    private fun addInvisibleTextLayer(
        canvas: Canvas,
        text: String,
        recognizedWords: List<HwrWord>,
        strokes: List<Stroke>,
        scaleX: Float,
        scaleY: Float,
        offsetX: Float,
        offsetY: Float,
        outputPageHeight: Int
    ) {
        val textPaint = TextPaint().apply {
            // Use alpha=1 (almost fully transparent) so it's in the PDF but invisible
            // Format: ARGB - 0x01000000 = alpha=1, RGB=0 (black)
            color = 0x01000000
            textSize = 10f
            isAntiAlias = true
        }

        val lines = text
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (lines.isEmpty()) {
            android.util.Log.d("PDFGenerator", "No text to add to searchable layer")
            return
        }

        val strokeBoxes = strokes
            .filter { it.points.isNotEmpty() }
            .mapNotNull { stroke -> computeBoxFromPoints(stroke, scaleX, scaleY, offsetX, offsetY) }
            .filter { it.width > 0f && it.height > 0f }
            .sortedBy { it.centerY }

        if (strokeBoxes.isEmpty()) {
            android.util.Log.d("PDFGenerator", "No stroke bounds for text layer, using fallback")
            drawFallbackTextLayer(canvas, lines, textPaint, outputPageHeight)
            return
        }

        val textBoxesUsed = mutableListOf<Box>()

        // Preferred path: use HWR-provided word bounding boxes when available.
        val placedWithHwrWords = tryPlaceFromHwrWords(
            canvas = canvas,
            textPaint = textPaint,
            strokeBoxes = strokeBoxes,
            hwrWords = recognizedWords,
            textBoxesUsed = textBoxesUsed
        )
        if (placedWithHwrWords) {
            if (DEBUG_DRAW_LAYOUT_BOXES) {
                // Keep orange line groups only for visual context in debug.
                val lineGroups = groupBoxesIntoLines(strokeBoxes)
                drawDebugLayoutBoxes(canvas, strokeBoxes, lineGroups, textBoxesUsed)
            }
            android.util.Log.d(
                "PDFGenerator",
                "Added searchable text layer from HWR words: ${recognizedWords.size} tokens, boxes=${textBoxesUsed.size}"
            )
            return
        }

        val lineGroups = groupBoxesIntoLines(strokeBoxes)
        if (lineGroups.isEmpty()) {
            drawFallbackTextLayer(canvas, lines, textPaint, outputPageHeight)
            return
        }

        // Rebalance OCR lines to stroke line count to reduce index drift caused by
        // OCR newline differences (merged/split lines).
        val balancedLines = rebalanceLinesToTargetCount(lines, lineGroups.size)

        // Map recognized text lines to stroke lines by order.
        val pairCount = minOf(balancedLines.size, lineGroups.size)
        for (i in 0 until pairCount) {
            val lineText = balancedLines[i]
            val lineBoxes = lineGroups[i]
            val words = lineText.split(Regex("\\s+")).filter { it.isNotBlank() }
            val lineUnion = union(lineBoxes)

            if (words.isNotEmpty()) {
                // Allocate text boxes inside the line union proportionally by word length.
                // This is much more stable than inferring words from stroke gaps.
                val wordBoxes = allocateWordBoxes(lineUnion, words)
                words.zip(wordBoxes).forEach { (word, box) ->
                    drawFittedTextInBox(canvas, word, box, textPaint)
                    textBoxesUsed.add(box)
                }
            } else {
                drawFittedTextInBox(canvas, lineText, lineUnion, textPaint)
                textBoxesUsed.add(lineUnion)
            }
        }

        if (DEBUG_DRAW_LAYOUT_BOXES) {
            drawDebugLayoutBoxes(canvas, strokeBoxes, lineGroups, textBoxesUsed)
        }

        android.util.Log.d(
            "PDFGenerator",
            "Added searchable text layer: ${lines.size} lines (balanced=${balancedLines.size}), ${text.length} chars, mapped to ${lineGroups.size} stroke lines"
        )
    }

    private fun groupBoxesIntoLines(boxes: List<Box>): List<List<Box>> {
        if (boxes.isEmpty()) return emptyList()

        val heights = boxes.map { it.height }.sorted()
        val medianHeight = heights[heights.size / 2]
        val yThreshold = (medianHeight * 1.25f).coerceIn(12f, 120f)

        val groups = mutableListOf<MutableList<Box>>()
        for (box in boxes) {
            val target = groups
                .map { grp -> grp to kotlin.math.abs(centerY(grp) - box.centerY) }
                .minByOrNull { it.second }

            if (target != null && target.second <= yThreshold) {
                target.first.add(box)
            } else {
                groups.add(mutableListOf(box))
            }
        }

        return groups
            .map { it.sortedBy { b -> b.left } }
            .sortedBy { it.minOf { b -> b.top } }
    }

    private fun allocateWordBoxes(lineBox: Box, words: List<String>): List<Box> {
        if (words.isEmpty()) return emptyList()
        if (words.size == 1) return listOf(lineBox)

        val weights = words.map { maxOf(it.length, 1).toFloat() }
        val weightSum = weights.sum().coerceAtLeast(1f)

        val gap = (lineBox.width * 0.02f).coerceIn(4f, 16f)
        val totalGap = gap * (words.size - 1)
        val contentWidth = (lineBox.width - totalGap).coerceAtLeast(words.size.toFloat())

        val boxes = mutableListOf<Box>()
        var x = lineBox.left
        for ((idx, w) in weights.withIndex()) {
            val width = if (idx == words.lastIndex) {
                // absorb any float rounding remainder in the last word
                lineBox.right - x
            } else {
                contentWidth * (w / weightSum)
            }
            boxes.add(
                Box(
                    left = x,
                    top = lineBox.top,
                    right = (x + width).coerceAtMost(lineBox.right),
                    bottom = lineBox.bottom
                )
            )
            x += width + gap
        }
        return boxes
    }

    private fun rebalanceLinesToTargetCount(lines: List<String>, targetCount: Int): List<String> {
        if (targetCount <= 0) return emptyList()
        if (lines.isEmpty()) return emptyList()

        val out = lines
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()

        if (out.isEmpty()) return emptyList()

        // If OCR merged lines, split longest line by words until counts match.
        while (out.size < targetCount) {
            val idx = out.indices.maxByOrNull { out[it].length } ?: break
            val words = out[idx].split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.size < 2) break

            val splitAt = (words.size / 2).coerceAtLeast(1)
            val left = words.take(splitAt).joinToString(" ").trim()
            val right = words.drop(splitAt).joinToString(" ").trim()
            if (left.isEmpty() || right.isEmpty()) break

            out[idx] = left
            out.add(idx + 1, right)
        }

        // If OCR over-split lines, merge shortest adjacent pair until counts match.
        while (out.size > targetCount && out.size >= 2) {
            var bestIdx = 0
            var bestScore = Int.MAX_VALUE
            for (i in 0 until out.lastIndex) {
                val score = out[i].length + out[i + 1].length
                if (score < bestScore) {
                    bestScore = score
                    bestIdx = i
                }
            }
            val merged = (out[bestIdx] + " " + out[bestIdx + 1]).trim()
            out[bestIdx] = merged
            out.removeAt(bestIdx + 1)
        }

        return out
    }

    private fun drawFittedTextInBox(canvas: Canvas, text: String, box: Box, paint: TextPaint) {
        if (text.isBlank() || box.width <= 2f || box.height <= 2f) return

        val maxHeightSize = (box.height * 1.05f).coerceIn(6f, 140f)
        val minSize = 4f
        val targetWidth = box.width * 0.98f

        paint.textSize = maxHeightSize
        val measuredAtMax = paint.measureText(text)
        if (measuredAtMax > 0f && measuredAtMax > targetWidth) {
            // O(1) width fit instead of iterative decrement loop per word.
            val fitted = (maxHeightSize * (targetWidth / measuredAtMax)).coerceAtLeast(minSize)
            paint.textSize = fitted
        }

        val fm = paint.fontMetrics
        val baseline = box.top + (box.height - (fm.descent - fm.ascent)) * 0.5f - fm.ascent
        canvas.drawText(text, box.left, baseline, paint)
    }

    private fun drawFallbackTextLayer(canvas: Canvas, lines: List<String>, paint: TextPaint, outputPageHeight: Int = DEFAULT_PAGE_HEIGHT) {
        var y = 30f
        for (line in lines) {
            if (y > outputPageHeight - 30) break
            canvas.drawText(line, 30f, y, paint)
            y += paint.textSize + 4f
        }
    }

    private fun tryPlaceFromHwrWords(
        canvas: Canvas,
        textPaint: TextPaint,
        strokeBoxes: List<Box>,
        hwrWords: List<HwrWord>,
        textBoxesUsed: MutableList<Box>
    ): Boolean {
        val boxedWords = hwrWords
            .mapNotNull { word ->
                val b = word.box ?: return@mapNotNull null
                val label = word.label.trim()
                if (label.isEmpty() || label == "\\n") return@mapNotNull null
                label to Box(b.x, b.y, b.x + b.width, b.y + b.height)
            }
            .filter { (_, b) -> b.width > 0f && b.height > 0f }

        if (boxedWords.isEmpty()) return false

        val hwrUnion = union(boxedWords.map { it.second })
        val strokeUnion = union(strokeBoxes)
        if (hwrUnion.width <= 0f || hwrUnion.height <= 0f) return false
        if (strokeUnion.width <= 0f || strokeUnion.height <= 0f) return false

        val sx = strokeUnion.width / hwrUnion.width
        val sy = strokeUnion.height / hwrUnion.height
        val tx = strokeUnion.left - hwrUnion.left * sx
        val ty = strokeUnion.top - hwrUnion.top * sy

        for ((label, src) in boxedWords) {
            val mapped = Box(
                left = src.left * sx + tx,
                top = src.top * sy + ty,
                right = src.right * sx + tx,
                bottom = src.bottom * sy + ty
            )
            drawFittedTextInBox(canvas, label, mapped, textPaint)
            textBoxesUsed.add(mapped)
        }

        return true
    }

    private fun drawDebugLayoutBoxes(
        canvas: Canvas,
        strokeBoxes: List<Box>,
        lineGroups: List<List<Box>>,
        textBoxes: List<Box>
    ) {
        val strokePaint = Paint().apply {
            style = Paint.Style.STROKE
            color = android.graphics.Color.argb(220, 255, 0, 0) // red
            strokeWidth = 1.2f
            isAntiAlias = true
        }
        val linePaint = Paint().apply {
            style = Paint.Style.STROKE
            color = android.graphics.Color.argb(220, 255, 140, 0) // orange
            strokeWidth = 1.4f
            isAntiAlias = true
        }
        val textPaint = Paint().apply {
            style = Paint.Style.STROKE
            color = android.graphics.Color.argb(220, 0, 120, 255) // blue
            strokeWidth = 1.8f
            isAntiAlias = true
        }

        // Raw stroke boxes (red)
        strokeBoxes.forEach { box ->
            canvas.drawRect(box.left, box.top, box.right, box.bottom, strokePaint)
        }

        // Grouped line unions (orange)
        lineGroups.forEach { line ->
            val u = union(line)
            canvas.drawRect(u.left, u.top, u.right, u.bottom, linePaint)
        }

        // Final text placement boxes (blue)
        textBoxes.forEach { box ->
            canvas.drawRect(box.left, box.top, box.right, box.bottom, textPaint)
        }
    }

    private fun centerY(boxes: List<Box>): Float = boxes.map { it.centerY }.average().toFloat()

    private fun computeBoxFromPoints(
        stroke: Stroke,
        scaleX: Float,
        scaleY: Float,
        offsetX: Float,
        offsetY: Float
    ): Box? {
        if (stroke.points.isEmpty()) return null

        val minX = stroke.points.minOf { it.x } * scaleX + offsetX
        val maxX = stroke.points.maxOf { it.x } * scaleX + offsetX
        val minY = stroke.points.minOf { it.y } * scaleY + offsetY
        val maxY = stroke.points.maxOf { it.y } * scaleY + offsetY

        if (maxX <= minX || maxY <= minY) return null
        return Box(minX, minY, maxX, maxY)
    }

    private fun union(boxes: List<Box>): Box {
        val rect = RectF(
            boxes.minOf { it.left },
            boxes.minOf { it.top },
            boxes.maxOf { it.right },
            boxes.maxOf { it.bottom }
        )
        return Box(rect.left, rect.top, rect.right, rect.bottom)
    }
}
