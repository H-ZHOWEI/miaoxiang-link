plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.updaterkit"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = false
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // FileProvider / ContextCompat
    api("androidx.core:core-ktx:1.13.1")
    // lifecycleScope + 判断 Activity 是否处于 STARTED
    api("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    api("androidx.activity:activity-ktx:1.9.3")
    // 网络
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
