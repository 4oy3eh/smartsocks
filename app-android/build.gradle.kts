plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin): no org.jetbrains.kotlin.android here.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.smartsocks.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.smartsocks.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = providers.gradleProperty("smartsocks.version").get()
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":app"))
    implementation(project(":transport-android"))
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
}
