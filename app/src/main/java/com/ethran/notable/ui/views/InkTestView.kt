package com.ethran.notable.ui.views

import android.graphics.Path
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.ethran.notable.navigation.NavigationDestination

object InkTestDestination : NavigationDestination {
    override val route = "ink_test"
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun InkTestView(onBack: () -> Unit) {
    val inkBuilder = remember { Ink.builder() }
    var currentStrokeBuilder by remember { mutableStateOf<Ink.Stroke.Builder?>(null) }
    val paths = remember { mutableStateListOf<Path>() }
    var currentPath by remember { mutableStateOf<Path?>(null) }

    var recognizedText by remember { mutableStateOf("(write something and tap Recognize)") }
    var modelStatus by remember { mutableStateOf("Checking model...") }
    var recognitionTimeMs by remember { mutableLongStateOf(0L) }

    val modelIdentifier = remember {
        DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US")
    }
    val model = remember {
        modelIdentifier?.let { DigitalInkRecognitionModel.builder(it).build() }
    }
    val recognizer = remember {
        model?.let {
            DigitalInkRecognition.getClient(
                DigitalInkRecognizerOptions.builder(it).build()
            )
        }
    }

    // Download model on first load
    DisposableEffect(model) {
        if (model != null) {
            val remoteModelManager = RemoteModelManager.getInstance()
            remoteModelManager.isModelDownloaded(model).addOnSuccessListener { isDownloaded ->
                if (isDownloaded) {
                    modelStatus = "Model ready"
                } else {
                    modelStatus = "Downloading model..."
                    remoteModelManager.download(model, DownloadConditions.Builder().build())
                        .addOnSuccessListener { modelStatus = "Model ready" }
                        .addOnFailureListener { modelStatus = "Download failed: ${it.message}" }
                }
            }
        } else {
            modelStatus = "Model identifier not found"
        }
        onDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("ML Kit Ink Test", fontSize = 20.sp, color = Color.Black)
            Text(
                "< Back",
                fontSize = 16.sp,
                color = Color.Black,
                modifier = Modifier.clickable { onBack() }
            )
        }

        Text(modelStatus, fontSize = 12.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))

        // Drawing area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .border(1.dp, Color.Black)
                .background(Color.White)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInteropFilter { event ->
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                val strokeBuilder = Ink.Stroke.builder()
                                strokeBuilder.addPoint(
                                    Ink.Point.create(
                                        event.x,
                                        event.y,
                                        event.eventTime
                                    )
                                )
                                currentStrokeBuilder = strokeBuilder
                                val path = Path()
                                path.moveTo(event.x, event.y)
                                currentPath = path
                                true
                            }

                            MotionEvent.ACTION_MOVE -> {
                                currentStrokeBuilder?.addPoint(
                                    Ink.Point.create(
                                        event.x,
                                        event.y,
                                        event.eventTime
                                    )
                                )
                                currentPath?.lineTo(event.x, event.y)
                                // Force recomposition
                                val p = currentPath
                                currentPath = null
                                currentPath = p
                                true
                            }

                            MotionEvent.ACTION_UP -> {
                                currentStrokeBuilder?.addPoint(
                                    Ink.Point.create(
                                        event.x,
                                        event.y,
                                        event.eventTime
                                    )
                                )
                                currentStrokeBuilder?.let { inkBuilder.addStroke(it.build()) }
                                currentStrokeBuilder = null
                                currentPath?.let { paths.add(it) }
                                currentPath = null
                                true
                            }

                            else -> false
                        }
                    }
            ) {
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.BLACK
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 4f
                    isAntiAlias = true
                }
                paths.forEach { path ->
                    drawContext.canvas.nativeCanvas.drawPath(path, paint)
                }
                currentPath?.let { path ->
                    drawContext.canvas.nativeCanvas.drawPath(path, paint)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .border(1.dp, Color.Black)
                    .clickable {
                        if (recognizer != null && modelStatus == "Model ready") {
                            recognizedText = "Recognizing..."
                            val ink = inkBuilder.build()
                            val start = System.currentTimeMillis()
                            recognizer.recognize(ink)
                                .addOnSuccessListener { result ->
                                    val elapsed = System.currentTimeMillis() - start
                                    recognitionTimeMs = elapsed
                                    val candidates = result.candidates
                                    recognizedText = if (candidates.isNotEmpty()) {
                                        candidates.joinToString("\n") { candidate -> "\"${candidate.text}\"" }
                                    } else {
                                        "(no results)"
                                    }
                                }
                                .addOnFailureListener { ex ->
                                    recognizedText = "Error: ${ex.localizedMessage}"
                                }
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Recognize", fontSize = 16.sp, color = Color.Black)
            }

            Box(
                modifier = Modifier
                    .border(1.dp, Color.Black)
                    .clickable {
                        paths.clear()
                        currentPath = null
                        currentStrokeBuilder = null
                        // Reset ink builder by creating a fresh one — we'll
                        // just rebuild in the recognizer call
                        recognizedText = "(cleared)"
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Clear", fontSize = 16.sp, color = Color.Black)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (recognitionTimeMs > 0) {
            Text("Recognition time: ${recognitionTimeMs}ms", fontSize = 12.sp, color = Color.Gray)
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Results
        Text("Results:", fontSize = 14.sp, color = Color.Black)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(1.dp, Color.LightGray)
                .padding(8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(recognizedText, fontSize = 16.sp, color = Color.Black)
        }
    }
}
