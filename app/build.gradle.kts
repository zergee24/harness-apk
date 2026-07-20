plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val testStoreFile = providers.environmentVariable("ANDROID_TEST_STORE_FILE")
val testStorePassword = providers.environmentVariable("ANDROID_TEST_STORE_PASSWORD")
val testKeyAlias = providers.environmentVariable("ANDROID_TEST_KEY_ALIAS")
val testKeyPassword = providers.environmentVariable("ANDROID_TEST_KEY_PASSWORD")
val hasTestSigning = listOf(
    testStoreFile,
    testStorePassword,
    testKeyAlias,
    testKeyPassword,
).all { it.isPresent }

val releaseStoreFile = providers.environmentVariable("ANDROID_RELEASE_STORE_FILE")
val releaseStorePassword = providers.environmentVariable("ANDROID_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = providers.environmentVariable("ANDROID_RELEASE_KEY_ALIAS")
val releaseKeyPassword = providers.environmentVariable("ANDROID_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it.isPresent }

val agentV2FixtureWorkspace = rootProject.layout.buildDirectory.dir("agent-v2-fixture")
val agentV2FixtureDist = rootProject.layout.buildDirectory.dir("agent-v2-dist")
val agentV2FixtureCompleteDist = rootProject.layout.buildDirectory.dir("agent-v2-complete-dist")
val agentV2FixtureSourceDist = rootProject.layout.buildDirectory.dir("agent-v2-source-dist")
val generatedAgentV2Assets = layout.buildDirectory.dir("generated/agent-v2-fixture-assets")

val prepareAgentV2Fixture = tasks.register<Exec>("prepareAgentV2Fixture") {
    doFirst {
        delete(agentV2FixtureCompleteDist, agentV2FixtureSourceDist)
    }
    workingDir(rootProject.projectDir)
    commandLine(
        "scripts/agent-builder.sh",
        "-m",
        "tools.agent_builder.tests.fixture_v2",
        "--source",
        "app/src/test/resources/agent/source.md",
        "--workspace",
        agentV2FixtureWorkspace.get().asFile.absolutePath,
        "--dist",
        agentV2FixtureDist.get().asFile.absolutePath,
        "--reset",
    )
}

val validateAgentV2Fixture = tasks.register<Exec>("validateAgentV2Fixture") {
    dependsOn(prepareAgentV2Fixture)
    workingDir(rootProject.projectDir)
    commandLine("scripts/agent-builder.sh", "validate", agentV2FixtureWorkspace.get().asFile.absolutePath)
}

val recommendAgentV2Fixture = tasks.register<Exec>("recommendAgentV2Fixture") {
    dependsOn(validateAgentV2Fixture)
    workingDir(rootProject.projectDir)
    commandLine(
        "scripts/agent-builder.sh",
        "recommend",
        agentV2FixtureWorkspace.get().asFile.absolutePath,
        "--key",
        agentV2FixtureWorkspace.get().file("test-key.pem").asFile.absolutePath,
    )
}

val packAgentV2Fixture = tasks.register<Exec>("packAgentV2Fixture") {
    dependsOn(recommendAgentV2Fixture)
    workingDir(rootProject.projectDir)
    commandLine(
        "scripts/agent-builder.sh",
        "pack",
        agentV2FixtureWorkspace.get().asFile.absolutePath,
        "--output",
        agentV2FixtureDist.get().asFile.absolutePath,
        "--key",
        agentV2FixtureWorkspace.get().file("test-key.pem").asFile.absolutePath,
        "--profile",
        "balanced",
    )
}

val packCompleteAgentV2Fixture = tasks.register<Exec>("packCompleteAgentV2Fixture") {
    dependsOn(recommendAgentV2Fixture)
    workingDir(rootProject.projectDir)
    commandLine(
        "scripts/agent-builder.sh",
        "pack",
        agentV2FixtureWorkspace.get().asFile.absolutePath,
        "--output",
        agentV2FixtureCompleteDist.get().asFile.absolutePath,
        "--key",
        agentV2FixtureWorkspace.get().file("test-key.pem").asFile.absolutePath,
        "--profile",
        "complete",
    )
}

val packSourceAgentV2Fixture = tasks.register<Exec>("packSourceAgentV2Fixture") {
    dependsOn(recommendAgentV2Fixture)
    workingDir(rootProject.projectDir)
    commandLine(
        "scripts/agent-builder.sh",
        "pack",
        agentV2FixtureWorkspace.get().asFile.absolutePath,
        "--output",
        agentV2FixtureSourceDist.get().asFile.absolutePath,
        "--key",
        agentV2FixtureWorkspace.get().file("test-key.pem").asFile.absolutePath,
        "--profile",
        "source",
    )
}

val syncAgentV2FixtureAssets = tasks.register<Sync>("syncAgentV2FixtureAssets") {
    dependsOn(packAgentV2Fixture, packCompleteAgentV2Fixture, packSourceAgentV2Fixture)
    from(agentV2FixtureDist) {
        include("*.hbundle", "*.hcorpus")
    }
    from(agentV2FixtureCompleteDist) {
        include("*-complete.hbundle")
    }
    from(agentV2FixtureSourceDist) {
        include("*-source.hbundle", "*.hsource")
    }
    into(generatedAgentV2Assets)
}

val appVersionCode = providers.gradleProperty("versionCodeOverride")
    .map { it.toInt() }
    .orElse(2000000)
    .get()
val appVersionName = providers.gradleProperty("versionNameOverride")
    .orElse("0.2.0")
    .get()

android {
    namespace = "com.harnessapk"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.harnessapk"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (hasTestSigning) {
            create("test") {
                storeFile = file(testStoreFile.get())
                storePassword = testStorePassword.get()
                keyAlias = testKeyAlias.get()
                keyPassword = testKeyPassword.get()
            }
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile.get())
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            buildConfigField(
                "String",
                "UPDATE_MANIFEST_URL",
                providers.gradleProperty("prodUpdateManifestUrl")
                    .orElse("https://www.zerg.work/harness-apk/prod/update.json")
                    .get()
                    .asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "PROVIDER_CATALOG_URL",
                providers.gradleProperty("prodProviderCatalogUrl")
                    .orElse("https://www.zerg.work/harness-apk/catalog/provider-capabilities.json")
                    .get()
                    .asBuildConfigString(),
            )
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            if (hasTestSigning) {
                signingConfig = signingConfigs.getByName("test")
            }
            buildConfigField(
                "String",
                "UPDATE_MANIFEST_URL",
                providers.gradleProperty("testUpdateManifestUrl")
                    .orElse("https://www.zerg.work/harness-apk/test/update.json")
                    .get()
                    .asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "PROVIDER_CATALOG_URL",
                providers.gradleProperty("testProviderCatalogUrl")
                    .orElse("https://www.zerg.work/harness-apk/test/provider-capabilities.json")
                    .get()
                    .asBuildConfigString(),
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    sourceSets.getByName("androidTest").assets.directories.add(
        generatedAgentV2Assets.get().asFile.absolutePath,
    )

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

tasks.matching { it.name == "mergeDebugAndroidTestAssets" }.configureEach {
    dependsOn(syncAgentV2FixtureAssets)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.commonmark:commonmark:0.25.1")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.25.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.7.0.202606012155-r")
    implementation("org.slf4j:slf4j-nop:2.0.17")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")

    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.room:room-testing:2.8.4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
