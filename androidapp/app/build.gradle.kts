plugins {
    id("com.android.application")
}

android {
    namespace = "edu.dlsu.esca_3_2526_c3"
    compileSdk = 36

    defaultConfig {
        applicationId = "edu.dlsu.esca_3_2526_c3"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "2.0-dms-pipeline"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core:1.17.0")
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-database")
    implementation("com.google.firebase:firebase-messaging")

    // Detection pipeline (see README.md): MediaPipe Face Landmarker + YOLO (.tflite).
    // Versions with native libraries aligned to 16 KB pages (Android 15+ devices, Google Play):
    //  - MediaPipe 0.10.26: its release notes state 16 KB page size support for the Android packages
    //  - LiteRT = TensorFlow Lite under its new name; same org.tensorflow.lite classes, so no code change
    //  - DataStore (used inside Firebase): raised to the latest stable for libdatastore_shared_counter.so
    // If Gradle cannot find a version, use the newest one listed at https://maven.google.com.
    // Check: Build > Analyze APK > lib/arm64-v8a (Android Studio flags libraries that are not 16 KB aligned).
    implementation("com.google.mediapipe:tasks-vision:0.10.26")
    implementation("com.google.ai.edge.litert:litert:1.4.0")
    implementation("androidx.datastore:datastore-core:1.3.0")

    // Unit tests (app/src/test): decision logic identical to the desktop Python code.
    // The real org.json replaces Android's stub, which does nothing in local unit tests.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// Allows UI-only testing before google-services.json exists.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}
