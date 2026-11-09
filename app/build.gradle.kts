import java.util.Properties

plugins {
    id("com.android.application")
}

// Small cryptography dependency for per-install TLS certificates on the local parent console.
android {
    namespace = "dev.plume"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tvquizlock.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 31
        versionName = "1.15.1"
        // What's new: only this version's section of CHANGELOG.md ships, one entry per line, ** marks kept for bold.
        // A ### heading ships as a line that starts with #. A fix release (1.7.1) ships its x.y section after its own,
        // so people who update from 1.6 still see what 1.7 brought.
        val changelog = rootProject.file("CHANGELOG.md").readText().replace("\r", "")
        val section = { v: String -> changelog.substringAfter("\n## $v\n", "").substringBefore("\n## ") }
        val minor = versionName!!.split('.').take(2).joinToString(".")
        val notes = (section(versionName!!) + if (minor != versionName) "\n" + section(minor) else "")
            .lines().filter { it.startsWith("- ") || it.startsWith("### ") }
            .map { if (it.startsWith("### ")) "#" + it.removePrefix("### ") else it.removePrefix("- ") }
        require(notes.isNotEmpty()) { "CHANGELOG.md has no entries under \"## $versionName\"" }
        resValue("string", "whats_new", notes.joinToString("\\n") { it.replace("'", "\\'").replace("\"", "\\\"") })
    }

    buildFeatures {
        resValues = true
    }

    // CI fails on new lint errors only; the ones already here are listed in lint-baseline.xml
    // (./gradlew updateLintBaseline rewrites it). Translations have their own check, so lag there isn't an error.
    lint {
        baseline = file("lint-baseline.xml")
        disable += setOf("MissingTranslation", "ExtraTranslation")
    }

    // A release key is supplied locally through keystore.properties or securely through CI environment variables.
    // Never fall back to the per-run Android debug key: that would make future updates impossible.
    val localKeys = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    val signingStoreFile = System.getenv("ANDROID_KEYSTORE_PATH") ?: localKeys?.getProperty("storeFile")
    val signingStorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: localKeys?.getProperty("storePassword")
    val signingAlias = System.getenv("ANDROID_KEY_ALIAS") ?: localKeys?.getProperty("keyAlias")
    val signingKeyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: localKeys?.getProperty("keyPassword")
    val hasReleaseSigning = listOf(signingStoreFile, signingStorePassword, signingAlias, signingKeyPassword)
        .all { !it.isNullOrBlank() }
    signingConfigs {
        if (hasReleaseSigning) create("release") {
            storeFile = file(signingStoreFile!!)
            storePassword = signingStorePassword
            keyAlias = signingAlias
            keyPassword = signingKeyPassword
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    // Kotlin's own metadata files (reflection and tooling data) aren't used at run time.
    packaging {
        resources {
            excludes += setOf("kotlin/**", "**/*.kotlin_builtins", "META-INF/*.kotlin_module", "META-INF/*.version", "DebugProbesKt.bin")
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        release {
            vcsInfo.include = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
}

dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")
    testImplementation("junit:junit:4.13.2")
}
