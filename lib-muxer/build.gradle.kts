plugins {
    id("com.android.library")
    id("maven-publish")
}

android {
    namespace = "androidx.media3.muxer"
    compileSdk = 34

    defaultConfig {
        minSdk = 21

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    sourceSets {
        this.getByName("test"){
            this.assets.srcDir("./assets")
        }
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    //Install to local maven repo
    //gradlew libraw:publishToMavenLocal
    publishing {
        singleVariant("release")
    }
}
//Install to local maven repo
//gradlew lib-muxer:publishToMavenLocal
afterEvaluate {
    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                // These are ignored by Jitpack.io
                groupId = "com.homesoft.android"
                artifactId = "muxer"
                version = "0.9.0"
                from(components["release"])
            }
        }
    }
}

dependencies {

    api("androidx.media3:media3-common:1.3.0")
    implementation("androidx.media3:media3-container:1.3.0")
    implementation("androidx.media3:media3-extractor:1.3.0")
    implementation("androidx.annotation:annotation:1.7.1")
    compileOnly("org.checkerframework:checker-qual:3.13.0")
    compileOnly("com.google.errorprone:error_prone_annotations:2.18.0")
    testImplementation("androidx.media3:media3-test-utils:1.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.1.3")
    testImplementation("org.robolectric:robolectric:4.11")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
