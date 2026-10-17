plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 署名(release)は環境変数があるときだけ。無ければ未署名の release になる(CI)。
val keystoreFile: String? = System.getenv("BUNRI_KEYSTORE_FILE")?.takeIf { it.isNotEmpty() }

android {
    namespace = "com.yusukedoi.bunrisaistaff"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yusukedoi.bunrisaistaff"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2.0"
        // 管理者パスワード(UTF-8 の 16 進)。ビルド時の環境変数だけから入る。ソースには無い。
        val hex = System.getenv("BUNRI_ADMIN_PASSWORD_HEX") ?: ""
        buildConfigField("String", "ADMIN_PASSWORD_HEX", "\"$hex\"")
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("BUNRI_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BUNRI_KEY_ALIAS") ?: "bunri-staff"
                keyPassword = System.getenv("BUNRI_KEY_PASSWORD") ?: System.getenv("BUNRI_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions { unitTests.isReturnDefaultValues = false }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    val camerax = "1.4.0"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")
}
