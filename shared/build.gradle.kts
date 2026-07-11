plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

group = rootProject.group
version = rootProject.version

repositories {
    mavenCentral()
    maven("https://maven.google.com")
}

kotlin {
    val nativeEntryPoint = "pw.binom.main"
    linuxArm64()
    linuxX64()
    mingwX64()
    jvm()
    js {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.serialization.json)
        }
    }
}
