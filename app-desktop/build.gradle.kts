import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":app"))
    implementation(compose.desktop.currentOs)
    // Theme and surface for the screen renderer (tools/RenderScreens).
    implementation(libs.compose.material3)
    // Dispatchers.Main for Swing (Compose desktop's UI thread).
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "com.smartsocks.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "SmartSocks"
            packageVersion = providers.gradleProperty("smartsocks.version").get()
            description = "SmartSocks pressure insoles"
            windows {
                menu = true
                upgradeUuid = "6f1d4c2e-8a35-4b7e-9c1a-2d5e7f0b3a61"
            }
        }
    }
}

tasks.register<JavaExec>("renderScreens") {
    group = "application"
    description = "Renders the UI off-screen with simulated feet into build/screens/*.png. Development tool."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.smartsocks.desktop.tools.RenderScreensKt"
    args(layout.buildDirectory.dir("screens").get().asFile.absolutePath)
}

tasks.register<JavaExec>("playbackBench") {
    group = "application"
    description = "Writes a simulated 10-minute 200 Hz two-foot recording and measures opening it. Development tool."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.smartsocks.desktop.tools.PlaybackBenchKt"
    maxHeapSize = "1g"
}
