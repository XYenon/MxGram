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
        versionCode = 6
        versionName = "1.0.5"
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
    compileOnly("io.github.libxposed:api:-100-ge2588ee-22@aar")
}
