import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseStoreFile = providers.environmentVariable("ASC_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("ASC_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ASC_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ASC_RELEASE_KEY_PASSWORD").orNull
val releaseSigningValues = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
val releaseSigningConfigured = releaseSigningValues.all { !it.isNullOrBlank() }
val localSupabaseProperties = Properties().apply {
    val propertiesFile = rootProject.file("supabase.properties")
    if (propertiesFile.isFile) propertiesFile.inputStream().use { load(it) }
}
val supabaseUrl = providers.gradleProperty("supabaseUrl").orNull
    ?: providers.environmentVariable("SUPABASE_URL").orNull
    ?: localSupabaseProperties.getProperty("supabase.url").orEmpty()
val supabasePublishableKey = providers.gradleProperty("supabasePublishableKey").orNull
    ?: providers.gradleProperty("supabaseAnonKey").orNull
    ?: providers.environmentVariable("SUPABASE_PUBLISHABLE_KEY").orNull
    ?: providers.environmentVariable("SUPABASE_ANON_KEY").orNull
    ?: localSupabaseProperties.getProperty(
        "supabase.publishableKey",
        localSupabaseProperties.getProperty("supabase.anonKey", ""),
    )
val apiBaseUrl = providers.gradleProperty("ascApiBaseUrl").orNull
    ?: providers.environmentVariable("ASC_API_BASE_URL").orNull
    ?: localSupabaseProperties.getProperty("asc.apiBaseUrl").orEmpty()
val googleDriveClientId = providers.gradleProperty("googleDriveClientId").orNull
    ?: providers.environmentVariable("GOOGLE_DRIVE_CLIENT_ID").orNull
    ?: localSupabaseProperties.getProperty("google.drive.clientId").orEmpty()
val spotifyClientId = providers.gradleProperty("spotifyClientId").orNull
    ?: providers.environmentVariable("SPOTIFY_CLIENT_ID").orNull
    ?: localSupabaseProperties.getProperty("spotify.clientId").orEmpty()
fun buildConfigString(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
if (releaseSigningValues.any { !it.isNullOrBlank() } && !releaseSigningConfigured) {
    throw GradleException("Set all ASC_RELEASE_STORE_FILE, ASC_RELEASE_STORE_PASSWORD, ASC_RELEASE_KEY_ALIAS, and ASC_RELEASE_KEY_PASSWORD environment variables to sign a release.")
}

android {
    namespace = "com.ascmusic"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.ascmusic"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("ascVersionCode").orNull?.toIntOrNull() ?: 2
        versionName = providers.gradleProperty("ascVersionName").orNull ?: "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SUPABASE_URL", buildConfigString(supabaseUrl))
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", buildConfigString(supabasePublishableKey))
        buildConfigField("String", "ASC_API_BASE_URL", buildConfigString(apiBaseUrl))
        buildConfigField("String", "GOOGLE_DRIVE_CLIENT_ID", buildConfigString(googleDriveClientId))
        buildConfigField("String", "SPOTIFY_CLIENT_ID", buildConfigString(spotifyClientId))
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-datasource:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
