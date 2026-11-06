import dev.nucleusframework.desktop.application.dsl.PublishMode
import dev.nucleusframework.desktop.application.dsl.ReleaseChannel
import dev.nucleusframework.desktop.application.dsl.ReleaseType
import dev.nucleusframework.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
    alias(libs.plugins.nucleus)
}

kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(compose.material3)
            implementation(libs.navigation3.ui)
            implementation(libs.adaptive.navigation3)
            implementation(libs.lifecycle.viewmodel.navigation3)
            implementation(libs.room3.runtime)
            implementation(libs.sqlite.bundled)
            implementation(libs.koin.core)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.koin.compose.viewmodel.navigation)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.coroutines.core)
            implementation(libs.coroutines.swing)
            implementation(libs.serialization.json)
            implementation(libs.reorderable)
            implementation(libs.jewel.decorated.window)
            implementation(libs.nucleus.notification)
            implementation(libs.nucleus.updater)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.koin.test)
            implementation(libs.ktor.server.test.host)
            implementation(libs.coroutines.test)
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
            implementation(compose.desktop.currentOs)
        }
    }
}

dependencies {
    add("kspJvm", libs.room3.compiler)
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

// Jewel's icon libraries pull in IntelliJ's coroutines fork: same classes, missing newer methods. If both jars ship,
// the fork can win on the classpath and crash on cancel. Substitute the real coroutines at the catalog version.
configurations.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core-jvm"))
            .using(module("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:${libs.versions.coroutines.get()}"))
    }
}

// Run / packaging tasks (run, runDistributable, package*, create*Distributable, *RuntimeImage, ...), matched on the
// requested task names with any ":project:" prefix dropped. Only these need the JBR 25 runtime.
val needsAppRuntime: Boolean = gradle.startParameter.taskNames.any { requested ->
    val name = requested.substringAfterLast(':')
    listOf("run", "package", "Distributable", "RuntimeImage", "suggestRuntimeModules", "prepareAppResources")
        .any { name.contains(it, ignoreCase = true) }
}

// Release CI passes -PappVersion=<tag without the leading v>; local builds stay 1.0.0. The updater compares this
// version with the one in the release's latest-*.yml, so every tag must be a higher semver than the last.
val appVersion: String = providers.gradleProperty("appVersion").getOrElse("1.0.0")

// Nucleus 2.5.18 writes package.json beside each format's output only when it is missing, and electron-builder takes
// the artifact and latest-*.yml version from it. A leftover file from an earlier build would label a new build with
// the old version, so drop it before every package task.
tasks.matching { it.name.startsWith("package") }.configureEach {
    // A local val, not a script property: the configuration cache can't serialize a reference to the script.
    val binaries = layout.buildDirectory.dir("compose/binaries")
    doFirst {
        binaries.get().asFile.walkTopDown().filter { it.name == "package.json" }.forEach { it.delete() }
    }
}

// Packaging is Nucleus (the compose.desktop DSL plus electron-builder formats and the update metadata the in-app
// updater reads). The Compose plugin stays for its dependencies and compiler wiring.
nucleus.application {
    mainClass = "com.programmersbox.cdm.MainKt"
    // The custom title bar (Jewel DecoratedWindow) needs JetBrains Runtime, and Jewel 0.41 is Java 25 bytecode,
    // so the app runs on JBR 25 (auto-provisioned via foojay). Compilation and tests stay on the default JDK.
    // javaHome is a plain String, so resolve the toolchain only when a run or packaging task was requested.
    if (needsAppRuntime) {
        javaHome = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
            vendor.set(JvmVendorSpec.JETBRAINS)
        }.get().metadata.installationPath.asFile.absolutePath
    }
    // JDK 25 warns when JNI libraries (skiko, sqlite-bundled) load without this.
    jvmArgs += "--enable-native-access=ALL-UNNAMED"
    // Name the macOS Dock/app menu entry instead of "MainKt". -Xdock is a macOS-only JVM flag (HotSpot elsewhere
    // refuses to start on an unknown -X option), and each OS packages its own build, so add it only on macOS.
    if (System.getProperty("os.name").startsWith("Mac")) {
        jvmArgs += listOf("-Xdock:name=Claude Device Manager", "-Dapple.awt.application.name=Claude Device Manager")
    }
    nativeDistributions {
        // Each OS builds only its own formats. macOS needs the ZIP next to the DMG: the DMG is the first install,
        // the updater swaps the .app from the ZIP. Windows updates through the NSIS installer, Linux through the
        // DEB (pkexec prompt) or the AppImage (replaced in place).
        // TargetFormat.Zip packages for the host OS, so add it only on macOS: elsewhere it is an unused extra asset.
        targetFormats = buildSet {
            addAll(listOf(TargetFormat.Dmg, TargetFormat.Nsis, TargetFormat.Deb, TargetFormat.AppImage))
            if (System.getProperty("os.name").startsWith("Mac")) add(TargetFormat.Zip)
        }
        packageName = "Claude Device Manager"
        packageVersion = appVersion
        description = "Shares connected Android devices between Claude Code sessions."
        vendor = "programmersbox"
        homepage = "https://github.com/jakepurple13/ClaudeDeviceManager"
        // No spaces in release asset names: they become URLs in latest-*.yml.
        artifactName = "ClaudeDeviceManager-\${version}-\${os}-\${arch}.\${ext}"
        macOS {
            bundleID = "com.programmersbox.cdm"
            iconFile.set(project.file("icons/app-icon.icns"))
        }
        linux {
            packageName = "claude-device-manager"
            debMaintainer = "jakepurple13 <jakepurple13@users.noreply.github.com>"
            iconFile.set(project.file("icons/app-icon.png"))
        }
        publish {
            // Only writes the GitHub provider into the packaged update config; CI creates the release and uploads.
            publishMode = PublishMode.Never
            github {
                enabled = true
                owner = "jakepurple13"
                repo = "ClaudeDeviceManager"
                channel = ReleaseChannel.Latest
                releaseType = ReleaseType.Release
            }
        }
    }
}
