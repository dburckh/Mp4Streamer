plugins {
    id("com.android.library")
}

android {
    namespace = "androidx.media3.muxer"
    compileSdk = 34

    defaultConfig {
        minSdk = 21

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {

    api("androidx.media3:media3-common:1.3.0")
    implementation("androidx.media3:media3-container:1.3.0")
    implementation("androidx.annotation:annotation:1.7.1")
    compileOnly("org.checkerframework:checker-qual:3.13.0")
    compileOnly("com.google.errorprone:error_prone_annotations:2.18.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}