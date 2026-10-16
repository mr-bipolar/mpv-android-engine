plugins {
    id("com.android.application")
}


android {
    namespace = "com.mrbipolar.mpvengine.sample"
    compileSdk {
        version = release(37)
    }



    buildFeatures {
        viewBinding = true
    }

    defaultConfig {
        applicationId = "com.mrbipolar.mpvengine.sample"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
//            optimization {
//                enable = true
//                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
//            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // MPV engine module
    implementation(project(":mpvengine"))

    // Android UI
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("com.google.android.material:material:1.14.0")

    // Unit tests
    testImplementation("junit:junit:4.13.2")

    // Instrumentation tests
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}