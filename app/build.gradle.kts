import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

fun props(path: String): Properties? =
    rootProject.file(path).takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

// Both are optional and never committed: without them the build is the public "base" app, signed with the debug key.
val keystore = props("signing/keystore.properties")
val personal = props("personal.properties")

android {
    namespace = "app.echoes"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.echoes"
        minSdk = 30
        targetSdk = 36
        versionCode = 4
        versionName = "1.3"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = rootProject.file("signing/" + keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    }

    flavorDimensions += "edition"
    productFlavors {
        create("base") {
            dimension = "edition"
            manifestPlaceholders["appName"] = "Echoes"
            resValue("string", "app_name", "Echoes")
            buildConfigField("String", "UPDATE_REPO", "\"Kolossus03/echoes\"")
            buildConfigField("String", "MUSIC_DIR", "\"Echoes\"")
        }
        // A private edition installed next to the base one, with its own name, music folder and, in
        // src/personal/res, icon and optional startup intro (drawable intro_image, raw intro_sound).
        if (personal != null) create("personal") {
            dimension = "edition"
            applicationIdSuffix = ".personal"
            val name = personal.getProperty("name", "Echoes")
            manifestPlaceholders["appName"] = name
            resValue("string", "app_name", name)
            buildConfigField("String", "UPDATE_REPO", "\"\"")
            buildConfigField("String", "MUSIC_DIR", "\"${personal.getProperty("musicDir", "Echoes")}\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

    buildFeatures { compose = true; buildConfig = true }

    packaging {
        resources.excludes += listOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties", "META-INF/*.kotlin_module")
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.palette:palette-ktx:1.0.0")

    val media3 = "1.9.4"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")

    val room = "2.8.4"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    val ktor = "3.3.3"
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-cio:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-server-partial-content:$ktor")
    implementation("io.ktor:ktor-server-status-pages:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.10.2")
    implementation("org.slf4j:slf4j-nop:2.0.17")

    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    testImplementation("junit:junit:4.13.2")
}
