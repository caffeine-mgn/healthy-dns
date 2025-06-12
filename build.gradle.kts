plugins {
    kotlin("plugin.serialization") version "2.1.0"
    kotlin("multiplatform") version "2.1.0"
    application
}

val nativeEntryPoint = "pw.binom.main"

kotlin {
    linuxArm64 {
        binaries {
            executable {
                entryPoint = nativeEntryPoint
            }
        }
    }
    linuxX64 {
        binaries {
            executable {
                entryPoint = nativeEntryPoint
            }
            compilerOptions {
                freeCompilerArgs.addAll("-Xallocator=std")
            }
        }
    }
    mingwX64 {
        binaries {
            executable {
                entryPoint = nativeEntryPoint
            }
        }
    }
    jvm {
        withJava()
        mainRun {
            mainClass = "pw.binom.MainKt"
        }
    }
    sourceSets {
        commonMain.dependencies {
            api("pw.binom.io:network:1.0.0-SNAPSHOT")
            api("com.charleskorn.kaml:kaml:0.66.0")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            api("pw.binom.io:httpClient:1.0.0-SNAPSHOT")
            api("pw.binom.io:strong:1.0.0-SNAPSHOT")
            api("pw.binom.io:signal:1.0.0-SNAPSHOT")
            api("pw.binom.io:file:1.0.0-SNAPSHOT")
            api("pw.binom.io:dns:1.0.0-SNAPSHOT")
            api("pw.binom.io:strong-properties-yaml:1.0.0-SNAPSHOT")
            api("pw.binom.io:strong-properties-ini:1.0.0-SNAPSHOT")
        }
        commonTest.dependencies {
            api(kotlin("test-common"))
            api(kotlin("test-annotations-common"))
            api("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
        }
        jvmTest.dependencies {
            api(kotlin("test-junit"))
        }
    }
}
repositories {
    mavenLocal()
    maven(url = "https://repo.binom.pw")
    mavenCentral()
}
