plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.autosentry.app"
    compileSdk = 35

    val releaseStoreFile = providers.environmentVariable("AUTOSENTRY_RELEASE_STORE_FILE").orNull
    val releaseStorePassword = providers.environmentVariable("AUTOSENTRY_RELEASE_STORE_PASSWORD").orNull
    val releaseKeyAlias = providers.environmentVariable("AUTOSENTRY_RELEASE_KEY_ALIAS").orNull
    val releaseKeyPassword = providers.environmentVariable("AUTOSENTRY_RELEASE_KEY_PASSWORD").orNull
    val hasReleaseKey = releaseStoreFile != null && releaseStorePassword != null &&
            releaseKeyAlias != null && releaseKeyPassword != null

    defaultConfig {
        applicationId = "com.autosentry.app"
        minSdk = 24
        targetSdk = 35
        // GitHub Actions run number: every CI APK has its own build number (shown in the
        // title bar) and installs over the previous one. Local builds are build 1.
        versionCode = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull() ?: 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            // Android only updates an app in place, keeping its data, when the new APK has the
            // same package name and signing key. A release built without the persistent key gets
            // its own package name, so it can never collide with (and force uninstalling) the
            // real install on the truck tablet.
            if (!hasReleaseKey) {
                applicationIdSuffix = ".local"
                versionNameSuffix = "-local"
            }
            signingConfig = signingConfigs.create("stableRelease") {
                if (hasReleaseKey) {
                    storeFile = file(releaseStoreFile)
                    storePassword = releaseStorePassword
                    keyAlias = releaseKeyAlias
                    keyPassword = releaseKeyPassword
                } else {
                    initWith(signingConfigs.getByName("debug"))
                }
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            // Debug builds are signed with each machine's own debug key, so they install as a
            // separate "AutoSentry Debug" app instead of conflicting with the real one.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = false
        buildConfig = true
    }
}

kapt {
    arguments {
        // Room writes each database version's schema to app/schemas. CI fails if a released
        // version's schema changes without a version bump and Migration: that would crash
        // every existing install on update, and reinstalling to recover wipes the data.
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.room:room-runtime:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    testImplementation("junit:junit:4.13.2")
}
