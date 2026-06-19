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
        versionCode = 7
        versionName = "2.0.0"
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
}

dependencies {
    compileOnly("io.github.libxposed:api:101.0.1")
}
