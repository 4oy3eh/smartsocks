plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.spotless)
}

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", "legacy/**", "tools/**")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**", "legacy/**", "tools/**")
        ktlint(libs.versions.ktlint.get())
    }
}
