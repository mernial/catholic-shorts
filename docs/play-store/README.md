# 구글 플레이 비공개 테스트 준비물과 순서

플레이 스토어판 이름은 **「말로 답장」**입니다. 다른 회사 상표인 '카톡'을 앱 이름과 아이콘에 쓰면 심사에서 거절될 수 있어서, 이름과 색을 바꿨습니다. 이름은 원하시는 대로 바꿀 수 있습니다. 바꾸려면 `android/app/src/play/res/values/strings.xml`을 수정합니다.

## 준비된 파일
| 무엇 | 어디 |
|---|---|
| 업로드할 앱 파일 (AAB) | https://github.com/mernial/catholic-shorts/releases/download/talk-helper/talk-helper-play.aab |
| 앱 아이콘 512×512 | `docs/play-store/icon-512.png` |
| 그래픽 이미지 1024×500 | `docs/play-store/feature-1024x500.png` |
| 휴대폰 스크린샷 | `docs/play-store/screenshots/` — 1장(그림). **실제 앱 화면을 2~3장 더 찍어 넣으세요** (최소 2장) |
| 개인정보처리방침 | `talk-helper-privacy.html` (아래 '개인정보처리방침 주소' 참고) |

---

## 1. 개발자 계정 만들기 (처음 한 번)
1. https://play.google.com/console 에서 **개인** 계정으로 가입합니다. 등록비는 25달러입니다.
2. 신분증 확인과 휴대폰 인증을 합니다. 며칠 걸릴 수 있습니다.
3. 개인 계정은 **정식 출시 전에 테스터 12명 이상이 14일 동안 비공개 테스트**를 해야 합니다. 이 비공개 테스트가 그 단계입니다.

## 2. 앱 만들기
- **앱 만들기** → 앱 이름 `말로 답장` · 기본 언어 `한국어` · **앱** · **무료** → 선언에 동의

## 3. 앱 설정 (대시보드의 "앱 설정" 항목들)
| 항목 | 답 |
|---|---|
| 개인정보처리방침 | 아래 '개인정보처리방침 주소' |
| 앱 액세스 권한 | "일부 기능이 제한됨" → 안내: "API 키 또는 설정 코드가 필요합니다. 테스트용 설정 코드: (심사용 키로 만든 설정 코드)" ※ 아래 '심사용 키' 참고 |
| 광고 | 광고 없음 |
| 콘텐츠 등급 | 설문: 카테고리 **유틸리티/생산성/커뮤니케이션 등**. 폭력·성·도박 모두 '아니요'. **사용자끼리 소통**: 아니요(앱이 자체 채팅을 제공하지 않음) |
| 타겟층 | **만 18세 이상** |
| 뉴스 앱 | 아니요 |
| 데이터 보안 | 아래 '데이터 보안 답변' |
| 정부 앱 | 아니요 |
| 금융 기능 | 없음 |
| 건강 | 없음 |
| **민감한 권한: 접근성 API** | 아래 '접근성 API 신고서' |

## 4. 스토어 등록정보
- **앱 이름 (30자):** `말로 답장 - 말하면 정중하게 바꿔 보내는 답장`
- **간단한 설명 (80자):** `자판이 어려워도 괜찮아요. 말로 하면 정중하게·친근하게·업무용으로 다듬어 카카오톡에 보내 드려요.`
- **자세한 설명:**
```
휴대폰 자판으로 글 쓰기가 어려운 분을 위한 답장 도우미입니다.

카카오톡 대화방에서 '도우미' 버튼을 누르고 편하게 말하세요.
"이거 토요일에 하자고 보내 줘"
→ "이 일은 토요일에 하도록 하겠습니다."

■ 이런 분께 좋아요
• 글씨가 작고 자판이 불편한 어르신
• 바쁠 때 말로 빠르게 답장하고 싶은 분
• 정중한 표현이 늘 고민되는 분

■ 말투 세 가지
• 정중하게: 어른·처음 연락하는 분께
• 친구처럼: 편하고 다정하게
• 업무 연락: 용건을 짧고 분명하게

■ 이렇게 동작해요
1. 대화방의 '도우미' 버튼을 누릅니다.
2. 하고 싶은 말을 하고 끝에 "보내 줘"라고 말합니다.
3. AI가 대화 흐름에 맞게 다듬어 보여 주고, 3초 뒤 보냅니다. 그 사이 '취소'할 수 있습니다.

■ 개인정보
• 버튼을 누른 순간 화면에 보이는 대화 글과 말한 내용만 답장을 다듬는 데 쓰며, 저장하지 않습니다.
• 답장 다듬기에는 Anthropic의 Claude AI를 사용합니다.
• 카카오톡 이외의 앱 화면은 읽지 않습니다.

※ 이 앱은 카카오와 관련이 없는 독립 앱입니다. 'Claude API 키'가 필요합니다.
```
- **카테고리:** 커뮤니케이션
- **연락처 이메일:** `mernial@naver.com`

## 5. 비공개 테스트 올리기
1. **테스트 및 출시 → 테스트 → 비공개 테스트** → **트랙 만들기**(또는 기본 'Closed testing')
2. **테스터**: 이메일 목록 만들기 → 테스터들의 **구글 계정(Gmail) 주소** 12개 이상 입력
3. **새 버전 만들기** → **Play 앱 서명**: "Google에서 앱 서명 키 관리"(권장) 그대로 → 위의 `talk-helper-play.aab` 업로드
   - 이 파일은 GitHub에 등록하신 서명 키로 서명돼 있고, 플레이에서는 이 키가 **업로드 키**가 됩니다.
4. 버전 이름은 자동으로 채워집니다. 출시 노트 예: `처음 비공개 테스트 버전입니다.`
5. **검토 시작** → 구글 심사(보통 며칠, 접근성 앱은 더 걸릴 수 있음)
6. 통과하면 테스터용 **참여 링크**가 생깁니다. 테스터는 링크 → **테스터 되기** → 플레이 스토어에서 **설치**합니다.
7. 12명 이상이 **14일 동안** 계속 설치해 둔 뒤 → **프로덕션(정식 출시) 신청**이 가능해집니다.

**주의:** 지금 직접 설치한 '카톡 도우미'(GitHub판)와 플레이판은 서명 키가 달라 같은 폰에 함께 깔 수 없습니다. 플레이판을 설치하기 전에 GitHub판을 지워 주세요.

---

## 개인정보처리방침 주소
이 저장소는 GitHub Pages로 공개되고 있어서, 작업 브랜치를 **main에 합치면** 아래 주소에서 열립니다.
`https://mernial.github.io/catholic-shorts/talk-helper-privacy.html`
합치기 전에는 그 주소가 열리지 않습니다. 정확한 주소는 저장소 Settings → Pages에서 확인하세요.

## 심사용 키
- 심사자가 앱을 써 볼 수 있어야 합니다. Claude Console에서 **심사용 API 키를 따로** 만드세요. 이름 예: `play-review`, 사용 한도를 낮게 걸어 두세요.
- 그 키로 앱의 **"가족에게 설정 보내기"** 코드를 만들어 '앱 액세스 권한' 안내에 붙여 넣습니다.
- 심사가 끝나면 그 키를 삭제하세요.

## 데이터 보안 답변
| 질문 | 답 |
|---|---|
| 데이터를 수집하거나 공유하나요? | **예** |
| 전송 중 암호화 | **예** (HTTPS) |
| 삭제 요청 방법 | 저장하지 않음. 앱 삭제 시 설정도 삭제 |
| 수집 항목 | **메시지 → 기타 앱 내 메시지**: 수집 **아니요(일시적 처리)** / **공유 예**(Anthropic, 앱 기능을 위해). 목적: **앱 기능** |
| | **오디오**: 아니요. 음성은 휴대폰 음성 인식이 글로 바꾸고, 글만 위 '메시지'에 포함됩니다 |
| | 위치·연락처·사진·기기 ID 등: 모두 아니요 |
| 처리 방식 | **일시적으로만 처리**(ephemeral), 필수(선택 불가) |

## 접근성 API 신고서
Play Console → 앱 콘텐츠 → **민감한 권한 및 API** → 접근성 API.

- **isAccessibilityTool:** 사용 안 함. 장애인 보조 도구로 신청하지 않습니다.
- **앱 안 공개와 동의:** 있음. 첫 화면의 '설정 열기'를 누르면 「접근성 권한 사용 안내」 창이 뜨고, **동의해야** 설정으로 이동합니다.
- **사용 목적 (영문 신고 예시):**
```
The app helps users who have difficulty typing (e.g., elderly users) reply in KakaoTalk by voice.
The AccessibilityService is used only when the user taps the app's floating button inside KakaoTalk:
(1) to show the floating button while KakaoTalk is in the foreground,
(2) to read the text currently visible on the KakaoTalk chat screen at the moment of the tap, so the reply fits the conversation,
(3) to type the reply the user dictated and confirmed into the KakaoTalk input box and press its send button.
Every action is initiated by the user (tap + speech), the drafted message is shown before sending with a cancel option,
and the app never acts on its own. It does not read other apps. Screen text and the user's words are sent to the Anthropic Claude API
only to rewrite the reply and are not stored. A prominent disclosure and consent dialog is shown before enabling the service.
```
- **영상 (필수, 30초~1분, 유튜브 미등록 링크):**
  1. 앱 첫 화면 → '설정 열기' → **공개·동의 창**이 보이게 → 동의
  2. 접근성에서 켜기
  3. 카카오톡 대화방 → '도우미' 버튼 → 말하기 "내일 회의 3시로 하자고 보내 줘"
  4. 다듬은 문장, 3초 카운트다운과 '취소' 버튼이 보이게 → 전송되는 장면

## 심사에서 거절될 수 있는 부분
- 접근성 API로 **다른 앱(카카오톡)에 입력하고 전송**하는 기능 자체가 거절될 수 있습니다. 거절 사유를 보내 주시면 고쳐서 다시 신청하겠습니다.
- 그래도 안 되면 대안이 있습니다. 카카오톡 **알림의 '답장' 기능**으로 보내는 방식이라 접근성이 필요 없습니다. 대신 카톡 위 버튼과 화면 읽기가 빠집니다.
- **카카오 약관**에서 자동 입력·전송을 허용하는지는 별도로 확인이 필요합니다.
