import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

// Music for the Pixel Watch (Wear OS). Music fork only; see MUSIC.md, "Watch".
// Same applicationId and signing key as :androidApp on purpose: the Wear OS Data Layer only
// connects a watch app to the phone app with the same package and certificate.
android {
    namespace = "com.xcantech.music.wear"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.xcantech.music"
        minSdk = 30
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 18001
        versionName = "0.17.0-music.1-watch"
    }

    signingConfigs {
        create("selfSigned") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        // Same name as :androidApp's, so `./gradlew assembleSelfSignedRelease` builds both with one key.
        create("selfSignedRelease") {
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("selfSigned")
        }
    }

    buildFeatures { compose = true }

    // The phone <-> watch wire is one source compiled into both apps.
    sourceSets["main"].kotlin.srcDir("../wire/src")
    sourceSets["test"].kotlin.srcDir("../wire/test")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildToolsVersion = "36.0.0"
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.androidx.activity.compose)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.complications.data.source)
    implementation(libs.play.services.wearable)
    implementation(libs.guava.android)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
