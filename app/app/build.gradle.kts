import com.android.build.gradle.internal.cxx.configure.gradleLocalProperties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

val localProps = gradleLocalProperties(rootDir, providers)
val mqttHost = localProps.getProperty("MQTT_HOST", "")
val mqttPort = localProps.getProperty("MQTT_PORT", "8883").toIntOrNull() ?: 8883
val mqttTopic = localProps.getProperty("MQTT_TOPIC", "pump")
val websocketUrl = localProps.getProperty("WEBSOCKET_URL", "")
val supabaseUrl = localProps.getProperty("SUPABASE_URL", "")
val supabaseKey = localProps.getProperty("SUPABASE_KEY", "")
val googleWebClientId = localProps.getProperty("GOOGLE_WEB_CLIENT_ID", "")
val releaseStorePassword = localProps.getProperty("RELEASE_STORE_PASSWORD", "")
val releaseKeyAlias = localProps.getProperty("RELEASE_KEY_ALIAS", "")
val releaseKeyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD", "")

android {
    namespace = "com.nndai.myhome"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nndai.myhome"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "MQTT_HOST", "\"$mqttHost\"")
        buildConfigField("int", "MQTT_PORT", mqttPort.toString())
        buildConfigField("String", "MQTT_TOPIC", "\"$mqttTopic\"")
        buildConfigField("String", "WEBSOCKET_URL", "\"$websocketUrl\"")
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_KEY", "\"$supabaseKey\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
    }

    signingConfigs {
        create("release") {
            val keystoreFile = file("../release-key.jks")
            if (keystoreFile.exists() && releaseStorePassword.isNotEmpty()) {
                storeFile = keystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Coroutines
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.core)

    // MQTT
    implementation(libs.paho.mqtt)

    // OkHttp
    implementation(libs.okhttp)

    // Supabase
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.ktor.client.android)

    // Google Sign-In & Credential Manager
    implementation(libs.credentials)
    implementation(libs.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.play.services.auth)

    // Test dependencies removed as requested
}
