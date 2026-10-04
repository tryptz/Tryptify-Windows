import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.ksp)
}

group = "tf.monochrome"
version = "1.9.3"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // The Android app was written against these; the warnings are noise here.
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xcontext-parameters",
        )
    }
}

// Native libraries: the CMake build drops its DLLs / .so files here and the
// Compose packaging plugin ships whatever is under resources/<os>-<arch> next
// to the app, reachable at runtime through compose.application.resources.dir.
val nativeResources = layout.projectDirectory.dir("resources")

dependencies {
    // Compose Multiplatform for the JVM. currentOs picks the Skiko natives for
    // the machine building (Linux here, Windows on the release runner); every
    // other artifact is pinned in the catalog because the plugin aliases are
    // deprecated since Compose Multiplatform 1.10.
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.animation.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window.size)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)
    implementation(libs.compose.ui.backhandler)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.components.resources)
    implementation(libs.material.icons.core)
    implementation(libs.material.icons.extended)
    implementation(libs.androidx.collection)
    implementation(libs.androidx.annotation)

    // Lifecycle, navigation
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.savedstate)
    implementation(libs.navigation.compose)

    // Kotlin
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)

    // Persistence: Room and DataStore on their JVM artifacts
    implementation(libs.room.runtime)
    implementation(libs.room.paging)
    implementation(libs.sqlite.bundled)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences.core)
    implementation(libs.paging.common)
    implementation(libs.paging.compose)

    // Network
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.websockets)

    // Images, glass
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.haze)
    implementation(libs.haze.materials)

    // Supabase
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)

    // Dependency injection
    implementation(libs.dagger)
    implementation(libs.javax.inject)
    ksp(libs.dagger.compiler)

    // Album-art colours and Windows secret storage
    implementation(libs.kmpalette.androidx.palette)
    implementation(libs.jna)
    implementation(libs.jna.platform)

    // Audio: tags, and FFmpeg through JavaCV for decoding
    implementation(libs.jaudiotagger)
    implementation(libs.javacpp)
    implementation(libs.ffmpeg)
    for (platform in listOf("windows-x86_64", "linux-x86_64")) {
        implementation(variantOf(libs.javacpp) { classifier(platform) })
        implementation(variantOf(libs.ffmpeg) { classifier(platform) })
    }

    // Visualizer: projectM renders into an OpenGL 3.3 core context on a hidden
    // GLFW window, read back into a Compose ImageBitmap (visualizer/gl/).
    implementation(libs.lwjgl)
    implementation(libs.lwjgl.glfw)
    implementation(libs.lwjgl.opengl)
    for (platform in listOf("natives-windows", "natives-linux")) {
        runtimeOnly(variantOf(libs.lwjgl) { classifier(platform) })
        runtimeOnly(variantOf(libs.lwjgl.glfw) { classifier(platform) })
        runtimeOnly(variantOf(libs.lwjgl.opengl) { classifier(platform) })
    }

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

// Packs the ~9.8k raw .milk presets in src/main/projectm-assets/presets into a
// single assets/projectm/presets.zip on the classpath, where the AssetManager
// shim opens it for ProjectMAssetInstaller. The Android app's task, unchanged
// but for where the archive lands: there an asset root, here a resources root,
// so the archive carries the assets/ prefix itself. One archive instead of
// ~10k loose resources is what lets the first-run install extract everything
// in a single ZipInputStream pass; never put the presets under
// src/main/resources, where each would be copied into the jar on its own.
@CacheableTask
abstract class PackProjectMPresetsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val presetDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun pack() {
        val root = presetDir.get().asFile
        val outFile = outputDir.get().asFile.resolve("assets/projectm/presets.zip")
        outFile.parentFile.mkdirs()
        ZipOutputStream(outFile.outputStream().buffered()).use { zip ->
            root.walkTopDown()
                .filter { it.isFile }
                // Lexicographic order keeps the archive reproducible and matches
                // the ordering the runtime catalog generator relies on for
                // stable preset ids.
                .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
                .forEach { file ->
                    val entry = ZipEntry(file.relativeTo(root).invariantSeparatorsPath)
                    entry.time = 0L
                    zip.putNextEntry(entry)
                    file.inputStream().use { it.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                }
        }
    }
}

val packProjectMPresets = tasks.register<PackProjectMPresetsTask>("packProjectMPresets") {
    presetDir.set(layout.projectDirectory.dir("src/main/projectm-assets/presets"))
    outputDir.set(layout.buildDirectory.dir("generated/projectm-resources"))
}
sourceSets["main"].resources.srcDir(packProjectMPresets.flatMap { it.outputDir })

compose.resources {
    publicResClass = false
    packageOfResClass = "tf.monochrome.desktop.res"
    generateResClass = always
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

compose.desktop {
    application {
        mainClass = "tf.monochrome.desktop.MainKt"
        jvmArgs += listOf("-Xss4m", "-Dfile.encoding=UTF-8")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Tryptify"
            packageVersion = project.version.toString()
            description = "Tryptify: a hi-fi music player with a DSP console, headphone AutoEQ and bit-perfect output."
            vendor = "tryptz"
            copyright = "tryptz"
            appResourcesRootDir.set(nativeResources)
            modules("java.sql", "java.naming", "java.management", "jdk.unsupported", "jdk.crypto.ec", "java.net.http", "jdk.zipfs")

            windows {
                menuGroup = "Tryptify"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Fixed for the life of the product so an MSI upgrades the previous install.
                upgradeUuid = "4d2f3a6e-5b1c-4c6b-9c7e-7f0e2a1b9d31"
                iconFile.set(project.file("packaging/tryptify.ico"))
            }
            linux {
                iconFile.set(project.file("packaging/tryptify.png"))
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnit()
    // The native libraries for in-process tests (the DSP engine, the stretch
    // shifter) come from the CMake build tree when it exists.
    systemProperty("tryptify.native.dir", nativeResources.dir("linux-x64").asFile.absolutePath)
    maxHeapSize = "2g"
}
