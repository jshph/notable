package com.ethran.notable.io

import android.content.Context
import android.net.Uri
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Date
import java.util.zip.ZipInputStream
import org.json.JSONObject
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.utils.Pen
import io.shipbook.shipbooksdk.ShipBook

private val log = ShipBook.getLogger("OnyxNoteParser")

/**
 * Parser for native Onyx Boox .note files.
 * 
 * .note files are ZIP archives containing:
 * - note/pb/note_info: Protobuf with document metadata
 * - shape (ZIP files): Stroke metadata (bounding boxes, pen settings)
 * - point (binary files): Stroke coordinate data
 * 
 * Usage example in code comments.
 */
object OnyxNoteParser {
    
    data class StrokeBbox(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )
    
    data class StrokeMeta(
        val id: String,
        val pointsId: String,
        val bbox: StrokeBbox,
        val dpi: Float = 320f,
        val maxPressure: Float = 4095f
    )

    data class NotePage(
        val id: String,
        val strokes: List<Stroke>
    )
    
    data class NoteDocument(
        val title: String,
        val pageWidth: Float,
        val pageHeight: Float,
        val pages: List<NotePage>
    ) {
        val strokes: List<Stroke>
            get() = pages.flatMap { it.strokes }

        val pageId: String
            get() = pages.firstOrNull()?.id ?: "imported-page"
    }
    
    /**
     * Parse a .note file from an InputStream.
     */
    fun parseNoteFile(inputStream: InputStream): NoteDocument? {
        return try {
            log.i("Starting .note file parsing")
            android.util.Log.i("OnyxNoteParser", "Starting .note file parsing")
            val zipStream = ZipInputStream(inputStream)
            
            var noteInfo: JSONObject? = null
            val shapeMetasByPage = linkedMapOf<String, List<StrokeMeta>>()
            val shapeTimestampByPage = mutableMapOf<String, Long>()
            val pointsByPage = linkedMapOf<String, ByteArray>()
            val pageOrder = mutableListOf<String>()
            var entriesFound = 0
            
            // Read all entries from ZIP
            android.util.Log.d("OnyxNoteParser", "Reading ZIP entries...")
            var entry = zipStream.nextEntry
            while (entry != null) {
                val name = entry.name
                entriesFound++
                log.d("ZIP entry: $name")
                android.util.Log.d("OnyxNoteParser", "ZIP entry: $name")
                
                when {
                    name.contains("note/pb/note_info") -> {
                        val data = zipStream.readBytes()
                        log.i("Found note_info, size: ${data.size} bytes")
                        noteInfo = parseNoteInfo(data)
                        log.i("Parsed noteInfo: ${noteInfo != null}")
                    }
                    isActiveShapeEntry(name) -> {
                        val shapeData = zipStream.readBytes()
                        val pageKey = extractPageKeyFromShapeEntry(name)
                        if (pageKey != null) {
                            val shapeMetas = parseShapeMetadata(shapeData)
                            val shapeTimestamp = extractEntryTimestamp(name)
                            val currentTimestamp = shapeTimestampByPage[pageKey] ?: Long.MIN_VALUE
                            if (shapeTimestamp >= currentTimestamp) {
                                shapeMetasByPage[pageKey] = shapeMetas
                                shapeTimestampByPage[pageKey] = shapeTimestamp
                            }
                            if (!pageOrder.contains(pageKey)) pageOrder.add(pageKey)
                            log.i("Parsed ${shapeMetas.size} stroke metadata for page=$pageKey")
                        }
                    }
                    isActivePointsEntry(name) -> {
                        val pointsData = zipStream.readBytes()
                        val pageKey = extractPageKeyFromPointsEntry(name)
                        if (pageKey != null) {
                            pointsByPage[pageKey] = pointsData
                            if (!pageOrder.contains(pageKey)) pageOrder.add(pageKey)
                            log.i("Found points file for page=$pageKey, size: ${pointsData.size} bytes")
                        }
                    }
                }
                
                zipStream.closeEntry()
                entry = zipStream.nextEntry
            }
            
            log.i("Total ZIP entries: $entriesFound")
            log.i(
                "noteInfo: ${noteInfo != null}, pagesWithShapes=${shapeMetasByPage.size}, pagesWithPoints=${pointsByPage.size}"
            )
            android.util.Log.i("OnyxNoteParser", "Total ZIP entries: $entriesFound")
            android.util.Log.i(
                "OnyxNoteParser",
                "noteInfo: ${noteInfo != null}, pagesWithShapes=${shapeMetasByPage.size}, pagesWithPoints=${pointsByPage.size}"
            )
            
            if (pointsByPage.isEmpty()) {
                log.w("Missing points data")
                android.util.Log.w(
                    "OnyxNoteParser",
                    "Missing points data - pagesWithPoints=${pointsByPage.size}, pagesWithShapes=${shapeMetasByPage.size}"
                )
                null
            } else {
                val pages = mutableListOf<NotePage>()
                for (pageKey in pageOrder) {
                    val pointsData = pointsByPage[pageKey] ?: continue
                    val shapeMetas = shapeMetasByPage[pageKey].orEmpty()

                    log.i("Parsing points and assigning strokes for page=$pageKey")
                    val pointChunks = parsePointChunks(pointsData)
                    val strokes = if (pointChunks.isNotEmpty()) {
                        val totalPoints = pointChunks.values.sumOf { it.size }
                        log.i("Page=$pageKey parsed $totalPoints points in ${pointChunks.size} chunks")
                        assignChunksToStrokes(pointChunks, shapeMetas, pageKey)
                    } else {
                        val allPoints = parsePointsFileLegacy(pointsData)
                        log.i("Page=$pageKey chunk parsing unavailable, fallback parsed ${allPoints.size} points")
                        android.util.Log.w("OnyxNoteParser", "Chunk parsing unavailable for page=$pageKey, using legacy parser")
                        assignPointsToStrokes(allPoints, shapeMetas, pageKey)
                    }

                    if (strokes.isNotEmpty()) {
                        pages.add(NotePage(id = pageKey, strokes = strokes))
                        log.i("Created ${strokes.size} strokes for page=$pageKey")
                    }
                }

                if (pages.isEmpty()) {
                    log.w("No pages with drawable strokes parsed")
                    null
                } else {
                    // Extract document info
                    val title = noteInfo?.optString("title") ?: "Untitled"
                    val pageInfo = noteInfo?.optJSONObject("pageInfo")
                    val pageWidth = pageInfo?.optDouble("width", 1860.0)?.toFloat() ?: 1860f
                    val pageHeight = pageInfo?.optDouble("height", 2480.0)?.toFloat() ?: 2480f

                    NoteDocument(
                        title = title,
                        pageWidth = pageWidth,
                        pageHeight = pageHeight,
                        pages = pages
                    )
                }
            }
        } catch (e: Exception) {
            log.e("Failed to parse .note file: ${e.message}")
            android.util.Log.e("OnyxNoteParser", "Failed to parse .note file: ${e.message}", e)
            e.printStackTrace()
            null
        }
    }

    private fun isActiveShapeEntry(name: String): Boolean {
        return name.contains("/shape/") &&
            name.endsWith(".zip") &&
            !name.contains("/stash/")
    }

    private fun isActivePointsEntry(name: String): Boolean {
        return name.contains("/point/") &&
            name.endsWith("#points") &&
            !name.contains("/stash/")
    }

    private fun extractPageKeyFromShapeEntry(name: String): String? {
        val fileName = name.substringAfterLast('/')
        val key = fileName.substringBefore('#')
        return key.takeIf { it.isNotBlank() }
    }

    private fun extractPageKeyFromPointsEntry(name: String): String? {
        val afterPoint = name.substringAfter("/point/", "")
        if (afterPoint.isNotBlank()) {
            val segment = afterPoint.substringBefore('/')
            if (segment.isNotBlank()) return segment
        }

        val fileName = name.substringAfterLast('/')
        val key = fileName.substringBefore('#')
        return key.takeIf { it.isNotBlank() }
    }

    private fun extractEntryTimestamp(name: String): Long {
        val fileName = name.substringAfterLast('/').removeSuffix(".zip")
        return fileName.substringAfterLast('#').toLongOrNull() ?: Long.MIN_VALUE
    }
    
    /**
     * Extract JSON objects and strings from protobuf note_info.
     */
    private fun parseNoteInfo(data: ByteArray): JSONObject? {
        return try {
            // Extract embedded JSON strings from protobuf
            val text = String(data, Charsets.UTF_8)
            val jsonStart = text.indexOf("{\"")
            if (jsonStart >= 0) {
                val jsonEnd = text.indexOf("}", jsonStart) + 1
                val jsonStr = text.substring(jsonStart, jsonEnd)
                JSONObject(jsonStr)
            } else {
                null
            }
        } catch (e: Exception) {
            log.w("Failed to parse note_info: ${e.message}")
            null
        }
    }
    
    /**
     * Parse shape ZIP file to extract stroke metadata.
     */
    private fun parseShapeMetadata(shapeZipData: ByteArray): List<StrokeMeta> {
        val metas = mutableListOf<StrokeMeta>()
        
        try {
            android.util.Log.d("OnyxNoteParser", "Parsing shape ZIP, size: ${shapeZipData.size} bytes")
            
            // Shape file is a nested ZIP - unzip it first
            val shapeZipStream = ZipInputStream(shapeZipData.inputStream())
            var shapeEntry = shapeZipStream.nextEntry
            var shapeContent: ByteArray? = null
            
            while (shapeEntry != null) {
                android.util.Log.d("OnyxNoteParser", "  Shape ZIP entry: ${shapeEntry.name}")
                if (shapeEntry.name.contains("pb") || !shapeEntry.isDirectory) {
                    shapeContent = shapeZipStream.readBytes()
                    android.util.Log.d("OnyxNoteParser", "  Read shape content: ${shapeContent.size} bytes")
                    break
                }
                shapeZipStream.closeEntry()
                shapeEntry = shapeZipStream.nextEntry
            }
            
            if (shapeContent == null) {
                android.util.Log.w("OnyxNoteParser", "No shape content found in nested ZIP")
                return emptyList()
            }
            
            // Shape file contains protobuf with embedded JSON
            val text = String(shapeContent, Charsets.UTF_8)
            android.util.Log.d("OnyxNoteParser", "Shape content length: ${text.length} chars")
            
            // Extract UUIDs (stroke ID and points ID)
            val uuidRegex = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}".toRegex()
            val uuids = uuidRegex.findAll(text).map { it.value }.toList()
            android.util.Log.d("OnyxNoteParser", "Found ${uuids.size} UUIDs")
            
            // Extract bounding box JSON objects (escape curly braces in regex)
            val bboxRegex = """\{"bottom":[0-9.]+,"empty":false,"left":[0-9.]+,"right":[0-9.]+,"stability":\d+,"top":[0-9.]+\}""".toRegex()
            val bboxMatches = bboxRegex.findAll(text).toList()
            android.util.Log.d("OnyxNoteParser", "Found ${bboxMatches.size} bounding boxes")
            
            // Each stroke has: strokeId, bbox JSON, pointsId
            // Pattern: strokeId appears first, then bbox, then pointsId
            var uuidIdx = 0
            for (bboxMatch in bboxMatches) {
                if (uuidIdx + 1 >= uuids.size) break
                
                val bboxJson = JSONObject(bboxMatch.value)
                val strokeId = uuids[uuidIdx]
                val pointsId = uuids[uuidIdx + 1]
                
                metas.add(StrokeMeta(
                    id = strokeId,
                    pointsId = pointsId,
                    bbox = StrokeBbox(
                        left = bboxJson.getDouble("left").toFloat(),
                        top = bboxJson.getDouble("top").toFloat(),
                        right = bboxJson.getDouble("right").toFloat(),
                        bottom = bboxJson.getDouble("bottom").toFloat()
                    )
                ))
                
                uuidIdx += 2  // Skip to next stroke's UUID pair
            }
            
            android.util.Log.i("OnyxNoteParser", "Parsed ${metas.size} stroke metadata entries")
        } catch (e: Exception) {
            log.e("Failed to parse shape metadata: ${e.message}")
            android.util.Log.e("OnyxNoteParser", "Failed to parse shape metadata: ${e.message}", e)
        }
        
        return metas
    }
    
    private data class PointChunkEntry(
        val strokeId: String,
        val offset: Int,
        val size: Int
    )

    /**
     * Parse modern Onyx point payload.
     *
     * Layout observed in Notebook-1.note:
     * - Data chunks at varying offsets.
     * - Tail index table: repeated (uuid[36], offset[4], size[4]).
     * - Last 4 bytes: big-endian table start offset.
     *
     * Chunk payload format:
     * - 4-byte chunk header
     * - repeated 16-byte records: x(float), y(float), dt(u16), pressure(u16), seq(u32)
     */
    private fun parsePointChunks(data: ByteArray): LinkedHashMap<String, List<StrokePoint>> {
        val chunks = linkedMapOf<String, List<StrokePoint>>()

        if (data.size < 8) return chunks

        try {
            val tableOffset = ByteBuffer.wrap(data, data.size - 4, 4)
                .order(ByteOrder.BIG_ENDIAN)
                .int

            if (tableOffset <= 0 || tableOffset >= data.size - 4) {
                android.util.Log.w("OnyxNoteParser", "Invalid point table offset: $tableOffset")
                return chunks
            }

            val entries = parsePointChunkEntries(data, tableOffset)
            if (entries.isEmpty()) {
                android.util.Log.w("OnyxNoteParser", "No chunk entries parsed from points table")
                return chunks
            }

            for (entry in entries) {
                val points = parsePointChunkRecords(data, entry)
                if (points.isNotEmpty()) {
                    chunks[entry.strokeId] = points
                }
            }

            android.util.Log.i("OnyxNoteParser", "Parsed ${chunks.size} point chunks from table offset $tableOffset")
        } catch (e: Exception) {
            log.e("Failed to parse point chunks: ${e.message}")
            android.util.Log.e("OnyxNoteParser", "Failed to parse point chunks", e)
        }

        return chunks
    }

    private fun parsePointChunkEntries(data: ByteArray, tableOffset: Int): List<PointChunkEntry> {
        val entries = mutableListOf<PointChunkEntry>()
        var cursor = tableOffset
        val tableEnd = data.size - 4

        // Each entry is exactly 44 bytes: 36-byte UUID + 4-byte offset + 4-byte size.
        while (cursor + 44 <= tableEnd) {
            val idBytes = data.copyOfRange(cursor, cursor + 36)
            val strokeId = String(idBytes, Charsets.US_ASCII)
            val isUuidLike = "[0-9a-f\\-]{36}".toRegex().matches(strokeId)
            if (!isUuidLike) break

            val offset = ByteBuffer.wrap(data, cursor + 36, 4).order(ByteOrder.BIG_ENDIAN).int
            val size = ByteBuffer.wrap(data, cursor + 40, 4).order(ByteOrder.BIG_ENDIAN).int

            if (offset < 0 || size <= 4 || offset + size > tableOffset) {
                android.util.Log.w("OnyxNoteParser", "Skipping invalid chunk entry: id=$strokeId, offset=$offset, size=$size")
                cursor += 44
                continue
            }

            entries.add(PointChunkEntry(strokeId, offset, size))
            cursor += 44
        }

        return entries
    }

    private fun parsePointChunkRecords(data: ByteArray, entry: PointChunkEntry): List<StrokePoint> {
        val chunk = data.copyOfRange(entry.offset, entry.offset + entry.size)
        if (chunk.size <= 4) return emptyList()

        val payload = chunk.copyOfRange(4, chunk.size)
        val recordSize = 16
        val recordCount = payload.size / recordSize
        if (recordCount == 0) return emptyList()

        val points = ArrayList<StrokePoint>(recordCount)

        for (i in 0 until recordCount) {
            val base = i * recordSize
            val x = ByteBuffer.wrap(payload, base, 4).order(ByteOrder.BIG_ENDIAN).float
            val y = ByteBuffer.wrap(payload, base + 4, 4).order(ByteOrder.BIG_ENDIAN).float
            val dtRaw = ByteBuffer.wrap(payload, base + 8, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
            val pressureRaw = ByteBuffer.wrap(payload, base + 10, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
            // base+12..15 is a sequence/index field that we currently don't store.

            points.add(
                StrokePoint(
                    x = x,
                    y = y,
                    pressure = pressureRaw / 4095f,
                    dt = dtRaw.toUShort()
                )
            )
        }

        return points
    }

    private fun assignChunksToStrokes(
        chunksByStrokeId: LinkedHashMap<String, List<StrokePoint>>,
        metas: List<StrokeMeta>,
        pageId: String
    ): List<Stroke> {
        val strokes = mutableListOf<Stroke>()
        val timestamp = Date()
        val metasById = metas.associateBy { it.id }

        for ((strokeId, points) in chunksByStrokeId) {
            if (points.isEmpty()) continue

            val minX = points.minOf { it.x }
            val maxX = points.maxOf { it.x }
            val minY = points.minOf { it.y }
            val maxY = points.maxOf { it.y }
            val meta = metasById[strokeId]

            strokes.add(
                Stroke(
                    id = strokeId,
                    size = 4.7244096f,
                    pen = Pen.BALLPEN,
                    color = -16777216,
                    maxPressure = (meta?.maxPressure ?: 4095f).toInt(),
                    top = meta?.bbox?.top ?: minY,
                    bottom = meta?.bbox?.bottom ?: maxY,
                    left = meta?.bbox?.left ?: minX,
                    right = meta?.bbox?.right ?: maxX,
                    points = points,
                    pageId = pageId,
                    createdAt = timestamp
                )
            )
        }

        return strokes
    }

    /**
     * Legacy fallback parser for older payload variants.
     */
    private fun parsePointsFileLegacy(data: ByteArray): List<StrokePoint> {
        val points = mutableListOf<StrokePoint>()

        try {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            if (buffer.limit() <= 88) return emptyList()
            buffer.position(88)

            while (buffer.remaining() >= 15) {
                val x = buffer.float
                val y = buffer.float
                val tsByte1 = buffer.get().toInt() and 0xFF
                val tsByte2 = buffer.get().toInt() and 0xFF
                val tsByte3 = buffer.get().toInt() and 0xFF
                val timestampDelta = (tsByte1 shl 16) or (tsByte2 shl 8) or tsByte3
                val pressureRaw = buffer.short.toInt() and 0xFFFF
                val pressure = pressureRaw / 4095f
                buffer.int // sequence, unused

                points.add(
                    StrokePoint(
                        x = x,
                        y = y,
                        pressure = pressure,
                        dt = if (timestampDelta <= 65535) timestampDelta.toUShort() else null
                    )
                )
            }
        } catch (e: Exception) {
            log.e("Failed to parse legacy points: ${e.message}")
        }

        return points
    }
    
    /**
     * Assign points to strokes based on bounding boxes.
     */
    private fun assignPointsToStrokes(
        allPoints: List<StrokePoint>,
        metas: List<StrokeMeta>,
        pageId: String
    ): List<Stroke> {
        val strokes = mutableListOf<Stroke>()
        val timestamp = Date()
        
        for (meta in metas) {
            val bbox = meta.bbox
            val tolerance = 5f
            
            // Filter points within bounding box
            val strokePoints = allPoints.filter { pt ->
                pt.x >= bbox.left - tolerance &&
                pt.x <= bbox.right + tolerance &&
                pt.y >= bbox.top - tolerance &&
                pt.y <= bbox.bottom + tolerance
            }
            
            if (strokePoints.isEmpty()) continue
            
            // Calculate bounding box
            val minX = strokePoints.minOf { it.x }
            val maxX = strokePoints.maxOf { it.x }
            val minY = strokePoints.minOf { it.y }
            val maxY = strokePoints.maxOf { it.y }
            
            strokes.add(Stroke(
                id = meta.id,
                size = 4.7244096f,      // Ballpoint pen width
                pen = Pen.BALLPEN,       // Ballpoint pen type
                color = -16777216,        // Black
                maxPressure = 4095,
                top = minY,
                bottom = maxY,
                left = minX,
                right = maxX,
                points = strokePoints,
                pageId = pageId,
                createdAt = timestamp
            ))
        }
        
        return strokes
    }
}

