plugins {
    id("com.android.application")
}

android {
    namespace = "dev.stone.photorotator"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.stone.photorotator"
        minSdk = 30
        targetSdk = 35
        versionCode = 2
        versionName = "0.9.0"
        testInstrumentationRunner = "dev.stone.photorotator.ExifRotationInstrumentation"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}

configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
}
