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
        versionCode = 300
        versionName = "3.0.0"
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
}


dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}
