plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core"))
    implementation(libs.jserialcomm)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.register<JavaExec>("probe") {
    group = "application"
    description = "Lists serial ports, or with --args=\"<port> [seconds]\" streams from a sock. Manual hardware tool."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.smartsocks.transport.desktop.tools.SerialProbeKt"
}
