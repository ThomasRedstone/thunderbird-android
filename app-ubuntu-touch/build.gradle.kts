import java.security.MessageDigest
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

plugins {
    id(ThunderbirdPlugins.App.jvm)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose)
}

version = "0.1.30"

application {
    mainClass.set("net.thunderbird.app.ubuntutouch.MainKt")
}

dependencies {
    implementation(libs.apache.httpclient5)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.dbus.java.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    runtimeOnly(libs.dbus.java.transport.junixsocket)
    implementation(projects.backend.demo)
    implementation(projects.backend.api)
    implementation(projects.backend.imap)
    implementation(projects.legacy.logging)
    implementation(projects.mail.common)
    implementation(projects.core.common)
    implementation(projects.feature.account.api)
    implementation(projects.feature.mail.account.api)
    implementation(libs.tb.mobile.components.ui.bolt)
    testImplementation(libs.tb.mobile.components.ui.testing)
    testImplementation(libs.jetbrains.compose.ui.test)
    testImplementation(libs.greenmail)
    implementation(
        when (providers.gradleProperty("utTargetArch").orNull) {
            null -> compose.desktop.currentOs
            "arm64" -> "org.jetbrains.compose.desktop:desktop-jvm-linux-arm64:${libs.versions.jetbrainsCompose.get()}"
            else -> error("utTargetArch must be arm64 when set")
        },
    )
}

codeCoverage {
    branchCoverage = 0
    lineCoverage = 0
}

// Preserve runtime order and every original JAR (including embedded licence notices).
// A target JDK performs jpackage; this task only stages portable JVM bytecode.
tasks.register("stageUtJvmInput") {
    val classpath = configurations.named("runtimeClasspath")
    val appJar = tasks.named<Jar>("jar")
    dependsOn(appJar, classpath)
    inputs.files(appJar, classpath)
    doLast {
        check(providers.gradleProperty("utTargetArch").orNull == "arm64")
        val target = file(providers.gradleProperty("utJvmOutput").get())
        check(!target.exists()) { "Choose an absent utJvmOutput directory" }
        check(target.mkdirs())
        val artifacts = classpath.get().files.filter { it.extension == "jar" }
        val stagedNames = artifacts.map { source ->
            val hash = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
                .joinToString("") { "%02x".format(it) }
            val name = "$hash-${source.name}"
            if (!target.resolve(name).exists()) source.copyTo(target.resolve(name))
            name
        }
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.MAIN_CLASS] = application.mainClass.get()
            mainAttributes[Attributes.Name.CLASS_PATH] = stagedNames.joinToString(" ")
        }
        JarOutputStream(target.resolve("mail-demo.jar").outputStream(), manifest).use { output ->
            JarFile(appJar.get().archiveFile.get().asFile).use { input ->
                input.entries().asSequence().filter { it.name != "META-INF/MANIFEST.MF" }.forEach { entry ->
                    output.putNextEntry(JarEntry(entry.name).apply { time = 0 })
                    if (!entry.isDirectory) input.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                }
            }
        }
        target.resolve("RUNTIME-CLASSPATH.txt").writeText(stagedNames.joinToString("\n") + "\n")
        val coordinates = classpath.get().resolvedConfiguration.resolvedArtifacts
            .joinToString("\n") { "${it.moduleVersion.id} ${it.file.name}" }
        target.resolve("DEPENDENCIES.txt").writeText(coordinates + "\n")
    }
}

// KMP projects can publish distinct files called api-jvm.jar. Keep all of them in
// desktop distributions and use the same collision-free names in the launcher.
fun File.distributionName(): String {
    val hash = MessageDigest.getInstance("SHA-256").digest(readBytes())
        .joinToString("") { "%02x".format(it) }
    return "$hash-$name"
}

distributions {
    main {
        contents {
            eachFile {
                if (file.extension == "jar") name = file.distributionName()
            }
        }
    }
}

tasks.named<CreateStartScripts>("startScripts") {
    doFirst {
        classpath = files(classpath!!.files.map { file(it.distributionName()) })
    }
}

// Opt-in integration probe: run on a disposable session bus/keyring, or explicitly on the test device.
tasks.register<JavaExec>("secretServiceProbe") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("net.thunderbird.app.ubuntutouch.SecretServiceProbe")
    providers.gradleProperty("utSecretProbeMode").orNull?.let { args(it) }
}

// Explicit controlled host server for the opt-in enforcing-profile device protocol probe.
tasks.register<JavaExec>("phoneProtocolServer") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("net.thunderbird.app.ubuntutouch.PhoneProtocolServer")
    providers.gradleProperty("utProtocolHost").orNull?.let { args(it) }
    providers.gradleProperty("utProtocolOutput").orNull?.let { args(it) }
    providers.gradleProperty("utProtocolSecurity").orNull?.let { args(it) }
    providers.gradleProperty("utProtocolFlags").orNull?.let { args(it) }
    providers.gradleProperty("utProtocolReply").orNull?.let { args(it) }
    providers.gradleProperty("utProtocolSent").orNull?.let { args(it) }
}

// Opt-in actual window close regression: requires an isolated synthetic XDG_DATA_HOME and Xvfb.
tasks.register<JavaExec>("desktopDraftCloseProbe") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("net.thunderbird.app.ubuntutouch.DesktopDraftCloseProbe")
    jvmArgs("-Dskiko.renderApi=SOFTWARE")
    environment("XDG_SESSION_TYPE", "x11")
    environment.remove("WAYLAND_DISPLAY")
    environment.remove("DBUS_SESSION_BUS_ADDRESS")
}
