import javax.inject.Inject

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Copies the Xcommunity Google OAuth WebExtension (git submodule) into the
 * APK's assets as `xcommunity-google-oauth/`, laid out as GeckoView loads a
 * built-in extension (see OAuthActivity.kt).
 */
abstract class BundleWebExtension : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun bundle() {
        val dest = outputDir.get().dir("xcommunity-google-oauth").asFile
        fs.delete { delete(outputDir) }
        fs.copy {
            from(source.dir("shared")) { into("shared") }
            from(source.dir("icons")) { into("icons") }
            from(source.file("geckoview/manifest.json"))
            into(dest)
        }
    }
}

val releaseKeystore = System.getenv("ANDROID_KEYSTORE")?.let { file(it) }

android {
    namespace = "com.xcommunity.app.google_oauth"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.xcommunity.app.google_oauth"
        // GeckoView needs Android 8.0.
        minSdk = 26
        targetSdk = 36
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"
        // GeckoView is ~85 MB per ABI; ship arm64-v8a only.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (releaseKeystore?.exists() == true) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // Without a keystore the APK is signed with the debug key so it
            // still installs; the Xcommunity app only trusts a handoff when
            // both apps share a signing key, so use one keystore for both.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // GeckoView's kotlin-stdlib carries Kotlin 2.3 metadata, which this
        // Kotlin 1.9 compiler refuses otherwise.
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }

    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

androidComponents {
    onVariants { variant ->
        val bundleTask = tasks.register<BundleWebExtension>(
            "bundle${variant.name.replaceFirstChar { it.uppercase() }}WebExtension"
        ) {
            source.set(file("vendor/xcommunity-google-oauth-webextension"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(bundleTask, BundleWebExtension::outputDir)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("org.mozilla.geckoview:geckoview:152.0.20260629141727")
}
