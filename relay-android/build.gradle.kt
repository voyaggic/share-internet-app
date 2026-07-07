plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
}

android {
    namespace 'app.relay'
    compileSdk 34

    defaultConfig {
        applicationId "app.relay"
        minSdk 26              // Android 8.0+ (covers ~95% of active Android devices)
        targetSdk 34
        versionCode 1
        versionName "1.0.0"

        // Your deployed backend URL — change this before building
        buildConfigField "String", "API_BASE_URL", '"https://your-railway-app.railway.app"'
    }

    buildTypes {
        debug {
            // For local testing, point to localhost via Android emulator IP
            buildConfigField "String", "API_BASE_URL", '"http://10.0.2.2:3000"'
        }
        release {
            minifyEnabled true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
            buildConfigField "String", "API_BASE_URL", '"https://your-railway-app.railway.app"'
        }
    }

    buildFeatures {
        viewBinding true
        buildConfig true
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = '17'
    }
}

dependencies {
    // AndroidX core
    implementation 'androidx.core:core-ktx:1.12.0'
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'androidx.activity:activity-ktx:1.8.0'
    implementation 'androidx.lifecycle:lifecycle-runtime-ktx:2.6.2'

    // UI
    implementation 'com.google.android.material:material:1.10.0'
    implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
    implementation 'androidx.recyclerview:recyclerview:1.3.2'

    // Coroutines (for background API calls)
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'

    // Slider (for the pricing duration selector)
    implementation 'com.google.android.material:material:1.10.0'
}
