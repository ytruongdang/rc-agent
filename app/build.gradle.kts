plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val fleetKs = rootProject.file("keystore/fleet.jks")
val knoxJar = file("libs/knoxsdk.jar")
val ksMap = linkedMapOf<String, String>().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) {
        f.readLines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val i = line.indexOf('=')
            if (i > 0) put(line.substring(0, i).trim(), line.substring(i + 1).trim())
        }
    }
}
fun ksProp(name: String, fallback: String = ""): String =
    ksMap[name] ?: providers.gradleProperty(name).orElse(fallback).get()

/** Cấu hình fleet: keystore.properties (gitignored) → gradle property → placeholder công khai. */
fun rcProp(name: String, fallback: String): String =
    ksMap[name] ?: providers.gradleProperty(name).orElse(fallback).get()

android {
    namespace = "com.you.rcagent"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.you.rcagent"
        minSdk = 26
        targetSdk = 36
        versionCode = 10405
        versionName = "1.4.5"
        ndk { abiFilters += listOf("arm64-v8a") }
        // Endpoint mặc định đến từ gradle property, không hardcode hạ tầng vào source.
        // Fleet riêng: đặt trong local gradle.properties / -P / CI secret (xem README).
        buildConfigField("String", "MQTT_BROKER", "\"${rcProp("RC_MQTT_BROKER", "ssl://relay.example.com:8883")}\"")
        buildConfigField("String", "WS_BASE", "\"${rcProp("RC_WS_BASE", "wss://relay.example.com")}\"")
        buildConfigField("String", "DEVICE_ID", "\"${rcProp("RC_DEVICE_ID", "")}\"")
        // Rỗng = phải do MDM đẩy xuống. Không bao giờ commit mật khẩu thật.
        buildConfigField("String", "MQTT_PASSWORD", "\"${rcProp("RC_MQTT_PASSWORD", "")}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Một keystore, vĩnh viễn. Mất nó = 10.000 máy phải cắm cáp lại.
        if (fleetKs.exists()) {
            create("fleet") {
                storeFile = fleetKs
                storeType = "pkcs12"
                storePassword = ksProp("FLEET_STORE_PASSWORD")
                keyAlias = ksProp("FLEET_KEY_ALIAS", "fleet")
                keyPassword = ksProp("FLEET_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "true")
        }
        release {
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "false")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("fleet")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true; aidl = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.eclipse.paho:org.eclipse.paho.mqttv5.client:1.2.5")
    implementation("org.slf4j:slf4j-nop:2.0.16")
    implementation("io.getstream:stream-webrtc-android:1.3.10")
    // Knox: compileOnly — không đóng gói vào APK; class có thể không tồn tại lúc runtime
    if (knoxJar.exists()) compileOnly(files(knoxJar))
    testImplementation("junit:junit:4.13.2")
}

gradle.taskGraph.whenReady {
    val releasing = gradle.taskGraph.allTasks.any { it.name.contains("Release") }
    if (releasing && !fleetKs.exists()) {
        throw GradleException("keystore/fleet.jks required for release — refuse debug-signed fleet APK")
    }
}
