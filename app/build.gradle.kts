import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing is read from keystore/keystore.properties (not committed).
val signingProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.airplaytv.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.airplaytv.receiver"
        minSdk = 23
        targetSdk = 35
        // CI passes these from the git tag (v1.2.3 → 1.2.3 / 10203); local builds use the defaults.
        versionName = (findProperty("appVersionName") as String?) ?: "1.0.0"
        versionCode = (findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
        // GitHub repository whose Releases are checked for updates (owner/name).
        val updateRepo = (findProperty("updateRepo") as String?)
            ?: System.getenv("GITHUB_REPOSITORY") ?: "AKondrackii/airplay-android-tv"
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }
    }

    signingConfigs {
        if (signingProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        jniLibs { useLegacyPackaging = false }
        // Library version markers and JAR indexes are not needed at runtime.
        resources.excludes += listOf("META-INF/*.version", "META-INF/INDEX.LIST", "META-INF/version-control-info.textproto")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":security"))
    implementation(project(":network"))
    implementation(project(":airplay"))
    implementation(project(":playback"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
