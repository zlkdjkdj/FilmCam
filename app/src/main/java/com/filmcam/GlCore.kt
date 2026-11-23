package com.filmcam

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/** EGL 컨텍스트 + 서피스 관리 (ES 3.0, 녹화 가능 설정). */
class EglCore {
    val display: android.opengl.EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val config: EGLConfig
    private val context: EGLContext

    init {
        val v = IntArray(2)
        check(EGL14.eglInitialize(display, v, 0, v, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT
            0x3142, 1,                        // EGL_RECORDABLE_ANDROID
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "no EGL config" }
        config = configs[0]!!
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
    }

    fun windowSurface(target: Any): EGLSurface =
        EGL14.eglCreateWindowSurface(display, config, target, intArrayOf(EGL14.EGL_NONE), 0)

    fun pbuffer(w: Int, h: Int): EGLSurface = EGL14.eglCreatePbufferSurface(
        display, config, intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE), 0,
    )

    fun makeCurrent(s: EGLSurface) { EGL14.eglMakeCurrent(display, s, s, context) }
    fun swap(s: EGLSurface) { EGL14.eglSwapBuffers(display, s) }
    fun setPresentationTime(s: EGLSurface, nanos: Long) { EGLExt.eglPresentationTimeANDROID(display, s, nanos) }
    fun swapInterval(i: Int) { EGL14.eglSwapInterval(display, i) }
    fun destroy(s: EGLSurface) { EGL14.eglDestroySurface(display, s) }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(display, context)
        // eglTerminate는 프로세스 공용 display를 죽이므로 호출하지 않는다.
    }
}

class GlProgram(vs: String, fs: String) {
    val id: Int
    private val locs = HashMap<String, Int>()

    init {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, v)
        GLES30.glAttachShader(id, f)
        GLES30.glLinkProgram(id)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] == GLES30.GL_TRUE) { "link failed: " + GLES30.glGetProgramInfoLog(id) }
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES30.GL_TRUE) { "shader failed: " + GLES30.glGetShaderInfoLog(s) }
        return s
    }

    fun use() = GLES30.glUseProgram(id)
    fun loc(name: String) = locs.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }
    fun release() = GLES30.glDeleteProgram(id)
}

private const val VERT = """#version 300 es
layout(location = 0) in vec2 aPos;
uniform mat4 uTexMatrix;
uniform float uFlipY;
out vec2 vUv;
out vec2 vTex;
void main() {
    vUv = aPos * 0.5 + 0.5;
    vTex = (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy;
    gl_Position = vec4(aPos.x, aPos.y * uFlipY, 0.0, 1.0);
}
"""

private const val FRAG_BODY = """
in vec2 vUv;
in vec2 vTex;
uniform vec3 uGain;
uniform float uExposure;
uniform float uSat;
uniform float uMono;
uniform float uCurve;
uniform float uFade;
uniform vec3 uShadow;
uniform vec3 uHigh;
uniform float uSplit;
uniform float uGrain;
uniform float uVig;
uniform float uLeak;
uniform float uSeed;
uniform float uScale;
uniform float uAspect;
uniform float uHal;
uniform mat4 uTexMatrix;
out vec4 outColor;

float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main() {
    vec3 lw = vec3(0.2126, 0.7152, 0.0722);
    vec3 c = texture(uTex, vTex).rgb;
    c *= uExposure * uGain;
    float l = dot(c, lw);
    c = mix(c, vec3(l), uMono);
    l = dot(c, lw);
    c = mix(vec3(l), c, uSat);
    c = clamp(c, 0.0, 1.0);
    c = mix(c, c * c * (3.0 - 2.0 * c), uCurve);
    c = vec3(uFade) + c * (1.0 - uFade);

    float lm = dot(c, lw);
    float sw = 1.0 - smoothstep(0.0, 0.55, lm);
    float hw = smoothstep(0.45, 1.0, lm);
    c += (uShadow * sw + uHigh * hw) * uSplit;

    if (uHal > 0.0) {
        // 할레이션: 밝은 부분 주변으로 번지는 붉은 빛 (필름 감광층 반사)
        vec3 acc = vec3(0.0);
        for (int ring = 0; ring < 2; ring++) {
            float rad = ring == 0 ? 0.016 : 0.038;
            vec2 ro = vec2(rad / uAspect, rad);
            for (int i = 0; i < 8; i++) {
                float a = float(i) * 0.7853982;
                vec2 t = (uTexMatrix * vec4(vUv + vec2(cos(a), sin(a)) * ro, 0.0, 1.0)).xy;
                vec3 sm = texture(uTex, t).rgb;
                acc += sm * smoothstep(0.62, 1.0, dot(sm, lw));
            }
        }
        float halo = dot(acc, lw) / 16.0;
        c += vec3(1.0, 0.30, 0.10) * halo * uHal * 1.7;
    }

    vec2 d = (vUv - 0.5) * vec2(uAspect, 1.0);
    float r = length(d) / (0.5 * length(vec2(uAspect, 1.0)));
    c *= 1.0 - uVig * smoothstep(0.35, 1.0, r);

    float lk = smoothstep(0.45, 0.0, vUv.x) * (0.55 + 0.45 * vUv.y);
    c += vec3(1.0, 0.38, 0.12) * lk * uLeak;

    float cs = max(1.0, floor(uScale * 1.2 + 0.5));
    vec2 gp = floor(gl_FragCoord.xy / cs);
    float n = hash(gp + uSeed) - 0.5;
    float n2 = hash(gp * 1.37 + uSeed + 11.0) - 0.5;
    float gLum = dot(c, lw);
    float gw = 1.0 - abs(gLum * 2.0 - 1.0) * 0.6;
    c += (vec3(n) + vec3(n2, -n2 * 0.5, n2 * 0.7) * 0.35) * uGrain * gw;

    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""

private fun fragSrc(oes: Boolean): String =
    "#version 300 es\n" +
        (if (oes) "#extension GL_OES_EGL_image_external_essl3 : require\n" else "") +
        "precision highp float;\n" +
        "uniform " + (if (oes) "samplerExternalOES" else "sampler2D") + " uTex;\n" +
        FRAG_BODY

private const val STAMP_VERT = """#version 300 es
layout(location = 0) in vec2 aPos;
uniform vec4 uRect;
uniform float uFlipY;
out vec2 vTc;
void main() {
    vec2 a = aPos * 0.5 + 0.5;
    vec2 p = mix(uRect.xy, uRect.zw, a);
    vTc = vec2(a.x, 1.0 - a.y);
    gl_Position = vec4(p.x, p.y * uFlipY, 0.0, 1.0);
}
"""

private const val STAMP_FRAG = """#version 300 es
precision mediump float;
in vec2 vTc;
uniform sampler2D uTex;
out vec4 outColor;
void main() { outColor = texture(uTex, vTc); }
"""

/** 필름 필터 + 타임스탬프 오버레이를 그리는 파이프라인. 사용하는 GL 스레드/컨텍스트에서 생성해야 한다. */
class FilmPipeline(private val oes: Boolean) {
    private val vbo: Int
    private val main = GlProgram(VERT, fragSrc(oes))
    private val stamp = GlProgram(STAMP_VERT, STAMP_FRAG)
    private val target = if (oes) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES30.GL_TEXTURE_2D

    init {
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        val data = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(data).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, buf, GLES30.GL_STATIC_DRAW)
    }

    private fun drawQuad() {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** 원본 텍스처에 필터를 적용해 현재 프레임버퍼에 그린다. 뷰포트는 호출 전에 설정. */
    fun drawMain(
        tex: Int, texMatrix: FloatArray, flipY: Float, f: FilmFilter,
        outW: Int, outH: Int, seed: Float,
    ) {
        GLES30.glDisable(GLES30.GL_BLEND)
        main.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(target, tex)
        GLES30.glUniform1i(main.loc("uTex"), 0)
        GLES30.glUniformMatrix4fv(main.loc("uTexMatrix"), 1, false, texMatrix, 0)
        GLES30.glUniform1f(main.loc("uFlipY"), flipY)
        GLES30.glUniform3fv(main.loc("uGain"), 1, f.gain, 0)
        GLES30.glUniform1f(main.loc("uExposure"), f.exposure)
        GLES30.glUniform1f(main.loc("uSat"), f.saturation)
        GLES30.glUniform1f(main.loc("uMono"), f.mono)
        GLES30.glUniform1f(main.loc("uCurve"), f.curve)
        GLES30.glUniform1f(main.loc("uFade"), f.fade)
        GLES30.glUniform3fv(main.loc("uShadow"), 1, f.shadow, 0)
        GLES30.glUniform3fv(main.loc("uHigh"), 1, f.highlight, 0)
        GLES30.glUniform1f(main.loc("uSplit"), f.split)
        GLES30.glUniform1f(main.loc("uGrain"), f.grain)
        GLES30.glUniform1f(main.loc("uVig"), f.vignette)
        GLES30.glUniform1f(main.loc("uLeak"), f.leak)
        GLES30.glUniform1f(main.loc("uHal"), f.halation)
        GLES30.glUniform1f(main.loc("uSeed"), seed)
        GLES30.glUniform1f(main.loc("uScale"), outH / 1440f)
        GLES30.glUniform1f(main.loc("uAspect"), outW.toFloat() / outH)
        drawQuad()
    }

    fun drawStamp(tex: Int, rect: FloatArray, flipY: Float) {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        stamp.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glUniform1i(stamp.loc("uTex"), 0)
        GLES30.glUniform4fv(stamp.loc("uRect"), 1, rect, 0)
        GLES30.glUniform1f(stamp.loc("uFlipY"), flipY)
        drawQuad()
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    fun release() {
        main.release()
        stamp.release()
        GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
    }
}

/** 타임스탬프 비트맵을 텍스처로 올려 두고 크기가 바뀔 때만 다시 만든다. */
class StampTexture {
    var id = 0
        private set
    private var key = ""
    private var bw = 0
    private var bh = 0

    fun ensure(text: String, outW: Int, outH: Int) {
        val k = "$text|$outW|$outH"
        if (k == key && id != 0) return
        release()
        val bmp = DateStamp.render(text, min(outW, outH) * 0.036f)
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        id = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        bw = bmp.width
        bh = bmp.height
        bmp.recycle()
        key = k
    }

    /** 우측 하단 정렬된 NDC 사각형 (x0, y0, x1, y1) */
    fun rect(outW: Int, outH: Int): FloatArray {
        val margin = min(outW, outH) * 0.035f
        val x1 = outW - margin
        val x0 = x1 - bw
        val y0 = margin
        val y1 = y0 + bh
        return floatArrayOf(
            x0 / outW * 2f - 1f, y0 / outH * 2f - 1f,
            x1 / outW * 2f - 1f, y1 / outH * 2f - 1f,
        )
    }

    fun release() {
        if (id != 0) GLES30.glDeleteTextures(1, intArrayOf(id), 0)
        id = 0
        key = ""
    }
}

object GlUtil {
    fun newOesTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return ids[0]
    }
}

/** 비트맵(앨범 사진/촬영본)에 필름 필터와 타임스탬프를 입힌다. 오프스크린 GL 사용. */
object FilterProcessor {
    private val FLIP_V = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, -1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 1f, 0f, 1f,
    )

    @Synchronized
    /**
     * @param rotation 사진을 바로 세우려고 시계방향으로 돌려야 하는 각도 (0/90/180/270). GPU에서 처리해 비트맵 복사본을 만들지 않는다.
     * @param recycleSource true면 GPU로 올린 직후 원본 비트맵을 해제해 메모리를 아낀다.
     */
    fun process(
        src: Bitmap, filter: FilmFilter, stampText: String?, seed: Float = 7f,
        rotation: Int = 0, recycleSource: Boolean = false,
    ): Bitmap {
        val swap = rotation == 90 || rotation == 270
        val w = if (swap) src.height else src.width
        val h = if (swap) src.width else src.height
        val egl = EglCore()
        val pb = egl.pbuffer(1, 1)
        egl.makeCurrent(pb)
        var pipe: FilmPipeline? = null
        val stamp = StampTexture()
        val ids = IntArray(3) // src tex, fbo tex, fbo
        try {
            pipe = FilmPipeline(false)

            GLES30.glGenTextures(2, ids, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
            texParams()
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, src, 0)
            if (recycleSource) src.recycle()

            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[1])
            texParams()
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
            )
            val fb = IntArray(1)
            GLES30.glGenFramebuffers(1, fb, 0)
            ids[2] = fb[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fb[0])
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, ids[1], 0,
            )
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                "framebuffer incomplete"
            }

            GLES30.glViewport(0, 0, w, h)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            // flipY=-1: 읽어낸 첫 행이 화면 위쪽이 되도록 뒤집어 그린다.
            val texMat = FloatArray(16)
            System.arraycopy(FLIP_V, 0, texMat, 0, 16)
            if (rotation != 0) {
                android.opengl.Matrix.translateM(texMat, 0, 0.5f, 0.5f, 0f)
                android.opengl.Matrix.rotateM(texMat, 0, rotation.toFloat(), 0f, 0f, 1f)
                android.opengl.Matrix.translateM(texMat, 0, -0.5f, -0.5f, 0f)
            }
            pipe.drawMain(ids[0], texMat, -1f, filter, w, h, seed)
            if (stampText != null) {
                stamp.ensure(stampText, w, h)
                pipe.drawStamp(stamp.id, stamp.rect(w, h), -1f)
            }

            val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            buf.rewind()
            out.copyPixelsFromBuffer(buf)
            return out
        } finally {
            runCatching {
                stamp.release()
                pipe?.release()
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                if (ids[2] != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(ids[2]), 0)
                GLES30.glDeleteTextures(2, ids, 0)
            }
            egl.destroy(pb)
            egl.release()
        }
    }

    private fun texParams() {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }
}
