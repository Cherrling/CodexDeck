import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val appVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use(::load)
}
val configuredVersionName = providers.environmentVariable("CODEXDECK_VERSION_NAME")
    .orElse("${appVersion.getProperty("baseVersion")}-dev")
    .get()
val configuredVersionCode = providers.environmentVariable("CODEXDECK_VERSION_CODE")
    .orElse(appVersion.getProperty("fallbackVersionCode"))
    .get()
    .toInt()
val buildTime = providers.environmentVariable("CODEXDECK_BUILD_TIME")
    .orElse("Local build (unrecorded)")
    .get()
val sourceRevision = providers.environmentVariable("CODEXDECK_SOURCE_REVISION")
    .orElse("local")
    .get()

require(configuredVersionCode > 0)
require(configuredVersionName.length <= 128)

val signingPrefix = "CODEXDECK"
val signingValues = listOf("KEYSTORE_PATH", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
    .associateWith { providers.environmentVariable("${signingPrefix}_$it").orNull }
val rollingDebugKeystore = providers.environmentVariable("CODEXDECK_DEBUG_KEYSTORE_PATH")
    .orNull
    ?.takeIf(String::isNotBlank)

android {
    namespace = "cc.cherr.codexdeck"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "cc.cherr.codexdeck"
        minSdk = 26
        targetSdk = 36
        versionCode = configuredVersionCode
        versionName = configuredVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        javaCompileOptions {
            annotationProcessorOptions {
                arguments["room.schemaLocation"] = "$projectDir/schemas"
            }
        }
        buildConfigField("String", "BUILD_TIME", "\"${buildTime.replace("\"", "\\\"")}\"")
        buildConfigField("String", "SOURCE_REVISION", "\"${sourceRevision.take(12)}\"")
    }

    signingConfigs {
        create("release") {
            signingValues["KEYSTORE_PATH"]?.takeIf(String::isNotBlank)?.let { storeFile = file(it) }
            storePassword = signingValues["KEYSTORE_PASSWORD"]
            keyAlias = signingValues["KEY_ALIAS"]
            keyPassword = signingValues["KEY_PASSWORD"]
        }
        create("rollingDebug") {
            rollingDebugKeystore?.let { storeFile = file(it) }
            storePassword = providers.environmentVariable("CODEXDECK_DEBUG_KEYSTORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("CODEXDECK_DEBUG_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("CODEXDECK_DEBUG_KEY_PASSWORD").orNull
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "CodexDeck Debug")
            if (rollingDebugKeystore != null) {
                signingConfig = signingConfigs.getByName("rollingDebug")
            }
        }
        release {
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.md",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.md",
            "META-INF/NOTICE.txt",
        )
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        warningsAsErrors = true
        disable += setOf(
            "AndroidGradlePluginVersion",
            "GradleDependency",
            "NewerVersionAvailable",
            // AGP 8.13 supports compileSdk 36; the hosted runner also has API 37, which
            // would otherwise fail lint until the separate AGP 9/Kotlin migration lands.
            "OldTargetApi",
            // Bouncy Castle contains internal trust-manager adapters for certificate parsing;
            // it does not configure this app's HTTPS/WebSocket trust policy.
            "TrustAllX509TrustManager",
        )
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":terminal-view"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    annotationProcessor(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.sshj)
    implementation(libs.bcprov)
    implementation(libs.bcpkix)
    implementation(libs.slf4j.nop)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
