import com.android.build.api.dsl.ApplicationExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.android.application)
}

kotlin {
    compilerOptions {
        languageVersion = KotlinVersion.KOTLIN_2_4
        jvmTarget = JvmTarget.fromTarget("17")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:unchecked")
    options.compilerArgs.add("-Xlint:deprecation")
}


// Gradle properties first, then environment. Both are configuration-cache
// safe; neither is required for debug or connectedAndroidTest.
fun secret(name: String) =
    providers.gradleProperty(name).orElse(providers.environmentVariable(name))

val keystoreFile = secret("MPMR_KEYSTORE_FILE")
val keystorePassword = secret("MPMR_KEYSTORE_PASSWORD")
val keystoreKeyAlias = secret("MPMR_KEYSTORE_KEY_ALIAS")
val keystoreKeyPassword = secret("MPMR_KEYSTORE_KEY_PASSWORD")

extensions.configure<ApplicationExtension>("android") {
    namespace = "com.dawud.mpmrbench"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }
    buildToolsVersion = "37.0.0"

    // The host harness (verify.sh) and the APK compile the SAME files.
    // Do not copy sources into the module -- they will drift. See CLAUDE.md.
    sourceSets {
        getByName("main") {
            kotlin.directories.add("../src")
        }
    }

    defaultConfig {
        applicationId = "com.dawud.mpmrbench"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // SM-G980F is Exynos 990 = arm64-v8a ONLY. phase1_solver.cpp
            // includes <arm_neon.h>, so it will not compile for x86_64 --
            // adding an emulator ABI here needs that guarded first.
            abiFilters += listOf("arm64-v8a")
        }
    }
    ndkVersion = "30.0.16248370"
    externalNativeBuild {
        cmake {
            version = "4.1.2"
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    // Release signing is OPTIONAL. Supply MPMR_KEYSTORE_FILE, _PASSWORD,
    // _KEY_ALIAS and _KEY_PASSWORD as Gradle properties or environment
    // variables to get a signed APK. Without them assembleRelease now
    // produces an UNSIGNED APK rather than failing in validateSigningRelease.
    if (keystoreFile.isPresent) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreFile.get())
                storePassword = keystorePassword.orNull
                keyAlias = keystoreKeyAlias.orNull
                keyPassword = keystoreKeyPassword.orNull
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            isJniDebuggable = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    androidComponents.onVariants { variant ->
        variant.outputs.forEach { output ->
            if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                output.outputFileName.set("mpmr.apk")
            }
        }
    }
}

dependencies {
    // NOTE: EjmlStandin.kt is kept deliberately -- it is exercised by all 45
    // passing checks, so the build needs no EJML dependency at all. To switch
    // to real EJML instead: uncomment below, delete src/EjmlStandin.kt, and
    // change the one import at the top of src/GuyanReduction.kt.
    // implementation("org.ejml:ejml-simple:0.43.1")

    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.kotlin)

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}