# 이 저장소에서 일하는 Claude에게

- **항상 한국어로** 답하세요. 사용자는 윤호요셉 신부님이고, 앱은 부모님 같은 어르신이 쓰십니다. 쉬운 말로, 짧게.
- 비밀 값(API 키, 서명 키, 서비스 계정 키, 설정 코드)은 **절대 커밋하거나 대화에 다시 적지 마세요.** GitHub 비밀(`SIGNING_KEY_B64`, `PLAY_SERVICE_ACCOUNT_JSON`)과 사용자의 iCloud `말로답장` 폴더에만 있습니다.

## 「말로 답장」 (android/)

카카오톡 위에 뜨는 🎤 버튼으로 말하면 Claude(Haiku 5.5)가 말투를 다듬어 카톡으로 보내 주는 안드로이드 앱. 전체 기능·주소·요금·문제 해결은 `docs/talk-helper-summary.md`(PDF: `docs/말로답장_전체정리.pdf`)에 정리돼 있으니 먼저 읽으세요.

- 패키지 `kr.yunho.talkhelper`, Kotlin, AppCompat 없이 기본 Activity. 플레이버 `play`(플레이 스토어판, 지금 쓰는 것) / `github`(예전 직접 설치판).
- 주요 파일: `KakaoAccessibilityService.kt`(떠 있는 버튼·동그라미 메뉴·카톡 화면 읽기/입력/전송·대화 내보내기 자동화), `HelperActivity.kt`(카톡 위 카드), `AskActivity.kt`(대화에서 찾기·내보낸 대화 받기), `MainActivity.kt`(첫 화면, 제목을 길게 누르면 숨은 설정), `core/`(Claude 호출·프롬프트·순수 로직, JVM 단위 테스트 있음).
- 모델: 다듬기·찾기 모두 `claude-haiku-5-5` (Sonnet은 쓰지 않기로 사용자가 결정).
- 테스트: `android/app/src/test/java/kr/yunho/talkhelper/core/` (Android SDK 없이 core만 돌리는 별도 Gradle 프로젝트로 확인해 왔음).

## 빌드와 배포

- 브랜치에 push → GitHub Actions(`.github/workflows/android.yml`)가 테스트·빌드 → Releases(`talk-helper` 태그)에 APK/AAB → **플레이 내부 테스트에 자동 업로드**. 버전 = 실행 번호(1.N).
- 개인정보처리방침·설치 안내 페이지는 main 브랜치의 GitHub Pages(`talk-helper-privacy.html`, `talk-helper-install.html`). 바꾸면 main에 합쳐야 공개됨.
- 사용자는 폭넓게 권한을 주었고, 지금까지 작업 브랜치에서 PR을 만들어 main에 합쳐 왔음.

## 남은 일

- 어머니(S26) 설치: 테스터 등록은 됨, 플레이 "다운로드할 수 없습니다" — 예전 '카톡 도우미'(서명이 다른 같은 패키지, 보안 폴더 포함) 잔존 여부 확인 중.
- 정식 출시: 비공개 테스트(12명 × 14일) → 접근성 심사 → 프로덕션. 카카오 문의 답변 대기.
