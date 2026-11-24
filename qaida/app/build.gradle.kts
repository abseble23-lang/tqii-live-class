plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tqii.qaida"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tqii.qaida"
        minSdk = 23
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("VERSION_CODE") ?: "1")
    }

    signingConfigs {
        create("release") {
            val ks = System.getenv("KEYSTORE_FILE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (System.getenv("KEYSTORE_FILE") != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // the Qaida web book is copied in from /web-qaida at build time
    sourceSets["main"].assets.srcDirs("src/main/assets", "build/qaidaAssets")
}

val copyQaidaWeb by tasks.registering(Copy::class) {
    from(rootProject.file("../web-qaida")) { exclude("sw.js") }
    into(layout.buildDirectory.dir("qaidaAssets/web"))
}
tasks.named("preBuild") { dependsOn(copyQaidaWeb) }
