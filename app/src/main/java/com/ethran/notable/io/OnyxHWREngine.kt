package com.ethran.notable.io

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.MemoryFile
import android.os.ParcelFileDescriptor
import com.ethran.notable.data.db.Stroke
import com.onyx.android.sdk.hwr.service.HWRInputArgs
import com.onyx.android.sdk.hwr.service.HWROutputArgs
import com.onyx.android.sdk.hwr.service.HWROutputCallback
import com.onyx.android.sdk.hwr.service.IHWRService
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val log = ShipBook.getLogger("OnyxHWREngine")

object OnyxHWREngine {

    private const val DEBUG_HWR_PAYLOAD = false

    data class HwrBox(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float
    ) {
        val right: Float get() = x + width
        val bottom: Float get() = y + height
    }

    data class HwrWord(
        val label: String,
        val box: HwrBox? = null
    )

    data class HwrRecognitionResult(
        val text: String,
        val words: List<HwrWord>,
        val blockBox: HwrBox? = null
    )

    @Volatile private var service: IHWRService? = null
    @Volatile private var bound = false
    @Volatile private var connectLatch = java.util.concurrent.CountDownLatch(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IHWRService.Stub.asInterface(binder)
            bound = true
            log.i("OnyxHWR service connected")
            connectLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            initialized = false
            log.w("OnyxHWR service disconnected")
        }
    }

    /**
     * Bind to the service and wait for connection (up to timeout).
     * Returns true if service is ready.
     */
    suspend fun bindAndAwait(context: Context, timeoutMs: Long = 2000): Boolean {
        if (bound && service != null) return true

        // Create a fresh latch for this bind attempt
        connectLatch = java.util.concurrent.CountDownLatch(1)

        val intent = Intent().apply {
            component = ComponentName(
                "com.onyx.android.ksync",
                "com.onyx.android.ksync.service.KHwrService"
            )
        }
        val bindStarted = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            log.w("Failed to bind OnyxHWR service: ${e.message}")
            return false
        }
        if (!bindStarted) return false

        // Wait for onServiceConnected on IO dispatcher
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            connectLatch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } && service != null
    }

    fun unbind(context: Context) {
        if (bound) {
            try {
                context.unbindService(connection)
            } catch (_: Exception) {}
            bound = false
            service = null
            initialized = false
        }
    }

    @Volatile private var initialized = false

    /**
     * Initialize the MyScript recognizer via the service's init method.
     */
    private suspend fun ensureInitialized(svc: IHWRService, viewWidth: Float, viewHeight: Float, language: String = "cs_CZ") {
        if (initialized) return
        log.i("Initializing OnyxHWR recognizer with language: $language")

        val inputArgs = HWRInputArgs().apply {
            lang = language
            contentType = "Text"
            recognizerType = "MS_ON_SCREEN"
            this.viewWidth = viewWidth
            this.viewHeight = viewHeight
            isTextEnable = true
        }

        suspendCancellableCoroutine { cont ->
            svc.init(inputArgs, true, object : HWROutputCallback.Stub() {
                override fun read(args: HWROutputArgs?) {
                    log.i("OnyxHWR init: recognizerActivated=${args?.recognizerActivated}, compileSuccess=${args?.compileSuccess}")
                    initialized = args?.recognizerActivated == true
                    cont.resume(Unit)
                }
            })
        }
        log.i("OnyxHWR initialized=$initialized")
    }

    /**
     * Recognize strokes using the Onyx HWR (MyScript) service.
     * Returns recognized text, or null if service is unavailable.
     */
    suspend fun recognizeStrokes(
        strokes: List<Stroke>,
        viewWidth: Float,
        viewHeight: Float,
        language: String = "cs_CZ"
    ): String? {
        return recognizeStrokesDetailed(strokes, viewWidth, viewHeight, language)?.text
    }

    suspend fun recognizeStrokesDetailed(
        strokes: List<Stroke>,
        viewWidth: Float,
        viewHeight: Float,
        language: String = "cs_CZ"
    ): HwrRecognitionResult? {
        val callStartNs = System.nanoTime()
        val svc = service ?: return null
        if (strokes.isEmpty()) return HwrRecognitionResult(text = "", words = emptyList(), blockBox = null)

        val ensureStartNs = System.nanoTime()
        ensureInitialized(svc, viewWidth, viewHeight, language)
        val ensureMs = (System.nanoTime() - ensureStartNs) / 1_000_000

        val buildStartNs = System.nanoTime()
        val protoBytes = buildProtobuf(strokes, viewWidth, viewHeight, language)
        val pfd = createMemoryFilePfd(protoBytes)
            ?: throw IllegalStateException("Failed to create MemoryFile PFD")
        val buildMs = (System.nanoTime() - buildStartNs) / 1_000_000

        return try {
            val requestStartNs = System.nanoTime()
            val result = withTimeoutOrNull(10_000) {
                suspendCancellableCoroutine { cont ->
                    svc.batchRecognize(pfd, object : HWROutputCallback.Stub() {
                        override fun read(args: HWROutputArgs?) {
                            val callbackStartNs = System.nanoTime()
                            try {
                                if (DEBUG_HWR_PAYLOAD) {
                                    logHwrOutputEnvelope(args)
                                }

                                // Check for error in hwrResult first
                                val errorJson = args?.hwrResult
                                if (!errorJson.isNullOrBlank()) {
                                    log.e("OnyxHWR error: ${errorJson.take(300)}")
                                    cont.resume(HwrRecognitionResult(text = "", words = emptyList(), blockBox = null))
                                    return
                                }

                                // Success: result is in PFD as JSON
                                val resultPfd = args?.pfd
                                if (resultPfd == null) {
                                    log.w("OnyxHWR returned no PFD and no hwrResult")
                                    cont.resume(HwrRecognitionResult(text = "", words = emptyList(), blockBox = null))
                                    return
                                }

                                val readStartNs = System.nanoTime()
                                val json = readPfdAsString(resultPfd)
                                val readMs = (System.nanoTime() - readStartNs) / 1_000_000
                                resultPfd.close()
                                if (DEBUG_HWR_PAYLOAD) {
                                    logHwrJsonDiagnostics(json)
                                }

                                val parseStartNs = System.nanoTime()
                                val parsed = parseHwrDetailedResult(json)
                                val parseMs = (System.nanoTime() - parseStartNs) / 1_000_000
                                val serviceWaitMs = (callbackStartNs - requestStartNs) / 1_000_000
                                val callbackTotalMs = (System.nanoTime() - callbackStartNs) / 1_000_000
                                val totalCallMs = (System.nanoTime() - callStartNs) / 1_000_000
                                log.i("OnyxHWR recognized ${parsed.text.length} chars, ${parsed.words.count { it.box != null }} boxed words")
                                log.i(
                                    "HWR_PROFILE strokes=${strokes.size} ensure=${ensureMs}ms build=${buildMs}ms wait=${serviceWaitMs}ms read=${readMs}ms parse=${parseMs}ms callback=${callbackTotalMs}ms total=${totalCallMs}ms"
                                )
                                cont.resume(parsed)
                            } catch (e: Exception) {
                                log.e("Error parsing OnyxHWR result: ${e.message}")
                                cont.resumeWithException(e)
                            }
                        }
                    })
                }
            }
            if (result == null) {
                log.e("OnyxHWR timed out after 10s")
            }
            result
        } finally {
            pfd.close()
        }
    }

    /**
     * Read a PFD's contents as a UTF-8 string.
     */
    private fun readPfdAsString(pfd: ParcelFileDescriptor): String {
        val input = java.io.FileInputStream(pfd.fileDescriptor)
        val buffered = java.io.BufferedInputStream(input)
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var n: Int
        while (buffered.read(buf).also { n = it } != -1) {
            baos.write(buf, 0, n)
        }
        return baos.toString("UTF-8")
    }

    /**
     * Parse the JSON result from the HWR service.
     * Success response: HWROutputData JSON with result.label containing recognized text.
     */
    private fun parseHwrResult(json: String): String {
        return parseHwrDetailedResult(json).text
    }

    private fun parseHwrDetailedResult(json: String): HwrRecognitionResult {
        return try {
            val obj = JSONObject(json)
            // Check for error
            if (obj.has("exception")) {
                val exc = obj.optJSONObject("exception")
                val cause = exc?.optJSONObject("cause")
                val msg = cause?.optString("message") ?: exc?.optString("message") ?: "unknown"
                log.e("OnyxHWR error response: $msg")
                return HwrRecognitionResult(text = "", words = emptyList(), blockBox = null)
            }

            // Success: result is HWRConvertBean with label and optional words[].
            val result = obj.optJSONObject("result")
            if (result != null) {
                val text = result.optString("label", "")
                val blockBox = parseBox(result.optJSONObject("bounding-box"))
                val words = mutableListOf<HwrWord>()
                val wordsArray = result.optJSONArray("words")
                if (wordsArray != null) {
                    for (i in 0 until wordsArray.length()) {
                        val item = wordsArray.optJSONObject(i) ?: continue
                        val label = item.optString("label", "")
                        val box = parseBox(item.optJSONObject("bounding-box"))
                        words.add(HwrWord(label = label, box = box))
                    }
                }
                return HwrRecognitionResult(text = text, words = words, blockBox = blockBox)
            }

            // Fallback: try top-level label
            val fallbackText = obj.optString("label", "")
            HwrRecognitionResult(text = fallbackText, words = emptyList(), blockBox = null)
        } catch (e: Exception) {
            log.w("Failed to parse HWR JSON: ${e.message}")
            HwrRecognitionResult(text = "", words = emptyList(), blockBox = null)
        }
    }

    private fun parseBox(boxObj: JSONObject?): HwrBox? {
        if (boxObj == null) return null
        val x = boxObj.optDouble("x", Double.NaN).toFloat()
        val y = boxObj.optDouble("y", Double.NaN).toFloat()
        val w = boxObj.optDouble("width", Double.NaN).toFloat()
        val h = boxObj.optDouble("height", Double.NaN).toFloat()
        if (!x.isFinite() || !y.isFinite() || !w.isFinite() || !h.isFinite()) return null
        if (w <= 0f || h <= 0f) return null
        return HwrBox(x = x, y = y, width = w, height = h)
    }

    private fun logHwrOutputEnvelope(args: HWROutputArgs?) {
        if (args == null) {
            log.w("OnyxHWR debug: output args = null")
            return
        }

        val itemMapPreview = args.itemIdMap?.take(400)?.replace('\n', ' ')
        log.i(
            "OnyxHWR debug envelope: outputType=${args.outputType}, recognizerActivated=${args.recognizerActivated}, " +
                "compileSuccess=${args.compileSuccess}, hasPfd=${args.pfd != null}, hwrResultLen=${args.hwrResult?.length ?: 0}, " +
                "itemIdMapLen=${args.itemIdMap?.length ?: 0}"
        )
        if (!itemMapPreview.isNullOrBlank()) {
            log.i("OnyxHWR debug itemIdMap preview: $itemMapPreview")
        }
    }

    private fun logHwrJsonDiagnostics(json: String) {
        val preview = json.take(1200).replace('\n', ' ')
        log.i("OnyxHWR debug payload len=${json.length}, preview=$preview")

        try {
            val root = JSONObject(json)
            val rootKeys = root.keys().asSequence().toList().joinToString(",")
            log.i("OnyxHWR debug root keys: $rootKeys")

            val result = root.optJSONObject("result")
            if (result != null) {
                val resultKeys = result.keys().asSequence().toList().joinToString(",")
                log.i("OnyxHWR debug result keys: $resultKeys")

                listOf("bbox", "bounds", "box", "rect", "segments", "items", "words", "strokes").forEach { key ->
                    if (result.has(key)) {
                        val valuePreview = result.opt(key)?.toString()?.take(600)
                        log.i("OnyxHWR debug result.$key present: $valuePreview")
                    }
                }
            }
        } catch (e: Exception) {
            log.w("OnyxHWR debug: failed to inspect JSON payload: ${e.message}")
        }
    }

    // --- Protobuf encoding (hand-rolled, no library needed) ---

    /**
     * Build the HWRInputProto protobuf bytes.
     * Field numbers from HWRInputDataProto.HWRInputProto:
     *   1: lang (string), 2: contentType (string), 3: editorType (string),
     *   4: recognizerType (string), 5: viewWidth (float), 6: viewHeight (float),
     *   7: offsetX (float), 8: offsetY (float), 9: gestureEnable (bool),
     *   10: recognizeText (bool), 11: recognizeShape (bool), 12: isIncremental (bool),
     *   15: repeated pointerEvents (HWRPointerProto)
     */
    private fun buildProtobuf(
        strokes: List<Stroke>,
        viewWidth: Float,
        viewHeight: Float,
        language: String = "cs_CZ"
    ): ByteArray {
        val out = ByteArrayOutputStream()

        // Field 1: lang (string)
        writeTag(out, 1, 2)
        writeString(out, language)

        // Field 2: contentType (string)
        writeTag(out, 2, 2)
        writeString(out, "Text")

        // Field 4: recognizerType (string)
        writeTag(out, 4, 2)
        writeString(out, "MS_ON_SCREEN")

        // Field 5: viewWidth (float, wire type 5 = fixed32)
        writeTag(out, 5, 5)
        writeFixed32(out, viewWidth)

        // Field 6: viewHeight (float, wire type 5 = fixed32)
        writeTag(out, 6, 5)
        writeFixed32(out, viewHeight)

        // Field 10: recognizeText = true (varint 1)
        writeTag(out, 10, 0)
        writeVarint(out, 1)

        // Field 15: repeated pointer events (wire type 2 = length-delimited)
        for (stroke in strokes) {
            val points = stroke.points
            if (points.isEmpty()) continue

            val strokeEpoch = stroke.createdAt.time

            for ((i, point) in points.withIndex()) {
                val eventType = when (i) {
                    0 -> 0           // DOWN
                    points.size - 1 -> 2  // UP
                    else -> 1        // MOVE
                }

                val timestamp = if (point.dt != null) {
                    strokeEpoch + point.dt.toLong()
                } else {
                    strokeEpoch + (i * 10L)
                }

                val pressure = point.pressure ?: 0.5f

                val pointerBytes = encodePointerProto(
                    x = point.x,
                    y = point.y,
                    t = timestamp,
                    f = pressure,
                    pointerId = 0,
                    eventType = eventType,
                    pointerType = 0  // PEN (0=PEN, 1=TOUCH, 2=ERASER, 3=MOUSE)
                )

                writeTag(out, 15, 2)
                writeBytes(out, pointerBytes)
            }
        }

        return out.toByteArray()
    }

    /**
     * Encode a single HWRPointerProto message.
     * Fields: float x(1), float y(2), sint64 t(3), float f(4),
     *         sint32 pointerId(5), enum eventType(6), enum pointerType(7)
     */
    private fun encodePointerProto(
        x: Float, y: Float, t: Long, f: Float,
        pointerId: Int, eventType: Int, pointerType: Int
    ): ByteArray {
        val out = ByteArrayOutputStream()

        // Field 1: x (wire type 5 = fixed32)
        writeTag(out, 1, 5)
        writeFixed32(out, x)

        // Field 2: y (wire type 5 = fixed32)
        writeTag(out, 2, 5)
        writeFixed32(out, y)

        // Field 3: t (wire type 0, sint64 = ZigZag encoded)
        writeTag(out, 3, 0)
        writeVarint(out, (t shl 1) xor (t shr 63))

        // Field 4: f / pressure (wire type 5 = fixed32)
        writeTag(out, 4, 5)
        writeFixed32(out, f)

        // Field 5: pointerId (wire type 0, sint32 = ZigZag encoded)
        writeTag(out, 5, 0)
        val zigzagPid = (pointerId shl 1) xor (pointerId shr 31)
        writeVarint(out, zigzagPid.toLong())

        // Field 6: eventType (wire type 0 = enum/varint)
        writeTag(out, 6, 0)
        writeVarint(out, eventType.toLong())

        // Field 7: pointerType (wire type 0 = enum/varint)
        writeTag(out, 7, 0)
        writeVarint(out, pointerType.toLong())

        return out.toByteArray()
    }

    // --- Low-level protobuf primitives ---

    private fun writeTag(out: ByteArrayOutputStream, fieldNumber: Int, wireType: Int) {
        writeVarint(out, ((fieldNumber shl 3) or wireType).toLong())
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v.toInt() and 0x7F) or 0x80))
            v = v ushr 7
        }
        out.write(v.toInt() and 0x7F)
    }

    private fun writeFixed32(out: ByteArrayOutputStream, value: Float) {
        val bits = java.lang.Float.floatToIntBits(value)
        out.write(bits and 0xFF)
        out.write((bits shr 8) and 0xFF)
        out.write((bits shr 16) and 0xFF)
        out.write((bits shr 24) and 0xFF)
    }

    private fun writeString(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun writeBytes(out: ByteArrayOutputStream, bytes: ByteArray) {
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    // --- MemoryFile → ParcelFileDescriptor ---

    /**
     * Write bytes to a MemoryFile and return a ParcelFileDescriptor.
     * Uses reflection to access MemoryFile.getFileDescriptor() (hidden API).
     * This matches the approach used by Onyx's own MemoryFileUtils.
     */
    private fun createMemoryFilePfd(data: ByteArray): ParcelFileDescriptor? {
        return try {
            val memFile = MemoryFile("hwr_input", data.size)
            memFile.writeBytes(data, 0, 0, data.size)

            val method = MemoryFile::class.java.getDeclaredMethod("getFileDescriptor")
            method.isAccessible = true
            val fd = method.invoke(memFile) as FileDescriptor

            val pfd = ParcelFileDescriptor.dup(fd)
            memFile.close()
            pfd
        } catch (e: Exception) {
            log.e("Failed to create MemoryFile PFD: ${e.message}")
            null
        }
    }
}
