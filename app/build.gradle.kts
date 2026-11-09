plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Version : vient du tag Git (v1.2.3) passé par la CI avec -PtrustVersionName=1.2.3.
// versionCode = majeur*10000 + mineur*100 + patch → toujours croissant si les tags montent.
// Même formule que update/VersionCodes.kt (la MAJ in-app compare avec le tag de la release).
val trustVersionName: String = (findProperty("trustVersionName") as String?) ?: "0.0.0"
val trustVersionCode: Int = Regex("""^(\d+)\.(\d+)\.(\d+)$""").matchEntire(trustVersionName)
    ?.destructured
    ?.let { (ma, mi, pa) ->
        require(mi.toInt() < 100 && pa.toInt() < 100) { "mineur et patch doivent être < 100" }
        ma.toInt() * 10000 + mi.toInt() * 100 + pa.toInt()
    }
    ?.coerceAtLeast(1)
    ?: error("trustVersionName invalide : '$trustVersionName' (attendu X.Y.Z)")

// Signature release : uniquement via variables d'environnement (CI ou local).
// Sans elles, le build release reste possible mais l'APK n'est pas signé.
val keystorePath: String? = System.getenv("TRUST_KEYSTORE_PATH")
val hasReleaseKeystore = !keystorePath.isNullOrBlank() && file(keystorePath).exists()

android {
    namespace = "fr.niakimbo.trust"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.niakimbo.trust"
        minSdk = 26
        targetSdk = 35
        versionCode = trustVersionCode
        versionName = trustVersionName
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("TRUST_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TRUST_KEY_ALIAS")
                keyPassword = System.getenv("TRUST_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // Audio (étape 3)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource)
    implementation(libs.media3.database)

    // Stockage (étape 5)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
