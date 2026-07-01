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
        versionCode = 8
        versionName = "2.1.0"
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
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
