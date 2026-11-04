plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kr.yunho.talkhelper"
    compileSdk = 36
    // 고급 받아쓰기(Whisper) 네이티브 라이브러리 빌드용
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "kr.yunho.talkhelper"
        minSdk = 26
        targetSdk = 36  // 구글 플레이: 2026-08-31 부터 새 앱은 API 36 필수
        // GitHub Actions 빌드 번호를 버전으로 써서, 앱이 새 버전을 알아볼 수 있게 함
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = run
        versionName = "1.$run"

        ndk {
            // 64비트 ARM 에만 Whisper 엔진이 들어가고, 나머지는 빈 라이브러리 (앱 설치는 됨)
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // 업데이트가 기존 앱 위에 덮어 설치되려면 항상 같은 키로 서명해야 함.
    // 키는 공개 저장소에 두지 않고, GitHub 비밀(SIGNING_KEY_B64)에서 빌드할 때만 꺼내 씀.
    // 플레이 스토어용 AAB 도 같은 키를 '업로드 키'로 씀 (실제 앱 서명은 구글이 관리)
    val storePath = System.getenv("SIGNING_STORE_FILE")
    if (storePath != null) {
        signingConfigs {
            getByName("debug") {
                storeFile = file(storePath)
                storePassword = "talkhelper"
                keyAlias = "talkhelper"
                keyPassword = "talkhelper"
            }
            create("upload") {
                storeFile = file(storePath)
                storePassword = "talkhelper"
                keyAlias = "talkhelper"
                keyPassword = "talkhelper"
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // github: 직접 설치용 (앱 안 업데이트 있음)
    // play:   플레이 스토어용 (스토어 정책상 스스로 업데이트하는 기능과 설치 권한을 뺌)
    flavorDimensions += "dist"
    productFlavors {
        create("github") {
            dimension = "dist"
            buildConfigField("boolean", "SELF_UPDATE", "true")
        }
        create("play") {
            dimension = "dist"
            buildConfigField("boolean", "SELF_UPDATE", "false")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (storePath != null) signingConfig = signingConfigs.getByName("upload")
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
