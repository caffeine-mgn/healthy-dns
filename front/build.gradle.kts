plugins {
    kotlin("multiplatform")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.compose.multiplatform)
}

group = rootProject.group
version = rootProject.version

repositories {
    mavenCentral()
    maven("https://maven.google.com")
}

kotlin {
    js {
        browser {
            binaries.executable()
        }
    }

    sourceSets {
        jsMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.compose.html.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.serialization.json)
        }
    }
}
