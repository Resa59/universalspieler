plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val updateManifestUrl = providers.gradleProperty("weeklyDjShowsUpdateManifestUrl").orElse("").get()
val updateManifestLiteral = "\"" + updateManifestUrl
    .replace("\\", "\\\\")
    .replace("\"", "\\\"") + "\""

android {
    namespace = "de.rdoe.weeklydjshows"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "de.rdoe.weeklydjshows"
        minSdk = 23
        targetSdk = 35
        versionCode = 19
        versionName = "1.3.1"
        buildConfigField("String", "UPDATE_MANIFEST_URL", updateManifestLiteral)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    packaging.resources.excludes += setOf(
        "META-INF/DEPENDENCIES",
        "META-INF/LICENSE*",
        "META-INF/NOTICE*",
        "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
    )
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":data-database"))
    implementation(project(":data-feeds"))
    implementation(project(":playback"))
    implementation(project(":resolver-api"))
    implementation(project(":resolver-newpipe"))
    implementation(project(":show-discovery"))
    implementation(project(":ui-components"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.runtime:runtime-saveable")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.media3:media3-session:1.9.2")
    implementation("androidx.media3:media3-cast:1.9.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.4")
}
