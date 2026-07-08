# Family LLM Android APK Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first Android APK for family LLM chat with local provider setup, encrypted API keys, local multi-session history, image input, and self-hosted APK updates.

**Architecture:** Native Android app using Kotlin and Jetpack Compose. Room stores conversations and messages, DataStore stores non-secret settings, Android Keystore protects provider API keys, OkHttp handles OpenAI-compatible chat and update downloads, and FileProvider launches the system APK installer.

**Tech Stack:** Android Gradle Plugin 9.2.1 with built-in Kotlin support, Compose Compiler Gradle plugin 2.3.21, KSP 2.3.9 for Room code generation, Jetpack Compose BOM 2026.06.01, Room 2.8.4, DataStore 1.2.1, Navigation Compose 2.9.8, Lifecycle 2.11.0, OkHttp 5.4.0, kotlinx.serialization JSON runtime, JUnit, AndroidX Test.

---

## File Structure

Create this Android project layout:

```text
harness-apk/
  settings.gradle.kts
  build.gradle.kts
  gradle.properties
  gradlew
  gradlew.bat
  gradle/wrapper/gradle-wrapper.properties
  gradle/wrapper/gradle-wrapper.jar
  app/
    build.gradle.kts
    proguard-rules.pro
    src/main/AndroidManifest.xml
    src/main/res/xml/apk_file_paths.xml
    src/main/res/xml/data_extraction_rules.xml
    src/main/res/values/styles.xml
    src/main/java/com/harnessapk/MainActivity.kt
    src/main/java/com/harnessapk/HarnessApkApplication.kt
    src/main/java/com/harnessapk/common/AppDispatchers.kt
    src/main/java/com/harnessapk/common/AppError.kt
    src/main/java/com/harnessapk/common/TimeProvider.kt
    src/main/java/com/harnessapk/storage/AppDatabase.kt
    src/main/java/com/harnessapk/storage/ConversationEntity.kt
    src/main/java/com/harnessapk/storage/MessageEntity.kt
    src/main/java/com/harnessapk/storage/MessageAttachmentEntity.kt
    src/main/java/com/harnessapk/storage/ProviderProfileEntity.kt
    src/main/java/com/harnessapk/storage/ConversationDao.kt
    src/main/java/com/harnessapk/storage/MessageDao.kt
    src/main/java/com/harnessapk/storage/ProviderProfileDao.kt
    src/main/java/com/harnessapk/storage/AppSettingsStore.kt
    src/main/java/com/harnessapk/security/ApiKeyCipher.kt
    src/main/java/com/harnessapk/provider/ProviderModels.kt
    src/main/java/com/harnessapk/provider/ProviderRepository.kt
    src/main/java/com/harnessapk/provider/ProviderTemplates.kt
    src/main/java/com/harnessapk/network/OpenAiCompatibleClient.kt
    src/main/java/com/harnessapk/network/ChatDtos.kt
    src/main/java/com/harnessapk/chat/ChatModels.kt
    src/main/java/com/harnessapk/chat/ChatRepository.kt
    src/main/java/com/harnessapk/chat/SendMessageUseCase.kt
    src/main/java/com/harnessapk/updater/UpdateModels.kt
    src/main/java/com/harnessapk/updater/UpdateRepository.kt
    src/main/java/com/harnessapk/updater/ApkInstaller.kt
    src/main/java/com/harnessapk/ui/HarnessApkApp.kt
    src/main/java/com/harnessapk/ui/theme/Theme.kt
    src/main/java/com/harnessapk/ui/conversation/ConversationListScreen.kt
    src/main/java/com/harnessapk/ui/chat/ChatScreen.kt
    src/main/java/com/harnessapk/ui/provider/ProviderSettingsScreen.kt
    src/main/java/com/harnessapk/ui/updater/UpdateSettingsScreen.kt
    src/test/java/com/harnessapk/security/ApiKeyCipherTest.kt
    src/test/java/com/harnessapk/network/OpenAiCompatibleClientTest.kt
    src/test/java/com/harnessapk/updater/UpdateRepositoryTest.kt
    src/androidTest/java/com/harnessapk/storage/AppDatabaseTest.kt
  release/update.example.json
  scripts/compute-sha256.sh
  docs/release-hosting.md
```

The first implementation should keep one Android app module. Do not split into Gradle submodules until the app works end-to-end.

---

### Task 1: Bootstrap Android Project

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradlew`
- Create: `gradlew.bat`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradle/wrapper/gradle-wrapper.jar`
- Create: `app/build.gradle.kts`
- Create: `app/proguard-rules.pro`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/xml/apk_file_paths.xml`
- Create: `app/src/main/res/xml/data_extraction_rules.xml`
- Create: `app/src/main/res/values/styles.xml`
- Create: `app/src/main/java/com/harnessapk/MainActivity.kt`
- Create: `app/src/main/java/com/harnessapk/HarnessApkApplication.kt`

- [ ] **Step 1: Create Gradle settings**

Write `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "HarnessApk"
include(":app")
```

- [ ] **Step 2: Create root build file**

Write `build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("com.google.devtools.ksp") version "2.3.9" apply false
}
```

- [ ] **Step 3: Create Gradle properties**

Write `gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

- [ ] **Step 4: Generate official Gradle wrapper**

Write `gradle/wrapper/gradle-wrapper.properties`:

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-9.6.1-bin.zip
networkTimeout=10000
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

On this machine, `gradle` is not installed. Use the official Gradle distribution once to generate the wrapper scripts and jar:

```bash
curl -L https://services.gradle.org/distributions/gradle-9.6.1-bin.zip -o /tmp/gradle-9.6.1-bin.zip
rm -rf /tmp/gradle-9.6.1
unzip -q /tmp/gradle-9.6.1-bin.zip -d /tmp
/tmp/gradle-9.6.1/bin/gradle wrapper --gradle-version 9.6.1 --distribution-type bin
```

Expected files:

```text
gradlew
gradlew.bat
gradle/wrapper/gradle-wrapper.jar
gradle/wrapper/gradle-wrapper.properties
```

Do not commit a hand-written fake wrapper jar.

- [ ] **Step 5: Create app module build file**

Write `app/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.harnessapk"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.harnessapk"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField(
            "String",
            "UPDATE_MANIFEST_URL",
            "\"https://download.example.com/harness-apk/update.json\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
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
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("com.squareup.okhttp3:okhttp-sse:5.4.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
    testImplementation("androidx.room:room-testing:2.8.4")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
```

- [ ] **Step 6: Create manifest**

Write `app/src/main/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:name=".HarnessApkApplication"
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="false"
        android:label="Harness APK"
        android:supportsRtl="true"
        android:theme="@style/Theme.HarnessApk">

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/apk_file_paths" />
        </provider>

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

Also add `app/src/main/res/xml/data_extraction_rules.xml` with no cloud backup:

```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="database" path="." />
        <exclude domain="sharedpref" path="." />
        <exclude domain="file" path="." />
    </cloud-backup>
</data-extraction-rules>
```

Write `app/src/main/res/xml/apk_file_paths.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="update_apks" path="updates/" />
</paths>
```

Write `app/src/main/res/values/styles.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.HarnessApk" parent="android:style/Theme.Material.Light.NoActionBar">
        <item name="android:windowActionBar">false</item>
        <item name="android:windowNoTitle">true</item>
        <item name="android:windowLightStatusBar">true</item>
    </style>
</resources>
```

- [ ] **Step 7: Create minimal app entry**

Write `app/src/main/java/com/harnessapk/HarnessApkApplication.kt`:

```kotlin
package com.harnessapk

import android.app.Application

class HarnessApkApplication : Application()
```

Write `app/src/main/java/com/harnessapk/MainActivity.kt`:

```kotlin
package com.harnessapk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.harnessapk.ui.HarnessApkApp
import com.harnessapk.ui.theme.HarnessApkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HarnessApkTheme {
                HarnessApkApp()
            }
        }
    }
}
```

- [ ] **Step 8: Verify project bootstrap**

Run:

```bash
./gradlew :app:assembleDebug
```

Expected: build reaches Kotlin/Android resource compilation. If Android SDK is missing, stop and install command-line tools before continuing.

- [ ] **Step 9: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradlew gradlew.bat gradle app
git commit -m "工程：初始化 Android Compose 项目"
```

---

### Task 2: Common UI Shell and Navigation

**Files:**
- Create: `app/src/main/java/com/harnessapk/ui/theme/Theme.kt`
- Create: `app/src/main/java/com/harnessapk/ui/HarnessApkApp.kt`
- Create: `app/src/main/java/com/harnessapk/ui/conversation/ConversationListScreen.kt`
- Create: `app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt`
- Create: `app/src/main/java/com/harnessapk/ui/provider/ProviderSettingsScreen.kt`
- Create: `app/src/main/java/com/harnessapk/ui/updater/UpdateSettingsScreen.kt`

- [ ] **Step 1: Add theme**

Write `Theme.kt`:

```kotlin
package com.harnessapk.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AppColorScheme = lightColorScheme(
    primary = Color(0xFF2457C5),
    secondary = Color(0xFF546179),
    tertiary = Color(0xFF1E7B5F),
    background = Color(0xFFFAFBFF),
    surface = Color(0xFFFFFFFF),
    error = Color(0xFFB3261E)
)

@Composable
fun HarnessApkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
```

- [ ] **Step 2: Add navigation shell**

Write `HarnessApkApp.kt`:

```kotlin
package com.harnessapk.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.harnessapk.ui.chat.ChatScreen
import com.harnessapk.ui.conversation.ConversationListScreen
import com.harnessapk.ui.provider.ProviderSettingsScreen
import com.harnessapk.ui.updater.UpdateSettingsScreen

object Routes {
    const val Conversations = "conversations"
    const val Chat = "chat"
    const val Providers = "providers"
    const val Updates = "updates"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HarnessApkApp() {
    val navController = rememberNavController()
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Harness APK") })
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.Conversations
        ) {
            composable(Routes.Conversations) {
                ConversationListScreen(
                    contentPadding = padding,
                    onOpenChat = { navController.navigate(Routes.Chat) },
                    onOpenProviders = { navController.navigate(Routes.Providers) },
                    onOpenUpdates = { navController.navigate(Routes.Updates) }
                )
            }
            composable(Routes.Chat) {
                ChatScreen(contentPadding = padding)
            }
            composable(Routes.Providers) {
                ProviderSettingsScreen(contentPadding = padding)
            }
            composable(Routes.Updates) {
                UpdateSettingsScreen(contentPadding = padding)
            }
        }
    }
}
```

- [ ] **Step 3: Add first-pass screens**

Write `ConversationListScreen.kt`:

```kotlin
package com.harnessapk.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ConversationListScreen(
    contentPadding: PaddingValues,
    onOpenChat: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenUpdates: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(onClick = onOpenChat) { Text("新建会话") }
        Button(onClick = onOpenProviders) { Text("Provider 设置") }
        Button(onClick = onOpenUpdates) { Text("检查更新") }
    }
}
```

Write `ChatScreen.kt`, `ProviderSettingsScreen.kt`, and `UpdateSettingsScreen.kt` with the same `Column` shape and distinct text labels.

- [ ] **Step 4: Verify UI shell**

Run:

```bash
./gradlew :app:assembleDebug
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/harnessapk/ui app/src/main/java/com/harnessapk/MainActivity.kt
git commit -m "界面：搭建 Compose 导航外壳"
```

---

### Task 3: Local Storage Models and DAOs

**Files:**
- Create: `app/src/main/java/com/harnessapk/storage/ConversationEntity.kt`
- Create: `app/src/main/java/com/harnessapk/storage/MessageEntity.kt`
- Create: `app/src/main/java/com/harnessapk/storage/MessageAttachmentEntity.kt`
- Create: `app/src/main/java/com/harnessapk/storage/ProviderProfileEntity.kt`
- Create: `app/src/main/java/com/harnessapk/storage/ConversationDao.kt`
- Create: `app/src/main/java/com/harnessapk/storage/MessageDao.kt`
- Create: `app/src/main/java/com/harnessapk/storage/ProviderProfileDao.kt`
- Create: `app/src/main/java/com/harnessapk/storage/AppDatabase.kt`
- Create: `app/src/androidTest/java/com/harnessapk/storage/AppDatabaseTest.kt`

- [ ] **Step 1: Add entities**

Create entities exactly matching the design fields. Example for `ConversationEntity.kt`:

```kotlin
package com.harnessapk.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val defaultProviderId: String?,
    val defaultModel: String?,
    val isArchived: Boolean
)
```

Use separate files for `MessageEntity`, `MessageAttachmentEntity`, and `ProviderProfileEntity` with table names `messages`, `message_attachments`, and `provider_profiles`.

- [ ] **Step 2: Add DAOs**

Write `ConversationDao.kt`:

```kotlin
package com.harnessapk.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE isArchived = 0 ORDER BY updatedAt DESC")
    fun observeActive(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ConversationEntity)

    @Update
    suspend fun update(entity: ConversationEntity)

    @Query("UPDATE conversations SET isArchived = 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun archive(id: String, updatedAt: Long)
}
```

Add `MessageDao` with `observeForConversation(conversationId)`, `insert`, `update`, and `deleteForConversation`. Add `ProviderProfileDao` with `observeEnabled`, `findById`, `insert`, `update`, and `delete`.

- [ ] **Step 3: Add database**

Write `AppDatabase.kt`:

```kotlin
package com.harnessapk.storage

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MessageAttachmentEntity::class,
        ProviderProfileEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun providerProfileDao(): ProviderProfileDao
}
```

- [ ] **Step 4: Add database instrumentation test**

Write `AppDatabaseTest.kt`:

```kotlin
package com.harnessapk.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
    @Test
    fun storesConversation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val entity = ConversationEntity(
            id = "conversation-1",
            title = "测试会话",
            createdAt = 1L,
            updatedAt = 1L,
            defaultProviderId = null,
            defaultModel = null,
            isArchived = false
        )

        db.conversationDao().insert(entity)

        assertEquals(listOf(entity), db.conversationDao().observeActive().first())
        db.close()
    }
}
```

- [ ] **Step 5: Verify**

Run:

```bash
./gradlew :app:testDebugUnitTest
```

Expected: PASS for unit tests. Run instrumentation test on a device/emulator when available:

```bash
./gradlew :app:connectedDebugAndroidTest
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/harnessapk/storage app/src/androidTest/java/com/harnessapk/storage
git commit -m "存储：新增会话消息和 Provider 数据模型"
```

---

### Task 4: Secure API Key Storage

**Files:**
- Create: `app/src/main/java/com/harnessapk/security/ApiKeyCipher.kt`
- Create: `app/src/test/java/com/harnessapk/security/ApiKeyCipherTest.kt`

- [ ] **Step 1: Write API key cipher**

Write `ApiKeyCipher.kt`:

```kotlin
package com.harnessapk.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ApiKeyCipher(
    private val keyAlias: String = "harness_apk_provider_keys"
) {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun encrypt(plainText: String): EncryptedValue {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val bytes = cipher.doFinal(plainText.encodeToByteArray())
        return EncryptedValue(
            cipherText = bytes,
            initializationVector = cipher.iv
        )
    }

    fun decrypt(value: EncryptedValue): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, value.initializationVector)
        )
        return cipher.doFinal(value.cipherText).decodeToString()
    }

    private fun getOrCreateKey(): SecretKey {
        val existing = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

data class EncryptedValue(
    val cipherText: ByteArray,
    val initializationVector: ByteArray
)
```

- [ ] **Step 2: Add test seam**

Because JVM unit tests cannot use `AndroidKeyStore`, extract a small interface if needed during implementation:

```kotlin
interface StringCipher {
    fun encrypt(plainText: String): EncryptedValue
    fun decrypt(value: EncryptedValue): String
}
```

Make `ApiKeyCipher` implement `StringCipher`, and test provider repository logic with a fake cipher.

- [ ] **Step 3: Verify no plain API keys are persisted**

Add a repository test that inserts a ProviderProfile with an encrypted key and asserts Room only receives `apiKeyAlias`, `encryptedApiKey`, and `apiKeyIv` fields. If the entity lacks encrypted fields after Task 3, update `ProviderProfileEntity` to include:

```kotlin
val encryptedApiKey: ByteArray?,
val apiKeyIv: ByteArray?
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/harnessapk/security app/src/main/java/com/harnessapk/storage app/src/test/java/com/harnessapk/security
git commit -m "安全：使用 Keystore 加密 Provider API Key"
```

---

### Task 5: Provider Settings and Repository

**Files:**
- Create: `app/src/main/java/com/harnessapk/provider/ProviderModels.kt`
- Create: `app/src/main/java/com/harnessapk/provider/ProviderTemplates.kt`
- Create: `app/src/main/java/com/harnessapk/provider/ProviderRepository.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/provider/ProviderSettingsScreen.kt`

- [ ] **Step 1: Add provider models**

Write `ProviderModels.kt`:

```kotlin
package com.harnessapk.provider

data class ProviderProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val defaultModel: String,
    val defaultVisionModel: String?,
    val supportsVision: Boolean,
    val enabled: Boolean
)

data class ProviderDraft(
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val defaultModel: String,
    val defaultVisionModel: String?,
    val supportsVision: Boolean
)
```

- [ ] **Step 2: Add provider templates**

Write `ProviderTemplates.kt`:

```kotlin
package com.harnessapk.provider

object ProviderTemplates {
    val defaults = listOf(
        ProviderTemplate(
            name = "Kimi",
            baseUrl = "https://api.moonshot.cn/v1",
            defaultModel = "kimi-k2",
            defaultVisionModel = "moonshot-v1-8k-vision-preview",
            supportsVision = true
        ),
        ProviderTemplate(
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            defaultModel = "deepseek-chat",
            defaultVisionModel = null,
            supportsVision = false
        ),
        ProviderTemplate(
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4.1-mini",
            defaultVisionModel = "gpt-4.1-mini",
            supportsVision = true
        )
    )
}

data class ProviderTemplate(
    val name: String,
    val baseUrl: String,
    val defaultModel: String,
    val defaultVisionModel: String?,
    val supportsVision: Boolean
)
```

- [ ] **Step 3: Add repository**

Write `ProviderRepository.kt` to:

- observe enabled ProviderProfile rows;
- save ProviderDraft by encrypting API Key;
- never return plain API Key except through an explicit `getApiKey(providerId)` suspend method used by the network layer;
- validate `baseUrl` starts with `https://`.

Use this validation snippet:

```kotlin
private fun requireHttpsBaseUrl(baseUrl: String) {
    require(baseUrl.startsWith("https://")) {
        "Provider Base URL 必须使用 HTTPS"
    }
}
```

- [ ] **Step 4: Replace the first-pass Provider UI**

Implement `ProviderSettingsScreen` with:

- provider list;
- template buttons;
- text fields for name, baseUrl, API Key, text model, vision model;
- switch for vision support;
- save button;
- masked key display after save.

Keep form state in the screen for first implementation. Extract ViewModel after repository behavior is verified.

- [ ] **Step 5: Verify**

Run:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/harnessapk/provider app/src/main/java/com/harnessapk/ui/provider
git commit -m "配置：新增 Provider 管理和本地密钥保存"
```

---

### Task 6: OpenAI-Compatible Network Client

**Files:**
- Create: `app/src/main/java/com/harnessapk/network/ChatDtos.kt`
- Create: `app/src/main/java/com/harnessapk/network/OpenAiCompatibleClient.kt`
- Create: `app/src/test/java/com/harnessapk/network/OpenAiCompatibleClientTest.kt`

- [ ] **Step 1: Add DTOs**

Write serializable request/response DTOs for:

- text messages;
- image content parts;
- stream delta chunks;
- non-stream fallback response.

Use kotlinx.serialization with `@Serializable`.

- [ ] **Step 2: Add client**

`OpenAiCompatibleClient` must expose:

```kotlin
class OpenAiCompatibleClient(
    private val okHttpClient: OkHttpClient,
    private val json: Json
) {
    fun streamChat(request: ChatRequest): Flow<ChatDelta>
}
```

Implementation requirements:

- POST to `${baseUrl.trimEnd('/')}/chat/completions`.
- Use `Authorization: Bearer <apiKey>`.
- Set `stream = true`.
- Parse Server-Sent Events lines beginning with `data:`.
- Stop on `[DONE]`.
- Map non-2xx responses to a sanitized error that excludes the API Key and full prompt.

- [ ] **Step 3: Add tests**

Use MockWebServer to verify:

- request path is `/chat/completions`;
- authorization header is present;
- stream chunks are emitted in order;
- API Key is not included in thrown error message.

- [ ] **Step 4: Verify**

Run:

```bash
./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/harnessapk/network app/src/test/java/com/harnessapk/network
git commit -m "网络：实现 OpenAI 兼容聊天客户端"
```

---

### Task 7: Chat Domain and Local Multi-Session UI

**Files:**
- Create: `app/src/main/java/com/harnessapk/chat/ChatModels.kt`
- Create: `app/src/main/java/com/harnessapk/chat/ChatRepository.kt`
- Create: `app/src/main/java/com/harnessapk/chat/SendMessageUseCase.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/conversation/ConversationListScreen.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt`

- [ ] **Step 1: Add chat models**

Define:

```kotlin
enum class MessageRole { SYSTEM, USER, ASSISTANT, ERROR }
enum class MessageStatus { PENDING, STREAMING, SUCCEEDED, FAILED }
```

Add `Conversation`, `ChatMessage`, and `ChatAttachment` domain models.

- [ ] **Step 2: Add repository**

`ChatRepository` must:

- create conversation with generated UUID;
- observe active conversations;
- observe messages for conversation;
- insert user message;
- insert pending assistant message;
- append assistant text while streaming;
- mark assistant succeeded or failed;
- archive conversation.

- [ ] **Step 3: Add send use case**

`SendMessageUseCase` must:

- fail fast if no Provider is configured;
- create local user and assistant messages before network call;
- stream deltas into assistant message;
- preserve failed message with sanitized error;
- allow retry by creating a new assistant message.

- [ ] **Step 4: Implement conversation UI**

Conversation list requirements:

- show active conversations ordered by `updatedAt`;
- new conversation button;
- provider settings button;
- update settings button;
- archive/delete action.

Chat screen requirements:

- message list;
- text input;
- send button;
- disabled send state while empty or no provider;
- retry button for failed assistant messages;
- copy action for assistant text.

- [ ] **Step 5: Verify**

Run:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/harnessapk/chat app/src/main/java/com/harnessapk/ui/conversation app/src/main/java/com/harnessapk/ui/chat
git commit -m "对话：实现本地多会话聊天流程"
```

---

### Task 8: Image Input and Privacy Confirmation

**Files:**
- Modify: `app/src/main/java/com/harnessapk/chat/ChatModels.kt`
- Modify: `app/src/main/java/com/harnessapk/chat/SendMessageUseCase.kt`
- Modify: `app/src/main/java/com/harnessapk/network/ChatDtos.kt`
- Modify: `app/src/main/java/com/harnessapk/network/OpenAiCompatibleClient.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/chat/ChatScreen.kt`

- [ ] **Step 1: Add image attachment support**

Represent image attachments with:

```kotlin
data class PendingImageAttachment(
    val uri: Uri,
    val mimeType: String
)
```

Persist selected URI and MIME type in `MessageAttachmentEntity`.

- [ ] **Step 2: Add system picker**

Use Android Photo Picker through `rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia())`.

UI behavior:

- image button opens picker;
- selected image shows preview;
- send action opens privacy confirmation dialog;
- confirmation text states that the selected image will be sent to the chosen Provider.

- [ ] **Step 3: Block unsupported Provider**

Before sending an image, check `ProviderProfile.supportsVision`.

If false, show:

```text
当前 Provider 未开启图片输入，请切换支持图片的模型或移除图片。
```

- [ ] **Step 4: Build image payload**

For OpenAI-compatible providers, serialize mixed content:

```json
{
  "role": "user",
  "content": [
    {"type": "text", "text": "请看这张截图"},
    {"type": "image_url", "image_url": {"url": "data:image/png;base64,..."}}
  ]
}
```

Read image bytes only after user confirmation. Limit first version to images under 8 MB and show a user-facing error when larger.

- [ ] **Step 5: Verify**

Run:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Manual check on device:

- select an image;
- see preview;
- cancel confirmation;
- confirm and send with a Vision provider;
- attempt send with non-Vision provider and see block.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/harnessapk/chat app/src/main/java/com/harnessapk/network app/src/main/java/com/harnessapk/ui/chat
git commit -m "多模态：支持截图选择和发送确认"
```

---

### Task 9: Update Manifest, APK Download, and Installer

**Files:**
- Create: `app/src/main/java/com/harnessapk/updater/UpdateModels.kt`
- Create: `app/src/main/java/com/harnessapk/updater/UpdateRepository.kt`
- Create: `app/src/main/java/com/harnessapk/updater/ApkInstaller.kt`
- Modify: `app/src/main/java/com/harnessapk/ui/updater/UpdateSettingsScreen.kt`
- Create: `app/src/test/java/com/harnessapk/updater/UpdateRepositoryTest.kt`

- [ ] **Step 1: Add update models**

Write `UpdateModels.kt`:

```kotlin
package com.harnessapk.updater

import kotlinx.serialization.Serializable

@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val minSupportedVersionCode: Int,
    val apkUrl: String,
    val sha256: String,
    val releaseNotes: List<String>,
    val publishedAt: String
)

data class UpdateCheckResult(
    val manifest: UpdateManifest?,
    val updateAvailable: Boolean,
    val forceUpdate: Boolean
)
```

- [ ] **Step 2: Implement repository**

`UpdateRepository` must:

- fetch manifest from `BuildConfig.UPDATE_MANIFEST_URL`;
- reject non-HTTPS `apkUrl`;
- compare manifest `versionCode` with current `BuildConfig.VERSION_CODE`;
- mark force update when current version is less than `minSupportedVersionCode`;
- download APK to `cacheDir/updates/harness-apk-<versionName>.apk`;
- compute SHA-256 while streaming;
- delete APK if hash mismatch.

- [ ] **Step 3: Implement installer**

`ApkInstaller` must:

- call `PackageManager.canRequestPackageInstalls()`;
- launch `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` when permission is missing;
- use `FileProvider.getUriForFile`;
- create an `Intent(Intent.ACTION_VIEW)` with MIME type `application/vnd.android.package-archive`;
- add `Intent.FLAG_GRANT_READ_URI_PERMISSION`.

- [ ] **Step 4: Implement update UI**

Update screen must show:

- current version;
- check update button;
- latest version and release notes;
- download progress;
- checksum failure state;
- install button;
- permission guidance for unknown-app installs.

- [ ] **Step 5: Add tests**

Test:

- no update when remote version equals current;
- optional update when remote version is greater;
- force update when current is below minimum;
- SHA-256 mismatch deletes file;
- non-HTTPS APK URL is rejected.

- [ ] **Step 6: Verify**

Run:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Manual check on device:

- host local update manifest;
- point debug build to that manifest;
- download test APK;
- verify installer opens.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/harnessapk/updater app/src/main/java/com/harnessapk/ui/updater app/src/test/java/com/harnessapk/updater app/src/main/AndroidManifest.xml app/src/main/res/xml/apk_file_paths.xml
git commit -m "更新：实现自建 APK 检测下载和安装"
```

---

### Task 10: Release Hosting Assets and Documentation

**Files:**
- Create: `release/update.example.json`
- Create: `scripts/compute-sha256.sh`
- Create: `docs/release-hosting.md`

- [ ] **Step 1: Add sample manifest**

Write `release/update.example.json`:

```json
{
  "versionCode": 1,
  "versionName": "0.1.0",
  "minSupportedVersionCode": 1,
  "apkUrl": "https://download.example.com/harness-apk/releases/0.1.0/app-release.apk",
  "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "releaseNotes": [
    "首个家用测试版"
  ],
  "publishedAt": "2026-07-05T00:00:00Z"
}
```

- [ ] **Step 2: Add checksum script**

Write `scripts/compute-sha256.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <apk-path>" >&2
  exit 64
fi

shasum -a 256 "$1" | awk '{print $1}'
```

Run:

```bash
chmod +x scripts/compute-sha256.sh
```

- [ ] **Step 3: Add hosting doc**

Write `docs/release-hosting.md`:

```markdown
# Release Hosting

第一版发布物只需要静态文件托管。

推荐结构：

```text
https://download.example.com/harness-apk/update.json
https://download.example.com/harness-apk/releases/0.1.0/app-release.apk
```

可用服务：

- 阿里云 OSS + 自定义 HTTPS 域名。
- 腾讯云 COS + 自定义 HTTPS 域名。

要求：

- 不使用默认 bucket 域名分发 APK。
- manifest 和 APK 必须使用 HTTPS。
- APK 上传后运行 `scripts/compute-sha256.sh`，把结果写入生产环境的 `update.json`。
- 每个 release 目录保留历史 APK，`update.json` 只指向当前推荐版本。
- 中国内地 bucket 绑定自定义域名时，需要按云厂商要求完成 ICP 备案。
```

- [ ] **Step 4: Verify docs and script**

Run:

```bash
scripts/compute-sha256.sh release/update.example.json
```

Expected: prints a SHA-256 hash.

- [ ] **Step 5: Commit**

```bash
git add release/update.example.json scripts/compute-sha256.sh docs/release-hosting.md
git commit -m "发布：补充 APK 更新托管说明"
```

---

### Task 11: End-to-End Verification on Device

**Files:**
- Modify: the exact files implicated by failures found during verification; list each changed file in the task notes before committing.

- [ ] **Step 1: Build debug APK**

Run:

```bash
./gradlew :app:assembleDebug
```

Expected: `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 2: Install on Android device**

Run:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: install succeeds.

- [ ] **Step 3: Smoke test Provider setup**

On device:

- open Provider settings;
- save one Provider with a real API Key;
- close and reopen app;
- confirm Provider remains configured and API Key is masked.

- [ ] **Step 4: Smoke test text chat**

On device:

- create a conversation;
- send a text message;
- verify assistant response streams or appears;
- copy assistant text;
- kill and reopen app;
- verify history remains.

- [ ] **Step 5: Smoke test image chat**

On device:

- select an image;
- see privacy confirmation;
- send with Vision provider;
- verify response;
- switch to non-Vision provider and confirm send is blocked.

- [ ] **Step 6: Smoke test updater**

Prepare a manifest that points to a locally hosted or cloud-hosted test APK.

On device:

- open update screen;
- check update;
- download APK;
- verify checksum passes;
- grant unknown-app install permission if prompted;
- confirm system installer opens.

- [ ] **Step 7: Final checks**

Run:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
git status --short
```

Expected: tests pass, debug APK builds, and only intentional changes remain.

- [ ] **Step 8: Commit fixes**

```bash
git add app docs release scripts
git commit -m "验证：修复首版 Android APK 冒烟问题"
```
