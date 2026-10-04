plugins {
    id("com.android.application")
}

val gitCommit = providers.exec {
    workingDir(rootProject.projectDir)
    commandLine("git", "rev-parse", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().take(7).ifEmpty { "unknown" }

val gitDirty = providers.exec {
    workingDir(rootProject.projectDir)
    commandLine("git", "status", "--porcelain")
    isIgnoreExitValue = true
}.standardOutput.asText.get().isNotBlank()

android {
    namespace = "dev.stone.photorotator"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.stone.photorotator"
        minSdk = 30
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.0"
        testInstrumentationRunner = "dev.stone.photorotator.ExifRotationInstrumentation"
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("boolean", "GIT_DIRTY", gitDirty.toString())
        }
        getByName("release") {
            buildConfigField("boolean", "GIT_DIRTY", "false")
        }
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
