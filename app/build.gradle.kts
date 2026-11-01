plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("com.google.gms.google-services") }
android {
    namespace = "com.ridepack.app"
    compileSdk = 34
    defaultConfig { applicationId = "com.ridepack.app"; minSdk = 26; targetSdk = 34; versionCode = 55; versionName = "3.13.1" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.maplibre.gl:android-sdk:11.5.2")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(platform("com.google.firebase:firebase-bom:33.1.0"))
    implementation("com.google.firebase:firebase-database-ktx")
    implementation("com.google.firebase:firebase-auth-ktx")
    testImplementation("junit:junit:4.13.2")
}
