import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The web UI (../web) is built with Vite and packed into the app as assets/web.
val webDir = rootProject.layout.projectDirectory.dir("web")
val webAssets = layout.buildDirectory.dir("generated/web-assets")

val npmInstall by tasks.registering(Exec::class) {
    description = "Installs the web UI's npm packages."
    workingDir = webDir.asFile
    commandLine("npm", "ci", "--no-audit", "--no-fund")
    inputs.file(webDir.file("package-lock.json"))
    outputs.dir(webDir.dir("node_modules"))
}

val buildWeb by tasks.registering(Exec::class) {
    description = "Builds the web UI (Three.js scene, panel and editor) with Vite."
    dependsOn(npmInstall)
    workingDir = webDir.asFile
    commandLine("npm", "run", "build")
    inputs.dir(webDir.dir("src"))
    inputs.dir(webDir.dir("public"))
    inputs.files(webDir.file("index.html"), webDir.file("vite.config.js"), webDir.file("package-lock.json"))
    outputs.dir(webDir.dir("dist"))
}

val copyWeb by tasks.registering(Sync::class) {
    description = "Puts the built web UI where the app's assets come from."
    dependsOn(buildWeb)
    from(webDir.dir("dist"))
    into(webAssets.map { it.dir("web") })
}

// Release signing: .signing/release.properties (storeFile, storePassword, keyAlias, keyPassword),
// or the same four as HOUSE_SIGNING_* environment variables (CI). Neither is in the repository.
val signing = Properties().apply {
    val file = rootProject.file(".signing/release.properties")
    if (file.exists()) file.inputStream().use { load(it) }
    System.getenv("HOUSE_SIGNING_STORE_FILE")?.let { put("storeFile", it) }
    System.getenv("HOUSE_SIGNING_STORE_PASSWORD")?.let { put("storePassword", it) }
    System.getenv("HOUSE_SIGNING_KEY_ALIAS")?.let { put("keyAlias", it) }
    System.getenv("HOUSE_SIGNING_KEY_PASSWORD")?.let { put("keyPassword", it) }
}

android {
    namespace = "dev.housecontrol.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.housecontrol.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (signing.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    sourceSets["main"].assets.srcDir(webAssets)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module")
    }

    testOptions.unitTests.isReturnDefaultValues = true
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

tasks.named("preBuild") { dependsOn(copyWeb) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-process:2.9.2")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // TLS 1.2 with a pre-shared key, for the encrypted link to House Control on a Mac.
    implementation("org.bouncycastle:bctls-jdk18on:1.81")
    // QR codes: drawing the Smart Life sign-in code, scanning the Mac's pairing code.
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") { isTransitive = false }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
