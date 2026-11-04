plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kr.yunho.talkhelper"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.yunho.talkhelper"
        minSdk = 26
        targetSdk = 35
        // GitHub Actions 빌드 번호를 버전으로 써서, 앱이 새 버전을 알아볼 수 있게 함
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = run
        versionName = "1.$run"
    }

    // 업데이트가 기존 앱 위에 덮어 설치되려면 항상 같은 키로 서명해야 함.
    // 키는 공개 저장소에 두지 않고, GitHub 비밀(SIGNING_KEY_B64)에서 빌드할 때만 꺼내 씀.
    val storePath = System.getenv("SIGNING_STORE_FILE")
    if (storePath != null) {
        signingConfigs {
            getByName("debug") {
                storeFile = file(storePath)
                storePassword = "talkhelper"
                keyAlias = "talkhelper"
                keyPassword = "talkhelper"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        // Claude SDK 가 쓰는 java.time 등 최신 Java API 를 오래된 안드로이드에서도 쓰도록
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/*.kotlin_module", "META-INF/versions/**", "META-INF/INDEX.LIST",
            )
        }
    }
}

dependencies {
    implementation("com.anthropic:anthropic-java:3.2.0")
    testImplementation("junit:junit:4.13.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
}
