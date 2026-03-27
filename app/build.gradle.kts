plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.muratov.intercom"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.muratov.intercom"
        minSdk = 27
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        compose = true
        buildConfig = true
        viewBinding = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    packaging {
        jniLibs {
            pickFirsts += setOf(
                "**/libc++_shared.so",
            )
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

}

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-stdlib")) {
            useVersion("1.9.24")
            because("Keep Kotlin stdlib compatible with the project's Kotlin compiler 1.9.24")
        }
    }
}

dependencies {
    val composeUiVersion = "1.6.8"
    val geckoViewVersion = "147.0.20260212191108"
    val media3Version = "1.4.1"

    constraints {
        implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24") {
            version { strictly("1.9.24") }
        }
        implementation("org.jetbrains.kotlin:kotlin-stdlib-common:1.9.24") {
            version { strictly("1.9.24") }
        }
        implementation("androidx.core:core:1.15.0") {
            version { strictly("1.15.0") }
        }
        implementation("androidx.core:core-ktx:1.15.0") {
            version { strictly("1.15.0") }
        }
        implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7") {
            version { strictly("2.8.7") }
        }
        implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7") {
            version { strictly("2.8.7") }
        }
    }

    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.datastore:datastore-preferences:1.1.2")
    implementation("com.google.android.material:material:1.12.0")

    implementation("androidx.compose.ui:ui:$composeUiVersion")
    implementation("androidx.compose.ui:ui-graphics:$composeUiVersion")
    implementation("androidx.compose.ui:ui-tooling-preview:$composeUiVersion")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.material:material-icons-extended:$composeUiVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("org.videolan.android:libvlc-all:3.6.5")
    implementation("org.mozilla.geckoview:geckoview:$geckoViewVersion")
    implementation("org.linphone:linphone-sdk-android:5.4.97")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:$composeUiVersion")
    debugImplementation("androidx.compose.ui:ui-tooling:$composeUiVersion")
    debugImplementation("androidx.compose.ui:ui-test-manifest:$composeUiVersion")
}
