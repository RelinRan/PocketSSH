plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.rockchip.sshsftp"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    packaging {
        resources {
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(files("../app/libs/sshd-common-2.12.1.jar"))
    implementation(files("../app/libs/sshd-core-2.12.1.jar"))
    implementation(files("../app/libs/sshd-scp-2.12.1.jar"))
    implementation(files("../app/libs/sshd-sftp-2.12.1.jar"))
    implementation(files("../app/libs/slf4j-api-1.7.32.jar"))
    implementation("org.slf4j:slf4j-android:1.7.36") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
}
