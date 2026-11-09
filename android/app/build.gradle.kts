import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

// Play upload key: android/keystore.properties (never committed) with storeFile, storePassword, keyAlias,
// keyPassword. Without it, release builds fall back to the debug key so they still build locally, but such
// an APK/AAB can't be uploaded to Play.
val keystoreProps = Properties().apply {
  rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    // Kotlin package/namespace kept from OpenVW; the Play identity is applicationId below.
    namespace = "dev.openvw"
    compileSdk = 36
    defaultConfig {
        // Play identity (vibhor.tech). It can never change once published.
        applicationId = "tech.vibhor.carpulse"
        minSdk = 24
        targetSdk = 36
        versionCode = 4
        versionName = "1.1.2"
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug" // installs next to the Play build
        }
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // The APK shared with testers outside Play (assembleSideload): the release build under its own app id
        // ("CarPulse Beta"), so it installs next to the Play app: Play re-signs with Google's key, and a same-id APK
        // signed with the upload key fails with "App not installed" while any trace of the Play install remains.
        create("sideload") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.kotlinx.coroutines.android)

  // OpenStreetMap (open-source maps, no API key)
  implementation(libs.osmdroid.android)

  // Live car feed (same SignalR hub the official app uses)
  implementation(libs.signalr)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation("org.json:json:20240303") // real org.json for JVM unit tests (Android's is a stub there)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // Android Auto / car app templates (+ projected: the car's own speed / fuel / odometer sensors)
  implementation(libs.androidx.car.app)
  implementation(libs.androidx.car.app.projected)

  // Background car alerts (15-minute check while alerts are on)
  implementation(libs.androidx.work.runtime.ktx)

  // App lock (biometric / screen lock); fragment pinned because biometric 1.1.0 pulls a 2020 version
  implementation(libs.androidx.biometric)
  implementation(libs.androidx.fragment.ktx)
}
