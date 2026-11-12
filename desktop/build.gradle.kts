import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":shared:protocol"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20250107")
    implementation("uk.co.caprica:vlcj:4.12.1") { exclude(group = "net.java.dev.jna", module = "jna-jpms"); exclude(group = "net.java.dev.jna", module = "jna-platform-jpms") }
    implementation("net.java.dev.jna:jna-platform:5.18.1")
    testImplementation(kotlin("test-junit"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
compose.desktop {
    application {
        mainClass = "ru.dedseckms.music.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.Exe)
            packageName = "DedSec Music"
            packageVersion = "0.3.1"
            description = "Your library. Your rhythm."
            vendor = "Dedseckms"
            copyright = "Copyright 2026 Dedseckms"
            licenseFile.set(rootProject.file("LICENSE"))
            appResourcesRootDir.set(rootProject.layout.projectDirectory.dir(".tools/desktop-resources"))
            modules("java.sql", "java.net.http", "jdk.crypto.ec", "jdk.crypto.mscapi", "jdk.unsupported")
            windows { menuGroup = "DedSec Music"; shortcut = true; perUserInstall = true; iconFile.set(rootProject.file("branding/app-icon.ico")) }
        }
    }
}
tasks.register<JavaExec>("renderGallery") {
    dependsOn("classes")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("ru.dedseckms.music.desktop.MainKt")
    workingDir = rootProject.projectDir
    args("--render=" + rootProject.file("docs/assets").absolutePath)
    maxHeapSize = "1024m"
}
tasks.register<JavaExec>("audioSmoke") {
    dependsOn("classes")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("ru.dedseckms.music.desktop.MainKt")
    workingDir = rootProject.projectDir
    systemProperty("compose.application.resources.dir", rootProject.file(".tools/desktop-resources/windows").absolutePath)
    args("--audio-smoke")
}
