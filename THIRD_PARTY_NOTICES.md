# OCR and build dependencies

## Golden spatula icon

`app/src/main/res/drawable-nodpi/jcc_icon.png` is an ImageGen-cleaned version of the golden spatula equipment artwork supplied by the user. It replaces the previously downloaded game launcher icon, following the user's clarification. The edit removes the screenshot border and website overlay, keeps the diagonal golden spatula and dark gold background, and adds no text or characters.

The underlying game artwork belongs to Tencent and the respective rights holders. This asset is used for the local helper launcher, main screen and translucent overlay.

## OCR

The APK uses Google's bundled Chinese Text Recognition model, `com.google.mlkit:text-recognition-chinese:16.0.1`, resolved from Google's Maven repository by Gradle. The model is packaged into the app; image processing stays on the device. The app removes the INTERNET permission from the merged manifest.

- ML Kit documentation: https://developers.google.com/ml-kit/vision/text-recognition/v2/android
- ML Kit terms: https://developers.google.com/ml-kit/terms
- Google APIs terms: https://developers.google.com/terms
- AndroidX source and licenses: https://android.googlesource.com/platform/frameworks/support/

ML Kit's published Maven POMs identify their applicable terms and transitive dependencies. Packaged META-INF notices from dependencies are handled by the Android Gradle plugin. No Tesseract library or language model is shipped in v0.4.

Portable development tools are kept outside Git under `.tools/`: Gradle 8.9 (Apache 2.0), Eclipse Temurin JDK 17 (GPLv2 with the Classpath Exception), and Android SDK tools (Android SDK license). Their archives include the upstream license notices. Preparation scripts verify the pinned archive checksums.

## Champion catalogs and portraits

`app/src/main/assets/champions_s18.json` (retrieved 2026-11-09) and `champions_s16.json` (retrieved 2026-11-11) were built from the public endpoint `https://www.dataj.cc/api/web/gamedata?setId=<set>` used by https://www.dataj.cc/explorer, via `tools/prepare_catalog.py`. Each file keeps unique visible, non-special (`heroType=0`) entries with prices 1–5 whose base-star id starts with `1`; duplicate names are collapsed, and the upstream typo “艾瑞利娅” is normalized to “艾瑞莉娅”. The upstream `season` text is inconsistent with `setId`; the request parameter and page selector identify the set. Names that the source lists with more than one price (S16 波比, 伊泽瑞尔) carry a `priceVariants` field and are not used for cost recovery. No claim of official completeness is made.

Portraits in `assets/portraits/s18/` and `assets/portraits/s16/` come from each record's `picture` URL on `img.dataj.cc` and are bundled for offline search. Underlying game art remains owned by its respective rights holders.

## Formation UI anchor

`assets/templates/lineup-button.bin` is a 432-byte RGB descriptor extracted from the center of the in-game formation button in a user-provided reference screenshot. It contains the button artwork only, not the full screenshot, player names or account details. Formation preview images and full fixture screenshots remain local and are not bundled into the public repository.
