plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

group = "com.yuno"
version = "1.1.1"

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

compose.desktop {
    application {
        mainClass = "com.yuno.desktop.MainKt"
        nativeDistributions {
            packageName = "Yuno"
            packageVersion = "1.1.1"
            vendor = "Yuno"
            description = "Yuno desktop client"
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Rpm,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
            )
            linux {
                appCategory = "AudioVideo"
                debMaintainer = "Yuno"
            }
            windows {
                menuGroup = "Yuno"
                upgradeUuid = "4e5e18d2-48b5-4b4e-a7f1-1b4cbecf5c12"
                perUserInstall = true
            }
        }
    }
}
