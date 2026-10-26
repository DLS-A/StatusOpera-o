plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun gradlePropertyOrEmpty(name: String): String =
    providers.gradleProperty(name).orNull.orEmpty()
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

android {
    namespace = "com.grm.rotagrm"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.grm.rotagrm"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        manifestPlaceholders["appAuthRedirectScheme"] = "com.grm.rotagrm"
        buildConfigField("String", "API_BASE_URL", "\"${gradlePropertyOrEmpty("rotaApiBaseUrl")}\"")
        buildConfigField("String", "OIDC_ISSUER", "\"${gradlePropertyOrEmpty("rotaOidcIssuer")}\"")
        buildConfigField("String", "OIDC_CLIENT_ID", "\"${gradlePropertyOrEmpty("rotaOidcClientId")}\"")
        buildConfigField("String", "OIDC_AUDIENCE", "\"${gradlePropertyOrEmpty("rotaOidcAudience")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.datastore:datastore-preferences:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("net.openid:appauth:0.11.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
