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
    // Android only installs an update over an app signed with the same key. Every APK built
    // by CI is signed with this one persistent key, which lives only in CI secrets, so each
    // build updates the one before it. Debug builds are also a separate app (see below).
    // See docs/UPDATES.md.
    val hasPersistentKey = !releaseStoreFile.isNullOrBlank() && !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()

    defaultConfig {
        applicationId = "com.autosentry.app"
        minSdk = 24
        targetSdk = 35
        // The build number. Android refuses to install a lower number over a higher one, so it
        // must only ever go up: CI uses the GitHub Actions run number (the same number as in the
        // release tag and the app's Info dialog), and the workflow fails a release whose number
        // is not above the last release's. AUTOSENTRY_VERSION_CODE overrides it for a local
        // build that has to install over a CI build. Plain local builds are build 1.
        versionCode = providers.environmentVariable("AUTOSENTRY_VERSION_CODE").orNull?.toIntOrNull()
            ?: providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull()
            ?: 1
        versionName = "0.2.0"
        // Shown in the Info dialog so a test-key build is never mistaken for an updatable one.
        buildConfigField("boolean", "PERSISTENT_KEY", hasPersistentKey.toString())
    }

    signingConfigs {
        if (hasPersistentKey) {
            create("persistent") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            // A release built without the persistent key gets its own package name and the
            // debug key, so it installs next to the real app and can never collide with (and
            // force uninstalling) the install on the truck tablet.
            if (!hasPersistentKey) {
                applicationIdSuffix = ".local"
                versionNameSuffix = "-local"
            }
            signingConfig = signingConfigs.getByName(if (hasPersistentKey) "persistent" else "debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            // Debug builds (pull-request CI, Android Studio) install as a separate
            // "AutoSentry Debug" app with their own data, so trying one never touches the real
            // install. With the key present (CI) they are signed with it, so each debug build
            // updates the last debug build in place. Without it (a developer's PC, a fork's
            // pull request) they get that machine's own debug key.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            if (hasPersistentKey) signingConfig = signingConfigs.getByName("persistent")
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
    testOptions {
        unitTests.isIncludeAndroidResources = true // Robolectric (the database migration test)
    }
}

kapt {
    arguments {
        // Room writes the database layout here on every build; the files are committed, and CI
        // fails when the layout changes without a new version (which needs a Migration).
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
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
