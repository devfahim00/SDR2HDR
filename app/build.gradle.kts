import java.io.File
import java.net.URL
import java.util.zip.ZipInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ── ncnn (Vulkan, static) for the on-device AI upscaler ──────────────────────────────────────
// Downloaded once into app/.deps (git-ignored); only the arm64-v8a slice is kept.
val ncnnVersion = "20260526"
val ncnnRoot = layout.projectDirectory.dir(".deps/ncnn-$ncnnVersion").asFile

val fetchNcnn = tasks.register("fetchNcnn") {
    val marker = File(ncnnRoot, ".ok")
    outputs.file(marker)
    doLast {
        if (marker.exists()) return@doLast
        ncnnRoot.deleteRecursively()
        ncnnRoot.mkdirs()
        val url = "https://github.com/Tencent/ncnn/releases/download/$ncnnVersion/ncnn-$ncnnVersion-android-vulkan.zip"
        val zip = File(ncnnRoot.parentFile, "ncnn-$ncnnVersion.zip")
        var lastError: Exception? = null
        for (attempt in 1..3) {
            try {
                URL(url).openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
                lastError = null
                break
            } catch (e: Exception) {
                lastError = e
            }
        }
        lastError?.let { throw GradleException("Could not download ncnn from $url", it) }
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val idx = entry.name.indexOf("/arm64-v8a/")
                if (!entry.isDirectory && idx >= 0) {
                    // keep "arm64-v8a/..." relative to ncnnRoot
                    val rel = entry.name.substring(idx + 1)
                    val out = File(ncnnRoot, rel).canonicalFile
                    if (!out.path.startsWith(ncnnRoot.canonicalPath)) throw GradleException("Bad zip entry: ${entry.name}")
                    out.parentFile.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
                entry = zis.nextEntry
            }
        }
        zip.delete()
        marker.writeText(ncnnVersion)
    }
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(fetchNcnn) }

android {
    namespace = "com.devfahim00.sdr2hdr"
    compileSdk = 34
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.devfahim00.sdr2hdr"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.0.1"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        // The AI upscaler (ncnn + Vulkan) is built for 64-bit ARM only; on other ABIs the
        // library is simply absent and the feature hides itself.
        externalNativeBuild {
            cmake {
                abiFilters += listOf("arm64-v8a")
                arguments += listOf("-DANDROID_STL=c++_static", "-DNCNN_ROOT=${ncnnRoot.absolutePath}")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // GitHub-release APKs are sideloaded: sign with the debug key so the
            // artifact installs without a dedicated keystore/secret setup.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.github.bumptech.glide:glide:4.16.0") // video thumbnails
    implementation("com.antonkarpenko:ffmpeg-kit-full-gpl:2.2.1")
}
