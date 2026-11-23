package com.filmcam

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.lifecycle.LiveData
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : ComponentActivity() {

    private val bg = Executors.newSingleThreadExecutor()

    private lateinit var root: FrameLayout
    private lateinit var textureView: TextureView
    private lateinit var shutter: ShutterView
    private lateinit var stampBtn: TextView
    private lateinit var gridBtn: TextView
    private lateinit var resBtn: TextView
    private lateinit var gridView: GridView
    private lateinit var zoomPill: TextView
    private lateinit var filterLabel: TextView
    private lateinit var flash: View
    private lateinit var strip: FilterStrip
    private lateinit var photoTab: TextView
    private lateinit var videoTab: TextView
    private lateinit var editor: EditorView

    private var renderer: CameraRenderer? = null
    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var zoomLive: LiveData<ZoomState>? = null
    private var curZoom = 1f
    private var minZoom = 1f
    private var maxZoom = 1f
    private var keepZoom = 1f

    private val prefs by lazy { getSharedPreferences("filmcam", MODE_PRIVATE) }
    private var gridOn = true
    /** 0 = 낮음(약 3MP), 1 = 표준(12MP), 2 = 최대(기기가 지원하는 최대, S25는 50MP) */
    private var resLevel = 1

    private var front = false
    private var videoMode = false
    private var recording = false
    private var stampMode = DateStamp.MODE_DATE
    private var recAnimator: ValueAnimator? = null

    private var savedFilter = Filters.DEFAULT_INDEX

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = windowManager.defaultDisplay.rotation

    /** 기기가 시계방향으로 돈 각도 (셰이더 회전용) */
    private fun deviceAngle(): Int = when (displayRotation()) {
        Surface.ROTATION_90 -> 270
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 90
        else -> 0
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.CAMERA] == true) {
            startRenderer()
        } else {
            toast("카메라 권한이 필요합니다")
        }
    }

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) openEditor(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let {
            front = it.getBoolean("front")
            videoMode = it.getBoolean("video")
            stampMode = it.getInt("stamp", DateStamp.MODE_DATE)
            savedFilter = it.getInt("filter", Filters.DEFAULT_INDEX)
        }
        gridOn = prefs.getBoolean("grid", true)
        resLevel = prefs.getInt("res", 1).coerceIn(0, 2)
        buildUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (editor.visibility == View.VISIBLE) {
                    editor.visibility = View.GONE
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        handleIncoming(intent)
        if (hasPermission(Manifest.permission.CAMERA)) {
            startRenderer()
        } else {
            permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("front", front)
        outState.putBoolean("video", videoMode)
        outState.putInt("stamp", stampMode)
        outState.putInt("filter", strip.selected)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** 갤러리의 공유/열기로 들어온 사진은 바로 편집 화면으로 연다. */
    @Suppress("DEPRECATION")
    private fun handleIncoming(intent: android.content.Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            android.content.Intent.ACTION_SEND -> intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
            android.content.Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null && intent.type?.startsWith("image/") == true) openEditor(uri)
    }

    /** 화면을 돌려도 액티비티를 다시 만들지 않는다. 레이아웃 위치만 바뀌고 카메라는 계속 돈다. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!recording) renderer?.deviceRotation = deviceAngle()
        imageCapture?.targetRotation = displayRotation()
    }

    override fun onPause() {
        super.onPause()
        if (recording) finishVideo()
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer?.release()
        renderer = null
        bg.shutdown()
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(BG_DARK) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        // 미리보기 프레임
        val frame = FrameLayout(this)
        textureView = TextureView(this)
        frame.addView(textureView, FrameLayout.LayoutParams(-1, -1))
        filterLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            setPadding(dp(14), dp(12), 0, 0)
        }
        frame.addView(filterLabel, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        gridView = GridView(this)
        frame.addView(gridView, FrameLayout.LayoutParams(-1, -1))
        flash = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0f }
        frame.addView(flash, FrameLayout.LayoutParams(-1, -1))
        zoomPill = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = pill(0x99000000.toInt(), 16)
            setOnClickListener { setZoom(1f) }
        }
        frame.addView(zoomPill, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(46)
        })
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                setZoom(curZoom * d.scaleFactor)
                return true
            }
        })
        frame.setOnTouchListener { _, e -> pinch.onTouchEvent(e); true }

        // 상단 바
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = "FILMCAM"
            textSize = 15f
            letterSpacing = 0.2f
            setTextColor(AMBER)
            setPadding(dp(16), 0, 0, 0)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
        }, LinearLayout.LayoutParams(0, -1, 1f))
        gridBtn = textButton("") { toggleGrid() }
        resBtn = textButton("") { cycleResolution() }
        stampBtn = textButton("") { cycleStamp() }
        listOf(gridBtn, resBtn, stampBtn).forEachIndexed { i, b ->
            b.textSize = 13f
            b.setPadding(dp(11), dp(6), dp(11), dp(6))
            top.addView(b, LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = if (i == 2) dp(12) else dp(6) })
        }

        strip = FilterStrip(this) { f -> setFilter(f) }

        val modes = LinearLayout(this).apply { gravity = Gravity.CENTER }
        photoTab = modeTab("사진") { setVideoMode(false) }
        videoTab = modeTab("3초 영상") { setVideoMode(true) }
        modes.addView(photoTab)
        modes.addView(videoTab)

        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val importBtn = circleButton("앨범") { if (!recording) pickFromAlbum() }
        shutter = ShutterView(this).apply { setOnClickListener { onShutter() } }
        val flipBtn = circleButton("전환") { if (!recording) flipCamera() }
        controls.addView(wrapCenter(importBtn), LinearLayout.LayoutParams(0, -1, 1f))
        controls.addView(wrapCenter(shutter, dp(84)), LinearLayout.LayoutParams(0, -1, 1f))
        controls.addView(wrapCenter(flipBtn), LinearLayout.LayoutParams(0, -1, 1f))

        root.addView(CameraLayout(this, frame, top, strip, modes, controls), FrameLayout.LayoutParams(-1, -1))

        editor = EditorView()
        editor.visibility = View.GONE
        root.addView(editor, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)
        strip.post { strip.select(savedFilter, false) }
        filterLabel.text = Filters.ALL[savedFilter].name
        refreshStampLabel()
        refreshModeTabs()
        refreshGrid()
        refreshResLabel()
        updateZoomPill()
    }

    private fun modeTab(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(6), dp(18), dp(6))
        setOnClickListener { if (!recording) onClick() }
    }

    private fun circleButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = pill(CHIP_BG, 28)
        setOnClickListener { onClick() }
        layoutParams = ViewGroup.LayoutParams(dp(56), dp(56))
    }

    private fun wrapCenter(v: View, size: Int = 0): FrameLayout = FrameLayout(this).apply {
        if (size > 0) {
            addView(v, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        } else {
            addView(v, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        }
    }

    private fun refreshModeTabs() {
        photoTab.setTextColor(if (!videoMode) AMBER else 0xFF888888.toInt())
        videoTab.setTextColor(if (videoMode) AMBER else 0xFF888888.toInt())
        shutter.video = videoMode
    }

    private fun refreshStampLabel() {
        stampBtn.text = when (stampMode) {
            DateStamp.MODE_OFF -> "날짜 OFF"
            DateStamp.MODE_DATE -> "날짜"
            else -> "날짜+시간"
        }
        stampBtn.setTextColor(if (stampMode == DateStamp.MODE_OFF) Color.WHITE else 0xFFFF8A1F.toInt())
    }

    private fun cycleStamp() {
        stampMode = (stampMode + 1) % 3
        renderer?.stampMode = stampMode
        refreshStampLabel()
    }

    private fun toggleGrid() {
        gridOn = !gridOn
        prefs.edit().putBoolean("grid", gridOn).apply()
        refreshGrid()
    }

    private fun refreshGrid() {
        gridView.visibility = if (gridOn) View.VISIBLE else View.GONE
        gridBtn.text = if (gridOn) "격자 ON" else "격자 OFF"
        gridBtn.setTextColor(if (gridOn) 0xFFFF8A1F.toInt() else Color.WHITE)
    }

    private fun cycleResolution() {
        if (recording) return
        resLevel = (resLevel + 1) % 3
        prefs.edit().putInt("res", resLevel).apply()
        refreshResLabel()
        toast(
            when (resLevel) {
                0 -> "사진 약 3MP · 영상 HD (720p)"
                1 -> "사진 12MP · 영상 FHD (1080p)"
                else -> "사진 최대 해상도 · 영상 FHD (1080p)"
            },
        )
        keepZoom = curZoom
        bindCamera()
    }

    private fun refreshResLabel() {
        resBtn.text = when (resLevel) {
            0 -> "3MP"
            1 -> "12MP"
            else -> "MAX"
        }
        resBtn.setTextColor(if (resLevel == 1) Color.WHITE else 0xFFFF8A1F.toInt())
        renderer?.videoShortSide = if (resLevel == 0) 720 else 1080
    }

    /** 렌즈 전환 없이 한 카메라의 줌만 쓴다 (1배 ~ 최대 10배). */
    private fun setZoom(ratio: Float) {
        val cam = camera ?: return
        val lo = max(1f, minZoom)
        val hi = minOf(10f, maxZoom)
        if (hi < lo) return
        cam.cameraControl.setZoomRatio(ratio.coerceIn(lo, hi))
    }

    private fun updateZoomPill() {
        zoomPill.text = String.format(Locale.US, "%.1f×", curZoom)
        zoomPill.setTextColor(if (curZoom > 1.05f) AMBER else Color.WHITE)
    }

    private fun captureSelector(): ResolutionSelector {
        val b = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        when (resLevel) {
            0 -> b.setResolutionStrategy(
                ResolutionStrategy(Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
            )
            1 -> b.setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            else -> {
                b.setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                b.setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
            }
        }
        return b.build()
    }

    private fun setFilter(f: FilmFilter) {
        renderer?.filter = f
        filterLabel.text = f.name
    }

    private fun setVideoMode(v: Boolean) {
        videoMode = v
        refreshModeTabs()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun hasPermission(p: String) =
        checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ 카메라

    private fun startRenderer() {
        if (renderer != null) return
        renderer = CameraRenderer(textureView) { bindCamera() }.also {
            it.stampMode = stampMode
            it.filter = Filters.ALL[strip.selected]
            it.deviceRotation = deviceAngle()
            it.mirror = false
            it.frontCamera = front
            it.videoShortSide = if (resLevel == 0) 720 else 1080
        }
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val p = future.get()
            provider = p
            val r = renderer ?: return@addListener
            val rs = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
            val preview = Preview.Builder()
                .setResolutionSelector(rs)
                .setTargetRotation(Surface.ROTATION_0)
                .build()
            preview.setSurfaceProvider(mainExecutor) { req -> r.provide(req, mainExecutor) }
            val capture = ImageCapture.Builder()
                .setResolutionSelector(captureSelector())
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetRotation(Surface.ROTATION_0)
                .build()
            imageCapture = capture
            capture.targetRotation = displayRotation()
            r.mirror = false
            r.frontCamera = front
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            try {
                p.unbindAll()
                zoomLive?.removeObservers(this)
                val cam = p.bindToLifecycle(this, selector, preview, capture)
                camera = cam
                val live = cam.cameraInfo.zoomState
                zoomLive = live
                live.observe(this) { zs ->
                    curZoom = zs.zoomRatio
                    minZoom = zs.minZoomRatio
                    maxZoom = zs.maxZoomRatio
                    updateZoomPill()
                }
                if (keepZoom > 1.05f) {
                    cam.cameraControl.setZoomRatio(keepZoom.coerceIn(max(1f, cam.cameraInfo.zoomState.value?.minZoomRatio ?: 1f), minOf(10f, cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f)))
                }
                keepZoom = 1f
            } catch (t: Throwable) {
                Log.e("FilmCam", "bind failed", t)
                toast("카메라를 열 수 없습니다")
            }
        }, mainExecutor)
    }

    private fun flipCamera() {
        front = !front
        keepZoom = 1f
        bindCamera()
    }

    private fun onShutter() {
        if (editor.visibility == View.VISIBLE) return
        if (videoMode) {
            if (!recording) startVideo()
        } else {
            takePhoto()
        }
    }

    private fun blink() {
        flash.alpha = 0.85f
        flash.animate().alpha(0f).setDuration(180).start()
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val filter = Filters.ALL[strip.selected]
        val mode = stampMode
        blink()
        capture.takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rot = image.imageInfo.rotationDegrees
                val buf = image.planes[0].buffer
                val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                image.close()
                val takenAt = System.currentTimeMillis()
                bg.execute {
                    try {
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        val out = FilterProcessor.process(
                            bmp, filter, DateStamp.text(mode, takenAt), (takenAt % 97).toFloat(),
                            rotation = rot, recycleSource = true,
                        )
                        val size = "${out.width}x${out.height}"
                        MediaSaver.savePhoto(this@MainActivity, out, takenAt)
                        out.recycle()
                        runOnUiThread { toast("저장됨 · ${filter.name} · $size") }
                    } catch (t: Throwable) {
                        Log.e("FilmCam", "photo failed", t)
                        runOnUiThread { toast("사진 저장 실패") }
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e("FilmCam", "capture error", exception)
                toast("촬영 실패")
            }
        })
    }

    private fun startVideo() {
        val r = renderer ?: return
        val file = File(cacheDir, "rec_${System.currentTimeMillis()}.mp4")
        val audio = hasPermission(Manifest.permission.RECORD_AUDIO)
        r.startRecording(this, file, audio) { ok ->
            if (!ok) {
                toast("녹화를 시작할 수 없습니다")
                return@startRecording
            }
            recording = true
            recFile = file
            shutter.recording = true
            recAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 3000
                interpolator = null
                addUpdateListener { shutter.progress = it.animatedValue as Float }
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(a: Animator) { cancelled = true }
                    override fun onAnimationEnd(a: Animator) { if (!cancelled) finishVideo() }
                })
                start()
            }
        }
    }

    private var recFile: File? = null

    private fun finishVideo() {
        if (!recording) return
        recording = false
        recAnimator?.cancel()
        recAnimator = null
        shutter.recording = false
        shutter.progress = 0f
        val file = recFile
        recFile = null
        renderer?.stopRecording { ok ->
            if (ok && file != null && file.exists()) {
                bg.execute {
                    try {
                        MediaSaver.saveVideo(this, file)
                        runOnUiThread { toast("영상 저장됨") }
                    } catch (t: Throwable) {
                        Log.e("FilmCam", "video save failed", t)
                        runOnUiThread { toast("영상 저장 실패") }
                    }
                }
            } else {
                file?.delete()
                toast("영상 저장 실패")
            }
        }
    }

    // ------------------------------------------------------------------ 앨범 사진에 필터

    private fun pickFromAlbum() {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun openEditor(uri: Uri) {
        toast("불러오는 중…")
        bg.execute {
            try {
                val (bmp, taken) = decodeForEdit(uri, 4096)
                runOnUiThread { editor.open(bmp, taken) }
            } catch (t: Throwable) {
                Log.e("FilmCam", "decode failed", t)
                runOnUiThread { toast("사진을 열 수 없습니다") }
            }
        }
    }

    private fun decodeForEdit(uri: Uri, maxSide: Int): Pair<Bitmap, Long> {
        val resolver = contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("decode null")

        var taken = System.currentTimeMillis()
        var orientation = ExifInterface.ORIENTATION_NORMAL
        runCatching {
            resolver.openInputStream(uri)!!.use { s ->
                val exif = ExifInterface(s)
                orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                val d = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                if (d != null) {
                    SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(d)?.let { taken = it.time }
                }
            }
        }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        if (!m.isIdentity) {
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }
        return bmp to taken
    }

    /** 앨범 사진 편집 오버레이: 필름 고르기 → 저장. */
    inner class EditorView : FrameLayout(this@MainActivity) {
        private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        private val editStrip = FilterStrip(context) { f -> filter = f; refresh() }
        private val editStamp = textButton("") { stamp = (stamp + 1) % 3; refresh(); updateStampLabel() }
        private val saveBtn = textButton("저장") { save() }
        private val cancelBtn = textButton("취소") { visibility = GONE }

        private var full: Bitmap? = null
        private var small: Bitmap? = null
        private var taken = 0L
        private var filter: FilmFilter = Filters.ORIGINAL
        private var stamp = DateStamp.MODE_DATE
        private var generation = 0
        private var saving = false

        init {
            setBackgroundColor(BG_DARK)
            isClickable = true
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(col, LayoutParams(-1, -1))

            val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            top.addView(cancelBtn, LinearLayout.LayoutParams(-2, dp(36)).apply { marginStart = dp(12) })
            top.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            top.addView(editStamp, LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(8) })
            top.addView(saveBtn, LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(12) })
            col.addView(top, LinearLayout.LayoutParams(-1, dp(52)))
            col.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
            col.addView(editStrip, LinearLayout.LayoutParams(-1, dp(62)))
            col.addView(View(context), LinearLayout.LayoutParams(-1, dp(16)))
            updateStampLabel()
        }

        private fun updateStampLabel() {
            editStamp.text = when (stamp) {
                DateStamp.MODE_OFF -> "날짜 OFF"
                DateStamp.MODE_DATE -> "날짜"
                else -> "날짜+시간"
            }
            editStamp.setTextColor(if (stamp == DateStamp.MODE_OFF) Color.WHITE else 0xFFFF8A1F.toInt())
        }

        fun open(bmp: Bitmap, takenMillis: Long) {
            full?.recycle()
            small?.recycle()
            full = bmp
            val scale = 1600f / max(bmp.width, bmp.height)
            small = if (scale < 1f) {
                Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
            } else {
                bmp.copy(Bitmap.Config.ARGB_8888, false)
            }
            taken = takenMillis
            stamp = stampMode
            filter = Filters.ALL[strip.selected]
            editStrip.select(strip.selected, false)
            updateStampLabel()
            visibility = VISIBLE
            refresh()
        }

        private fun refresh() {
            val src = small ?: return
            val f = filter
            val text = DateStamp.text(stamp, taken)
            val gen = ++generation
            bg.execute {
                try {
                    val out = FilterProcessor.process(src, f, text, 3f)
                    runOnUiThread { if (gen == generation) image.setImageBitmap(out) }
                } catch (t: Throwable) {
                    Log.e("FilmCam", "preview failed", t)
                }
            }
        }

        private fun save() {
            val src = full ?: return
            if (saving) return
            saving = true
            val f = filter
            val text = DateStamp.text(stamp, taken)
            val t = taken
            toast("저장 중…")
            bg.execute {
                try {
                    val out = FilterProcessor.process(src, f, text, (t % 97).toFloat())
                    MediaSaver.savePhoto(this@MainActivity, out, t)
                    out.recycle()
                    runOnUiThread { toast("저장됨 · ${f.name}"); saving = false; visibility = GONE }
                } catch (e: Throwable) {
                    Log.e("FilmCam", "edit save failed", e)
                    runOnUiThread { toast("저장 실패"); saving = false }
                }
            }
        }
    }
}
