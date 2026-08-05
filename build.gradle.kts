plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlinx.serialization)
}


kotlin {
    val nativeEntryPoint = "pw.binom.main"
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
//            compilerOptions {
//                freeCompilerArgs.addAll("-Xallocator=std")
//            }
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
        mainRun {
            mainClass = "pw.binom.MainKt"
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":shared"))
            api(libs.kotlinx.coroutines.core)
            api(libs.binom.dns)
            api(libs.ktor.network)
            api(libs.ktor.client.cio)
            api(libs.ktor.server.cio)
            api(libs.ktor.server.content.negotiation)
            api(libs.ktor.serialization.kotlinx.json)
            api(libs.ktor.server.html.builder)
            api(libs.serialization.yaml)
            api(libs.serialization.json)
            api(libs.kotlin.logging)
            api(libs.mcp.sdk.server)
        }
        jvmMain {
            dependencies {
            }
        }
        commonTest.dependencies {
            api(kotlin("test-common"))
            api(kotlin("test-annotations-common"))
            api(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            api(kotlin("test-junit"))
        }
    }
}

repositories {
//    maven {
//        this.url = uri("https://central.sonatype.com/repository/maven-snapshots/")
//    }
    mavenLocal()
//    maven(url = "https://repo.binom.pw")
    mavenCentral()
}
