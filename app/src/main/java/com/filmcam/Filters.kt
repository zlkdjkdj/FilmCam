package com.filmcam

/** 필름 룩 파라미터. 셰이더 유니폼과 1:1 대응한다. */
class FilmFilter(
    val id: String,
    val name: String,
    val gain: FloatArray = floatArrayOf(1f, 1f, 1f),   // 색온도/틴트 (RGB 게인)
    val exposure: Float = 1f,
    val saturation: Float = 1f,
    val mono: Float = 0f,
    val curve: Float = 0f,       // S커브 세기 (콘트라스트)
    val fade: Float = 0f,        // 블랙 리프트 (바랜 느낌)
    val shadow: FloatArray = floatArrayOf(0f, 0f, 0f),     // 쉐도우 틴트
    val highlight: FloatArray = floatArrayOf(0f, 0f, 0f),  // 하이라이트 틴트
    val split: Float = 0f,
    val grain: Float = 0f,
    val vignette: Float = 0f,
    val leak: Float = 0f,        // 라이트리크 (왼쪽 가장자리 주황빛)
    val halation: Float = 0f,    // 밝은 곳 주변의 붉은 번짐
)

object Filters {
    val ORIGINAL = FilmFilter("original", "원본")

    /** 앱을 켰을 때 기본으로 선택되는 필름 */
    const val DEFAULT_INDEX = 1

    val ALL: List<FilmFilter> = listOf(
        ORIGINAL,
        FilmFilter(
            "disposable", "일회용",
            gain = floatArrayOf(1.04f, 1.03f, 0.92f), exposure = 1.10f, saturation = 1.10f,
            curve = 0.55f, fade = 0.05f,
            shadow = floatArrayOf(0.0f, 0.03f, 0.0f), highlight = floatArrayOf(0.04f, 0.03f, 0.0f),
            split = 1f, grain = 0.09f, vignette = 0.55f, leak = 0.35f, halation = 0.15f,
        ),
        FilmFilter(
            "gold", "Gold 200",
            gain = floatArrayOf(1.06f, 1.0f, 0.90f), exposure = 1.04f, saturation = 1.18f,
            curve = 0.35f, fade = 0.02f,
            shadow = floatArrayOf(0.03f, 0.01f, -0.02f), highlight = floatArrayOf(0.05f, 0.03f, -0.03f),
            split = 1f, grain = 0.06f, vignette = 0.25f, halation = 0.15f,
        ),
        FilmFilter(
            "portra", "Portra 400",
            gain = floatArrayOf(1.03f, 1.0f, 0.96f), exposure = 1.06f, saturation = 0.92f,
            curve = 0.20f, fade = 0.05f,
            shadow = floatArrayOf(0.0f, 0.01f, 0.02f), highlight = floatArrayOf(0.04f, 0.02f, 0.0f),
            split = 1f, grain = 0.045f, vignette = 0.12f, halation = 0.2f,
        ),
        FilmFilter(
            "fuji", "Fuji 400",
            gain = floatArrayOf(0.96f, 1.03f, 1.02f), exposure = 1.02f, saturation = 1.12f,
            curve = 0.30f, fade = 0.03f,
            shadow = floatArrayOf(-0.02f, 0.025f, 0.02f), highlight = floatArrayOf(0.01f, 0.0f, 0.02f),
            split = 1f, grain = 0.07f, vignette = 0.18f,
        ),
        FilmFilter(
            "ektar", "Ektar 100",
            gain = floatArrayOf(1.03f, 1.0f, 0.97f), exposure = 1.02f, saturation = 1.40f,
            curve = 0.50f,
            shadow = floatArrayOf(0.0f, 0.0f, 0.015f), highlight = floatArrayOf(0.02f, 0.01f, 0.0f),
            split = 1f, grain = 0.035f, vignette = 0.20f,
        ),
        FilmFilter(
            "velvia", "Velvia 50",
            gain = floatArrayOf(0.98f, 1.02f, 1.0f), saturation = 1.55f,
            curve = 0.65f,
            shadow = floatArrayOf(-0.01f, 0.0f, 0.03f), highlight = floatArrayOf(0.02f, 0.01f, -0.01f),
            split = 1f, grain = 0.04f, vignette = 0.22f,
        ),
        FilmFilter(
            "cine", "Cine 800T",
            gain = floatArrayOf(0.92f, 1.0f, 1.12f), saturation = 1.10f,
            curve = 0.45f, fade = 0.02f,
            shadow = floatArrayOf(-0.02f, 0.02f, 0.05f), highlight = floatArrayOf(0.06f, 0.0f, -0.02f),
            split = 1f, grain = 0.11f, vignette = 0.30f, halation = 0.7f,
        ),
        FilmFilter(
            "polaroid", "Polaroid 600",
            gain = floatArrayOf(1.05f, 1.0f, 0.93f), exposure = 1.12f, saturation = 0.85f,
            curve = 0.15f, fade = 0.10f,
            shadow = floatArrayOf(0.0f, 0.02f, 0.04f), highlight = floatArrayOf(0.05f, 0.03f, -0.02f),
            split = 1f, grain = 0.05f, vignette = 0.35f, leak = 0.10f, halation = 0.2f,
        ),
        FilmFilter(
            "expired", "Expired '94",
            gain = floatArrayOf(1.06f, 0.94f, 1.04f), saturation = 0.85f,
            curve = 0.35f, fade = 0.12f,
            shadow = floatArrayOf(0.05f, 0.0f, 0.06f), highlight = floatArrayOf(0.05f, 0.04f, -0.02f),
            split = 1f, grain = 0.12f, vignette = 0.45f, leak = 0.45f, halation = 0.25f,
        ),
        FilmFilter(
            "cross", "Lomo Cross",
            gain = floatArrayOf(1.02f, 1.0f, 0.95f), saturation = 1.35f,
            curve = 0.90f,
            shadow = floatArrayOf(-0.02f, 0.04f, 0.06f), highlight = floatArrayOf(0.08f, 0.05f, -0.06f),
            split = 1.2f, grain = 0.08f, vignette = 0.70f,
        ),
        FilmFilter(
            "sunburn", "Sunburn",
            gain = floatArrayOf(1.10f, 0.98f, 0.88f), exposure = 1.08f, saturation = 1.20f,
            curve = 0.30f, fade = 0.05f,
            shadow = floatArrayOf(0.05f, 0.0f, 0.03f), highlight = floatArrayOf(0.06f, 0.03f, -0.04f),
            split = 1f, grain = 0.07f, vignette = 0.30f, leak = 0.80f, halation = 0.5f,
        ),
        FilmFilter(
            "trix", "Tri-X 400",
            mono = 1f, exposure = 1.05f, curve = 0.75f, fade = 0.02f,
            grain = 0.16f, vignette = 0.30f,
        ),
        FilmFilter(
            "hp5", "Ilford HP5",
            mono = 1f, exposure = 1.08f, curve = 0.30f, fade = 0.06f,
            shadow = floatArrayOf(0.0f, 0.0f, 0.01f), highlight = floatArrayOf(0.02f, 0.012f, 0.0f),
            split = 1f, grain = 0.12f, vignette = 0.35f, halation = 0.2f,
        ),
    )
}
