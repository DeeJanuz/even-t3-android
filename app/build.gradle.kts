import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val signingPropertiesFile = file(System.getenv("EVEN_COMPANION_SIGNING_PROPERTIES") ?: "${System.getProperty("user.home")}/.android/even-companion-signing.properties")
val signingProperties = Properties().apply {
    if (signingPropertiesFile.isFile) {
        require(!signingPropertiesFile.canonicalFile.toPath().startsWith(rootDir.canonicalFile.toPath())) {
            "Keep signing properties outside the source checkout."
        }
        signingPropertiesFile.inputStream().use { load(it) }
        require(listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !getProperty(it).isNullOrBlank() }) {
            "Signing properties must define storeFile, storePassword, keyAlias, and keyPassword."
        }
    }
}
val generatedLicenseAssets = layout.buildDirectory.dir("generated/licenseAssets")
val packageProjectLicense = tasks.register<Copy>("packageProjectLicense") {
    from(rootProject.file("LICENSE"))
    into(generatedLicenseAssets)
    rename { "LICENSE.txt" }
}
android {
    namespace = "dev.evenbridge.companion"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "dev.evenbridge.companion"
        minSdk = 28
        targetSdk = 35
        versionCode = 5
        versionName = "0.1.4-beta.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { buildConfig = true }
    sourceSets.getByName("main").assets.srcDir(generatedLicenseAssets)
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        if (signingPropertiesFile.isFile) create("personalRelease") {
            val keystore = signingPropertiesFile.parentFile.resolve(signingProperties.getProperty("storeFile")).canonicalFile
            require(keystore.isFile && !keystore.toPath().startsWith(rootDir.canonicalFile.toPath())) {
                "The release keystore must exist outside the source checkout."
            }
            storeFile = keystore
            storePassword = signingProperties.getProperty("storePassword")
            keyAlias = signingProperties.getProperty("keyAlias")
            keyPassword = signingProperties.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (signingPropertiesFile.isFile) signingConfig = signingConfigs.getByName("personalRelease")
        }
    }
    lint { abortOnError = true }
}
tasks.named("preBuild") { dependsOn(packageProjectLicense) }
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
