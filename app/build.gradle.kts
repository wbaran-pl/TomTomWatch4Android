import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val stravaProperties = Properties().apply {
    val propertiesFile = rootProject.file("strava.properties")
    if (propertiesFile.exists()) {
        load(FileInputStream(propertiesFile))
    }
}

android {
    namespace = "pl.wojciechbaran.tomtomwatch"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.wojciechbaran.tomtomwatch"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val clientId = stravaProperties.getProperty("strava.client_id") ?: ""
        val clientSecret = stravaProperties.getProperty("strava.client_secret") ?: ""
        val redirectScheme = stravaProperties.getProperty("strava.redirect_scheme") ?: "tomtomwatch"
        val redirectHost = stravaProperties.getProperty("strava.redirect_host") ?: "strava"

        buildConfigField("String", "STRAVA_CLIENT_ID", "\"$clientId\"")
        buildConfigField("String", "STRAVA_CLIENT_SECRET", "\"$clientSecret\"")
        buildConfigField("String", "STRAVA_REDIRECT_SCHEME", "\"$redirectScheme\"")
        buildConfigField("String", "STRAVA_REDIRECT_HOST", "\"$redirectHost\"")

        manifestPlaceholders["stravaRedirectScheme"] = redirectScheme
        manifestPlaceholders["stravaRedirectHost"] = redirectHost
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.appcompat)
    implementation(libs.hirondelle.date4j)
    implementation(libs.gson)
    implementation(libs.protobuf.java)
    implementation(libs.osmdroid)
    implementation(libs.androidx.documentfile)
    implementation(libs.okhttp)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
