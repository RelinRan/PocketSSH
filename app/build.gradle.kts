plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

import com.android.build.gradle.internal.api.BaseVariantOutputImpl

android {
    namespace = "io.pocketssh.server"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.pocketssh.server"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "1.0"

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
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "META-INF/DEPENDENCIES"
        }
    }
    defaultConfig {
        buildConfigField("String", "SSH_BIND_ADDRESS", "\"0.0.0.0\"")
        buildConfigField("int", "SSH_PORT", "2222")
        buildConfigField("String", "SSH_USERNAME", "\"android\"")
        buildConfigField("String", "SSH_PASSWORD", "\"android\"")
        buildConfigField("boolean", "SSH_ENABLED", "true")
    }

    applicationVariants.all {
        outputs.all {
            val output = this as BaseVariantOutputImpl
            val code = versionCode.toString().padStart(4, '0')
            output.outputFileName = "PocketSSH-v$versionName.$code.apk"
        }
    }
}

dependencies {

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(files("libs/sshd-common-2.12.1.jar"))
    implementation(files("libs/sshd-core-2.12.1.jar"))
    implementation(files("libs/sshd-scp-2.12.1.jar"))
    implementation(files("libs/sshd-sftp-2.12.1.jar"))
    implementation(files("libs/slf4j-api-1.7.32.jar"))
    implementation("org.slf4j:slf4j-android:1.7.36") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
}
