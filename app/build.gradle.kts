import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

val releaseSigningFile = rootProject.file("keystore.properties")
val releaseSigning = Properties().apply {
    if (releaseSigningFile.isFile) releaseSigningFile.inputStream().use { load(it) }
}
val signingFields = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
if (releaseSigningFile.isFile) {
    require(signingFields.all { !releaseSigning.getProperty(it).isNullOrBlank() }) {
        "keystore.properties must contain storeFile, storePassword, keyAlias and keyPassword"
    }
}
val selectedAbis = providers.gradleProperty("transcriptor.abis").orNull
    ?.split(',')?.map(String::trim)?.distinct() ?: listOf("arm64-v8a", "x86_64")
require(selectedAbis.isNotEmpty() && selectedAbis.all { it in setOf("arm64-v8a", "x86_64") }) {
    "transcriptor.abis supports arm64-v8a and x86_64"
}

android {
    namespace = "id.tomz.transcriptor"
    compileSdk { version = release(37) { minorApiLevel = 2 } }

    defaultConfig {
        applicationId = "id.tomz.transcriptor"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=c++_shared" }
        }
    }

    ndkVersion = "28.2.13676358"

    signingConfigs {
        if (releaseSigningFile.isFile) create("release") {
            storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
            storePassword = releaseSigning.getProperty("storePassword")
            keyAlias = releaseSigning.getProperty("keyAlias")
            keyPassword = releaseSigning.getProperty("keyPassword")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    packaging {
        jniLibs.useLegacyPackaging = false
    }
    bundle { language { enableSplit = false } }
    splits {
        abi {
            isEnable = true
            reset()
            include(*selectedAbis.toTypedArray())
            isUniversalApk = false
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

kotlin { jvmToolchain(25) }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.core.ktx)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.commons.compress)
    implementation(libs.sherpa.onnx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.ext.junit)
    androidTestImplementation(libs.room.testing)
    debugImplementation(libs.compose.ui.test.manifest)
    debugImplementation(libs.compose.ui.tooling)
}
