plugins {
    id("com.android.application")
}

android {
    namespace = "dev.xyenon.mxgram"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.xyenon.mxgram"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "2.2.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("org.luckypray:dexkit:2.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}
