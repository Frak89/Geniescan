plugins {
    id("com.android.application")
}
android {
    namespace = "com.frak89.geniescan"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.frak89.geniescan"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation("com.google.ar:core:1.56.0")
}
