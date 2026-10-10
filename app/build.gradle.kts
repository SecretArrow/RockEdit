plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.secretarrow.rockedit"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.secretarrow.rockedit"
        minSdk = 26
        targetSdk = 36
        versionCode = (findProperty("rockeditVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (findProperty("rockeditVersionName") as String?) ?: "0.24.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(property("rockedit.storeFile") as String)
            storePassword = property("rockedit.storePassword") as String
            keyAlias = property("rockedit.keyAlias") as String
            keyPassword = property("rockedit.keyPassword") as String
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
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
        buildConfig = true
        viewBinding = true
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = false
        // AppLinkUrlError: false positive for our text/* open-with filter (no web app links by design).
        disable += listOf("GradleDependency", "AppLinkUrlError")
    }
    packaging {
        resources {
            excludes += listOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.github.albfernandez:juniversalchardet:2.5.0")
    // Storage Manager (v0.6.0): FTP/FTPS + SFTP. WebDAV is dependency-free.
    implementation("commons-net:commons-net:3.11.0")
    implementation("com.github.mjdev:libaums:0.7.4")
    implementation("com.hierynomus:sshj:0.38.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // XmlPullParser implementation for JVM tests (WebDAV parser).
    testImplementation("net.sf.kxml:kxml2:2.3.0")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
