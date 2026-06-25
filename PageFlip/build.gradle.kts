plugins {
    id("com.android.library")
}

android {
    namespace = "com.eschao.android.widget.pageflip"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    lint {
        targetSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
}
