# 스케치 변환 (Sketch Converter)

사진을 연필 스케치 PNG로 바꿔 갤러리(`Pictures/Sketch`)에 저장하는 안드로이드 앱.

## 다운로드

**[SketchConverter.apk (v1.0)](https://github.com/chanmin0723/sketch/releases/download/v1.0/SketchConverter.apk)** — Android 10 이상

1. 휴대폰 브라우저에서 위 링크를 열어 APK를 받습니다.
2. 받은 파일을 누르고, "출처를 알 수 없는 앱" 안내가 나오면 허용합니다.
3. Play 프로텍트 경고가 뜨면 *세부정보 더보기 › 무시하고 설치*. (디버그 서명이라 나오는 경고)

## 사용법

- **사진 선택**으로 여러 장을 한 번에 고르면 차례로 변환해 `Pictures/Sketch/원본이름_sketch.png`로 저장합니다.
- 갤러리에서 사진 **공유 › 스케치로 변환**으로도 실행됩니다.
- 미리보기를 누르면 원본과 스케치를 번갈아 봅니다. **공유** 버튼으로 결과를 보냅니다.

## 변환 알고리즘

`src/com/chanmin/sketch/SketchCore.java` — 순수 Java (OpenCV 의존성 없음).

1. 회색조 변환 후 self-guided filter 2회로 노이즈 제거 (원래 Python 버전의 NL-means + bilateral 대체)
2. CLAHE (clip 2.0, 8×8 타일)
3. Color-dodge 음영: `gray × 255 / (255 − blur(255 − gray))`, blur σ = 긴 변 / 70
4. DoG 윤곽선 (σ 1.2, 3.0)
5. 곱하기 → 감마 1.25 → 종이 질감 노이즈

긴 변이 3000px보다 크면 3000px로 줄여서 처리합니다.

데스크톱에서 결과 확인:

```bash
javac -d build/desk src/com/chanmin/sketch/SketchCore.java test/DesktopTest.java
java -cp build/desk DesktopTest input.jpg output.png
```

## 빌드

Android SDK 없이 `build.sh`로 빌드합니다. `TOOLS_DIR`(기본 `.toolchain/`)에 다음이 필요합니다.

| 경로 | 출처 |
|---|---|
| `aapt/aapt2_64` | apktool jar 안의 `prebuilt/linux/aapt2_64` |
| `android-34.jar` | Sable/android-platforms |
| `d8-fixed.jar` | D8을 dex2jar로 JVM jar로 변환 후 `tools/FixItf.java`로 interface method ref 보정 |
| `fixitf/StripParams.class`, `asm.jar` | `tools/StripParams.java` (ASM 9) |
| `uber-apk-signer-1.3.0.jar` | patrickfav/uber-apk-signer |

```bash
./build.sh   # -> build/apk/signed/*.apk
```
