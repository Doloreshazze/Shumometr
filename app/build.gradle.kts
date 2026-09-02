plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.playeverywhere.noiselog"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.playeverywhere.graphmetr"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.3.0"

        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }
}

dependencies {
    implementation("com.bihe0832.android:lib-sherpa-onnx:8.6.6") {
        exclude(group = "com.bihe0832.android", module = "lib-audio")
    }
    implementation("com.bihe0832.android:lib-onnx:8.5.4")
    testImplementation("junit:junit:4.13.2")
}
