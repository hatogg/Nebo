plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ua.nebo.watch"
    compileSdk = 34

    defaultConfig {
        applicationId = "ua.nebo.watch"
        minSdk = 24
        targetSdk = 34
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0"
    }

    signingConfigs {
        create("release") {
            val ks = file(System.getenv("KEYSTORE") ?: "../nebo.p12")
            if (ks.exists()) {
                storeFile = ks
                storeType = "pkcs12"
                storePassword = System.getenv("STORE_PASS") ?: "nebo-android"
                keyAlias = System.getenv("KEY_ALIAS") ?: "nebo"
                keyPassword = System.getenv("STORE_PASS") ?: "nebo-android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val rel = signingConfigs.getByName("release")
            signingConfig = if (rel.storeFile != null) rel else signingConfigs.getByName("debug")
        }
    }

    buildFeatures { buildConfig = true }

    packaging {
        jniLibs.pickFirsts.add("**/libc++_shared.so")
        resources.excludes.add("META-INF/INDEX.LIST")
        resources.excludes.add("META-INF/*.kotlin_module")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
}

dependencies {
    // FitCloudPro SDK (офіційні AAR з github.com/htangsmart/FitCloudPro-SDK-Android, див. get-sdk.sh)
    implementation(files("libs/sdk-base-v3.0.2.7.aar"))
    implementation(files("libs/sdk-fitcloud-v3.0.2.7.aar"))
    implementation(files("libs/ext-realtek-dfu-1.0.4.aar"))
    implementation(files("libs/ext-realtek-bbpro-1.0.4.aar"))
    implementation(files("libs/ext-realtek-file-1.0.4.aar"))

    // Залежності, яких потребує SDK
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.2.10"))
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.annotation:annotation:1.5.0")
    implementation("io.reactivex.rxjava3:rxjava:3.1.5")
    implementation("io.reactivex.rxjava3:rxandroid:3.0.2")
    implementation("com.polidea.rxandroidble3:rxandroidble:1.17.2")
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.10.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("no.nordicsemi.android:dfu:2.2.2")
    implementation("androidx.media:media:1.6.0")
    implementation("pl.droidsonroids.gif:android-gif-drawable:1.2.32")
}
