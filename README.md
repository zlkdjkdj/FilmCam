<div align="center">

# 📷 FilmCam

**갤럭시 S25에서 쓰려고 만든 필름 카메라 앱**
실시간 필름 룩 · 데이트백 타임스탬프 · 3초 영상 · 앨범 사진 필름 입히기

![Platform](https://img.shields.io/badge/platform-Android%2010%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-native-7F52FF?logo=kotlin&logoColor=white)
![OpenGL ES](https://img.shields.io/badge/OpenGL%20ES-3.0-5586A4)
![License](https://img.shields.io/badge/license-MIT-blue)

</div>

---

## ✨ 기능

| | |
|---|---|
| 🎞️ **실시간 필름 필터** | 촬영 화면에서 바로 필름 룩이 적용되어 보입니다. 13종 내장, 기본값은 **일회용** |
| 🕰️ **데이트백 타임스탬프** | 주황색 7세그먼트 숫자(`'26 11 23`)를 사진·영상·앨범 사진에 새깁니다. OFF → 날짜 → 날짜+시간 |
| 🎬 **3초 영상** | 필터와 타임스탬프가 영상에 그대로 구워지고, 소리도 함께 녹음됩니다 |
| 🖼️ **앨범 사진에 필름 입히기** | 갤러리 사진을 골라 필름을 바꿔 보고 저장. 촬영일(EXIF)을 타임스탬프로 사용 |
| 🔄 **가로·세로 촬영** | 폰을 돌리면 화면이 따라 돌고, 타임스탬프는 항상 사진의 오른쪽 아래에 찍힙니다 |
| 🤳 **전면 카메라** | 셀카 지원. 미리보기와 저장 결과의 방향이 같습니다 |

## 🎞️ 필름 목록

| 이름 | 느낌 |
|---|---|
| **일회용** *(기본)* | 노란 기운, 강한 비네팅, 살짝 새는 빛 |
| **Gold 200** | 따뜻하고 포근한 골드 톤 |
| **Portra 400** | 부드러운 파스텔, 피부톤이 예쁜 색 |
| **Fuji 400** | 시원한 초록·청록 기운 |
| **Ektar 100** | 쨍하고 선명한 풍경용 색감 |
| **Velvia 50** | 아주 진한 채도와 강한 대비 |
| **Cine 800T** | 청록 그림자와 붉은 할레이션 번짐 |
| **Polaroid 600** | 크림빛으로 바랜 검정, 포근한 인스턴트 사진 |
| **Expired '94** | 유통기한 지난 필름 같은 마젠타 틴트와 빛샘 |
| **Lomo Cross** | 크로스 프로세스, 푸른 그림자와 노란 하이라이트 |
| **Sunburn** | 한쪽에서 번지는 강한 주황 빛샘 |
| **Tri-X 400** | 거친 입자의 고대비 흑백 |
| **Ilford HP5** | 부드러운 흑백 |

모든 필름은 **색 보정 + 쉐도우/하이라이트 틴트 + 입자 + 비네팅 + 빛샘 + 할레이션** 조합입니다.
룩을 바꾸거나 새로 만들려면 [`Filters.kt`](app/src/main/java/com/filmcam/Filters.kt)의 숫자만 고치면 됩니다.

## 🛠️ 어떻게 만들었나

```
CameraX Preview ──▶ SurfaceTexture ──▶ OpenGL ES 3.0 셰이더 ─┬─▶ 미리보기 (TextureView)
                                      (필름 룩 + 타임스탬프)  └─▶ MediaRecorder (3초 영상)

CameraX ImageCapture ──▶ 같은 셰이더(오프스크린) ──▶ JPEG 저장
앨범 사진 ───────────────▶ 같은 셰이더(오프스크린) ──▶ JPEG 저장
```

미리보기, 영상, 사진, 앨범 편집이 **하나의 셰이더**를 공유해서 어디서든 같은 색이 나옵니다.

| 파일 | 역할 |
|---|---|
| [`MainActivity.kt`](app/src/main/java/com/filmcam/MainActivity.kt) | 화면, 촬영·녹화 흐름, 앨범 편집 |
| [`CameraRenderer.kt`](app/src/main/java/com/filmcam/CameraRenderer.kt) | GL 스레드, 카메라 프레임 렌더링, 3초 영상 녹화 |
| [`GlCore.kt`](app/src/main/java/com/filmcam/GlCore.kt) | EGL, 셰이더(필름 룩), 오프스크린 필터 처리 |
| [`Filters.kt`](app/src/main/java/com/filmcam/Filters.kt) | 필름 파라미터 |
| [`DateStamp.kt`](app/src/main/java/com/filmcam/DateStamp.kt) | 7세그먼트 날짜 숫자 그리기 |
| [`Views.kt`](app/src/main/java/com/filmcam/Views.kt) | 가로·세로 레이아웃, 셔터 버튼, 필름 선택줄 |
| [`MediaSaver.kt`](app/src/main/java/com/filmcam/MediaSaver.kt) | 갤러리에 저장 |

## 🚀 빌드와 설치

**필요한 것:** JDK 21, Android SDK (플랫폼 36)

```bash
# Windows
gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

결과물은 `app/build/outputs/apk/debug/app-debug.apk` 입니다. 폰에서 **USB 디버깅**(또는 무선 디버깅)을 켜고 연결한 뒤:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> 💡 **프로젝트 경로에 한글이 있으면** Android Gradle Plugin이 빌드에 실패합니다.
> 영문 경로로 옮기거나, Windows에서는 `subst F: "<프로젝트 폴더>"` 로 드라이브를 매핑한 뒤 `F:\`에서 빌드하세요.

## 📱 사용 환경

- 갤럭시 S25에서 개발·확인했습니다. 다른 기기는 확인하지 않았습니다.
- Android 10(API 29) 이상
- 권한: **카메라**, **마이크**(3초 영상 녹음용)만 요청합니다. 저장소 권한은 쓰지 않고, 앨범은 시스템 사진 선택기로 불러옵니다.
- 결과물은 `Pictures/FilmCam`, `Movies/FilmCam`에 저장됩니다.
- 인터넷을 사용하지 않습니다.

## 📝 참고

- 개인용으로 만든 앱이라 후면 카메라 1배 렌즈와 전면 카메라만 지원합니다. 줌과 렌즈 전환은 없습니다.
- 필름 색감은 숫자로 흉내 낸 것입니다. 실제 필름 스캔과 똑같지는 않습니다.
- 서명은 디버그 키로 되어 있어 스토어 배포용이 아닙니다.

## 📄 라이선스

[MIT License](LICENSE)
