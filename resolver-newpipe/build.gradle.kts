plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    buildToolsVersion = "35.0.0"
    namespace = "de.rdoe.weeklydjshows.resolver.newpipe"
    compileSdk = 35
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    packaging.resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":resolver-api"))
    implementation(project(":show-discovery"))
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.4")
    // NewPipe's app-side PoToken helper uses nanojson directly. The extractor publishes it as a
    // runtime dependency, so declare the matching revision explicitly for this module's compiler.
    implementation("com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("io.reactivex.rxjava3:rxjava:3.1.10")
    implementation("io.reactivex.rxjava3:rxandroid:3.0.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.4")
}
