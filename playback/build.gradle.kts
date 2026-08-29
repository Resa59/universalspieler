plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.rdoe.weeklydjshows.playback"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":data-database"))
    implementation(project(":resolver-api"))
    implementation(project(":resolver-direct"))
    implementation(project(":resolver-newpipe"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.media3:media3-common:1.9.2")
    implementation("androidx.media3:media3-exoplayer:1.9.2")
    implementation("androidx.media3:media3-exoplayer-hls:1.9.2")
    implementation("androidx.media3:media3-exoplayer-dash:1.9.2")
    implementation("androidx.media3:media3-datasource-okhttp:1.9.2")
    implementation("androidx.media3:media3-session:1.9.2")
    implementation("androidx.media3:media3-cast:1.9.2")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.4")
}
