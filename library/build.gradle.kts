plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val artifactVersion = "${providers.gradleProperty("project.versionName").get()}.${providers.gradleProperty("project.versionCode").get().padStart(4, '0')}"

group = "io.rockchip.sshsftp"
version = artifactVersion

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
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    api(fileTree("libs") { include("*.jar") })
    implementation("org.slf4j:slf4j-android:1.7.36") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
}

val fatReleaseClasses by tasks.registering(Jar::class) {
    dependsOn("bundleLibCompileToJarRelease")
    archiveFileName.set("classes.jar")
    destinationDirectory.set(layout.buildDirectory.dir("intermediates/fat-aar/release"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(layout.buildDirectory.file("intermediates/compile_library_classes_jar/release/bundleLibCompileToJarRelease/classes.jar").map { zipTree(it) })
    from(configurations.getByName("releaseRuntimeClasspath").filter { it.extension == "jar" }.map { zipTree(it) })
}

val fatReleaseAarStaging = layout.buildDirectory.dir("intermediates/fat-aar/release/staging")

val prepareFatReleaseAar by tasks.registering(Sync::class) {
    dependsOn("bundleReleaseAar", fatReleaseClasses)
    from({ zipTree(layout.buildDirectory.file("outputs/aar/${project.name}-release.aar")) }) {
        exclude("classes.jar", "libs/**")
    }
    from(fatReleaseClasses) {
        into(".")
    }
    into(fatReleaseAarStaging)
}

val fatReleaseAar by tasks.registering(Zip::class) {
    dependsOn(prepareFatReleaseAar)
    archiveFileName.set("rockchip-ssh-sftp-library-v$artifactVersion.aar")
    destinationDirectory.set(layout.buildDirectory.dir("outputs/aar"))
    from(fatReleaseAarStaging)
}

val fatReleaseJar by tasks.registering(Copy::class) {
    dependsOn(fatReleaseClasses)
    from(fatReleaseClasses)
    into(layout.buildDirectory.dir("outputs/jar"))
    rename { "rockchip-ssh-sftp-library-v$artifactVersion-all.jar" }
}
