plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

val enableNative = project.findProperty("enableNative") == "true"

android {
    namespace = "com.paperleaf.sketchbook"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.paperleaf.sketchbook"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        if (enableNative) {
            externalNativeBuild {
                cmake {
                    cppFlags.add("-std=c++17")
                }
            }
        }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }

    if (enableNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.10.2"
            }
        }
    }
}

dependencies {
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")

    // Coroutines + Lifecycle
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")

    // PageFlip module (eschao/android-PageFlip)
    implementation(project(":PageFlip"))

    // Lottie animations
    implementation("com.airbnb.android:lottie:6.4.0")
}