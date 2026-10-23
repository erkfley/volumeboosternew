# Volume Booster — Project Plan

## Package
- **Default:** `com.hermes.volumebooster`
- Ganti `[USERNAME_GITHUB]` dengan username GitHub Anda di `app/build.gradle` (`namespace` + `applicationId`) dan di semua file Kotlin (`package com...`).

## Daftar File

### Gradle Wrapper
| File | Fungsi |
|------|--------|
| `gradle/wrapper/gradle-wrapper.jar` | Binary JAR — sudah terdownload (Gradle 8.7) |
| `gradle/wrapper/gradle-wrapper.properties` | URL distribusi Gradle 8.7 + path konfigurasi |
| `gradlew` | Shell script untuk menjalankan Gradle di CI |
| `gradlew.bat` | Batch script (Windows fallback) |

### Project-Level
| File | Fungsi |
|------|--------|
| `build.gradle` | Plugin AGP 8.5.2 + Kotlin 2.0.20 |
| `settings.gradle` | Konfigurasi repositori (Google, Maven Central) + nama modul |
| `gradle.properties` | `android.useAndroidX=true`, JVM args |

### App Module (`app/`)
| File | Fungsi |
|------|--------|
| `app/build.gradle` | Konfigurasi AGP, Compose, Shizuku dependency, minSdk 26 / targetSdk 35 |
| `app/proguard-rules.pro` | Keep rules untuk Shizuku |
| `app/src/main/AndroidManifest.xml` | Permission MODIFY_AUDIO_SETTINGS, ShizukuProvider, queries untuk moe.shizuku.privileged.api |
| `app/src/main/kotlin/.../MainActivity.kt` | Logika utama: LoudnessEnhancer, AudioManager, headset detection, Shizuku mode, Compose UI |
| `app/src/main/res/values/strings.xml` | String resources |
| `app/src/main/res/values/colors.xml` | Warna tema |
| `app/src/main/res/values/themes.xml` | Tema Material3 DayNight NoActionBar |
| `app/src/main/res/values-night/themes.xml` | Tema dark (fallback) |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | Vector drawable foreground ikon |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | Adaptive icon |
| `app/src/test/java/.../ExampleUnitTest.kt` | Unit test stub |
| `app/src/androidTest/java/.../ExampleInstrumentedTest.kt` | Instrumented test stub |

### CI
| File | Fungsi |
|------|--------|
| `.github/workflows/build.yml` | Trigger push ke `main`, runner `ubuntu-latest`, Java 17 (Temurin), `./gradlew assembleDebug`, upload APK artifact |

## Dependency Versions
| Library | Versi |
|---------|-------|
| AGP | 8.5.2 |
| Gradle | 8.7 |
| Kotlin | 2.0.20 |
| Compose BOM | 2024.09.00 (Compose 1.7.0) |
| Shizuku API | 13.1.5 |
| Shizuku Provider | 13.1.5 |
| compileSdk | 35 |
| minSdk | 26 |
| targetSdk | 35 |
