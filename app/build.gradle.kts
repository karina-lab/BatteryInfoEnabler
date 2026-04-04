plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "org.klab.batteryinfo"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.klab.batteryinfo"
        minSdk = 34
        targetSdk = 36
        versionCode = project.property("versionCode").toString().toInt()
        versionName = project.property("versionName").toString()
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}
