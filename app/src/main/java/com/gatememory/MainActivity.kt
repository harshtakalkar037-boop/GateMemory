package com.gatememory

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Size
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.gatememory.audio.WavAudioRecorder
import com.gatememory.data.GateTag
import com.gatememory.data.GateTagStore
import com.gatememory.recognition.GateRecognitionEngine
import com.gatememory.recognition.RecognitionConfig
import com.gatememory.recognition.SavedTagFeatures
import com.gatememory.transcription.WhisperTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val config = RecognitionConfig()
    private lateinit var recognitionEngine: GateRecognitionEngine
    private lateinit var whisperTranscriber: WhisperTranscriber
    private lateinit var tagStore: GateTagStore
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var matchOverlay: TextView
    private lateinit var qualityText: TextView
    private lateinit var memoryTrailText: TextView
    private lateinit var confidenceBar: ProgressBar
    private lateinit var galleryContainer: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var nameInput: EditText
    private lateinit var noteInput: EditText
    private lateinit var recordButton: Button
    private lateinit var tagButton: Button
    private lateinit var recognizeButton: Button
    private lateinit var saveButton: Button
    private lateinit var playButton: Button
    private lateinit var privacyButton: Button
    private lateinit var demoGuideButton: Button
    private lateinit var resetButton: Button
    private var latestGrayFrame: Mat? = null
    private var allTags: List<GateTag> = emptyList()
    private var savedFeatures: List<SavedTagFeatures> = emptyList()
    private var mode = Mode.Idle
    private var lastAnalysisMillis = 0L
    private var lastPlayedTagId: String? = null
    private var lastMatchedTag: GateTag? = null
    private var candidateTagId: String? = null
    private var candidateMatchCount = 0
    private val confidenceHistory = ArrayDeque<Int>()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recorder: WavAudioRecorder? = null
    private var recordedAudioFile: File? = null
    private var player: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tagStore = GateTagStore(this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildUi()

        if (!OpenCVLoader.initLocal()) {
            statusText.text = "OpenCV failed to load on this device."
            return
        }

        recognitionEngine = GateRecognitionEngine(config)
        whisperTranscriber = WhisperTranscriber(this)
        loadSavedTags()
        ensurePermissionsAndStartCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        appScope.cancel()
        cameraExecutor.shutdown()
        latestGrayFrame?.release()
        recorder?.release()
        player?.release()
        toneGenerator?.release()
    }

    private fun buildUi() {
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            setBackgroundColor(Color.BLACK)
        }

        matchOverlay = TextView(this).apply {
            text = ""
            visibility = View.GONE
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(28, 22, 28, 22)
            background = roundedBackground(Color.argb(72, 31, 180, 138), 32f, Color.rgb(72, 201, 176), 6)
        }

        val titleText = TextView(this).apply {
            text = "GateMemory"
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
        }

        val badgeText = TextView(this).apply {
            text = "100% offline • on-device ORB"
            setTextColor(Color.rgb(72, 201, 176))
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 8, 0, 0)
        }

        statusText = TextView(this).apply {
            text = "Starting GateMemory..."
            setTextColor(Color.WHITE)
            textSize = 19f
            maxLines = 3
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(24, 18, 24, 18)
            background = roundedBackground(Color.rgb(25, 38, 49), 22f)
        }

        qualityText = TextView(this).apply {
            text = "Quality: point at a textured gate or sign"
            setTextColor(Color.rgb(40, 40, 44))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(16, 14, 16, 10)
            background = roundedBackground(Color.rgb(239, 244, 242), 18f)
        }

        memoryTrailText = TextView(this).apply {
            text = "Memory Trail: waiting for a saved gate."
            setTextColor(Color.rgb(35, 42, 48))
            textSize = 14f
            setPadding(18, 16, 18, 16)
            background = roundedBackground(Color.rgb(247, 250, 249), 18f, Color.rgb(214, 226, 222))
        }

        confidenceBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            setPadding(0, 10, 0, 10)
        }

        searchInput = EditText(this).apply {
            hint = "Search saved locations"
            setSingleLine(true)
            background = roundedBackground(Color.rgb(247, 250, 249), 18f, Color.rgb(214, 226, 222))
            setPadding(18, 12, 18, 12)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                    renderGallery(filteredTags(text?.toString().orEmpty()))
                }
                override fun afterTextChanged(text: Editable?) = Unit
            })
        }

        nameInput = EditText(this).apply {
            hint = "Location name"
            setSingleLine(true)
        }

        noteInput = EditText(this).apply {
            hint = "Text note"
            minLines = 2
        }

        tagButton = Button(this).apply {
            text = "TAG LOCATION"
            background = roundedBackground(Color.rgb(33, 150, 136), 18f)
            setTextColor(Color.WHITE)
            setOnClickListener {
                mode = Mode.Tagging
                candidateTagId = null
                candidateMatchCount = 0
                confidenceHistory.clear()
                confidenceBar.progress = 0
                matchOverlay.visibility = View.GONE
                memoryTrailText.text = "Memory Trail: capture a place once, then return to unlock it."
                statusText.text = "Frame ready. Add name/note, record voice if needed, then save."
            }
        }

        recognizeButton = Button(this).apply {
            text = "RECOGNIZE"
            background = roundedBackground(Color.rgb(23, 98, 167), 18f)
            setTextColor(Color.WHITE)
            setOnClickListener {
                loadSavedTags()
                mode = Mode.Recognizing
                lastPlayedTagId = null
                lastMatchedTag = null
                candidateTagId = null
                candidateMatchCount = 0
                confidenceHistory.clear()
                matchOverlay.visibility = View.GONE
                memoryTrailText.text = "Memory Trail: scanning for a familiar gate..."
                statusText.text = if (savedFeatures.isEmpty()) {
                    "No saved gates yet. Tag a location first."
                } else {
                    "Recognizing saved gates..."
                }
            }
        }

        recordButton = Button(this).apply {
            text = "RECORD VOICE"
            background = roundedBackground(Color.rgb(94, 73, 160), 18f)
            setTextColor(Color.WHITE)
            setOnClickListener { toggleRecording() }
        }

        saveButton = Button(this).apply {
            text = "SAVE TAG"
            background = roundedBackground(Color.rgb(40, 120, 75), 18f)
            setTextColor(Color.WHITE)
            setOnClickListener { saveCurrentTag() }
        }

        playButton = Button(this).apply {
            text = "PLAY NOTE"
            background = roundedBackground(Color.rgb(42, 42, 46), 18f)
            setTextColor(Color.WHITE)
            setOnClickListener { playCurrentVoiceNote() }
        }

        privacyButton = Button(this).apply {
            text = "PRIVACY PROOF"
            background = roundedBackground(Color.rgb(15, 23, 32), 18f)
            setTextColor(Color.WHITE)
            setOnClickListener { showPrivacyProof() }
        }

        demoGuideButton = Button(this).apply {
            text = "DEMO GUIDE"
            background = roundedBackground(Color.rgb(96, 71, 46), 18f)
            setTextColor(Color.WHITE)
            setOnClickListener { showDemoGuide() }
        }

        resetButton = Button(this).apply {
            text = "RESET DEMO DATA"
            background = roundedBackground(Color.rgb(150, 54, 54), 18f)
            setTextColor(Color.WHITE)
            setOnClickListener { confirmResetDemoData() }
        }

        galleryContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 0)
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 18, 24, 24)
            setBackgroundColor(Color.WHITE)
            addView(qualityText)
            addView(confidenceBar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(memoryTrailText, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = 8
            })
            addView(nameInput)
            addView(noteInput)
            addView(horizontalButtons(tagButton, recognizeButton))
            addView(horizontalButtons(recordButton, saveButton))
            addView(playButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(privacyButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(demoGuideButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(resetButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(searchInput, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = 10
            })
            addView(galleryContainer)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(18, 18, 18, 16)
            background = roundedBackground(Color.rgb(16, 24, 32), 0f)
            addView(titleText, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(badgeText, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(statusText, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = 12
            })
            setOnApplyWindowInsetsListener { view, insets ->
                val topInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.getInsets(WindowInsets.Type.systemBars()).top
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetTop
                }
                view.setPadding(18, topInset + 18, 18, 16)
                insets
            }
        }

        val cameraFrame = FrameLayout(this).apply {
            addView(previewView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            addView(matchOverlay, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply {
                leftMargin = 32
                rightMargin = 32
            })
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(16, 24, 32))
            addView(header)
            addView(cameraFrame, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
            addView(
                ScrollView(this@MainActivity).apply { addView(controls) },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    controlsPanelHeight(),
                ),
            )
        }
        setContentView(root)
    }

    private fun horizontalButtons(left: Button, right: Button): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 6)
            addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = 6
            })
            addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = 6
            })
        }
    }

    private fun roundedBackground(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    private fun controlsPanelHeight(): Int {
        val screenHeight = resources.displayMetrics.heightPixels
        return (screenHeight * 0.38f).toInt().coerceIn(dp(280), dp(360))
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun roundedBackground(color: Int, radius: Float, strokeColor: Int): GradientDrawable {
        return roundedBackground(color, radius, strokeColor, 2)
    }

    private fun roundedBackground(color: Int, radius: Float, strokeColor: Int, strokeWidth: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            setStroke(strokeWidth, strokeColor)
        }
    }

    private fun showPrivacyProof() {
        AlertDialog.Builder(this)
            .setTitle("Privacy Proof")
            .setMessage(
                "GateMemory has no internet permission.\n\n" +
                    "Recognition runs with on-device OpenCV ORB.\n\n" +
                    "Images, descriptors, text notes, and voice notes stay in this phone's private app storage.",
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showDemoGuide() {
        AlertDialog.Builder(this)
            .setTitle("Fast Demo")
            .setMessage(
                "1. Point at a textured gate, sign, wall, or entrance.\n\n" +
                    "2. Tap TAG LOCATION and wait for Quality: good or excellent.\n\n" +
                    "3. Add a name, record at least 1 second of voice, then save.\n\n" +
                    "4. Walk away, tap RECOGNIZE, and point back at the same place.\n\n" +
                    "5. When Memory Unlocked appears, the saved note and voice note should play.",
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun confirmResetDemoData() {
        AlertDialog.Builder(this)
            .setTitle("Reset Demo Data?")
            .setMessage("This clears saved gates, thumbnails, descriptors, and voice notes from this phone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Reset") { _, _ ->
                stopPlayback()
                recorder?.release()
                recorder = null
                recordedAudioFile = null
                tagStore.clearAll()
                savedFeatures = emptyList()
                lastMatchedTag = null
                lastPlayedTagId = null
                candidateTagId = null
                candidateMatchCount = 0
                confidenceHistory.clear()
                mode = Mode.Idle
                confidenceBar.progress = 0
                matchOverlay.visibility = View.GONE
                qualityText.text = "Quality: point at a textured gate or sign"
                memoryTrailText.text = "Memory Trail: clean demo slate ready."
                statusText.text = "Demo data reset. Tag a fresh gate."
                loadSavedTags()
            }
            .show()
    }

    private fun ensurePermissionsAndStartCamera() {
        val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), PERMISSIONS_REQUEST)
        } else {
            startCamera()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startCamera()
        } else {
            statusText.text = "Camera and microphone permissions are needed."
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(
            {
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analyzer = ImageAnalysis.Builder()
                        .setTargetResolution(Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { analysis ->
                            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                analyzeFrame(imageProxy)
                            }
                        }

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analyzer,
                    )
                    statusText.text = "Camera ready. Tag a gate or start recognition."
                } catch (error: Exception) {
                    statusText.text = "Camera failed to start: ${error.message ?: "unknown error"}"
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        val gray = imageProxy.toGrayMat()
        imageProxy.close()

        synchronized(this) {
            latestGrayFrame?.release()
            latestGrayFrame = gray.clone()
        }

        if (mode == Mode.Idle || (mode == Mode.Recognizing && savedFeatures.isEmpty())) {
            gray.release()
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastAnalysisMillis < 750L) {
            gray.release()
            return
        }
        lastAnalysisMillis = now

        val features = recognitionEngine.extractFeatures(gray)
        val keypointCount = features.keypoints.rows().toInt()
        val brightness = Core.mean(gray).`val`[0]

        if (mode == Mode.Tagging) {
            gray.release()
            runOnUiThread { updateQualityMeter(keypointCount, brightness) }
            return
        }

        val result = recognitionEngine.score(features.descriptors, savedFeatures)
        gray.release()
        runOnUiThread {
            val confidencePercent = (result.confidence * 100f).toInt().coerceIn(0, 100)
            confidenceBar.progress = confidencePercent
            pushConfidence(confidencePercent)
            if (keypointCount < config.minimumLiveKeypoints) {
                candidateTagId = null
                candidateMatchCount = 0
                matchOverlay.visibility = View.GONE
                statusText.text = if (brightness < LOW_LIGHT_THRESHOLD) {
                    "LOW LIGHT\nMove to better light for recognition."
                } else {
                    "NO KNOWN GATE\nToo few visual features in live view."
                }
                updateMemoryTrail(result.bestTag, confidencePercent, false)
                return@runOnUiThread
            }

            val confidence = String.format(Locale.US, "%.0f%%", result.confidence * 100f)
            if (result.isMatch(config)) {
                val tag = result.bestTag ?: return@runOnUiThread
                if (candidateTagId == tag.id) {
                    candidateMatchCount += 1
                } else {
                    candidateTagId = tag.id
                    candidateMatchCount = 1
                }
                lastMatchedTag = tag
                if (candidateMatchCount >= REQUIRED_STABLE_MATCHES) {
                    statusText.text = "MATCH FOUND\n${tag.name}\nConfidence: $confidence"
                    showMatchOverlay(tag.name, confidence)
                    updateMemoryTrail(tag, confidencePercent, true)
                    if (lastPlayedTagId != tag.id) {
                        previewView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        playUnlockCue()
                    }
                    playVoiceNoteOnce(tag)
                } else {
                    statusText.text = "LOCKING MATCH\n${tag.name}\nConfidence: $confidence"
                    matchOverlay.visibility = View.VISIBLE
                    matchOverlay.text = "Almost familiar\nHold steady..."
                    updateMemoryTrail(tag, confidencePercent, false)
                }
            } else {
                candidateTagId = null
                candidateMatchCount = 0
                lastMatchedTag = result.bestTag
                matchOverlay.visibility = View.GONE
                statusText.text = "NO KNOWN GATE\nBest confidence: $confidence"
                updateMemoryTrail(result.bestTag, confidencePercent, false)
            }
        }
    }

    private fun showMatchOverlay(name: String, confidence: String) {
        matchOverlay.visibility = View.VISIBLE
        matchOverlay.text = "MEMORY UNLOCKED\n$name\n$confidence"
    }

    private fun pushConfidence(confidencePercent: Int) {
        confidenceHistory.addLast(confidencePercent)
        while (confidenceHistory.size > CONFIDENCE_HISTORY_SIZE) {
            confidenceHistory.removeFirst()
        }
    }

    private fun updateMemoryTrail(tag: GateTag?, confidencePercent: Int, locked: Boolean) {
        if (tag == null) {
            memoryTrailText.text = "Memory Trail: no saved gate feels close yet.\nConfidence path: ${confidenceTrail()}"
            return
        }

        val firstSeen = ageText(System.currentTimeMillis() - tag.createdAtMillis)
        val noteLine = tag.noteText.ifBlank { "No text note saved." }
        val fingerprint = sceneFingerprint(tag)
        memoryTrailText.text = if (locked) {
            "Memory Trail: You were here before.\n${tag.name} • $fingerprint • saved $firstSeen\n\"$noteLine\"\nConfidence path: ${confidenceTrail()}"
        } else {
            "Memory Trail: this feels familiar.\nClosest: ${tag.name} • $fingerprint at $confidencePercent%\nConfidence path: ${confidenceTrail()}"
        }
    }

    private fun confidenceTrail(): String {
        return if (confidenceHistory.isEmpty()) {
            "waiting"
        } else {
            confidenceHistory.joinToString(" -> ") { "$it%" }
        }
    }

    private fun ageText(ageMillis: Long): String {
        val minutes = (ageMillis / 60_000L).coerceAtLeast(0L)
        val hours = minutes / 60L
        val days = hours / 24L
        return when {
            minutes < 1 -> "just now"
            minutes == 1L -> "1 minute ago"
            minutes < 60 -> "$minutes minutes ago"
            hours == 1L -> "1 hour ago"
            hours < 24 -> "$hours hours ago"
            days == 1L -> "1 day ago"
            else -> "$days days ago"
        }
    }

    private fun updateQualityMeter(keypointCount: Int, brightness: Double) {
        qualityText.text = when {
            brightness < LOW_LIGHT_THRESHOLD -> "Quality: low light • move closer to light"
            keypointCount >= 120 -> "Quality: excellent • $keypointCount keypoints"
            keypointCount >= 60 -> "Quality: good • $keypointCount keypoints"
            keypointCount >= config.minimumTagKeypoints -> "Quality: usable • $keypointCount keypoints"
            else -> "Quality: too blank • $keypointCount/${config.minimumTagKeypoints} keypoints"
        }
        confidenceBar.progress = ((keypointCount / 120f) * 100f).toInt().coerceIn(0, 100)
    }

    private fun saveCurrentTag() {
        val name = nameInput.text.toString().trim()
        if (recorder != null) {
            statusText.text = "Stop recording before saving this tag."
            return
        }
        if (name.isBlank()) {
            statusText.text = "Add a location name before saving."
            return
        }

        val frame = synchronized(this) { latestGrayFrame?.clone() }
        if (frame == null || frame.empty()) {
            statusText.text = "No camera frame is ready yet."
            return
        }

        val features = recognitionEngine.extractFeatures(frame)
        val keypointCount = features.keypoints.rows().toInt()
        if (features.descriptors.empty() || keypointCount < config.minimumTagKeypoints) {
            statusText.text = "Cannot tag reliably: only $keypointCount keypoints found. Point at a textured gate or sign."
            frame.release()
            return
        }

        val savedAudioPath = recordedAudioFile?.absolutePath
        val tag = tagStore.saveTag(
            name = name,
            noteText = noteInput.text.toString().trim(),
            transcript = null,
            isTranscribing = savedAudioPath != null,
            image = frame,
            descriptors = features.descriptors,
            audioPath = savedAudioPath,
        )
        frame.release()
        nameInput.text.clear()
        noteInput.text.clear()
        recordedAudioFile = null
        recordButton.text = "RECORD VOICE"
        loadSavedTags()
        if (savedAudioPath != null) {
            transcribeSavedVoiceNote(tag)
        }
        mode = Mode.Idle
        confidenceBar.progress = 100
        qualityText.text = "Quality: saved successfully"
        statusText.text = if (tag.audioPath != null) {
            "Saved ${tag.name} with $keypointCount keypoints. Whisper transcription is running..."
        } else {
            "Saved ${tag.name} with $keypointCount keypoints."
        }
    }

    private fun loadSavedTags() {
        val tags = tagStore.loadTags()
        allTags = tags
        savedFeatures = tags.mapNotNull { tag ->
            tagStore.loadDescriptors(tag)?.let { descriptors -> SavedTagFeatures(tag, descriptors) }
        }
        renderGallery(filteredTags(searchInput.text?.toString().orEmpty()))
    }

    private fun filteredTags(query: String): List<GateTag> {
        val normalizedQuery = query.trim().lowercase(Locale.US)
        if (normalizedQuery.isBlank()) return allTags

        return allTags.filter { tag ->
            tag.name.contains(normalizedQuery, ignoreCase = true) ||
                tag.noteText.contains(normalizedQuery, ignoreCase = true) ||
                tag.transcript.orEmpty().contains(normalizedQuery, ignoreCase = true)
        }
    }

    private fun renderGallery(tags: List<GateTag>) {
        galleryContainer.removeAllViews()

        val title = TextView(this).apply {
            val query = searchInput.text?.toString()?.trim().orEmpty()
            text = if (query.isBlank()) {
                "Saved Locations"
            } else {
                "Saved Locations (${tags.size} match${if (tags.size == 1) "" else "es"})"
            }
            setTextColor(Color.rgb(20, 26, 32))
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 8, 0, 8)
        }
        galleryContainer.addView(title)

        if (tags.isEmpty()) {
            galleryContainer.addView(TextView(this).apply {
                text = if (allTags.isEmpty()) {
                    "No saved gates yet. Tag your first entrance."
                } else {
                    "No saved gates match this search."
                }
                setTextColor(Color.DKGRAY)
                textSize = 14f
                setPadding(0, 4, 0, 12)
            })
            return
        }

        tags.reversed().forEach { tag ->
            galleryContainer.addView(galleryCard(tag))
        }
    }

    private fun galleryCard(tag: GateTag): LinearLayout {
        val image = ImageView(this).apply {
            val bitmap = BitmapFactory.decodeFile(tag.imagePath)
            if (bitmap != null) {
                setImageBitmap(bitmap)
            } else {
                setBackgroundColor(Color.rgb(230, 235, 233))
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        val name = TextView(this).apply {
            text = tag.name
            setTextColor(Color.rgb(17, 25, 32))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }

        val note = TextView(this).apply {
            text = tag.noteText.ifBlank { "No text note" }
            setTextColor(Color.rgb(70, 76, 82))
            textSize = 13f
            maxLines = 2
        }

        val transcript = TextView(this).apply {
            text = when {
                tag.isTranscribing -> "Transcript: Transcribing..."
                !tag.transcript.isNullOrBlank() -> "Transcript: ${tag.transcript}"
                else -> ""
            }
            visibility = if (tag.isTranscribing || !tag.transcript.isNullOrBlank()) View.VISIBLE else View.GONE
            setTextColor(Color.rgb(44, 92, 124))
            textSize = 13f
            maxLines = 3
            setPadding(0, 4, 0, 0)
        }

        val fingerprint = TextView(this).apply {
            text = sceneFingerprint(tag)
            setTextColor(Color.rgb(33, 150, 136))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 3, 0, 0)
        }

        val audioButton = Button(this).apply {
            text = if (tag.audioPath != null && File(tag.audioPath).exists()) "PLAY VOICE" else "NO VOICE"
            isEnabled = tag.audioPath != null && File(tag.audioPath).exists()
            background = roundedBackground(Color.rgb(42, 42, 46), 16f)
            setTextColor(Color.WHITE)
            setOnClickListener {
                tag.audioPath?.let { audioPath -> playAudioFile(File(audioPath)) }
            }
        }

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 0, 0, 0)
            addView(name)
            addView(fingerprint)
            addView(note)
            addView(transcript)
            addView(audioButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = 8
            })
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 12, 12, 12)
            background = roundedBackground(Color.WHITE, 18f, Color.rgb(226, 232, 230))
            addView(image, LinearLayout.LayoutParams(110, 110))
            addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = 12
            }
        }
    }

    private fun toggleRecording() {
        if (recorder != null) {
            try {
                recorder?.stop()
                statusText.text = "Voice note recorded. Tap PLAY NOTE to check it."
            } catch (error: Exception) {
                recordedAudioFile?.delete()
                recordedAudioFile = null
                statusText.text = "Recording was too short. Hold record for at least one second."
            } finally {
                recorder?.release()
                recorder = null
                recordButton.text = "RECORD VOICE"
            }
            return
        }

        val audioFile = tagStore.createAudioFile()
        recordedAudioFile = audioFile
        try {
            recorder = WavAudioRecorder().apply {
                start(audioFile)
            }
            recordButton.text = "STOP RECORDING"
            statusText.text = "Recording WAV voice note for Whisper..."
        } catch (error: Exception) {
            recorder?.release()
            recorder = null
            recordedAudioFile = null
            statusText.text = "Could not record voice note: ${error.message ?: "unknown error"}"
        }
    }

    private fun transcribeSavedVoiceNote(tag: GateTag) {
        val audioPath = tag.audioPath ?: return
        appScope.launch {
            statusText.text = "Whisper is transcribing ${tag.name}..."
            val transcript = withContext(Dispatchers.IO) {
                runCatching { whisperTranscriber.transcribe(audioPath) }.getOrNull()
            }
            tagStore.updateTranscript(tag.id, transcript, false)
            loadSavedTags()
            statusText.text = if (transcript.isNullOrBlank()) {
                "Saved ${tag.name}. Whisper could not find clear speech."
            } else {
                "Whisper transcript ready for ${tag.name}."
            }
        }
    }

    private fun playVoiceNoteOnce(tag: GateTag) {
        val audioPath = tag.audioPath ?: return
        if (lastPlayedTagId == tag.id) return
        val audioFile = File(audioPath)
        if (!audioFile.exists()) return
        lastPlayedTagId = tag.id
        playAudioFile(audioFile)
    }

    private fun playCurrentVoiceNote() {
        val audioFile = recordedAudioFile
            ?: lastMatchedTag?.audioPath?.let { File(it) }
            ?: tagStore.loadTags().lastOrNull { it.audioPath != null }?.audioPath?.let { File(it) }

        if (audioFile == null || !audioFile.exists()) {
            statusText.text = "No saved voice note found yet."
            return
        }

        playAudioFile(audioFile)
    }

    private fun playAudioFile(audioFile: File) {
        try {
            player?.release()
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(audioFile.absolutePath)
                setOnCompletionListener {
                    it.release()
                    if (player === it) {
                        player = null
                    }
                }
                setOnPreparedListener {
                    it.start()
                    statusText.text = "Playing voice note..."
                }
                prepareAsync()
            }
        } catch (error: Exception) {
            player?.release()
            player = null
            statusText.text = "Could not play voice note: ${error.message ?: "unknown error"}"
        }
    }

    private fun playUnlockCue() {
        if (toneGenerator == null) {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
        }
        toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 120)
    }

    private fun stopPlayback() {
        player?.release()
        player = null
    }

    private fun sceneFingerprint(tag: GateTag): String {
        val seed = "${tag.id}:${tag.createdAtMillis}:${tag.descriptorPath}".hashCode()
        val value = seed.toUInt().toString(16).uppercase(Locale.US).takeLast(4).padStart(4, '0')
        return "GATE-$value"
    }

    private fun ImageProxy.toGrayMat(): Mat {
        val plane = planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val frameWidth = width
        val frameHeight = height
        val allBytes = ByteArray(buffer.remaining())
        buffer.get(allBytes)

        val gray = Mat(frameHeight, frameWidth, CvType.CV_8UC1)
        if (rowStride == frameWidth) {
            gray.put(0, 0, allBytes)
        } else {
            val row = ByteArray(frameWidth)
            for (rowIndex in 0 until frameHeight) {
                System.arraycopy(allBytes, rowIndex * rowStride, row, 0, frameWidth)
                gray.put(rowIndex, 0, row)
            }
        }

        return when (imageInfo.rotationDegrees) {
            90 -> Mat().also { Core.rotate(gray, it, Core.ROTATE_90_CLOCKWISE); gray.release() }
            180 -> Mat().also { Core.rotate(gray, it, Core.ROTATE_180); gray.release() }
            270 -> Mat().also { Core.rotate(gray, it, Core.ROTATE_90_COUNTERCLOCKWISE); gray.release() }
            else -> gray
        }
    }

    private enum class Mode {
        Idle,
        Tagging,
        Recognizing,
    }

    companion object {
        private const val PERMISSIONS_REQUEST = 42
        private const val REQUIRED_STABLE_MATCHES = 2
        private const val CONFIDENCE_HISTORY_SIZE = 5
        private const val LOW_LIGHT_THRESHOLD = 45.0
    }
}
