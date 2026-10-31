plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val nativeAbis = listOf("arm64-v8a", "x86_64").filter {
    file("src/main/jniLibs/$it/libmain.so").isFile
}

android {
    namespace = "dev.horizon.android"
    compileSdk = 36
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "dev.horizon.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"
        ndk { abiFilters += nativeAbis.ifEmpty { listOf("arm64-v8a") } }
        buildConfigField("String", "ENGINE_COMMIT", "\"073afd484e8667f597790eff62321c35f07e7ba7\"")
        buildConfigField("boolean", "NATIVE_BUNDLED", nativeAbis.isNotEmpty().toString())
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint { abortOnError = true; checkReleaseBuilds = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Must match the Java ABI used by android-activity 0.6.1 / Winit 0.30.13.
    implementation("androidx.games:games-activity:4.4.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

val verifyNative by tasks.registering {
    doLast {
        check(nativeAbis.isNotEmpty()) {
            "Missing Horizon runtime. Run scripts/build-native.sh first. " +
                "Source/UI-only checks may use -PallowMissingNative=true."
        }
    }
}
tasks.matching {
    (it.name.startsWith("package") || it.name.startsWith("bundle")) &&
        (it.name.endsWith("Debug") || it.name.endsWith("Release"))
}
    .configureEach {
        if (providers.gradleProperty("allowMissingNative").orNull != "true") dependsOn(verifyNative)
    }
