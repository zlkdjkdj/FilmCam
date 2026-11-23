# FilmCam (갤럭시 S25 개인용 필름카메라)

네이티브 Kotlin + CameraX + OpenGL ES 3.0

## 기능
- 실시간 필름 필터 미리보기: 원본 / Gold 200 / Fuji 400 / Tri-X 400 / Portra 400 / Cine 800T / 일회용
- 날짜 타임스탬프 (주황 7세그먼트): OFF → 날짜 → 날짜+시간 (사진·영상·앨범 사진 모두 적용)
- 3초 영상 촬영 (필터·타임스탬프가 영상에 구워짐, 1080x1440)
- 앨범 사진에 필터 적용 (EXIF 촬영일을 타임스탬프로 사용)
- 저장 위치: Pictures/FilmCam, Movies/FilmCam

## 설치
USB 디버깅 켜고 PC 연결 후:  adb install -r FilmCam.apk
(또는 FilmCam.apk 를 폰으로 옮겨 직접 설치)

## 빌드
경로에 한글이 있으면 AGP가 실패하므로 드라이브 매핑 후 빌드:
  subst F: "<프로젝트 폴더 경로>"
  cd /d F:\ && gradlew.bat assembleDebug
필터 룩 조정: app/src/main/java/com/filmcam/Filters.kt
