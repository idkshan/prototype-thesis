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
        versionCode = 1
        versionName = "1.0-firebase-demo"
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
}

// Allows UI-only testing before google-services.json exists.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}
