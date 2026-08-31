import java.util.Properties

plugins {
    id("com.android.application")
}

val releaseKeystorePropertiesFile = rootProject.file("keystore.properties")
val releaseKeystoreProperties = Properties().apply {
    if (releaseKeystorePropertiesFile.isFile) {
        releaseKeystorePropertiesFile.inputStream().use(::load)
    }
}

fun releaseSigningProperty(name: String): String =
    releaseKeystoreProperties.getProperty(name)?.trim().orEmpty()

android {
    namespace = "com.vokie.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vokie.phone"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.3.6"
        buildConfigField(
            "String",
            "UPDATE_MANIFEST_URL",
            "\"https://github.com/anyshu/vokie-plugin-android/releases/latest/download/latest.json\""
        )
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (releaseKeystorePropertiesFile.isFile) {
            create("release") {
                val configuredStoreFile = releaseSigningProperty("storeFile")
                require(configuredStoreFile.isNotEmpty()) {
                    "storeFile is required in ${releaseKeystorePropertiesFile.path}"
                }
                storeFile = rootProject.file(configuredStoreFile).also {
                    require(it.isFile) { "Release keystore was not found at ${it.path}" }
                }
                storePassword = releaseSigningProperty("storePassword").also {
                    require(it.isNotEmpty()) {
                        "storePassword is required in ${releaseKeystorePropertiesFile.path}"
                    }
                }
                keyAlias = releaseSigningProperty("keyAlias").also {
                    require(it.isNotEmpty()) {
                        "keyAlias is required in ${releaseKeystorePropertiesFile.path}"
                    }
                }
                keyPassword = releaseSigningProperty("keyPassword").also {
                    require(it.isNotEmpty()) {
                        "keyPassword is required in ${releaseKeystorePropertiesFile.path}"
                    }
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystorePropertiesFile.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
