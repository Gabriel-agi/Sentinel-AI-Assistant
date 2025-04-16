plugins {
    id("com.android.application")
    id("kotlin-android")
}

android {
    namespace = "com.example.aisentinel" // Ensure this matches your project's namespace
    compileSdk = 33 // Keeping this at 33 as requested

    defaultConfig {
        applicationId = "com.example.aisentinel"
        minSdk = 21 // CameraX works with API 21+
        targetSdk = 33 // Keeping this at 33
        versionCode = 1
        versionName = "1.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true // Keep if you use ViewBinding
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {

    // --- Standard Android Libraries (Using versions likely compatible with SDK 33) ---
    implementation("androidx.core:core-ktx:1.9.0") // Downgraded from 1.12.0
    implementation("androidx.appcompat:appcompat:1.6.1") // Usually compatible
    implementation("com.google.android.material:material:1.9.0") // Usually compatible, could go to 1.8.0 if needed
    implementation("androidx.constraintlayout:constraintlayout:2.1.4") // Should be fine

    // --- CameraX Dependencies (Using older versions compatible with SDK 33) ---
    val cameraxVersion = "1.2.3" // Last stable version before SDK 34 requirement
    implementation("androidx.camera:camera-core:${cameraxVersion}")
    implementation("androidx.camera:camera-camera2:${cameraxVersion}")
    implementation("androidx.camera:camera-lifecycle:${cameraxVersion}")

    // --- Lifecycle Dependencies (Using versions likely compatible with SDK 33) ---
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1") // Slightly downgraded from 2.6.2 just in case

    // Explicitly adding Activity KTX version compatible with SDK 33
    // This might help resolve transitive dependencies correctly.
    implementation("androidx.activity:activity-ktx:1.7.2")


    // --- Other Dependencies (Add any others your project might need) ---
    // Required for JSON parsing in MainActivity
    implementation("org.json:json:20231013") // Or the version bundled with Android


    // --- Testing Dependencies (Optional) ---
    // testImplementation("junit:junit:4.13.2")
    // androidTestImplementation("androidx.test.ext:junit:1.1.5")
    // androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}