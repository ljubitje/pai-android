import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) load(file.inputStream())
}

android {
    namespace = "kle.ljubitje.apai"
    compileSdk = 35

    defaultConfig {
        applicationId = "kle.ljubitje.apai"
        minSdk = 26
        targetSdk = 28 // Required: allows execute_no_trans for binaries in app data (like Termux)
        versionCode = 3
        versionName = "0.3.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file(keystoreProperties.getProperty("storeFile", ""))
            storePassword = keystoreProperties.getProperty("storePassword", "")
            keyAlias = keystoreProperties.getProperty("keyAlias", "")
            keyPassword = keystoreProperties.getProperty("keyPassword", "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        noCompress += "zip"
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }
}

// Pre-patch the Termux bootstrap at build time so runtime doesn't need to
// scan + rewrite 349 files in bin/ + etc/ on a cold Android filesystem.
// See scripts/prebuild-bootstrap.py and BootstrapInstaller.patchTermuxPaths.
val prebuildBootstrap = tasks.register<Exec>("prebuildBootstrap") {
    val script = rootProject.file("scripts/prebuild-bootstrap.py")
    val bootstrap = file("src/main/assets/bootstrap-aarch64.zip")
    val stamp = file("src/main/assets/bootstrap-aarch64.zip.patched-stamp")
    val pkg = android.defaultConfig.applicationId!!

    inputs.file(script)
    inputs.property("applicationId", pkg)
    outputs.file(stamp)

    commandLine("python3", script.absolutePath, "--package", pkg,
                "--zip", bootstrap.absolutePath)
}

// Wire into every variant's mergeAssets task so it runs before packaging.
androidComponents {
    onVariants { variant ->
        afterEvaluate {
            tasks.named("merge${variant.name.replaceFirstChar { it.uppercase() }}Assets")
                .configure { dependsOn(prebuildBootstrap) }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")

    implementation(project(":terminal-view"))
    implementation(project(":terminal-emulator"))
}
