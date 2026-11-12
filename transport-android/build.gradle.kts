plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "com.smartsocks.transport.android"
        compileSdk = 37
        minSdk = 26
        // Unit tests on the host JVM (source set androidHostTest); the transport logic is tested with a fake socket.
        withHostTest {}
    }

    sourceSets {
        androidMain.dependencies {
            api(project(":core"))
        }
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
