import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val publicConfig = Properties().apply {
    val configFile = rootProject.file("rota-public.properties")
    if (configFile.exists()) configFile.inputStream().use { load(it) }
}

fun publicValue(name: String): String = providers.gradleProperty(name).orNull
    ?: publicConfig.getProperty(name, "")

fun gradlePropertyOrEmpty(name: String): String =
    publicValue(name)
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

val fcmEnabled = publicValue("rotaFcmEnabled").equals("true", ignoreCase = true)
if (fcmEnabled) {
    check(file("google-services.json").exists()) { "Firebase ativado sem google-services.json do projeto real." }
    apply(plugin = "com.google.gms.google-services")
}

val signingFile = System.getenv("ROTA_SIGNING_STORE_FILE")
val signingPassword = System.getenv("ROTA_SIGNING_STORE_PASSWORD")
val signingAlias = System.getenv("ROTA_SIGNING_KEY_ALIAS")
val signingKeyPassword = System.getenv("ROTA_SIGNING_KEY_PASSWORD")
val signingReady = listOf(signingFile, signingPassword, signingAlias, signingKeyPassword).all { !it.isNullOrBlank() }

android {
    namespace = "com.grm.rotagrm"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.grm.rotagrm"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0-test"
        manifestPlaceholders["fcmEnabled"] = fcmEnabled.toString()
        buildConfigField("boolean", "FCM_ENABLED", fcmEnabled.toString())
        buildConfigField("String", "API_BASE_URL", "\"${gradlePropertyOrEmpty("rotaApiBaseUrl")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    if (signingReady) {
        signingConfigs.create("release") {
            storeFile = file(signingFile!!)
            storePassword = signingPassword
            keyAlias = signingAlias
            keyPassword = signingKeyPassword
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.12.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(platform("com.google.firebase:firebase-bom:34.7.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("androidx.work:work-runtime-ktx:2.11.0")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

gradle.taskGraph.whenReady {
    if (allTasks.any { it.name.contains("Release") && (it.name.startsWith("assemble") || it.name.startsWith("bundle")) }) {
        listOf("rotaApiBaseUrl").forEach { name ->
            val uri = URI(publicValue(name))
            check(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
                "Configure $name com a URL HTTPS real."
            }
        }
        check(signingReady && file(signingFile!!).isFile) { "Configure a assinatura release no ambiente seguro de compilação." }
    }
}
