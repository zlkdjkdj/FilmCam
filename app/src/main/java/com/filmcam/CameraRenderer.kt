package com.filmcam

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaRecorder
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.camera.core.SurfaceRequest
import java.io.File
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * CameraX 프리뷰 스트림을 받아 GL 셰이더로 필름 필터 + 타임스탬프를 입혀
 * TextureView(미리보기)와 MediaRecorder(3초 영상)에 동시에 그린다.
 */
class CameraRenderer(
    private val view: TextureView,
    private val onReady: () -> Unit,
) : TextureView.SurfaceTextureListener {

    companion object {
        private const val TAG = "CameraRenderer"
    }

    private val thread = HandlerThread("film-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var egl: EglCore? = null
    private var pipe: FilmPipeline? = null
    private var previewSurf: EGLSurface? = null
    private var camTex = 0
    @Volatile private var camST: SurfaceTexture? = null
    private var viewW = 0
    private var viewH = 0

    private val stampPreview = StampTexture()
    private val stampRec = StampTexture()
    private val stMat = FloatArray(16)
    private val texMat = FloatArray(16)
    private val random = Random()

    @Volatile var filter: FilmFilter = Filters.ORIGINAL
    @Volatile var stampMode: Int = DateStamp.MODE_DATE
    @Volatile var mirror: Boolean = false
    @Volatile var frontCamera: Boolean = false
    /** 전면 카메라 방향 보정: bit0 = 상하 반전, bit1 = 좌우 반전 (기기 고정 방향 기준) */
    @Volatile var frontVariant: Int = 0
    /** 기기가 시계방향으로 돈 각도(0/90/180/270). 장면은 항상 바로 선 채로 보이고 저장된다. */
    @Volatile var deviceRotation: Int = 0
    private var recDev = 0
    private var recTsOffset = Long.MIN_VALUE
    private var recW = 1080
    private var recH = 1440
    @Volatile private var rotation: Int = 90

    private var recSurf: EGLSurface? = null
    private var recorder: MediaRecorder? = null

    private var cachedMinute = -1L
    private var cachedText: String? = null
    private var cachedMode = -1

    init {
        view.surfaceTextureListener = this
        if (view.isAvailable) {
            val st = view.surfaceTexture
            if (st != null) handler.post { setup(st, view.width, view.height) }
        }
    }

    // ---- TextureView 콜백 ----
    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        handler.post { setup(st, width, height) }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        handler.post {
            val e = egl
            if (e != null && (width != viewW || height != viewH)) {
                // 크기가 바뀌면 EGL 서피스를 새로 만들어 미리보기가 늘어나지 않게 한다.
                try {
                    previewSurf?.let { e.destroy(it) }
                    val ns = e.windowSurface(st)
                    previewSurf = ns
                    e.makeCurrent(ns)
                } catch (t: Throwable) {
                    Log.e(TAG, "resize failed", t)
                }
            }
            viewW = width
            viewH = height
        }
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        val latch = CountDownLatch(1)
        handler.post { teardown(); latch.countDown() }
        latch.await(2, TimeUnit.SECONDS)
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    // ---- GL 스레드 ----
    private fun setup(st: SurfaceTexture, w: Int, h: Int) {
        try {
            val e = EglCore()
            egl = e
            val ps = e.windowSurface(st)
            previewSurf = ps
            e.makeCurrent(ps)
            e.swapInterval(1)
            pipe = FilmPipeline(true)
            camTex = GlUtil.newOesTexture()
            viewW = w
            viewH = h
            val cam = SurfaceTexture(camTex)
            cam.setOnFrameAvailableListener({ drawFrame() }, handler)
            camST = cam
            mainHandler.post(onReady)
        } catch (t: Throwable) {
            Log.e(TAG, "setup failed", t)
        }
    }

    private fun teardown() {
        finishRecording()
        camST?.let { it.setOnFrameAvailableListener(null); it.release() }
        camST = null
        runCatching {
            stampPreview.release()
            stampRec.release()
            pipe?.release()
        }
        pipe = null
        val e = egl
        if (e != null) {
            previewSurf?.let { e.destroy(it) }
            e.release()
        }
        previewSurf = null
        egl = null
    }

    private fun stampText(): String? {
        val mode = stampMode
        if (mode == DateStamp.MODE_OFF) return null
        val now = System.currentTimeMillis()
        val minute = now / 60_000L
        if (minute != cachedMinute || mode != cachedMode) {
            cachedText = DateStamp.text(mode, now)
            cachedMinute = minute
            cachedMode = mode
        }
        return cachedText
    }

    private fun buildTexMatrix(mirrored: Boolean, dev: Int) {
        System.arraycopy(stMat, 0, texMat, 0, 16)
        Matrix.translateM(texMat, 0, 0.5f, 0.5f, 0f)
        // 전면 카메라 버퍼는 세로 화면 기준으로 상하가 뒤집혀 들어온다 (기기 고정 방향 보정)
        if (frontCamera) Matrix.scaleM(texMat, 0, if (frontVariant and 2 != 0) -1f else 1f, if (frontVariant and 1 != 0) -1f else 1f, 1f)
        if (dev != 0) Matrix.rotateM(texMat, 0, dev.toFloat(), 0f, 0f, 1f)
        Matrix.scaleM(texMat, 0, if (mirrored) -1f else 1f, 1f, 1f)
        Matrix.translateM(texMat, 0, -0.5f, -0.5f, 0f)
    }

    private fun drawFrame() {
        val e = egl ?: return
        val p = pipe ?: return
        val ps = previewSurf ?: return
        val cam = camST ?: return
        try {
            e.makeCurrent(ps)
            cam.updateTexImage()
            cam.getTransformMatrix(stMat)
            val f = filter
            val text = stampText()
            val seed = random.nextFloat() * 100f

            // 미리보기 (가로 화면이면 뷰 자체가 4:3 가로형이다)
            buildTexMatrix(mirror, deviceRotation)
            GLES30.glViewport(0, 0, viewW, viewH)
            p.drawMain(camTex, texMat, 1f, f, viewW, viewH, seed)
            if (text != null) {
                stampPreview.ensure(text, viewW, viewH)
                p.drawStamp(stampPreview.id, stampPreview.rect(viewW, viewH), 1f)
            }
            e.swap(ps)

            // 녹화 (거울 반전 없이 저장)
            val rs = recSurf
            if (rs != null) {
                e.makeCurrent(rs)
                buildTexMatrix(false, recDev)
                GLES30.glViewport(0, 0, recW, recH)
                p.drawMain(camTex, texMat, 1f, f, recW, recH, seed)
                if (text != null) {
                    stampRec.ensure(text, recW, recH)
                    p.drawStamp(stampRec.id, stampRec.rect(recW, recH), 1f)
                }
                val ts = cam.timestamp
                if (recTsOffset == Long.MIN_VALUE) {
                    // MediaRecorder는 CLOCK_MONOTONIC 기준 시각을 기대한다. 카메라가 부팅시각(절전 포함)으로
                    // 찍어 주면 그 차이를 빼서 같은 시계로 맞춘다 (안 그러면 영상이 수십 시간부터 시작).
                    val bt = android.os.SystemClock.elapsedRealtimeNanos()
                    val mono = System.nanoTime()
                    recTsOffset = if (kotlin.math.abs(ts - bt) < kotlin.math.abs(ts - mono)) bt - mono else 0L
                }
                e.setPresentationTime(rs, ts - recTsOffset)
                e.swap(rs)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "drawFrame failed", t)
        }
    }

    // ---- CameraX 연결 ----
    /** CameraX Preview 가 요청하는 서피스를 GL 카메라 텍스처로 연결한다 (메인 스레드). */
    fun provide(request: SurfaceRequest, executor: Executor) {
        val st = camST
        if (st == null) {
            request.willNotProvideSurface()
            return
        }
        st.setDefaultBufferSize(request.resolution.width, request.resolution.height)
        val surface = Surface(st)
        request.setTransformationInfoListener(executor) { info -> rotation = info.rotationDegrees }
        request.provideSurface(surface, executor) { surface.release() }
    }

    // ---- 3초 영상 녹화 ----
    fun startRecording(ctx: Context, file: File, withAudio: Boolean, cb: (Boolean) -> Unit) {
        handler.post {
            var mr: MediaRecorder? = null
            try {
                val e = egl ?: throw IllegalStateException("no egl")
                recDev = deviceRotation
                recTsOffset = Long.MIN_VALUE
                val land = recDev == 90 || recDev == 270
                recW = if (land) 1440 else 1080
                recH = if (land) 1080 else 1440
                mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
                if (withAudio) mr.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                mr.setOutputFile(file.absolutePath)
                mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                mr.setVideoSize(recW, recH)
                mr.setVideoFrameRate(30)
                mr.setVideoEncodingBitRate(16_000_000)
                if (withAudio) {
                    mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    mr.setAudioEncodingBitRate(128_000)
                    mr.setAudioSamplingRate(44_100)
                }
                mr.prepare()
                val s = e.windowSurface(mr.surface)
                e.makeCurrent(s)
                e.swapInterval(0)
                recSurf = s
                mr.start()
                recorder = mr
                mainHandler.post { cb(true) }
            } catch (t: Throwable) {
                Log.e(TAG, "startRecording failed", t)
                runCatching { mr?.release() }
                recSurf?.let { s -> egl?.destroy(s) }
                recSurf = null
                recorder = null
                mainHandler.post { cb(false) }
            }
        }
    }

    fun stopRecording(cb: (Boolean) -> Unit) {
        handler.post {
            val ok = finishRecording()
            mainHandler.post { cb(ok) }
        }
    }

    private fun finishRecording(): Boolean {
        val mr = recorder ?: return false
        recorder = null
        val s = recSurf
        recSurf = null
        s?.let { egl?.destroy(it) }
        var ok = true
        try {
            mr.stop()
        } catch (t: RuntimeException) {
            ok = false
            Log.e(TAG, "recorder stop failed", t)
        }
        mr.release()
        previewSurf?.let { egl?.makeCurrent(it) }
        return ok
    }

    fun release() {
        handler.post { teardown() }
        thread.quitSafely()
    }
}
