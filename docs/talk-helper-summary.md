# 카톡 도우미 / 말로 답장 — 전체 정리

정리일: 2026년 11월 4일 · 운영자: 윤호요셉 신부 · 문의: mernial@naver.com

> 이 문서에는 **비밀번호나 키 값을 적지 않습니다.** 키는 아래 "iCloud에 보관할 파일"에 따로 보관하세요.

---

## 1. 무엇을 만들었나

카카오톡 대화방에서 노란 **'도우미' 버튼**을 누르고 편하게 말하면, AI가 뜻을 알아듣고 고른 말투로 다듬어 **카톡으로 보내 주는** 안드로이드 앱입니다.

| 구분 | 직접 설치판 | 플레이 스토어판 |
|---|---|---|
| 앱 이름 | 카톡 도우미 (노란 아이콘) | **말로 답장** (초록 아이콘) |
| 받는 곳 | GitHub 링크 (APK) | 플레이 **내부 테스트** 링크 |
| 업데이트 | 앱 안 "🔔 새 버전" 버튼 | 플레이 스토어가 자동으로 |
| 패키지 이름 | `kr.yunho.talkhelper` (둘 다 같음 → 한 폰에 하나만 설치 가능) | |

**주요 기능**
- 💬 버튼 한 번 → 말하기 → 끝에 **"보내 줘" / "끝"** → 다듬어서 전송
- 말투 모드: 🙏 정중하게 · 😊 친구처럼 · 💼 업무 연락 · 🤗 자녀에게(부모님 말투)
- 보내는 방식: **바로** · **3초 뒤**(그 사이 취소 가능) · **확인 후**
- 받아쓰기: 휴대폰 기본 음성 인식(구글, 무료). 예전 고급 받아쓰기(Whisper)는 반응이 느려 뺐음
- **가족에게 설정 보내기**: API 키를 카톡 메시지(설정 코드)로 넘겨, 받는 쪽은 복사만 하면 설정 완료
- 카드 위치 끌어서 옮기기, 큰 글씨

---

## 2. 주소 모음

| 무엇 | 주소 |
|---|---|
| 코드 저장소 (GitHub) | https://github.com/mernial/catholic-shorts |
| 직접 설치 파일 (APK) | https://github.com/mernial/catholic-shorts/releases/download/talk-helper/talk-helper.apk |
| 플레이 업로드 파일 (AAB) | https://github.com/mernial/catholic-shorts/releases/download/talk-helper/talk-helper-play.aab |
| 최신 버전 정보 | https://github.com/mernial/catholic-shorts/releases/download/talk-helper/version.json |
| 개인정보처리방침 | https://mernial.github.io/catholic-shorts/talk-helper-privacy.html |
| 빌드 기록 (Actions) | https://github.com/mernial/catholic-shorts/actions |
| GitHub 비밀 설정 | https://github.com/mernial/catholic-shorts/settings/secrets/actions |
| Play Console | https://play.google.com/console |
| Claude Console (API 키·요금) | https://platform.claude.com |
| 플레이 등록 안내 | https://github.com/mernial/catholic-shorts/blob/main/docs/play-store/README.md |
| 내부 테스트 배포 순서 | https://github.com/mernial/catholic-shorts/blob/main/docs/play-store/internal-test.md |
| 그림 설치 설명서 (갤럭시) | https://github.com/mernial/catholic-shorts/tree/main/docs/talk-helper-guide |

---

## 3. 계정과 키 (값은 적지 않음)

| 무엇 | 쓰는 곳 | 보관 위치 | 잃어버리면 |
|---|---|---|---|
| **Claude API 키** (`sk-ant-…`) | 앱이 문장을 다듬을 때 | 휴대폰 앱 설정 · 설정 코드 메시지 · iCloud | Claude Console에서 새로 만들고 앱에 다시 넣기 |
| **앱 서명 키** (`SIGNING_KEY_B64`) | GitHub가 앱을 만들 때 서명 | GitHub 비밀 설정 · **iCloud (꼭!)** | GitHub판은 업데이트 불가(다시 설치 필요). 플레이판은 구글에 '업로드 키 재설정' 요청 |
| **플레이 서비스 계정 키** (`PLAY_SERVICE_ACCOUNT_JSON`) | 플레이 내부 테스트 자동 업로드 | GitHub 비밀 설정 · iCloud | Google Cloud에서 새 키를 만들어 다시 등록 |
| 플레이 개발자 계정 | 앱 등록·출시 | 가톨릭그램과 **같은 계정** | — |
| 문의 이메일 | 스토어·개인정보처리방침 | mernial@naver.com | — |

**GitHub 비밀 설정은 넣은 뒤 다시 꺼내 볼 수 없습니다.** 그래서 원본 파일을 iCloud에 꼭 보관해야 합니다.

---

## 4. iCloud에 보관할 파일 체크리스트

`iCloud Drive / 카톡도우미` 폴더를 만들어 아래를 넣으세요.

- [ ] **이 정리 문서** (PDF)
- [ ] **SIGNING_KEY_B64.txt** — 앱 서명 키 (가장 중요. 다른 사람에게 보내지 말 것)
- [ ] **플레이 서비스 계정 키 .json** — 만들면 바로 보관
- [ ] **Claude API 키** — 메모로 보관하거나 "카톡 도우미 설정" 메시지 내용을 저장
- [ ] **그림 설치 설명서** 8장 (부모님께 다시 보낼 때)
- [ ] 플레이 스토어용 아이콘·그래픽 (`docs/play-store/`)

키 파일들은 iCloud의 **잠긴 메모**나 **암호가 걸린 폴더**에 두면 더 안전합니다.

---

## 5. 업데이트가 흘러가는 길

1. 제가(Claude) 코드를 고쳐 GitHub에 올립니다.
2. GitHub가 자동으로 테스트하고, **APK(직접 설치판)와 AAB(플레이판)**를 만들어 Releases에 올립니다. 버전 번호는 자동으로 올라갑니다.
3. 직접 설치판: 앱을 열면 **"🔔 새 버전" → 지금 업데이트**
4. 플레이판:
   - 서비스 계정 키를 등록했다면 → **내부 테스트에 자동 업로드** → 폰이 **자동 업데이트**
   - 등록 전이라면 → Play Console → 내부 테스트 → 새 버전 만들기 → AAB 업로드 → 출시

---

## 6. 설치 요약 (플레이 내부 테스트)

1. Play Console → 말로 답장 → 테스트 및 출시 → 내부 테스트 → **테스터 목록 '가족'**에 Gmail 추가
2. **참여 링크**를 카톡으로 보냄
3. 폰: (예전 '카톡 도우미'가 있으면 삭제) → 링크 → **테스터 되기** → **설치**
4. 앱 열기 → 준비 1/3 접근성 켜기(동의 → 켜기 → 허용) → 2/3 마이크 허용 → 3/3 설정 메시지 복사
5. 카톡 → 노란 **도우미** → 말하고 **"보내 줘"**

---

## 7. 요금

- 문장 다듬기 (기본): Claude **Haiku 5.5** — 1회 약 1원, 가족 3명이 하루 10번씩 써도 한 달 약 1,000원 수준
- 대화에서 찾기: Claude **Haiku 5.5** — 1회 1~4원
- 카톡 화면 글은 최근 1,200자만 보내고, 지침은 프롬프트 캐싱으로 재사용해 요금을 줄임
- 받아쓰기: 휴대폰 기본 음성 인식 **무료**
- Claude Console → 지출 → **월 사용 한도**를 걸어 두면 그 이상 나가지 않음

---

## 8. 자주 생기는 문제

| 증상 | 해결 |
|---|---|
| 설치 시 "Play 프로텍트 앱 차단됨" (직접 설치판) | Play 스토어 → 프로필 → Play 프로텍트 → ⚙ → 앱 검사 끄기 → 설치 → 다시 켜기 |
| 접근성이 회색 "제한된 설정" (직접 설치판) | 앱 정보 → ⋮ → 제한된 설정 허용 (⋮가 없으면 자동 차단기 끄기) |
| "API 키가 올바르지 않습니다" | 키 만료·삭제 → 새 키 만들어 앱에 넣고 '가족에게 설정 보내기' 다시 |
| 노란 도우미 버튼이 안 보임 | 앱 열기 → 준비 1/3 접근성 다시 켜기 |
| 보내기가 안 됨 | 앱 첫 화면 맨 아래 '마지막 보내기 기록' 캡처 → Claude에게 전달 |
| 플레이에서 "앱을 찾을 수 없음" | 플레이 스토어 계정이 테스터 명단의 Gmail인지 확인, 잠시 후 다시 |

---

## 9. 남은 일

- [ ] 플레이 서비스 계정 키 만들기 → GitHub에 `PLAY_SERVICE_ACCOUNT_JSON` 등록 (자동 업데이트)
- [ ] 부모님 폰에 '말로 답장' 설치
- [ ] 정식 출시를 원하면: 비공개 테스트(테스터 12명 이상 × 14일, 실제 사용) → 구글 심사 → 프로덕션 신청
- [ ] 정식 출시 전 확인: 접근성 API 심사, 카카오 약관(자동 입력·전송 허용 여부), 실제 앱 스크린샷 2장 이상
- [ ] 판매를 원하면: 무료 앱 + 구독(구글 플레이 결제), 사용자별 요금 처리용 서버 필요
