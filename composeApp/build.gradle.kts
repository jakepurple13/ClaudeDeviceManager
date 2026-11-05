import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
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

compose.desktop {
    application {
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
        // Unpackaged runs: name the macOS Dock/app menu entry instead of "MainKt".
        jvmArgs += listOf("-Xdock:name=Claude Device Manager", "-Dapple.awt.application.name=Claude Device Manager")
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "Claude Device Manager"
            packageVersion = "1.0.0"
            macOS { iconFile.set(project.file("icons/app-icon.icns")) }
        }
    }
}
