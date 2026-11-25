# 3.0.0 Java 전환 4단계 설계 (설정 바인딩 · 두 포트 웹 · 보안 · MCP 도구 · 관리자 API · UI · 연동)

상태: 설계 확정본(U0)에 2026-11-21 독립 검토(`cb8ff00` 기준, 발견 N1~N21)와 같은 날 사용자 결정·리드 판정을 반영한 개정판(U0r). 구현 단위 U2~U9와 이 개정이 더한 U18~U21의 담당은 이 문서를 기준으로 삼는다. 계약 기준본 개정(U1·U0r)은 B-1절의 변경 목록과 같다. 검토 항목별 반영 위치는 F.1절 표, 마이그레이션 번호는 F.2절, 단위별 쓰기 범위는 F.3절, 운영체제별 지원 표는 F.4절, 결정 반영 위치는 F.5절, 미정 항목은 F.6절에 있다.
기준: 브랜치 `feature/v300-stage4-contract`(`develop` `f3a0efe`에서 분기). 형식 본보기는 `stage3-design.md`(이 문서에서 `S3`)다.

## 0. 읽는 법

### 0.1 표기
- `[사실]` 직접 읽은 것이다. 근거는 `파일:줄` 또는 클래스 이름이다. 2.x 경로는 저장소 루트 기준(태그 `v2.4.2`, `a44c4d1`), Java 경로는 `java/` 기준이다.
- `[결정]` 이 문서가 정한 것이다. 근거를 같은 줄이나 바로 아래에 적는다.
- `[미확인]` 공식 문서·소스로 확인하지 못한 것이다. 각 항목은 그것을 확인하는 사례 ID를 함께 적는다. 구현 담당은 그 사례를 먼저 시험으로 만들고, 결과가 다르면 이 문서를 고치는 변경을 같은 단위에 넣는다.
- 패키지 접두는 `io.github.jaeseongs95.portal`이고 본문에서는 `web.…`·`app.…`·`server.…`로 줄인다.
- 제품 설계 기준: 저장소는 공개이고, 제품은 불특정 사용자가 자기 호스트에 설치한다. 특정 클라우드·터널·DNS 업체는 기본값·설계 근거가 아니다. 업체 API 연동은 벤더 중립 포트 뒤의 선택 구현이다.
- 사례 ID는 `S4-<영역>-<번호>`다. 영역: ST 설정, AS 조립, WS 웹 골격, ER 오류 표현, HE health, SE 보안, DT 기기 토큰, MC MCP 도구, SR SOURCE 실행, AD 관리자 API, RC 소유자 정리, UI 화면, RK 위험 평가·자동화·비밀, RL 릴리스·환경 편집·선택 어댑터, RS 재시작·정지, AT 작업 본문 첨부, OS Windows 대상 보완, IN 설치·자동 시작, CB 계약 기준본 대조.

### 0.2 읽은 입력
- 계획 정본(4단계 절, 2026-11-15 결정 1~7, Q2 보완), `contract-baseline.md` 전체, `feature-map.md`(4.1 판정 C2, 포털 웹·서버·클라이언트 행), `stage3-design.md` 11절, `foundation-cases.md` 4절(`PortsSpikeTest` Q-01~Q-07), `official-docs-spring.md` Q1.
- 기준본 JSON 전부(`contract/mcp/**`, `http/admin-routes.json`, `settings/settings-contract.json`, `hooks/**`)와 이를 읽는 시험 세 개(`ContractRecordsStructureTest`, `ErrorCodeBaselineTest`, `SettingsVectors`).
- Java: `server.assembly.*` 전부, `app.execution.{OperationRunner,CommandOperationRunner}`, `app.admission.AdmissionGate`, `app.artifact.ArtifactRefusal`, `app.target.TargetPlatform`, `domain.operation.{Operation,ReconciliationEvidence,CompletionEvidence,SourceInput}`, `domain.schedule.ConcurrencyLimits`, `domain.contract.{ContractVersion,Envelope,GuiDescriptor,GuiOpenRequest,WireFormat}`, `domain.error.DesktopErrorCode`.
- 2.x: `src/application/desktop.mjs:12-35`(open 요청 키 발급·검증), `bridge.mjs:12-15`(감사·전송 경로 기본값), `scripts/portal-files.mjs`(클라이언트가 부르는 경로).
- 개정(U0r, 2026-11-21)이 더 읽은 것: 2.x `src/adapters/http/opaque-file-api.mjs`·`file-api.mjs`·`protocol.mjs`(경로·정적 파일·다운로드)·`src/adapters/security/admin-policy.mjs`·`src/adapters/presentation/capabilities.mjs`(티켓)·`src/adapters/cli/{portal-client,opaque-files-client,owner-http-session}.mjs`(클라이언트가 부르는 경로)·`bridge.mjs`(바인딩·종료), 2.x 문서 `docs/{mcp-tools,environment-editor,desktop,windows-targets}.ko.md`와 2.x의 HTTPS 안내 문서 두 개(`docs/*-https.ko.md`). Java: `app.audit.*`, `domain.operation.{Operation,CompletionEvidence,ReconciliationEvidence,RecordedResult,SourceInput,CommandInput}`, `store.journal.{OperationMapper,JdbcRestartRecovery}`, `db/expand/V1~V3`·`V8`(fix-store-domain 브랜치), `app.target.TargetPlatform`, `ssh.platform.{WindowsTargetPlatform,LinuxTargetPlatform,PythonSourceReceiver}`, `app.storage.PrivateStorage`, `domain.error.{DesktopErrorCode,DispatchReason,DispatchStage}`, `migration-runner.md`, `stage3-design.md` 4.4절. 공식 문서: Commons Daemon procrun, systemd.service, Microsoft `SERVICE_FAILURE_ACTIONS_FLAG`·`icacls`·`taskkill`·Run 키, Spring Boot 「Running Behind a Front-end Proxy Server」(각 URL은 쓴 절에 적었다).

### 0.3 2026-11-21 사용자 결정(이 문서가 따르는 것)
| 항목 | 사용자 선택(원문) | 이 문서의 반영 |
|---|---|---|
| 관리 화면 로그인 | 「OIDC + 토큰 (Recommended)」 | U4: 로그인은 OIDC(Spring Security OAuth2 Login)와 고정 관리자 토큰 두 가지다 |
| OAuth 로그인 제한 | 「계정 1개만 허용 (Recommended)」 | 설정에 적은 소유자 계정 하나(제공자의 사용자 식별자 `sub`)만 로그인된다. 같은 제공자의 다른 계정은 거절하고 감사에 남긴다. 키 `BRIDGE_OIDC_OWNER_SUBJECT`(B-1) |
| passkey | 「passkey는 전부 제거 유지」 | 로그인·승인 모두 passkey가 없다. WebAuthn 라이브러리를 들이지 않는다 |
| 고위험 승인의 추가 확인 | 「로그인 세션만 (추가 확인 없음)」 | 승인·거부·정리 같은 고위험 요청은 허용 계정의 로그인 세션 + CSRF 토큰 + Origin 검사로 받는다. 잔여 위험은 C절 |
| 라이브러리 | 「Spring Security 스타터, Playwright for Java, Commons Daemon procrun, Spring Boot Actuator」 | D절 「승인 기록」. `/health`는 Actuator `HealthIndicator`로 낸다(개발원칙 3.6) |
| RFB 클라이언트 | 라이브러리 `io.xpipe:vernacular` 1.21 도입(소유자 결정, 2026-11-21) | C.1절 제약과 D.1절 승인 기록. 데스크톱 단위(이 문서 범위 밖)의 입력이다 |

### 0.3a 2026-11-21 검토 뒤 사용자 결정(객관식 답 원문)
| 번호 | 사용자 선택(원문) | 이 문서의 반영 |
|---|---|---|
| U-1 | 「Lax로 변경」(세션 쿠키 SameSite) | B-4: `SameSite=Lax`. 변경 요청은 POST + CSRF 헤더 + Origin 검사로 계속 막는다(C.2). 검토 N4 |
| U-2 | 「묶어서 감사 기록」(로그인 실패) | B-4a·B-12: 같은 출처의 연속 실패를 한 행(횟수·처음·마지막 시각)으로 감사 표에 남기고 속도 제한을 함께 둔다. 감사 표에 고정 한도를 두거나 이력을 지우지 않는다. 검토 N9 |
| U-3 | 「RDP까지 지원」(포털 서버가 Windows일 때 원격 데스크톱) | C.3·F.4: 「Windows 서버는 데스크톱 전부 미지원」 문장은 폐기한다. RFB는 서버 OS와 무관하게 지원하고, RDP는 지원 대상이나 구현 방식은 조사 뒤 확정한다(미정) |
| U-4 | 「둘 다 허용」(Windows 대상의 `icacls`·`taskkill` 고정 명령) | B-16: 대상 호스트에서 SSH exec로 실행하는 고정 명령이다. 포털 본체의 로컬 프로세스 실행 금지 원칙은 그대로다 |
| U-5 | 「Debian·RHEL 계열까지」(Linux 설치 스크립트) | F.4·B-17: 패키지 관리자(apt, dnf)를 구분하고 CI에 배포판별 설치 시험과 arm64 시험을 더한다(U21) |
| U-6 | 「라이브러리 조사 후 재질문」(ssh-agent 인증) | C.4: 미정. 이 문서는 방식을 정하지 않는다 |

### 0.3b 리드 판정(사용자 결정이 아니라 확정 규칙에서 도출한 것)
| 번호 | 판정 | 도출 근거(사용자 확정 규칙) | 반영 |
|---|---|---|---|
| L-1 | staged UUID 흐름 유지, 3.0 staging 경로는 `owner-or-device` | 「굳이 편의기능 있는건 빼지마」 | B-2a, 기준본 `/api/artifacts` 4항목 복원 |
| L-2 | `download`를 approve·deny와 분리해 `owner-or-device`, approve·deny는 `owner` | 같은 규칙과 「에이전트 자기 승인 금지」 | B-2, 기준본 |
| L-3 | `/downloads`는 2.x처럼 capability 티켓만으로 허용 | 같은 규칙 | B-2b |
| L-4 | OIDC `redirect-uri`는 `BRIDGE_ADMIN_ORIGIN`에서 만들고 전달 헤더는 기본으로 신뢰하지 않음. 앱 내 TLS는 2.x에 없었다 | 2.x 소스 확인 | B-8a |
| L-5 | V8 이후 마이그레이션 번호표 | 브랜치 충돌 해소 | F.2 |
| L-6 | OwnerConfirmed·감사·desktop·files 멱등 기록의 저장 방식 | 설계 요구 | B-10, B-12, B-13, B-14 |
| L-7 | 작업 본문 열을 첨부 메타 표 + 파일로 | 「본문은 DB 열 금지」 | B-15 |
| L-8 | 설정 반영 재시작·서비스 정지의 OS 중립 계약 | 「운영체제에 종속적인 기능들 있으면 싹다 고쳐줘」 | B-11 |
| L-9 | SOURCE 런타임, Windows 명령 분류, PC 클라이언트 자동 시작, 지원 파일 시스템 | 같은 규칙 | B-3, B-16, B-17 |

### 0.4 바로잡은 전제
이 문서는 아래 전제를 쓰지 않는다. 계획 정본은 저장소 밖 문서라 여기서 고치지 않고, 저장소 안 문서는 이 단위에서 고쳤다.

| 위치 | 옛 전제 | 바로잡은 것 | 근거 |
|---|---|---|---|
| 계획 정본 「로그인은 OIDC·passkey·token 중 하나, 승인은 passkey」(4단계 보안 절과 Q2 보완) | 승인용 passkey, WebAuthn 라이브러리 | passkey 없음. 로그인은 OIDC·토큰, 승인은 로그인 세션 | 0.3절 사용자 결정 |
| 계획 정본 Q2 보완 「OIDC 소유자 이메일 1개」 | 이메일 일치로 소유자 판정 | 제공자 사용자 식별자(`sub`) 1개 일치. 이메일 키는 없앤다 | 0.3절 「계정 1개만 허용」 원문 설명 「제공자의 사용자 식별자」 |
| `feature-map.md` `src/adapters/security/admin-access.mjs` 행 | `AccessJwtAuthMethod`, `AdminAuthMode`에 access | 행의 Java 대상은 `AdminAuthMethod`(token·oidc)만. `AccessJwtAuthMethod` 없음 | 2026-11-15 결정 1(`access` 모드 제거) |
| `feature-map.md` `scripts/cloudflare_environment.py` 행, 기준본 `settings-contract.json` | `BRIDGE_ACCESS_*` 연결·복원, 허용 키 3개 | 행은 삭제로, 키 3개는 기준본에서 뺀다(B-1.6) | 같은 결정 |
| `feature-map.md` `scripts/deploy180.mjs` 행 | `cli.UpgradeCommand`가 portal-migrate `LegacyImportRunner`를 부름 | 본체는 migrate를 부르지 않는다(B-6) | 2026-11-15 결정 2 「본체는 의존하지 않음」 |
| `feature-map.md` Q1 행 「Access JWT 검증 모드는 유지된다」 | access 모드 유지 | 유지하지 않는다 | 2026-11-15 결정 1 |
| `feature-map.md` portal-web `HealthController` | 손으로 만든 health 컨트롤러 | Actuator `HealthIndicator`(B-5) | 0.3절 Actuator 승인, 개발원칙 3.6 |
| 기준본 `contractVersion` `"2.0"`, `desktop-open`의 `requestKey`(서버 발급, 선택) | 2.x 계약 판 | 계약 판 `"3.0"`, `desktop-open`은 호출자 `operationKey` 필수(B-1.1·B-1.3) | SSH_PORTAL-D-0112, 2026-11-15 결정 1 |
| 계획 정본 「Windows 서버(포털을 Windows에 설치)에서는 데스크톱 전부 미지원」(2026-11-15 이전 계획 문장) | Windows 서버 데스크톱 전부 제외 | RFB(붙인 VNC 서버, 원격 Linux의 Xvnc)는 서버 OS와 무관하게 지원한다. RDP는 지원 대상이고 구현 방식은 미정(C.3) | U-3 「RDP까지 지원」 |
| B-1.5(cb8ff00)의 「`/api/artifacts` 4항목 삭제」 | 3.0에서 staged UUID 경로 삭제 | 복원한다(`owner-or-device`). MCP `files`가 쓰는 UUID를 만들 경로가 없어지기 때문이다(B-2a) | 검토 N1, L-1 |
| `feature-map.md` 299행의 `OpaqueTokenIntrospector`, 296행·322행의 `/api/session/start`·`logout`, 298행의 JWKS·소유자 이메일 검증 | 폐기된 설계가 남은 행 | 이 단위에서 고쳤다(F.1 N19) | 검토 N19 |
| B-4의 「CSRF 토큰은 `GET /api/session`의 응답에서 받는다」(익명에게도 응답) | 익명 요청이 메모리 세션을 만듦 | 익명은 세션도 CSRF 토큰도 받지 않는다(B-4) | 검토 N15 |

## 1. 단위와 순서

| 단위 | 맡는 것 | 앞선 단위 |
|---|---|---|
| U2 | 설정 바인딩(`BridgeProperties`)·옛 키 가드·서버 조립(시험용 기본값 교체)·감사 모델과 DB 표 | U1 |
| U3 | 두 포트 웹 골격(부모+자식 컨텍스트)·공통 오류 변환·`/health` | U2 |
| U4 | 보안: 로그인(OIDC·토큰)·MCP bearer·기기 토큰·브라우저 세션·CSRF·Origin·로그인 속도 제한 | U3 |
| U5 | MCP 도구 24개·SOURCE 실행기·desktop 키·lease 기록·files 키 멱등 기록 | U4 |
| U6 | 관리자 HTTP API(기기 토큰 관리·staged UUID·소유자 정리와 OwnerConfirmed 증거 저장 포함) | U4 |
| U7 | 정적 관리 UI·MCP Apps 카드·AGPL 소스 안내 | U6(화면이 부르는 API) |
| U8 | 위험 평가(Jev)·명령 자동화·비밀 해석기 | U3 |
| U9 | 릴리스 카탈로그·환경 편집·선택 어댑터 | U4(환경 편집 API의 인증), U19(재시작 계약) |
| U18 | 작업 본문을 첨부 메타 표 + 파일로 옮김(B-15) | U5·U6(마이그레이션 `V11`~`V13` 뒤, F.2절. 같은 `operation_*` 표를 바꾸기 때문) |
| U19 | 재시작·정지 계약과 서비스 진입점(systemd, procrun)(B-11) | U3 |
| U20 | Windows 대상 보완: `icacls`·`taskkill`·명령 분류·SOURCE 런타임의 Windows 쪽(B-16) | 3단계 portal-ssh |
| U21 | 설치 스크립트·PC 클라이언트 자동 시작·배포판·arm64 CI(B-17) | U19 |

U5·U6·U8은 U4 뒤에 병렬로 할 수 있다. U18~U21은 이 개정(U0r)이 더한 단위이고 번호는 이 문서 안에서만 쓴다(계획 정본의 U10~U17과 겹치지 않게 18부터 붙였다. 리드가 계획 정본의 번호로 바꿔도 내용은 같다). 데스크톱 단위(RFB·RDP, C.1·C.3)는 이 문서 범위 밖이다. 쓰기 범위는 패키지로 나눈다(A절 각 단위의 「쓰기 범위」와 F.3절의 표). 공유 파일(`settings.gradle.kts`, 루트 `build.gradle.kts`, `portal-arch-checks`, 기준본 JSON)은 리드가 소유하고, 단위가 기준본을 바꿀 때는 8절 규칙을 따른다.

의존 방향(모든 단위 공통, `foundation-cases.md` A-DIR): `domain ← app ← (store, ssh, desktop, web) ← server`. 새 포트는 `app`에, 어댑터는 `store`·`web`·`server`에 둔다. `web`은 `store`·`ssh`를 보지 않는다. Actuator `HealthIndicator`는 `app` 포트를 읽는 어댑터라 `server`(조립 모듈)에 둔다.

## A. 단위별 설계

### A.1 U2 설정 바인딩·서버 조립
목표: `server.assembly.AssemblyDefaults`의 시험용 기본값을 3.0 설정 계약(B-1.6)의 바인딩으로 바꾼다. S3 11.4 「설정 바인딩」 항목을 닫는다.

- `server.config.BridgeProperties` `[결정]`: `@ConfigurationProperties(prefix = "bridge")` `@Validated` record. 환경 변수 이름의 밑줄마다 점이 되도록 중첩한다(`BRIDGE_MCP_TOKEN` → `bridge.mcp.token`, `BRIDGE_ADMIN_AUTH_MODE` → `bridge.admin.auth.mode`). 근거: 계획 정본 「env 이름」(Boot relaxed binding은 대시를 지우므로 중첩으로 맞춘다).
  - 하위 record 예: `Mcp(port, token)`, `Admin(port, token, origin, Auth(mode))`, `Oidc(issuer, Client(id, secret), Owner(subject))`, `Ui(enabled, Tool(prefix), Ticket(key))`, `Data(dir)`, `Config(path)`, `Transfer(maxBytes→max.bytes …)`, `Source(url)`, `Jev(…)`, `Vault(…)`.
  - 비밀 값(`*_TOKEN`, `*_KEY`, `*_SECRET`)은 문자열이 아니라 기존 `SecretValue`로 받는다(로그·`toString`에 값이 나오지 않음).
- `server.config.BridgeTargetsConverter`: `BRIDGE_TARGETS`(JSON)를 받는 `@ConfigurationPropertiesBinding` 변환기 하나. Windows 대상의 Python 해석기 경로(`pythonExecutable`)도 여기서 받는다(B-3).
- `server.config.SecretEnvFileLoader`(`EnvironmentPostProcessor`): 비밀 env 파일을 읽는다(feature-map 이름 그대로).
- `server.config.LegacyKeyGuard`(`EnvironmentPostProcessor`, `SecretEnvFileLoader` 뒤에 실행) `[결정]`(검토 N16, 사례 S4-ST-04): 환경과 읽어 들인 env 파일에서 접두 `BRIDGE_`를 가진 키를 모두 모아 기준본 `settings-contract.json`의 `allowedKeys`와 `removedKeys`로 가른다. 허용 키 밖이면 시작을 멈추고, `removedKeys`에 있으면 그 이유(교체할 키)와 업그레이드 안내의 절 이름을 메시지에 넣는다. 값은 메시지에 넣지 않는다.
  - 왜 `@ConfigurationProperties(ignoreUnknownFields = false)`로 하지 않는가: 환경 변수 이름은 `BRIDGE_FOO`처럼 점 표기와 1대1이 아니어서 Boot의 바인더는 환경 변수 쪽 알 수 없는 키를 알려 주지 않는다(`ignoreUnknownFields`는 `bridge.*` 속성 소스의 키에만 닿는다). 그래서 허용 목록 대조를 명시적인 가드가 맡는다(S4-ST-04가 두 경로 모두 고정한다).
  - 허용·제거 목록의 정본은 제품의 main 자원 `settings/settings-keys.json`(기준본 `allowedKeys`·`removedKeys`와 같은 내용)이고, S4-CB-05가 기준본과의 차이 0을 단정한다. 가드는 이 자원만 읽는다.
  - `JEV_API_KEY`는 `BRIDGE_` 접두가 없는 비밀 파일 키라 가드의 대상이 아니다(비밀 파일 키로 따로 대조).
- `server.assembly.SettingsAssembly`: `BridgeProperties`를 `SshSettings`, `ConcurrencyLimits`, `AdmissionLimits`, `ApprovalPolicy`, `ExecutionLimits`, `StorageSettings`, `ConnectionFiles`, `TargetSource`, `ConfiguredTargets`로 옮기는 `@Bean` 모음. `AssemblyDefaults`는 지운다(시험용 값은 시험 소스로 옮긴다).
  - 데이터 폴더 `[결정]`: `BRIDGE_DATA_DIR` 하나 아래에 H2 파일(`portal`), `artifacts/`, `outputs/`, `attachments/`(작업 본문, B-15), `known_hosts`를 둔다. 2.x의 `BRIDGE_AUDIT_PATH`·`BRIDGE_TRANSFER_ROOT`는 없앤다(B-1.6). 근거: 감사는 DB 표(2026-11-15 결정 5)라 파일 경로가 필요 없고, 저장 위치를 한 곳으로 모으면 백업·이전·권한 검사가 한 폴더로 끝난다(S3 D3-10 「기본은 데이터 디렉터리 아래」와 같은 방향). 기본값은 없다(설치 명령이 쓴다, `StorageSettings` 주석과 같은 원칙).
  - DB: `spring.datasource.url`이 비어 있으면 `jdbc:h2:file:<dataDir>/portal`. 다른 DBMS는 Boot 표준 키(`SPRING_DATASOURCE_*`)로 준다(새 `BRIDGE_*` 키를 만들지 않는다).
- 감사 DB 표 `[결정]`: `store.audit.JdbcAuditLog`(`AuditLog` 구현)와 `db/expand/V9__audit_record.sql`(번호는 F.2절. `origin/feature/v300-fix-store-domain`이 `V8__budget_gate.sql`을 이미 쓰므로 V8이 아니다). 추가만 하고, 커밋 뒤 반환하며, 읽기·수정·삭제 경로가 없고, 고정 한도가 없다. 비밀·명령 원문·본문은 열에 넣지 않는다(본문은 DB 열 금지 기준). `JsonlAuditLog`·`AuditFile`·`AuditFileLocation`은 지운다. 작업 id가 없는 사건(`ADMIN_LOGIN_REFUSED`, `DEVICE_TOKEN_*`, `files stat`의 상관 키)을 담도록 `AuditEvent`·`AuditAction`을 일반화한다(B-12). 같은 출처의 연속 로그인 실패를 한 행으로 묶는 표 `audit_refusal_streak`도 같은 마이그레이션에 둔다(B-12, U-2).
- 저장 위치 검사 `[결정]`(지원 파일 시스템, B-17): `StorageSettings`를 만들 때 데이터 폴더의 파일 저장소가 `posix` 또는 `acl` 보기를 지원하는지(`PrivateStorages`, 이미 있음) 확인하고, 지원하지 않으면 시작을 멈추며 저장소 종류·부족한 보기·허용되는 종류를 메시지에 적는다.
- 쓰기 범위: `portal-server/src/**/server/{config,assembly}/**`, `portal-server/src/main/resources/settings/**`, `portal-app/src/**/app/audit/**`(검토 N8: 어느 단위의 범위에도 없던 패키지), `portal-store/src/**/store/audit/**`, `portal-store/src/main/resources/db/expand/V9__audit_record.sql`.
- 완료 사례: S4-ST-01~06, S4-AS-01~06, S4-CB-05.

### A.2 U3 두 포트 웹 골격·공통 오류 변환·`/health`
- `server.PortalServerApplication` `[결정]`: 부모 컨텍스트는 비웹(`WebApplicationType.NONE`)이고 domain·app·store·ssh·desktop 빈을 둔다. 자식 웹 컨텍스트 두 개를 `SpringApplicationBuilder.child(…)`로 띄운다(B-8).
- `server.McpWebConfiguration`(자식 1): `bridge.mcp.port`, Spring AI MCP 서버(webmvc), MCP 보안 사슬, Actuator health.
- `server.AdminWebConfiguration`(자식 2): `bridge.admin.port`, 관리자 MVC 컨트롤러, 정적 UI, 관리자 보안 사슬. Actuator 자동 구성은 제외한다.
- 자식마다 자동 구성을 명시적으로 켜고 끈다 `[결정]`(검토 N14): 두 자식과 부모의 자동 구성 모음이 같은 클래스 경로에서 같이 켜지면 MCP 서버(`McpServerAutoConfiguration` 계열)와 OAuth2 client(`OAuth2ClientAutoConfiguration` 계열)가 반대쪽 자식에서도 생길 수 있다. 그래서 어느 구성 클래스도 `@EnableAutoConfiguration`을 쓰지 않고, 자식 구성 클래스가 필요한 자동 구성만 `@ImportAutoConfiguration`으로 나열한다. MCP 자식은 MCP 서버·Actuator health·Security만, 관리자 자식은 MVC·Security·(OIDC 모드일 때) OAuth2 client만 나열한다. 반대쪽 것은 각 자식의 속성(`SpringApplicationBuilder.child(…).properties(…)`)에 `spring.autoconfigure.exclude`로도 적어 이중으로 막는다. 부모는 비웹이라 웹·보안·MCP 자동 구성을 나열하지 않는다.
  - 시험: S4-WS-01(포트별 404)에 더해 S4-WS-06(관리자 포트의 `POST /mcp`·MCP 포트의 `/oauth2/authorization/oidc`·`/login/**`·`/logout`이 404이고, 관리자 자식 컨텍스트에 `McpSyncServer` 계열 빈이 없고, MCP 자식 컨텍스트에 `ClientRegistrationRepository`·`SecurityFilterChain`(관리자용)이 없음). 이 시험이 구성 한 곳을 바꿔도 반대쪽이 켜지지 않음을 지킨다.
- `web.presentation.RefusalResult`, `web.presentation.ResultPresenter`, `web.presentation.HttpStatusTable`, `web.presentation.AdminExceptionHandler`(`ResponseEntityExceptionHandler` 하나): B-7.
- `server.health.StorageHealthIndicator`(DB 연결·데이터 폴더 쓰기 가능), `server.health.AdmissionHealthIndicator`(유지보수 창이 닫혀 있지 않음). B-5.
- 쓰기 범위: `portal-server/src/**/server/{PortalServerApplication.java,McpWebConfiguration.java,AdminWebConfiguration.java,health/**}`, `portal-web/src/**/web/presentation/**`, `portal-server/build.gradle.kts`(Actuator), `portal-web/build.gradle.kts`.
- 완료 사례: S4-WS-01~06, S4-ER-01~04, S4-HE-01~04.

### A.3 U4 보안
- `web.security.AdminAuthMode`(`TOKEN`, `OIDC`), `web.security.AdminAuthMethod`(interface: `mode()`와 그 방식이 거는 보안 설정), 구현 `TokenAdminAuthMethod`, `OidcAdminAuthMethod`. 설정 모드와 `mode()`가 같은 구현을 `List<AdminAuthMethod>`에서 고른다. 공통 코드에 모드 `if`·`switch`를 두지 않는다(feature-map 메인 판정 유지). feature-map의 `OidcJwtAuthMethod`(2.x 프록시 JWT 검증)와 `OidcLoginConfig`는 `OidcAdminAuthMethod` 하나로 합친다. 2.x는 앞단 프록시가 준 JWT를 검증했으나 3.0은 앱이 직접 OAuth2 Login을 한다(Q2 「Spring Security 로그인」).
- `web.security.OwnerSubjectPolicy`: OIDC 로그인 결과의 `iss`가 `BRIDGE_OIDC_ISSUER`와 같고 `sub`가 `BRIDGE_OIDC_OWNER_SUBJECT`와 정확히 같을 때만 소유자 권한을 준다. 다르면 세션을 버리고 403 화면으로 보내며 감사 `ADMIN_LOGIN_REFUSED`(issuer·subject, 비밀 없음)를 남긴다. 설정에 쉼표나 공백이 든 값(여러 계정)은 시작 단계에서 오류로 멈춘다.
- OIDC 등록 정보 `[결정]`(검토 N5, 결정 L-4): `ClientRegistration`은 `ClientRegistrations.fromIssuerLocation(BRIDGE_OIDC_ISSUER)`(발급자 discovery)로 만들고 `redirectUri`는 `BRIDGE_ADMIN_ORIGIN + "/login/oauth2/code/oidc"`로 못 박는다. Spring Security의 기본값 `{baseUrl}`은 요청의 `Host`·전달 헤더에서 나오므로 쓰지 않는다(B-8a). 범위는 `openid`만 요청하고 이메일·프로필은 요청하지 않는다(소유자 판정은 `sub`).
- `web.security.TokenLoginFilter`: `POST /login/token`(폼 필드 `token`)이 `BRIDGE_ADMIN_TOKEN`과 상수 시간 비교로 같으면 세션을 만든다. CSRF에서는 빼고(세션이 아직 없다) `Origin == BRIDGE_ADMIN_ORIGIN`을 요구한다. 틀린 토큰은 세션을 만들지 않는다.
- `web.security.LoginThrottle`·`web.security.PreAuthSessionGuard`·`web.security.LoginRefusalRecorder` `[결정]`(검토 N9·N15, 사용자 결정 U-2): B-4a. 로그인 시도 속도 제한, 인증 전 세션 상한, 같은 출처의 연속 실패를 한 감사 행으로 묶는 기록.
- `web.security.SecurityRefusal`(401·403·429·503 본문): 보안 사슬이 컨트롤러 앞에서 내는 응답은 기준본 `x-portal-errorCodes.webLayerCodes`의 여섯 코드(`UNAUTHENTICATED`, `FORBIDDEN`, `CSRF_REJECTED`, `ORIGIN_REJECTED`, `LOGIN_THROTTLED`, `LOGIN_BUSY`)를 `application/problem+json`으로 낸다(B-7). `ErrorCode` 열거형(217개)은 바꾸지 않는다. MCP 포트는 거절된 Bearer에 본문 없는 401을 낸다.
- `web.security.AdminSecurityConfiguration`: `@Order`+`securityMatcher` 다중 `SecurityFilterChain`(계획 정본 공식 문서 반영).
  1. Bearer 사슬(`Authorization: Bearer` 헤더가 있는 요청): 무상태, CSRF 없음. `BearerTokenAuthenticationProvider`(spring-security의 `AuthenticationProvider` 구현, 헤더는 `AuthenticationFilter`+`AuthenticationConverter`가 읽는다. resource-server 스타터는 쓰지 않는다, D.2)가 관리자 토큰이면 `OWNER`, 기기 토큰이면 `DEVICE` 권한을 준다. Origin 헤더가 있으면 `BRIDGE_ADMIN_ORIGIN`과 같아야 한다.
  2. 브라우저 사슬(그 밖): 세션, CSRF(세션 저장, 헤더 `X-CSRF-TOKEN`), 안전하지 않은 메서드는 Origin 일치(`web.security.AdminRequestPolicy`). 로그인은 모드별 `AdminAuthMethod`가 건다.
  3. 경로별 권한은 `admin-routes.json`의 `auth30`(B-1.5) 범주 그대로다. `owner-token` 범주(유지보수 6경로)는 1번 사슬의 관리자 토큰만 통과한다.
- `web.security.McpSecurityConfiguration`(MCP 자식): `McpTokenAuthenticationProvider`(같은 구성)가 `BRIDGE_MCP_TOKEN`만 받는다. 관리자 토큰·기기 토큰은 MCP 포트에서 거절된다.
- `web.security.BrowserSessionAdapter`: B-4.
- `web.security.UiTicketSigner`: MCP Apps 카드용 HMAC 티켓(2.x `tickets.mjs`, 이름 그대로).
- 기기 토큰: `domain.device.{DeviceTokenId,DeviceToken,DeviceTokenSecretDigest}`, `app.device.{DeviceTokenService,DeviceTokenStore}`(포트), `store.device.JdbcDeviceTokenStore`, `db/expand/V10__device_token.sql`(번호는 F.2절). B-2.
- 쓰기 범위: `portal-web/src/**/web/security/**`, `portal-{domain,app,store}/src/**/device/**`, `portal-store/src/main/resources/db/expand/V10__device_token.sql`, `portal-web/build.gradle.kts`(Security 스타터). 로그인 거절 기록은 U2가 만든 `app.audit`의 포트(`RefusalStreakLog`)를 부르기만 한다.
- 완료 사례: S4-SE-01~16, S4-DT-01~09.

### A.4 U5 MCP 도구 24개·SOURCE 실행기
- `web.mcp.McpServerConfiguration`: 서버 정보(이름 `SSH Approval Portal`, 판 = 프로젝트 판), `instructions`(기준본 문장 + `{sourceUrl}` 치환, B-9), 리소스(카드), 도구 등록.
- `web.mcp.PublicToolDefinitions`(공개 15개), `web.mcp.UiToolDefinitions`(`show-*` 3개, `ui-*` 6개), `web.mcp.ArtifactChunkCodec`, `web.mcp.CardResource`.
  - 도구 정의 `[결정]`: 도구별 `description`·`inputSchema`·`outputSchema`·`annotations`는 제품의 main 자원 `mcp/tools/<이름>.json`에 두고 Spring AI의 프로그램 방식 도구 명세로 등록한다. record에서 스키마를 생성하지 않는다. 근거: 기준본과 키 순서 무시 동일이 완료 조건이고(S4-CB-01), 생성기는 Zod 유래 스키마의 세부(`(?![\s\S])`, `anyOf` null 등)를 같은 모양으로 내지 않는다. 시험 기준본(`src/test/resources/contract`)과 제품 정의(`src/main/resources/mcp`)는 역할이 다른 두 파일이고, S4-CB-01이 둘을 대조한다.
  - 입력 검증: 스키마 검증 뒤의 도구별 규칙(`portalRules`)은 `app` 유스케이스 입력 record의 생성자와 도구 어댑터에 둔다. 규칙 위반은 `ok=false` 봉투다.
- `files`: `operationKey`가 네 동작 모두 필수다(B-1.2). `copy`·`move`·`remove`는 같은 키·같은 입력이면 처음 결과를 돌려주고, 같은 키·다른 입력이면 `OPERATION_INPUT_CONFLICT`다. `stat`은 읽기라 키를 멱등 판정에 쓰지 않고 감사 기록의 상관 키로만 남긴다. 저장 방식(검토 N11): B-14 — `copy`·`move`는 기존 `staged_action`, `remove`는 새 표 `staged_remove`(`V13`), `stat`은 감사 표의 `correlation_key` 열.
- `desktop-open`: `operationKey` 필수(B-1.3). 같은 키·같은 대상·같은 판이면 같은 lease를 돌려주고, 닫혔거나 만료된 키는 다시 열지 않는다(2.x 설명의 「A closed/expired key cannot reopen」 의미 유지). 이 의미를 재시작 뒤에도 지키는 저장 방식(검토 N10): B-13 — 새 표 `desktop_lease`(`V12`).
- SOURCE 실행기: B-3. `app.execution.SourceOperationRunner`, `app.execution.SourcePlanRegistry`, 해석기 매개변수 `SourceRuntime`.
- 쓰기 범위: `portal-web/src/**/web/mcp/**`, `portal-web/src/main/resources/mcp/**`, `portal-app/src/**/app/execution/Source*`, `portal-app/src/**/app/target/TargetPlatform.java`(서명), `portal-ssh/src/**/ssh/platform/*TargetPlatform.java`, `portal-domain/src/**/domain/target/PosixPythonExecutable.java`(B-3, 새 값 타입), `portal-domain/src/**/domain/operation/SourceInput.java`(Javadoc만, 검토 N18), `portal-app/src/**/app/artifact/**`·`portal-app/src/**/app/desktop/DesktopLease*`(files·desktop 멱등 유스케이스와 포트, 검토 N11·N10. `app/desktop`의 나머지는 데스크톱 단위의 것이라 `DesktopLease*` 이름의 파일만 쓴다), `portal-store/src/**/store/{artifact,desktop}/**`, `portal-store/src/main/resources/db/expand/{V12__desktop_lease.sql,V13__staged_remove.sql}`.
- 완료 사례: S4-MC-01~13, S4-SR-01~08, S4-CB-01~03.

### A.5 U6 관리자 HTTP API
- `web.admin.*Controller`: feature-map의 `AdminApiControllers`·`FileTransferController`·`DownloadController`를 경로 묶음별 컨트롤러로 나눈다(요청·대기·작업 조회, 파일 업로드·다운로드, 용량, 데스크톱, 에이전트 CLI, 환경, 명령 정책, 유지보수, 출력).
- `web.admin.StagedFileController` `[결정]`(검토 N1, 결정 L-1): `POST /api/artifacts`, `GET /api/artifacts/{uuid}`, `PUT /api/artifacts/{uuid}/chunks/{n}`, `POST /api/artifacts/{uuid}/complete`. 2.x의 경로·요청·응답을 그대로 옮긴다(B-2a). 이전 개정이 이 컨트롤러를 지운 것은 철회한다.
- `web.admin.DownloadController`: `POST /api/{id}/download`(`owner-or-device`)와 `GET`·`HEAD /downloads/{id}/{nonce}`(티켓만, B-2b). `approve`·`deny`는 이름이 다른 `ApprovalController`가 `owner`로 맡는다(검토 N2).
- `web.admin.DeviceTokenController`: `GET /api/devices`, `POST /api/devices`, `POST /api/devices/{id}/revoke`(B-2).
- `web.admin.ReconciliationController`: `GET /api/operations/reconciliation`, `POST /api/operations/{id}/reconcile`(B-10).
- `app.operation.OperationReconciliation`: 정리 대상 목록과 예산(사용 중 unknown 수, 예산), 소유자 정리. `domain.operation.CompletionEvidence.OwnerConfirmed(Instant confirmedAt)`을 더한다(소유자가 대상을 확인해 완료라고 판단한 증거). 저장·관측까지의 변경 범위는 B-10(검토 N7).
- 오류 응답: 모두 `ResultPresenter`를 거친 `application/problem+json`(B-7).
- 쓰기 범위: `portal-web/src/**/web/admin/**`, `portal-app/src/**/app/operation/OperationReconciliation*`, `portal-domain/src/**/domain/operation/{CompletionEvidence,ExecutionOutcome,Operation,OperationSnapshot,ReconciledAs}.java`(`OwnerConfirmed`와 `reconcile`이 정리 기록을 남기는 변경, B-10a), `portal-store/src/**/store/journal/{OperationMapper,OperationStorage,OperationReconciliationRow,OperationReconciliationRowRepository,JdbcOperationJournal}.java`, `portal-store/src/main/resources/db/expand/V11__operation_reconciliation.sql`, `portal-app/src/**/app/history/**`(관측: 이력에 정리 기록 표시).
- 완료 사례: S4-AD-01~11, S4-RC-01~08, S4-CB-04.

### A.6 U7 정적 관리 UI·카드
- `web.ui.StaticPageConfiguration`: ES 모듈을 classpath `static/`에서 그대로 서빙한다(번들러 없음, 계획 정본). 경로는 기준본의 정적 항목 두 개다(검토 N12): 화면 경로 `/`, `/settings`, `/bridge`, `/preferences`, `/servers`, `/history`, `/devices`, `/reconciliation`, `/login`, `/app.js`(같은 셸 파일)와 `/assets/**`(ES 모듈·스타일·그림, 점으로 시작하는 구간 없음), 그리고 `/signed-out`. 모두 `auth30: static`이라 로그인 전에도 받는다. 서버는 HTML에 값을 끼워 넣지 않고 제목도 고쳐 쓰지 않는다. 화면이 `GET /api/session`으로 모드·표시 이름·로그인 여부를 읽는다(2.x는 `data-*`로 주입했다).
  - OIDC 실패 경로: 허용 계정이 아니면 `/login/oauth2/code/oidc`가 403 `text/html`(금지 화면)을 직접 내고, 그 밖의 흐름 실패(제공자 오류, state 불일치)는 `/login?error`로 보낸다. `/login`은 질의 `error`를 받아 거절 문구를 보인다. 어느 쪽도 세션을 만들지 않는다.
- 화면에 더하는 것: 로그인 화면(모드에 맞는 버튼 또는 토큰 입력), 기기 토큰 화면(발급 시 한 번 보여 줌, 회수), 정리 배너·목록(B-10), 바닥글의 소스 안내(B-9).
- `web.presentation.CapabilityTickets`, `web.mcp.CardResource`: MCP Apps 카드(기준본 `server.json` 리소스).
- 시험: Playwright for Java(승인됨)로 브라우저 사례(S4-UI-*)를 돌린다.
- 쓰기 범위: `portal-web/src/main/resources/static/**`, `portal-web/src/**/web/ui/**`, `portal-web/src/test/**/web/ui/**`.
- 완료 사례: S4-UI-01~08.

### A.7 U8 위험 평가·명령 자동화·비밀
- `web.jev.JevRiskEvaluator`(`app.risk.RiskEvaluator` 구현, `RestClient`), 호출 상한 `BRIDGE_JEV_MAX_CALLS`.
- 명령 자동화: 기존 `app.policy`의 정책 저장과 `BRIDGE_COMMAND_AUTOMATION` 스위치를 잇는다. 자동 승인은 정책·위험 평가 둘 다 허용일 때만이다(2.x 의미 유지).
- 비밀: `app.secret.SecretResolver` 포트, `server.secret.LocalFileSecretStore`(`BRIDGE_SECRET_STORE_DIR`), `server.secret.VaultSecretStore`(`RestClient`, `BRIDGE_VAULT_*`). `*_REF` 키는 이 해석기를 거친다.
- 쓰기 범위: `portal-web/src/**/web/jev/**`, `portal-app/src/**/app/{risk,policy,secret}/**`, `portal-server/src/**/server/secret/**`.
- 완료 사례: S4-RK-01~06.

### A.8 U9 릴리스 카탈로그·환경 편집·선택 어댑터
- `web.release.NpmRegistryCatalog`(`app.agentcli.AgentReleaseCatalog` 구현, `RestClient`): S3 11.4 「릴리스 카탈로그 어댑터」를 닫는다. `AssemblyDefaults.agentReleaseCatalog()`의 `Unavailable`을 대신한다. 무결성 값이 없는 릴리스는 받지 않는다(지금의 「알 수 없는 출처의 바이트를 설치하지 않음」 유지).
- `web.release.PortalReleaseChecker`: `GET /api/version`(제품 새 판 확인).
- 환경 편집: `server.config.EnvironmentFileEditor`(허용 키만, B-1.6의 `allowedKeys`). 2.x가 하던 편의 기능을 그대로 잇는다(`docs/environment-editor.md`): 저장 전 백업(소유자 전용), 다른 편집과의 충돌 거절, 실행 중·승인 대기·전송 중·유지보수 중 재시작 거절, 재시작 뒤 기동 실패 시 이전 환경으로 복구. 반영 재시작은 OS 중립 계약 하나다(B-11, 검토 N13): 본체는 「재시작 요청」만 알고, 어떻게 다시 띄우는지는 감독자(systemd, Windows 서비스)가 맡는다.
- 선택 어댑터: DNS 레코드·원본 인증서 포트(`DnsRecordProvisioner`, `OriginCertificateSource`) 뒤의 업체 구현은 선택이고 설치 기본 흐름에 들어가지 않는다. `AccessProvisioner`·`ConfigureAccessCommand`는 만들지 않는다(access 모드 없음).
- 쓰기 범위: `portal-web/src/**/web/release/**`, `portal-server/src/**/server/config/EnvironmentFileEditor*`, 선택 어댑터 패키지. 재시작 계약의 구현은 U19(`portal-server/src/**/server/lifecycle/**`)다. U9는 그 포트(`RestartRequester`)를 부르기만 한다.
- 완료 사례: S4-RL-01~07.

### A.9 U18 작업 본문 이전 (B-15)
- 목표: `operation_command`·`operation_source`·`operation_download`·`operation_result`의 긴 텍스트 열 10개를 첨부 메타 표 + 파일로 옮긴다(사용자 확정 규칙 「본문은 DB 열 금지, 첨부 메타 표+파일」). 분류와 길이 근거는 B-15.
- 만드는 것: `domain.attachment.{AttachmentId,AttachmentRole}`, `app.attachment.{AttachmentStore(포트),AttachmentSpec}`, `store.attachment.{JdbcAttachmentStore,AttachmentFiles}`, `db/expand/V14__attachment.sql`, `V15__operation_body_attachment.sql`, `OperationMapper`·`OperationStorage`가 본문을 읽고 쓰는 부분.
- 쓰기 범위: `portal-domain/src/**/domain/attachment/**`, `portal-app/src/**/app/attachment/**`, `portal-store/src/**/store/{attachment,journal}/**`, `portal-store/src/main/resources/db/expand/{V14__attachment.sql,V15__operation_body_attachment.sql}`, `portal-store/src/test/**/migration/ProductScriptsTest.java`(`LONG_TEXT_COLUMNS`와 S-D0109-21이 10개를 고정하고 있어 같은 커밋에서 고친다)와 `docs/migration/migration-runner.md`의 「긴 텍스트 열」 절, `portal-server/src/**/server/assembly/**`(저장소 배선).
- 완료 사례: S4-AT-01~07.

### A.10 U19 재시작·정지 계약과 서비스 진입점 (B-11)
- 목표: 「재시작 요청」「정지 요청」을 OS 중립 계약으로 정의하고, systemd와 Windows 서비스(Apache Commons Daemon procrun)가 각각 그것을 어떻게 받는지 구현한다.
- 만드는 것: `server.lifecycle.{RestartRequester(포트),ExitCodeRestartRequester,SupervisorKind,StartupFailureGuard,PortalLifecycle}`, `server.lifecycle.service.{WindowsServiceEntry,SystemdEntry}`(진입점), `server.PortalServerApplication`의 종료 배선.
- 쓰기 범위: `portal-server/src/**/server/lifecycle/**`, `portal-server/src/**/server/PortalServerApplication.java`(종료 배선만).
- 완료 사례: S4-RS-01~08.

### A.11 U20 Windows 대상 보완 (B-16)
- 목표: Windows 대상이 Linux 대상과 같은 수준이 되도록 한다: 명령 분류의 destructive·privileged, 업로드 임시 파일의 비공개 권한과 덮어쓰기 때 DACL·소유자 보존(`icacls`), 취소·기한 초과 때 원격 프로세스 종료(`taskkill`).
- 쓰기 범위: `portal-ssh/src/**/ssh/platform/**`, `portal-ssh/src/main/resources/**`(분류 표), `portal-app/src/**/app/remote/**`(고정 명령 포트), `portal-domain/src/**/domain/classify/**`(Windows 분류 데이터가 필요하면).
- 완료 사례: S4-OS-01~10.

### A.12 U21 설치·PC 클라이언트 자동 시작·배포판 CI (B-17)
- 목표: Linux 설치 스크립트가 Debian 계열(apt)과 RHEL 계열(dnf)을 구분하고, CI가 배포판별 설치와 arm64를 시험한다. PC 클라이언트 자동 시작(Linux `systemd --user`, Windows HKCU `Run`).
- 쓰기 범위: 설치 스크립트 폴더(7단계 패키징의 폴더), `.github/workflows/**`(리드 승인 뒤), `portal-client/src/**/autostart/**`.
- 완료 사례: S4-IN-01~06.

## B. 판정

### B-1 새 계약 판과 전체 변경 목록
`[결정]` 계약 판은 `"3.0"`이다. 근거: SSH_PORTAL-D-0112(쓰기 도구 키 결정 위반)와 2026-11-15 결정 1(하위 호환 없음, 계약 판을 올린다). 아래가 2.4.2 기준본과의 차이 전부다. U1과 이 개정(U0r, 2026-11-21 검토 반영)은 이 목록만 기준본에 반영했고, 목록 밖의 차이는 없다(`contract-baseline.md` 9절 변경표와 같은 내용). 이 개정이 바꾼 항목은 5번(`/api/artifacts` 복원, `download` 분리, `/api/files/downloads`, 정적 경로, `/assets/**`, `/api/session`, 로그인 경로의 응답)과 6번(`removedKeys` 등)과 `webLayerCodes`다. 기준본을 읽는 최소 시험은 F.3절(검토 N21)이 정한다.

1. **계약 판**: `server.json`의 `contractVersion`·`instructions` 첫 문장(`Contract 3.0.`), `envelope.schema.json`의 `$id`·`title`·`contractVersion.const`, 공개 도구 15개의 `outputSchema` `contractVersion.const`, `show-*`·`ui-*` 9개 `portalRules.outputSchema`의 「Not the Contract 3.0 envelope」 문구(`show-ui-probe`는 「does NOT use the Contract 3.0 envelope」), `admin-routes.json` `GET /api/operations/by-key` 응답 모양의 `contractVersion:'3.0'`, `server.json`의 `contractVersionSource`·`instructionsSource`(설계 위치와 2.4.2 값을 함께 적음). 도메인 `ContractVersion.V3_0("3.0")`.
2. **`files`**: `required`에 `operationKey`를 더한다(네 동작 모두). 설명의 「Copy/move require operationKey, …」를 「Every action requires a stable operationKey; copy/move also require …」로 고친다. `portalRules.inputSchema`의 `stat|remove` 허용 필드에 `operationKey`를 더한다.
3. **`desktop-open`**: 입력 `requestKey`(서버 발급 형식)를 없애고 `operationKey`(공통 키 형식 `^[A-Za-z0-9_.:-]{16,128}(?![\s\S])`)를 `required`에 둔다. 설명을 호출자 키로 고친다. `desktop-close` 설명의 「The original open requestKey」를 「The original open operationKey」로 고친다(뜻은 같고 이름만). `targets`의 `gui.openRequest`를 없앤다(서버 발급 키가 없으므로). 오류 코드 `DESKTOP_OPEN_REQUEST_CAPACITY`·`DESKTOP_OPEN_REQUEST_GENERATION`·`DESKTOP_OPEN_REQUEST_EXPIRED`는 3.0에서 내지 않으므로 `envelope.schema.json`의 `x-portal-errorCodes.contract30Removals`에 적고 `DesktopErrorCode`에서 지운다(72 → 69). 도메인 `GuiOpenRequest`·`WireFormat.OPEN_REQUEST_KEY`·`GuiDescriptor.openRequest`를 지운다.
   - 이름만 바꾸는 안(서버 발급 키를 `operationKey`라 부름)을 고르지 않은 이유: `operationKey`는 「호출자가 정하고 불확실할 때 같은 값으로 다시 보내는 키」다(`submit` 설명 「never replay with a new key」). 서버가 만료와 프로세스 세대를 넣어 발급하는 키에 같은 이름을 붙이면 재시작 뒤 같은 키가 무효가 되어 그 의미가 깨진다. 3.0은 작업 기록이 DB에 남으므로 호출자 키만으로 같은 lease를 찾을 수 있다.
4. **`instructions`의 소스 안내**: 끝에 「Corresponding source (AGPL-3.0-or-later): {sourceUrl}」 한 문장을 더한다. `{sourceUrl}`은 대조 때 실제 설정값으로 바꾼다(B-9).
5. **관리자 HTTP(`admin-routes.json`)**:
   - 지움: `/api/session/start`·`/api/session/logout`(2.x 앞단 신원으로 세션을 여는 경로, 3.0은 로그인 경로가 세션을 연다). `/api/artifacts` 4항목은 **지우지 않는다**(검토 N1, 결정 L-1: cb8ff00의 삭제를 철회, B-2a).
   - 더함: `POST /login/token`, `GET /oauth2/authorization/oidc`, `GET /login/oauth2/code/oidc`, `POST /logout`(로그인), `GET /api/devices`, `POST /api/devices`, `POST /api/devices/{id}/revoke`(기기 토큰, B-2), `GET /api/operations/reconciliation`, `POST /api/operations/{id}/reconcile`(정리, B-10). 따로 `GET /assets/**`(UI 모듈, 정적, 검토 N12) 1항목.
   - 나눔: `POST /api/{id}/(approve|deny|download)`를 `(approve|deny)`(`owner`)와 `download`(`owner-or-device`)로 나눈다(검토 N2, 결정 L-2).
   - 바꿈: `POST /api/files/downloads`를 `owner-or-device`로(2.x 클라이언트 명령 `request-download`를 기기 토큰으로 쓰게 함, 승인 권한은 없음). 정적 화면 경로 항목에 `/devices`, `/reconciliation`, `/login`을 더한다(검토 N12). `GET /api/session` 응답은 B-4. 로그인 항목 3개(`POST /login/token`, `GET /oauth2/authorization/oidc`, `GET /login/oauth2/code/oidc`)의 요청·응답 문장은 B-4a·B-8a(CSRF 제외, 속도 제한 429, 인증 전 세션 상한 503, `redirect_uri`, 실패 경로).
   - 모든 항목에 `auth30`(3.0 인증 범주)을 더한다. 기존 `auth`는 2.4.2 동작 기록으로 남긴다. 범주 정의는 `contract-baseline.md` 9.2절.
   - 항목 수: 51 → 49(지움 2) → 58(더함 9: 로그인 4, 기기 토큰 3, 정리 2) → 59(`/assets/**` 1) → 60(나눔 1: `download`가 별도 항목). 범주별: `owner` 29, `owner-or-device` 14, `owner-token` 6, `static` 3(화면, `/assets/**`, `/signed-out`), `login` 3, `download-capability` 2, `public` 1, `mcp-bearer` 1, `session-probe` 1.
   - `GET /health`: 응답을 Actuator 모양(`{"status":"UP"}`, 실패 503 `{"status":"DOWN"}`)으로 바꾼다(B-5). 2.x가 인증 없이 내던 판·인증 모드·수용 상태는 내지 않는다. 2.4.2 응답 문장은 같은 필드 뒤에 기록으로 남긴다.
   - 오류 본문: 관리자 HTTP의 4xx·5xx 본문은 RFC 9457 `application/problem+json`이다(B-7). 2.x의 `{error:'…'}`는 `auth`·`responseShape`의 2.4.2 기록으로만 남는다.
6. **설정(`settings-contract.json`)**: 허용 키에서 `BRIDGE_ACCESS_TEAM_DOMAIN`, `BRIDGE_ACCESS_AUD`, `BRIDGE_ACCESS_OWNER_EMAILS`(access 모드), `BRIDGE_OIDC_OWNER_EMAILS`(이메일 대신 계정 식별자), `BRIDGE_OIDC_JWKS_URL`(OAuth2 Login은 발급자 discovery로 키를 찾음), `BRIDGE_AUDIT_PATH`(감사는 DB 표), `BRIDGE_TRANSFER_ROOT`(데이터 폴더 하나)를 뺀다. `BRIDGE_DATA_DIR`, `BRIDGE_OIDC_OWNER_SUBJECT`, `BRIDGE_OIDC_CLIENT_SECRET`, `BRIDGE_SOURCE_URL`을 더한다. 기본값에 `BRIDGE_SOURCE_URL: ""`(빈 값 = 이 판의 공개 저장소 태그)를 더한다. `BRIDGE_ADMIN_AUTH_MODE`가 받는 값은 `token`·`oidc`다(기본 `token`). 이 값 목록을 새 최상위 키 `adminAuthModes: ["token","oidc"]`로 적는다(2.4.2 기준본에는 값 목록이 없었다). 허용 키는 40 → 37(뺀 7, 더한 4). 뺀 7개는 새 최상위 키 `removedKeys`(키와 교체 이유)로 옮겨 적고, `keyPrefix: "BRIDGE_"`와 `unknownKeyPolicy`(가드의 규칙, A.1)를 더한다. 이는 S4-ST-04가 읽는 입력이다(검토 N16). TLS·바인딩 주소 설정 키는 더하지 않는다(B-8a).
   - **`envelope.schema.json`**: `x-portal-errorCodes.webLayerCodes`를 더한다(보안 계층이 컨트롤러 앞에서 내는 HTTP 전용 코드 여섯 개, 상태 포함). `ErrorCode` 열거형 여덟 개와 217개 합계는 바뀌지 않는다(B-7).
7. **hooks**: JSON은 바꾸지 않는다. 대조 규칙만 바꾼다: 플러그인 매니페스트의 `version`은 제품 판을 따르므로 `serverInfo.version`처럼 대조에서 뺀다(`contract-baseline.md` 9.3절).
8. **바꾸지 않는 것**(확인 목록): 도구 이름·개수(24), 나머지 도구의 입력(도구 스키마는 이 개정에서 한 글자도 바뀌지 않았다), 하위 쓰기 도구(`cancel`, `artifact-chunk`, `artifact-finish`, `desktop-input`, `desktop-close`)에 키를 더하지 않음(2026-11-15 결정 1), `desktop-input`의 요청 단위 `requestKey`, 봉투 구조와 `error.code` 형식, 위 6번 밖의 설정 키와 기본값, 포트 기본값, hook 이벤트·필드, 클라이언트 명령 이름 `portal-files`(설명 문장에 나옴), `server.json`(MCP `instructions`가 말하는 「portal-files가 UUID를 돌려준다」는 `/api/artifacts` 복원으로 그대로 맞다, B-2a).

### B-2 기기 토큰
`[결정]` 근거 요약: 계획 정본 결정 5(「owner가 관리 화면에서 한 번 승인하면 기기별로 범위가 제한된 토큰을 발급하고, 회수할 수 있다」), feature-map C2 초안, 「에이전트 자기 승인 금지」.

- 발급: 소유자가 관리 화면에서 이름을 주고 `POST /api/devices`(`auth30: owner`)를 부른다. 발급이 곧 그 한 번의 승인이다. 응답에 토큰을 **한 번만** 담는다. 클라이언트는 표준 입력으로 받아 사용자 전용 파일에 둔다(클라이언트 단위의 일).
  - 인증 없는 「기기가 먼저 등록을 요청하고 소유자가 승인하는」 흐름은 두지 않는다. 인증 없는 경로·대기 표·폴링을 더 열어야 하고, 개인판(소유자 한 명)에는 화면에서 바로 발급하는 것으로 충분하다.
- 형식: `pdt1.<id>.<secret>`. `id`는 128비트, `secret`은 256비트 무작위 값을 base64url(패딩 없음)로 쓴다. Bearer의 token68 문자 집합 안이다.
- 저장: 표 `device_token(id, name, secret_sha256, created_at, created_by)`, 회수는 표 `device_token_revocation(token_id, revoked_at, revoked_by)`에 행을 더한다(물리 삭제·갱신 없음, append-only). 비밀은 SHA-256만 둔다. 256비트 무작위 값이라 느린 해시가 필요 없다. 비교는 `MessageDigest.isEqual`.
- 검증: 관리자 포트 Bearer 사슬의 `BearerTokenAuthenticationProvider`가 형식으로 기기 토큰을 가려 `id`로 찾고, 회수 행이 없고 해시가 같으면 `DEVICE` 권한을 준다. 캐시하지 않으므로 회수는 다음 요청부터 듣는다. MCP 포트에서는 받지 않는다.
- 허용 경로(16항목): `auth30: owner-or-device` 14개 = `GET /api/targets`, 업로드 4개(`POST /api/files/uploads`, `GET /api/files/uploads/{id}`, `POST …/chunks`, `POST …/finish`), staged UUID 4개(`POST /api/artifacts`, `GET /api/artifacts/{uuid}`, `PUT …/chunks/{n}`, `POST …/complete`, B-2a), 다운로드 2개(`POST /api/files/downloads`, `POST /api/{id}/download`), 작업 읽기 3개(`GET /api/{id}`, `GET /api/{id}?observe=1`, `GET /api/operations/by-key`). `auth30: download-capability` 2개 = 다운로드 받기(`GET`·`HEAD /downloads/{id}/{nonce}`, 티켓만으로 허용, B-2b).
  - 이전 개정(cb8ff00)의 10항목에서 늘어난 6개: staged UUID 4개(검토 N1, L-1), `POST /api/{id}/download`(검토 N2, L-2), `POST /api/files/downloads`(2.x 클라이언트 `request-download`가 기기 토큰으로 계속 쓰도록, 「굳이 편의기능 있는건 빼지마」에서 도출한 판정. 이 경로는 승인 대기 요청을 만들 뿐이다).
  - C2 초안의 「ACK·수신함」은 PC 클라이언트 안의 H2 수신함 동작이라 서버 경로가 없다(`portal-files.mjs`의 `ack`는 로컬 수신함을 고친다).
  - 승인·거부(`POST /api/{id}/approve|deny`), 환경·정책·용량·데스크톱·에이전트 CLI·유지보수, 기기 토큰 관리, 정리는 받지 않는다. 기기 토큰은 에이전트가 도는 PC에 있으므로 승인 권한이 없어야 자기 승인을 막는다. 소유자 CLI 명령(대기 목록·승인·거부)은 관리자 토큰으로 부른다.
  - 근거(2.x): `src/adapters/cli/portal-client.mjs:107`(`request-download` → `POST /api/files/downloads`), `:172`(`save` → `POST /api/{id}/download`), `src/adapters/security/admin-policy.mjs`(`/api/artifacts` 읽기·쓰기는 Origin 일치와 신원만 요구).
- 회수: `POST /api/devices/{id}/revoke`(`owner`). 이미 회수된 것은 같은 결과(멱등).
- 감사(B-12): `DEVICE_TOKEN_ISSUED`(id·이름)와 `DEVICE_TOKEN_REVOKED`(id)는 소유자가 한 일이라 한 건씩 남긴다. `DEVICE_TOKEN_REFUSED`(회수된 토큰이 쓰인 경우, id)는 같은 출처·같은 토큰 id의 연속 사용을 한 행(횟수·처음·마지막 시각)으로 묶는다(U-2와 같은 장치). 형식이 틀리거나 없는 id는 401만 내고 감사에 남기지 않는다(인증 없는 요청이 감사 표를 채우지 못하게).
- 목록: `GET /api/devices`는 id·이름·발급 시각·회수 시각을 낸다. 비밀·해시는 내지 않는다.

### B-2a staged UUID 경로 (검토 N1, 결정 L-1)
`[사실]` MCP `files`는 `uuid`(PC 클라이언트가 모델 밖에서 올린 임시 파일의 이름)를 입력으로 받는다(`mcp/tools/files.json`, `mcp/server.json`의 `instructions`는 「portal-files가 UUID를 돌려준다」). 2.x는 그 UUID를 `/api/artifacts`로 만들었다(`src/adapters/http/opaque-file-api.mjs:5,22-76`, 클라이언트 `src/adapters/cli/opaque-files-client.mjs:45-67`, 문서 `docs/mcp-tools.ko.md` 「모델 밖 파일 전송과 UUID files 계약」). `/api/files/uploads`는 `remotePath`와 대상이 필수인 대상 지정 전송이라 대체가 되지 않는다(`file-api.mjs:11-18`).
`[결정]` 경로·요청·응답을 2.x와 같게 3.0에 둔다. 이름을 바꿀 이유가 없고(클라이언트가 이 경로를 부른다), 바꾸면 기준본·클라이언트·시험이 모두 따라 바뀐다.
- `POST /api/artifacts` 본문 `{operationKey, fileName, size, sha256}`(정확히 이 네 키) → 200 `{uuid}`. `PUT /api/artifacts/{uuid}/chunks/{n}`(`application/octet-stream`, 머리글 `X-Chunk-SHA256`, 1~262144바이트) → 200 `{uuid, sequence, received}`. `POST /api/artifacts/{uuid}/complete`(본문 `{}`) → 200 `{uuid}`. `GET /api/artifacts/{uuid}` → 상태·크기·해시·만료·수신 진행·연결된 작업. 8192바이트 본문 상한, 질의 문자열 거절, 본문을 다 읽은 뒤 신원을 다시 확인한다(`ARTIFACT_AUTHORIZATION_CHANGED`, 2.x와 같음).
- 인증: `owner-or-device`. Origin 머리글이 있으면 `BRIDGE_ADMIN_ORIGIN`과 같아야 한다(2.x는 토큰 요청에도 Origin 일치를 요구했다. Bearer 요청에 Origin이 없는 것은 허용한다: 기기 토큰 클라이언트는 브라우저가 아니다).
- 저장: 이미 있는 `artifact`(`purpose = STAGED`, `public_uuid`, `owner_id`, `operation_key`, `UNIQUE (owner_id, operation_key)`)·`artifact_chunk`다. 새 표는 없다. `owner_id`는 요청의 신원(관리자 토큰 소유자, 세션 소유자, 기기 토큰이면 `device:<id>`)이다. 같은 `operationKey`로 같은 입력이면 같은 UUID를 돌려주고 다른 입력이면 `OPERATION_KEY_CONFLICT` 계열 거절이다(2.x 의미).
- 오류 본문은 B-7의 `ProblemDetail`이다(2.x의 `{error:'ARTIFACT_*'}`는 `code`가 된다).
- 시험: S4-AD-09. 구현 단위: U6(`StagedFileController`).

### B-2b 다운로드 링크는 티켓만 (검토 N3, 결정 L-3)
`[사실]` 2.x에서 `GET`·`HEAD /downloads/{id}/{nonce}?ticket=…`는 관리자 토큰 모드에서 관리자 인증이 없고(`admin-policy.mjs`의 `download` 범주: 토큰 모드는 무인증), Access·OIDC 모드에서만 신원과 브라우저 세션이 추가로 필요했다. 파일에 대한 권한은 서명된 일회용 티켓이다(`src/adapters/presentation/capabilities.mjs:23-33,41-48`). 링크를 여는 쪽은 MCP Apps 카드(모델 호스트의 브라우저)라서 관리자 쿠키·Bearer를 가지지 않는다(`server.mjs:82-83`의 `ui-download-link`가 링크를 만든다).
`[결정]` 3.0도 티켓만으로 허용한다. `auth30: download-capability`의 뜻은 「인증 없음 + 서명된 티켓」이다(이전 개정의 「`owner-or-device`에 더해」는 철회한다). 2.x의 보호 조건을 그대로 지킨다.
- 티켓: `UiTicketSigner`가 HMAC(`BRIDGE_UI_TICKET_KEY`)으로 서명한다. 대상은 `{artifactId}:{sha256}:{nonce}`와 대상 신원(`targetId`·`targetRevision`)에 묶인다. 수명은 10분과 파일 만료 중 짧은 쪽이다.
- 일회성: `GET`은 티켓을 쓴 것으로 표시하고(메모리 집합, 10분 뒤 정리) 같은 티켓의 두 번째 `GET`을 거절한다. `HEAD`는 소비하지 않는다. 파일 바이트는 열기 전에 검증한다(`store.verified`).
- 응답 머리글: `Cache-Control: no-store`, `Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff`, `Content-Disposition: attachment`(2.x와 같음).
- `[사실, 2.x와 같은 잔여 위험]` (1) 링크를 가진 사람은 10분 안에 한 번 받는다. 링크는 질의 문자열에 티켓이 있어 역방향 프록시 접근 기록에 남을 수 있으므로 운영자가 프록시 기록에서 질의 문자열을 빼야 한다. (2) 사용한 티켓 집합이 메모리라 재시작하면 비워진다. 같은 10분 안의 재시작 뒤에는 이미 쓴 링크가 한 번 더 통한다(2.x와 같음, 개선은 별도 결정). (3) 3.0의 OIDC 모드는 2.x의 OIDC 모드와 달리 다운로드에 로그인 세션을 요구하지 않는다(2.x 토큰 모드와 같아진다). 이 차이는 업그레이드 안내에 적는다(7절). 링크를 만드는 일(`POST /api/{id}/download`, `ui-download-link`)은 `owner-or-device`·보기 티켓이 있어야 하므로 링크는 승인된 다운로드에만 생긴다.
- 시험: S4-AD-11(티켓만으로 200, 쿠키·Bearer 없이, 두 번째 GET은 거절, HEAD는 소비하지 않음, 티켓 없음은 거절). 구현 단위: U6(`DownloadController`)·U4(`UiTicketSigner`).

### B-3 SOURCE 실행기
`[결정]` `app.execution.SourceOperationRunner`(`OperationRunner`, `kind() = SOURCE`)를 더한다. S3 11.4 「SOURCE 실행기 미정」과 「`prepareSource`의 Windows 서명」을 함께 닫는다.
- 원문 보관(검토 N18로 바로잡음): 저장되는 것과 메모리에만 있는 것을 가른다. **원문(`SourceText`, 16 KiB 이하)은 저장하지 않는다.** `SourceInput`이 저장하는 것은 계획의 식별 값(계획 해시, 원문 해시와 길이, 작업 폴더, 환경 판)과 **인자 JSON(`argv_json`)**, 고정 런처 명령, 호출 맥락, 표시 문구다. 인자와 런처는 소유자가 승인 화면에서 보는 내용이고 재시작 뒤에도 보여야 하므로 저장한다(본문이라 DB 열이 아니라 첨부 파일로, B-15). 이전 문장의 「`SourceArguments`를 메모리에만 둔다」는 `argv_json` 저장과 맞지 않아 철회한다. `app.execution.SourcePlanRegistry`가 접수 때 작업 id별로 **`SourceText`만** 메모리에 두고(인자는 `argv_json`에서 다시 만든다), 실행기가 꺼내며, 정산·만료 때 지운다. 크기 상한은 요청 용량 × 16 KiB다.
- 재시작(검토 N18로 바로잡음): 도메인은 재시작 뒤 대기 중(`QUEUED`) 작업을 그대로 두고(`Operation.recoverAfterRestart`, `JdbcRestartRecovery`), 실행 중이던 작업은 `UNKNOWN`으로 만든다. SOURCE도 같다. 달라지는 것은 대기 중이던 SOURCE 작업이 시작될 때다: 계획 등록부에 원문이 없으므로 디스패처가 보낸 것 없이 거절한다(`domain.error.DispatchReason.PRIVATE_PLAN`과 `DispatchStage.SOURCE_PLAN`은 이미 있고 V1의 CHECK에도 있다 → 결과 `NOT_DISPATCHED`, 새 코드 없음). `SourceInput`의 Javadoc(「재시작이 작업을 unknown으로 만든다」)은 실행 중이던 작업에만 해당하므로 U5가 그 문장을 이 구분으로 고친다. 원문을 파일로 남겨 재시작 뒤 실행하는 안은 고르지 않았다. 확인할 수 없는 계획을 다시 돌리지 않는다는 2.x의 의미를 바꾸게 되고, 원문이 디스크에 남는다.
- 해석기(장부 D-0170·D-0172): `TargetPlatform.prepareSource`에 `SourceRuntime`(sealed: `Posix(PosixPythonExecutable)`, `WindowsPython(WindowsPythonExecutable)`) 매개변수를 더한다. 값은 대상 설정 `BRIDGE_TARGETS`의 `pythonExecutable`에서 온다. Windows는 필수(없으면 capability 거짓, 지금처럼), **Linux 대상도 선택 설정으로 받는다**(없으면 `/usr/bin/python3`, 지금 `PythonSourceReceiver.POSIX_INTERPRETER`의 값). `PosixPythonExecutable`은 절대 경로(`/`로 시작), 4096바이트 이하, 제어 문자·NUL 없음을 만드는 곳에서 검증하는 `domain.target` 값 타입이다(`WindowsPythonExecutable`과 같은 모양). 지금의 「매개변수가 없어 Windows는 거절」 우회를 없앤다(`WindowsTargetPlatform.prepareSource(…)` 세 인자 판의 `TARGET_CAPABILITY_UNSUPPORTED`).
- 실행: 대상 해석·출력 예약·`RemoteExecutor` 호출은 `CommandOperationRunner`와 같다. 같은 부분은 패키지 내부 클래스 하나(`RemoteCommandRun`)로 묶어 두 실행기가 쓴다. 다형성은 `OperationRunner.kind()`가 고르므로 호출하는 쪽에 `kind` 분기를 두지 않는다.
- 거절 코드: 계획이 없을 때는 위의 `PRIVATE_PLAN` 거절이다. 새 `ErrorCode`는 더하지 않는다.

### B-4 브라우저 세션 저장
`[결정]` 서블릿 컨테이너의 메모리 `HttpSession`을 쓴다. Spring Session(JDBC 저장)은 새 의존이고, 개인판은 프로세스 하나라 공유 저장이 필요 없다. 재시작하면 다시 로그인한다.
- 쿠키(사용자 결정 U-1 「Lax로 변경」, 검토 N4): 이름 `__Host-portal-admin`, `Secure`, `HttpOnly`, `SameSite=Lax`, `Path=/`. 관리자 Origin이 https라는 기존 규칙(`originPattern`)과 맞는다. Boot 설정 `server.servlet.session.cookie.same-site=lax`.
  - 왜 Strict를 못 쓰는가: OIDC 콜백(`/login/oauth2/code/oidc`)은 제공자 사이트에서 돌아오는 최상위 이동이라 `SameSite=Strict` 쿠키(인증 요청을 담은 세션)가 따라오지 않아 `authorization_request_not_found`로 끝난다. Lax는 이 안전한 최상위 GET 이동에 쿠키를 보낸다.
  - 변경 요청은 Lax로 약해지지 않는다: Lax는 교차 사이트 POST·PUT에 쿠키를 보내지 않고, 우리는 그 위에 이중으로 막는다(POST만 변경, CSRF 머리글, Origin). **불변식**: `auth30: owner` 이상의 **GET·HEAD 경로는 상태를 바꾸지 않는다**(기준본의 GET 항목은 모두 읽기). 이것이 깨지면 Lax가 교차 사이트 링크 이동으로 변경을 허용하므로 S4-SE-13이 지킨다.
  - 이 문서의 이전 문장(C.2의 `SameSite=Strict`)과 사례 S4-SE-10의 기대는 이 결정으로 바뀌었다.
- 수명: 유휴 30분, 절대 12시간. 절대 수명은 서블릿 표준에 없으므로 `BrowserSessionAdapter`가 세션 생성 시각으로 검사한다. 로그인 때 세션 id를 바꾼다(Spring Security 기본 session fixation 보호).
- CSRF: Spring Security `CsrfFilter`, 세션 저장 토큰, 요청 헤더 `X-CSRF-TOKEN`. 화면은 **로그인한 세션으로** `GET /api/session`을 불러 토큰을 받는다(검토 N15). 익명 요청에는 세션도 토큰도 주지 않는다(`GET /api/session`은 `getSession(false)`만 쓴다). `POST /login/token`은 CSRF에서 빼고(아직 세션이 없다) Origin과 속도 제한으로 막는다.
- Origin: 브라우저 사슬의 안전하지 않은 메서드는 `Origin == BRIDGE_ADMIN_ORIGIN`(2.x `admin-policy.mjs`의 규칙 유지).
- MCP 포트는 세션을 만들지 않는다(`SessionCreationPolicy.STATELESS`).

### B-4a 로그인 시도의 속도 제한·인증 전 세션 상한·묶음 감사 (검토 N9·N15, 사용자 결정 U-2)
`[사실]` 이전 개정은 로그인 거절을 매번 감사한다(S4-SE-03)고 하면서 「인증 없는 요청이 감사 표를 채우지 못하게」(B-2)도 말해 서로 어긋났다. 또 익명 `GET /oauth2/authorization/oidc`는 OAuth2 Login 기본 구현(`HttpSessionOAuth2AuthorizationRequestRepository`)이 인가 요청을 담으려고 메모리 세션을 만들고, 상한이 없었다.
`[결정]` 세 장치를 둔다. 상수는 코드의 상수이고 설정 키를 더하지 않는다.
1. **속도 제한** `web.security.LoginThrottle`: 출처별로 로그인 시도(`POST /login/token`, OIDC 시작·콜백의 거절)를 센다. 연속 실패가 5회가 되면 그 출처를 429 `LOGIN_THROTTLED`(`Retry-After`)로 막고 대기 시간을 실패마다 두 배로 늘린다(최대 15분). 성공하면 그 출처의 기록이 지워진다. 출처 = `ServletRequest.getRemoteAddr()`(IPv6는 앞 64비트)이다. 전달 헤더는 기본으로 신뢰하지 않으므로(B-8a) 프록시 뒤에서는 모든 요청이 한 출처로 보인다. 운영자가 Boot 표준 속성 `server.request.header.forwarded.strategy`(새 `BRIDGE_*` 키가 아님)를 `framework`로 켜면 프레임워크가 전달 머리글로 원래 주소를 되살려 준다. 기본값은 `none`이다(Boot 공식 문서 「Running Behind a Front-end Proxy Server」, https://docs.spring.io/spring-boot/how-to/webserver.html#howto.webserver.use-behind-a-proxy-server 를 2026-11-21에 읽음. 이 문서는 현재 판이므로 프로젝트가 쓰는 4.1.2의 속성 이름과 같은지는 U3가 시험으로 확인한다 `[미확인]`). 막힌 시도 자체는 세션도 감사 행도 만들지 않는다(이미 열린 묶음 행의 횟수만 늘린다).
2. **인증 전 세션 상한** `web.security.PreAuthSessionGuard`: 인증 전에 만든 세션(OIDC 시작이 만드는 것)은 최대 비활성 5분으로 줄이고, 동시에 살아 있는 인증 전 세션 수를 `HttpSessionListener`로 세어 상한(256)에 닿으면 새 OIDC 시작을 503 `LOGIN_BUSY`로 거절한다. 로그인에 성공하면 세션의 비활성 한도를 30분으로 올리고 인증 전 수에서 뺀다. 이 상한은 메모리 보호용이고 감사·작업 이력의 한도가 아니다(사용자 확정 규칙 「이력 저장 한도 금지」는 이력에만 닿는다).
3. **묶음 감사** `LoginRefusalRecorder` → 포트 `app.audit.RefusalStreakLog`: 같은 출처의 연속 실패는 감사 표 `audit_refusal_streak`의 **한 행**에 `(출처, 이유, 횟수, 처음 시각, 마지막 시각)`으로 남는다(B-12). 성공하거나 15분 동안 실패가 없으면 그 묶음은 닫히고, 다음 실패는 새 행이다. 이유: `TOKEN_MISMATCH`, `OWNER_MISMATCH`(OIDC의 허용 계정이 아님. 마지막으로 본 발급자·계정 식별자를 같은 행에 적는다), `DEVICE_TOKEN_REVOKED`. 행은 지우지 않고 한도도 없다.
   - 인증 없는 요청이 표를 채우지 못하게: 새 행을 여는 속도에 전역 상한(분당 60행)을 둔다. 넘는 실패는 `source = '*'`(전역 넘침) 한 행의 횟수로 모이므로 어떤 실패도 조용히 버려지지 않고 표는 공격으로 부풀지 않는다.
- 시험: S4-SE-14(익명 `GET /api/session` 1000회에 세션 0개, 응답에 토큰 없음), S4-SE-15(연속 실패 N회 → 감사 행 1개·횟수 N·처음≤마지막, 429, 성공 뒤 새 묶음), S4-SE-16(인증 전 세션 상한 도달 → 503, 로그인 성공이 수를 줄임), S4-AS-05(동시 실패에서도 횟수가 맞고 행이 하나).

### B-5 `/health`
`[결정]` Spring Boot Actuator(승인됨)의 health 엔드포인트를 MCP 자식 컨텍스트에서 `/health`로 낸다. `management.endpoints.web.base-path=/`, `exposure.include=health`, `show-details=never`, `show-components=never`. 2.x 경로(MCP 포트 `GET /health`, 인증 없음)는 유지하고 본문은 Actuator 모양이다.
- 지표: `StorageHealthIndicator`, `AdmissionHealthIndicator`(부모 컨텍스트 빈).
- `[미확인]` 두 가지는 U3가 먼저 시험한다. (1) 자식 컨텍스트의 health 엔드포인트가 부모의 `HealthIndicator` 빈을 모으는지(S4-HE-02). 모으지 않으면 자식에 부모 빈을 감싼 `CompositeHealthContributor` 하나를 둔다. (2) Environment가 공유되므로 관리자 자식에서 Actuator 자동 구성을 빼야 관리자 포트에 `/health`가 생기지 않는지(S4-HE-03).
- 설치·업그레이드가 쓰던 판·배포 세대 확인은 `/health`에서 빠지므로 유지보수 경로(`/api/maintenance/status`, 관리자 토큰)로 한다(7단계 CLI가 쓴다).

### B-6 migrate와 UpgradeCommand의 연결
`[결정]` 본체(`portal-server`)는 `portal-migrate`에 의존하지 않고 부르지도 않는다. 근거: 2026-11-15 결정 2(「sqlite-jdbc는 이 모듈만, 본체는 의존하지 않음」)와 결정 1(「본체에는 2.x 형식을 아는 코드를 남기지 않는다」).
- 2.x → 3.0: 3.0을 새로 설치하고, 첫 시작 전에 운영자가 별도 jar `portal-migrate`를 실행한다. migrate가 3.0 DB에 이전 기록(3.0 스키마의 표)을 남긴다(migrate → store 의존은 허용 방향).
- `cli.UpgradeCommand`는 3.x → 3.x만 다룬다. 3.0 설치 영수증이 없는 곳에서는 업그레이드 안내(영·한)의 migrate 절을 가리키며 멈춘다. 2.x 파일 형식을 읽어서 판단하지 않는다(영수증이 「있는지」만 본다).
- feature-map `deploy180.mjs` 행을 이에 맞게 고쳤다. 구현은 7단계(패키징)와 migrate 단위다.

### B-7 출력 JSON 모양·오류 변환 한 곳
`[결정]`
- MCP 도구 결과: 성공은 `structuredContent` = 봉투, `content[0]` = 봉투 JSON 문자열. 실패는 같은 모양에 `isError: true`(기준본 `x-portal-wire` 유지). `desktop-observe`의 이미지 `content[1]`도 그대로다.
- 명령 출력: MCP `observe`의 `outputPage`와 관리자 `GET /api/outputs/{id}`는 같은 도메인 record `OutputPage`를 쓴다. 관리자 경로의 `offset`·`limit`·`format`은 `cursor`·`maxBytes`·인코딩으로 옮긴다. S3 11.4 「출력 JSON 모양」을 닫는다.
- 변환 한 곳: `web.presentation.RefusalResult`(코드, 재시도 가능, 실행 시도 여부, 선택 세부값)를 `PortalException`과 `ArtifactRefusal`에서 만든다. `ResultPresenter`가 이것을 (a) MCP 봉투(`ErrorData`)와 (b) `ProblemDetail`로 바꾸는 유일한 곳이다.
  - `ProblemDetail`: `type = urn:ssh-approval-portal:error:<CODE>`, `title = <CODE>`, 확장 속성 `code`·`retryable`·`executionAttempted`. HTTP 상태는 `HttpStatusTable` 한 곳에서 정한다. 모든 `ErrorCode` 상수가 상태를 가지는지 시험한다(S4-ER-02). 컨트롤러 앞에서 보안 계층이 내는 응답(401·403·429·503)은 `ErrorCode`가 아니라 기준본 `x-portal-errorCodes.webLayerCodes`의 여섯 코드이고 같은 `ProblemDetail` 모양이다(`type = urn:ssh-approval-portal:error:<CODE>`, `retryable`은 `LOGIN_THROTTLED`·`LOGIN_BUSY`만 true, `executionAttempted`는 false). 상태는 이 표가 아니라 `web.security.SecurityRefusal`이 기준본 값으로 정한다.
  - `ArtifactRefusal`의 숫자(요청 바이트·예산·여유 공간)는 MCP 봉투에 넣지 않는다(봉투 `errorData`는 세 필드만, `additionalProperties: false`). 관리자 `ProblemDetail`의 확장 속성 `detail`로만 낸다. S3 11.4 「ArtifactRefusal 값을 오류 봉투로 옮기는 일」을 이렇게 닫는다.
- 컨트롤러는 예외를 잡지 않는다. 관리자 쪽은 `AdminExceptionHandler` 하나, MCP 쪽은 도구 어댑터의 공통 감싸개 하나가 `ResultPresenter`를 부른다.

### B-8 두 포트 컨텍스트 구조
`[결정]` 부모(비웹) + 자식 웹 컨텍스트 두 개. 근거: 1단계 `PortsSpikeTest` Q-01~05(경로 집합이 컨텍스트로 갈라지고 부모 빈이 공유됨)와 Q 비교(커넥터 추가 방식은 경로 제한을 포트 번호 검사로 해야 함).
- 포트: Environment가 공유되므로 `server.port`를 쓰지 않는다. 각 자식 구성의 `WebServerFactoryCustomizer<ConfigurableServletWebServerFactory>`가 `BridgeProperties`(부모 빈)에서 포트와 주소를 넣는다. 기본 주소는 `127.0.0.1`(2.x와 같음).
- 보안 사슬·`@ControllerAdvice`·MCP 서버 빈은 자식에만 있다. 부모에는 웹 빈이 없다(공식 문서 「Web components must be contained within the child context」).
- 종료: 부모를 닫으면 두 포트가 닫힌다(Q-05). 정지·설정 반영 재시작은 B-11의 계약이다.
- 자식마다 켜는 자동 구성 목록은 A.2절(검토 N14)이다. 자식을 만드는 `SpringApplicationBuilder`의 각 `child(…)`는 자기 구성 클래스 하나만 소스로 받는다.

### B-8a 프록시 뒤의 OIDC redirect·TLS·바인딩 (검토 N5, 결정 L-4)
`[사실]` 2.x는 앱 안에서 TLS를 하지 않았다: 두 포트 모두 `node:http`의 `createServer`로 `127.0.0.1`에만 열었고(`bridge.mjs:16-17`, `protocol.mjs:4,87,112`), HTTPS는 운영자가 고른 앞단(역방향 프록시·터널)이 맡았다(2.x의 HTTPS 안내 문서 `docs/*-https.ko.md` 두 개, 역방향 프록시 예). 앱 코드에 PEM·ACME·`tls`·`https` 서버 구현은 없다(2.x `src`·`scripts`·`bridge.mjs`를 `createServer|tls|https|acme|pem`으로 검색, 서버 쪽 해당 없음. 있는 것은 원격 HTTPS 호출 `fetch` 쪽 TLS뿐이다).
`[결정]`
- **바인딩**: 두 포트 모두 `127.0.0.1`이다(2.x와 같음, 설정 키 없음). 바깥에 여는 일은 운영자의 프록시가 한다.
- **OIDC `redirect-uri`**: `BRIDGE_ADMIN_ORIGIN + "/login/oauth2/code/oidc"`로 고정한다(A.3). `BRIDGE_ADMIN_ORIGIN`은 이미 필수 설정이고 `originPattern`이 https 형식을 검증한다. 요청의 `Host`·`X-Forwarded-*`·`Forwarded`로 redirect-uri나 쿠키 속성을 만들지 않는다.
- **전달 머리글**: 기본으로 신뢰하지 않는다(Boot 기본 `none`). 앱은 프록시가 `X-Forwarded-*`를 붙여도 읽지 않는다. 프록시 앞단의 실제 접속자 주소가 필요한 운영자(로그인 속도 제한의 출처, B-4a)는 Boot 표준 속성으로 `framework` 전략을 켤 수 있고, 그때 신뢰하는 프록시가 머리글을 덮어쓰도록 운영자가 설정해야 한다(설치 문서에 적는다).
- **TLS**: 3.0.0 기본은 2.x와 같다. 앱은 루프백 HTTP만 열고 HTTPS 종단은 앞단이 맡는다. feature-map의 `portal.py` 행(230행)에 적힌 「선택 TLS 구성(`PemCertificateSource`·`AcmeCertificateSource`)」은 **2.x에 없던 새 기능**이므로 「삭제하지 않는다」는 규칙의 대상이 아니다. 이 문서는 그것을 3.0.0 범위에 넣지 않고 미정으로 둔다(F.6 항목 5): PEM은 Spring Boot의 SSL 번들(`server.ssl.bundle`, 새 라이브러리 없음)로 되지만 포트별 적용과 설정 키가 늘어 기준본을 바꾸게 되고, ACME는 RFC 8555 클라이언트 라이브러리가 필요해 **승인 필요**다(후보 미조사).

### B-9 AGPL 13조 안내
`[결정]` 설정 키 `BRIDGE_SOURCE_URL`(바인딩 `bridge.source.url`). 비면 이 판의 공개 저장소 태그 URL(`https://github.com/jaeseongs95/ssh-approval-portal/tree/v<판>`)이고, 운영자가 수정판을 돌리면 그 소스 URL을 적는다. https URL만 받는다.
- 표시: 관리 UI 바닥글 「Source code (AGPL-3.0-or-later)」 링크, MCP `instructions` 마지막 문장(B-1.4). 값은 `web.presentation.SourceOffer` record 하나가 들고 두 곳이 같은 값을 쓴다.

### B-10 unknown 예산 거절의 표시와 소유자 정리
`[사실]` `ConcurrencyLimits.DEFAULT_UNKNOWN_BUDGET = 32`, 예산이 차면 스케줄러는 `SaturationReason.UNKNOWN_BUDGET`, `AdmissionGate.codeFor`는 `OPERATION_RECONCILIATION_CAPACITY`(재시도 가능). `Operation.reconcile(ReconciliationEvidence, Instant)`가 `UNKNOWN`에서 나가는 유일한 길인데 이를 부르는 유스케이스·경로가 없다(SSH_PORTAL-D-0153, 2.4.2 운영에서 unknown 32건으로 새 작업이 모두 막힌 실제 장애).
`[결정]`
- 호출자: 예산이 찬 동안 새 실행 요청은 봉투 `error.code = OPERATION_RECONCILIATION_CAPACITY`, `retryable: true`, `executionAttempted: false`로 거절한다. 이미 대기 중인 작업은 `observe`에서 `state: queued`와 `diagnostic: {origin: "policy", policyReason: "OPERATION_RECONCILIATION_CAPACITY", nativeStep: null, nativeError: null}`로 이유를 보인다. 계약 스키마는 바꾸지 않는다(기존 필드).
- 소유자 화면: 예산이 찼거나 80% 이상이면 모든 화면 위에 배너(사용 중 수/예산, 「정리하기」 링크). 목록 화면은 `GET /api/operations/reconciliation`(`{unknownCount, budget, saturated, operations:[{id, kind, target, finishedAt, recovery}]}`).
- 정리: `POST /api/operations/{id}/reconcile` 본문 `{"outcome":"completed"|"failed"|"never_ran"}`. `completed`는 `ReconciliationEvidence.Completed(new CompletionEvidence.OwnerConfirmed(now))`, `failed`는 `Failed`, `never_ran`은 `NeverRan`. 대상이 `UNKNOWN`이 아니면 409 `INVALID_OPERATION_TRANSITION`. 결과는 감사 `OPERATION_RECONCILED`(id·결과·소유자)에 남는다. 다시 실행하지 않는다(정리는 기록만 바꾼다).
- 예산 계산은 정리 즉시 줄어 다음 스케줄 판단에 반영된다.

#### B-10a 소유자 정리 증거의 저장과 관측 (검토 N7, 결정 L-6)
`[사실]` 이전 개정은 `CompletionEvidence.OwnerConfirmed`를 더한다고만 했다. 그러나 (1) `Operation.reconcile`은 이전 결과(`previous.result()`)를 그대로 두고 받은 증거를 버린다(`Operation.java` 325-339줄), (2) 결과 행(`operation_result`)은 `RecordedResult.Executed`·`Refused`에만 있다. 재시작 복구가 `UNKNOWN`으로 만든 작업의 결과는 `NoResult`이고(`Operation#recoverAfterRestart`, `RecordedResult.NoResult`의 Javadoc 「settled by recovery」) 결과 행이 없어 증거를 담을 곳이 없다. (3) `OperationMapper.evidenceColumns`의 sealed switch(303-310줄)와 `evidence(row)`(269-276줄)에 이 값이 없고, (4) V1의 `ck_operation_result_ekind`가 네 값만 허용하며, (5) 이 변경이 닿는 파일이 U6의 쓰기 범위에 없었다. S4-RC-02가 단정하려던 「증거 `OwnerConfirmed`」는 저장도 관측도 되지 않았다.
`[결정]` 정리 사실은 결과 행에 끼워 넣지 않고 **자기 표 하나**에 둔다. 결과 행의 모양(실행기의 결과)과 정리(소유자의 사후 확인)는 다른 사실이고, `NoResult`인 작업도 정리되기 때문이다.
- 도메인: `CompletionEvidence.OwnerConfirmed(Instant confirmedAt)`(`confirmsCompletion() = true`)를 더한다. `OperationSnapshot`에 `reconciledAs`(없을 수 있음: `ReconciledAs(ReconciliationEvidence evidence, Instant at)`)를 더하고 `Operation.reconcile`이 그것을 채운다. 이전 결과·종료 시각은 그대로 둔다(정리는 기록만 바꾼다). `ExecutionOutcome`의 생성자는 증거가 `OwnerConfirmed`이면 거절한다(실행기는 소유자 확인을 만들 수 없다). 그래서 결과 표의 `evidenceColumns` switch의 새 분기는 도달할 수 없고(불변식 위반이면 `IllegalStateException`), V1의 `ck_operation_result_ekind`는 바꾸지 않는다.
- 저장: 새 표 `operation_reconciliation(operation_id CHAR(48) PK, outcome VARCHAR(9) NOT NULL, evidence_kind VARCHAR(15), confirmed_at TIMESTAMP(6) NOT NULL, reconciled_by VARCHAR(5) NOT NULL)`, `V11__operation_reconciliation.sql`. `outcome`은 `COMPLETED`·`FAILED`·`NEVER_RAN`, `evidence_kind`는 `COMPLETED`일 때만 `OWNER_CONFIRMED`(나머지는 NULL), `reconciled_by`는 `OWNER`. CHECK는 V1처럼 `CASE WHEN … END = 1`로 쓰고, 외래 키 `fk_operation_reconciliation_op`는 V1의 다른 작업 표와 같다. 행은 `UNKNOWN`에서 나가는 같은 트랜잭션에서 더하고(`JdbcOperationJournal`의 상태 전이와 함께) 갱신·삭제 경로가 없다. 모든 열이 짧은 값이라 본문 규칙(B-15)과 부딪치지 않는다.
- 관측: (1) 감사 `OPERATION_RECONCILED`(id·결과·소유자, B-12), (2) 관리자 `GET /api/{id}`와 `GET /api/history`의 작업 항목에 `reconciliation: {outcome, confirmedAt, by:"owner"}`를 더한다(관리자 응답 모양은 기준본에서 「미확인」 부분이라 가산 필드다). MCP 도구 스키마는 바꾸지 않는다(`observe`의 계약 모양은 그대로이고 정리된 작업은 일반 완료·실패·취소 상태로 보인다).
- 담당: U6(쓰기 범위는 A.5절에 넓혔다). 시험: S4-RC-02(증거가 저장되고 다시 읽으면 같음, `NoResult`인 작업에서도), S4-RC-08(`ExecutionOutcome`이 `OwnerConfirmed`를 거절하고 `ck_operation_reconciliation`이 값 밖을 거절함).

### B-11 설정 반영 재시작과 서비스 정지: OS 중립 계약 (검토 N13, 결정 L-8)
`[사실]`
- 2.x: 서비스 정지는 SIGTERM·SIGINT에서 정리하고 `process.exit(0)`(`bridge.mjs:19`). 설정 반영 재시작은 root 도우미가 systemd의 고정 서비스를 다시 시작하고, 건강 확인과 실패 시 이전 환경 복구까지 했으며, 실행 중·승인 대기·전송 중·유지보수 중에는 거절했다(`docs/environment-editor.md` 10-14행).
- 이전 개정(A.8)은 「`ExitCodeGenerator`의 0이 아닌 고정 종료 코드」로 재시작한다고만 적었다. 이는 종료 코드로 다시 띄우는 감독자(systemd)를 전제로 하고, Windows 서비스(procrun)에서 JVM이 그 코드로 끝나면 무슨 일이 생기는지는 정의되지 않았다(검토 N13, OS 조사 C2·C5).
- Apache Commons Daemon procrun 공식 문서(https://commons.apache.org/proper/commons-daemon/procrun.html, 2026-11-21 읽음): `jvm` 모드는 서비스 프로세스 안에서 JVM을 실행하며(`jvm.dll` 필요), `StartClass`/`StartMethod`는 `static void` + `(String args[])`이고, 「in jvm mode, the start method should not return until the stop method has been called」이며, 시작·정지 메서드는 서로 다른 스레드에서 불린다. `--StopTimeout` 기본 60초이고 시간이 지나면 서비스가 만든 프로세스 트리를 죽인다. **이 문서는 종료 코드, `System.exit`, 서비스 복구(재시작)를 말하지 않는다.**
- systemd(https://www.freedesktop.org/software/systemd/man/latest/systemd.service.html, 같은 날 읽음): `Restart=on-failure`는 「unclean exit code」(0이 아닌 코드)·「unclean signal」·시간 초과·워치독에서 다시 시작한다. 깨끗한 종료는 코드 0 또는 `SIGHUP`·`SIGINT`·`SIGTERM`·`SIGPIPE`와 `SuccessExitStatus=`에 적은 상태다. `RestartPreventExitStatus=`에 적은 상태는 `Restart=`와 관계없이 다시 시작하지 않고, `RestartForceExitStatus=`는 반대로 강제한다. `systemctl stop` 같은 관리자 정지는 다시 시작하지 않는다. `RestartSec=` 기본 100ms.
- Windows 서비스 복구(https://learn.microsoft.com/en-us/windows/win32/api/winsvc/ns-winsvc-service_failure_actions_flag, 같은 날 읽음): 복구 동작이 설정된 서비스는 「service process terminates without reporting a status of SERVICE_STOPPED」일 때, 그리고 `fFailureActionsOnNonCrashFailures`가 TRUE이면 「SERVICE_STOPPED이지만 `dwWin32ExitCode`가 0이 아닐 때」도 실패 동작이 대기열에 들어간다. 이 설정은 다음 시스템 시작 때 효력이 난다.
`[결정]` 본체는 **「재시작 요청」 한 가지만** 안다. 어떻게 다시 띄우는지는 감독자가 맡고, 본체는 감독자 종류로 분기하지 않는다(`SupervisorKind`는 진입점이 한 번 알려 주는 값이고, 호출하는 쪽에 `if`·`switch`를 두지 않는다).
1. **포트** `server.lifecycle.RestartRequester.request(RestartReason)`: 결과는 sealed `RestartOutcome`: `Accepted`(요청을 받았고 정리 뒤 종료함), `Refused(Busy | NoSupervisor)`. `Busy`는 2.x와 같은 조건(실행 중·시작 중·종료 중 작업, 승인 대기, 전송 중, 유지보수 중, 열린 desktop lease)을 `app` 포트(`OperationQueueView`, `MaintenanceState`, …)로 묻는 `RestartGate`가 판정한다. `NoSupervisor`는 다시 띄워 줄 감독자가 없을 때(콘솔 실행)다. 새 `ErrorCode`는 더하지 않는다: `POST /api/environment/restart`는 200 `{status:"accepted"}`나 `{status:"refused", reason:"BUSY|NO_SUPERVISOR"}`로 답한다.
2. **재시작의 실체**: 정상 정지 절차(새 요청 거절 → `server.shutdown=graceful`로 처리 중 요청 마무리(기다리는 한도는 Boot의 `spring.lifecycle.timeout-per-shutdown-phase`, 기본 30초이며 감독자의 정지 한도 `TimeoutStopSec`·`--StopTimeout`(60초)보다 작게 둔다) → 부모 컨텍스트 닫기, 작업 이력 정리는 재시작 복구가 이미 한다)를 거친 뒤 **고정 종료 상태 `RESTART_EXIT_STATUS = 75`로 JVM이 끝난다**. 75와 78은 관례 값(BSD `sysexits.h`의 `EX_TEMPFAIL`·`EX_CONFIG`)에서 빌린 임의의 값이고 0(정상)·1(일반 오류)·143(SIGTERM)과 겹치지 않으면 된다.
3. **서비스 정지의 실체**: 같은 정상 정지 절차 뒤 종료 상태 0(systemd의 SIGTERM으로 끝나면 143)이다. 정지는 다시 시작을 요구하지 않는다.
4. **systemd**(U19·U21의 유닛 파일): `Type=simple`, `Restart=on-failure`, `RestartSec=2s`, `SuccessExitStatus=143`(JVM이 SIGTERM으로 끝날 때), `RestartPreventExitStatus=78`(시작 실패 가드, 아래 5번: 설정 오류가 재시작 루프를 만들지 않게), `TimeoutStopSec=60`. 75는 0이 아니므로 `on-failure`가 다시 시작하고, `systemctl stop`은 다시 시작하지 않는다(위 문서). 감독자 판정: systemd가 서비스에 주는 환경 변수 `INVOCATION_ID`가 있으면 `SupervisorKind.SYSTEMD`다 `[미확인: systemd.exec 문서에서 확인하지 않음. U19가 확인하고, 다르면 진입점이 유닛 파일의 Environment= 줄로 알리게 바꾼다]`.
5. **시작 실패 가드(2.x의 「ENV 변경 후 기동 실패 시 이전 ENV로 복구」)** `StartupFailureGuard`: `EnvironmentFileEditor`가 환경 파일을 저장할 때 백업(소유자 전용)과 표식 파일 `environment-change.pending`(백업 이름, 시각, 시도 수 0)을 데이터 폴더에 쓴다. `main`이 `SpringApplication.run`을 감싸 시작이 예외로 끝나면: 표식이 있고 시도 수가 0이면 백업을 원자적으로 되돌리고 시도 수를 1로 적은 뒤 75로 끝난다(감독자가 이전 환경으로 다시 띄운다). 표식이 없거나 시도 수가 1이면 78로 끝난다(감독자는 다시 띄우지 않는다). 시작에 성공해 `/health`가 UP이면 표식을 지운다. 설정 검증 실패(`LegacyKeyGuard`, 바인딩 검증)는 이 가드가 잡는다. JVM이 시작 중 죽는 경우(메모리 부족 등)는 잡지 못한다(한계).
6. **Windows 서비스(procrun)**: 진입점 `server.lifecycle.service.WindowsServiceEntry`의 `static void start(String[])`와 `static void stop(String[])`, 설치는 `--StartMode=jvm --StopMode=jvm --StartClass=… --StartMethod=start --StopClass=… --StopMethod=stop --StopTimeout=60 --Startup=auto`(문서의 옵션 이름 그대로, 7단계 패키징이 설치 스크립트에 쓴다). `start`는 Spring 컨텍스트를 올리고 **`stop`이 불릴 때까지 돌아오지 않는다**(문서: jvm 모드). `stop`은 정상 정지 절차를 시작하고 `start`를 풀어 준다. 진입점이 `SupervisorKind.WINDOWS_SERVICE`를 알린다.
   - 재시작 요청: 정상 정지 절차 뒤 JVM이 75로 끝난다. JVM이 서비스 프로세스 안에서(jvm 모드) 끝나므로 SCM이 `SERVICE_STOPPED` 보고 없는 종료로 보고 복구 동작을 대기열에 넣으리라고 **기대**한다(위 Microsoft 문서의 조건. 이 기대는 아래 `[미확인]`이 확인할 때까지 가정이다). 설치 스크립트가 `sc.exe failure <이름> reset= 86400 actions= restart/5000/restart/30000//`(두 번까지, 그 뒤 멈춤: 재시작 루프 방지)와 `sc.exe failureflag <이름> 1`을 설정한다. **`[미확인]` procrun `jvm` 모드에서 JVM이 `System.exit(75)`로 끝날 때 서비스 프로세스가 어떤 상태를 SCM에 보고하는지는 procrun 문서에 없다.** S4-RS-05(Windows CI에서 실제 서비스를 설치해 재시작 요청 뒤 새 프로세스 id가 생기는지)가 통과하기 전에는 Windows 서비스의 재시작 지원을 주장하지 않는다. 통과하지 못하면 대비책은 `NoSupervisor`와 같다(`{status:"refused", reason:"NO_SUPERVISOR"}`, 화면이 서비스를 직접 다시 시작하라고 안내). 대비책으로 내려가도 계약은 바뀌지 않는다.
   - 시작 실패(78): `sc failure`의 두 번까지만 다시 시작하므로 루프가 없다.
7. 시험: S4-RS-01(재시작 요청 → 종료 상태 75, 정리 순서), S4-RS-02(`Busy` 거절 조건 각각), S4-RS-03(시작 실패 가드 두 경로), S4-RS-04(systemd 유닛 파일의 `Restart`·`SuccessExitStatus`·`RestartPreventExitStatus` 값), S4-RS-05(Windows 서비스 재시작 실제 확인), S4-RS-06(`stop` 호출 뒤 `start`가 돌아옴), S4-RS-07(`NoSupervisor`), S4-RS-08(서비스 정지는 종료 상태 0).

### B-12 감사 모델의 일반화와 로그인 거절의 묶음 기록 (검토 N8·N9, 결정 L-6, 사용자 결정 U-2)
`[사실]` `app.audit.AuditEvent(AuditAction action, OperationId operationId, String note)`는 작업 id가 null이 아니어야 하고(`AuditEvent.java`), `AuditAction`에는 작업 동작 11개뿐이다(`AuditAction.java`). 그래서 `ADMIN_LOGIN_REFUSED`, `DEVICE_TOKEN_*`, `files stat`의 상관 키를 담지 못한다. `app.audit`는 어느 단위의 쓰기 범위에도 없었고, 감사 표 열 정의도 없었다.
`[결정]`
1. **감사 사건의 일반화**: `AuditEvent(AuditAction action, AuditSubject subject, String correlationKey, String note)`. `AuditSubject`는 sealed: `OperationSubject(OperationId)`, `DeviceSubject(DeviceTokenId)`, `NoSubject`. `AuditAction`의 각 상수가 자기 주체 종류를 선언하고(생성자 인자), `AuditEvent`의 생성자가 `action.subjectKind() == subject.kind()`를 확인한다(OCP: 새 동작은 상수 하나, 기존 작업 동작 11개는 그대로 `OPERATION`이다). 새 상수: `DEVICE_TOKEN_ISSUED`·`DEVICE_TOKEN_REVOKED`(`DEVICE`), `OPERATION_RECONCILED`(`OPERATION`), `FILES_STAT`(`NO_SUBJECT`, `correlationKey` = 호출자의 `operationKey`). `note`는 512자 이하(호출자가 이미 가린 짧은 문구)다. 동작 이름의 선 표기는 기존 규칙(소문자, 하이픈)을 따른다.
2. **표 `audit_record`**(`V9`): `id CHAR(48) PK`(앱이 만든 48 hex), `occurred_at TIMESTAMP(6) NOT NULL`, `action VARCHAR(32) NOT NULL`(값 목록은 V1의 `CASE WHEN … END = 1` 방식), `subject_kind VARCHAR(9) NOT NULL`(`OPERATION`·`DEVICE`·`NONE`), `subject_id VARCHAR(48)`(작업 id 48 hex 또는 기기 토큰 id 22자, 종류가 `NONE`이면 NULL이라는 CHECK), `correlation_key VARCHAR(128)`(`operationKey` 문법의 상한), `actor_kind VARCHAR(6) NOT NULL`(`OWNER`·`DEVICE`·`SYSTEM`), `note VARCHAR(512) NOT NULL`. 외래 키는 없다(감사는 작업·기기 표와 독립). 인덱스 `(occurred_at, id)`, `(subject_kind, subject_id)`. 모든 열이 짧은 값이라 본문 규칙(B-15)에 걸리지 않는다. **추가만 한다**: 갱신·삭제 SQL이 코드에 없고(S4-AS-02, ArchUnit), 고정 한도와 정리가 없다(사용자 확정 규칙).
3. **쓰기 의미**: `append`는 커밋 뒤 반환하고 실패하면 `AuditFailedException`(기존 `AUDIT_FAILED` 경로)이다. 소유자 동작의 감사(`DEVICE_TOKEN_ISSUED`·`REVOKED`, `OPERATION_RECONCILED`)는 그 동작과 같은 트랜잭션에서 쓴다(`@Transactional` 유스케이스 하나가 `DeviceTokenStore`와 `AuditLog`를 부른다): 감사가 실패하면 동작도 없다.
4. **묶음 감사 표 `audit_refusal_streak`**(`V9`): 인증 전·거절 사건(`ADMIN_LOGIN_REFUSED`, `DEVICE_TOKEN_REFUSED`)은 한 건씩 `audit_record`에 넣지 않는다(익명 요청이 표를 채우지 못하게, B-2·B-4a). 열: `kind VARCHAR(20)`, `source_key VARCHAR(64)`(출처, IPv6는 앞 64비트, 전역 넘침이면 `*`), `subject_key VARCHAR(255)`(이유와 세부: `TOKEN_MISMATCH`, `OWNER_MISMATCH:<발급자>|<계정 식별자>`, `DEVICE_TOKEN_REVOKED:<토큰 id>`), `streak_no BIGINT`, `attempt_count BIGINT NOT NULL`(1 이상), `first_at`·`last_at TIMESTAMP(6) NOT NULL`(`first_at <= last_at`), `state VARCHAR(4) NOT NULL`(`OPEN`·`DONE`). 기본 키 `(kind, source_key, subject_key, streak_no)`. 포트 `app.audit.RefusalStreakLog.record(kind, sourceKey, subjectKey, at)`: 열린 묶음이 있고 마지막 시각이 15분 안이면 `UPDATE … SET attempt_count = attempt_count + 1, last_at = ?`, 아니면 새 `streak_no`로 `INSERT`한다. 같은 묶음의 첫 실패가 동시에 둘 들어와 기본 키가 부딪치면 진 쪽이 위 `UPDATE`로 다시 한 번 시도한다(횟수를 잃지 않는다, S4-AS-05). 성공한 로그인·사용은 그 출처의 열린 묶음을 `DONE`으로 닫는다. 이 표가 감사 표 중 **행이 바뀌는 유일한 표**이고, 바뀌는 열은 `attempt_count`·`last_at`·`state` 셋이다. 행은 지우지 않는다. 쓰기가 실패하면 로그만 남기고 거절 응답은 그대로 나간다(거절은 실패해도 거절이다. 소유자 동작의 감사와 달리 닫지 않는다).
5. 새 행을 여는 전역 속도 상한(분당 60)과 넘침 행 `source_key = '*'`는 B-4a다.

### B-13 desktop-open 키와 lease 기록 (검토 N10, 결정 L-6)
`[사실]` 「닫히거나 만료된 키는 다시 열지 않는다」는 근거가 되는 DB 기록이 없었다: desktop 표가 없고 `ck_operation_kind`에 `DESKTOP`이 없다(V1). 2.x는 서버가 발급한 키를 메모리에 두었다(`src/application/desktop.mjs:12-35`).
`[결정]` desktop lease는 작업(`operation`)이 아니므로 작업 표에 넣지 않고 표 하나를 따로 둔다: `desktop_lease`(`V12`): `session_id CHAR(36) PK`(도구가 돌려주는 `sessionId` UUID), `operation_key VARCHAR(128) NOT NULL`(`UNIQUE`: 호출자 키는 전역에서 하나), `input_digest CHAR(64) NOT NULL`(대상·`expectedTargetRevision`·backend의 해시: 같은 키·다른 입력이면 `OPERATION_INPUT_CONFLICT`), `target_id VARCHAR(32) NOT NULL`, `target_revision CHAR(64) NOT NULL`, `backend VARCHAR(4) NOT NULL`(`XVNC`·`RDP`·`RFB`), `epoch BIGINT NOT NULL`(1 이상), `state VARCHAR(7) NOT NULL`(`OPEN`·`CLOSED`·`EXPIRED`), `opened_at`·`expires_at TIMESTAMP(6) NOT NULL`, `closed_at TIMESTAMP(6)`, `version BIGINT NOT NULL`. 외래 키 없음. 모든 열이 짧은 값이다.
- 같은 키·같은 입력·`OPEN`이면 같은 `sessionId`·`epoch`를 돌려준다(새 lease 없음). `CLOSED`·`EXPIRED`이면 다시 열지 않고 닫힘을 알린다(기존 `DesktopErrorCode.DESKTOP_STALE_LEASE`(닫힘)·`DESKTOP_EXPIRED`(만료)에 대응. 2.x의 정확한 대응은 U5가 `desktop.mjs`와 대조해 확정 `[미확인]`).
- 재시작: lease의 실체(화면 세션·프로세스·터널)는 재시작으로 사라지므로 시작 복구가 `OPEN`인 행을 `EXPIRED`로 바꾼다(재시작 복구의 작업 처리와 같은 시점, 보낸 것은 다시 보내지 않음). 그래서 재시작 뒤에도 같은 키로 다시 열 수 없다.
- 담당: U5(`app.desktop.DesktopLeaseStore` 포트, `store.desktop.JdbcDesktopLeaseStore`). 시험: S4-MC-06·07(재시작 뒤에도), S4-MC-11(같은 키·다른 대상은 충돌), S4-MC-12(시작 복구가 `OPEN`을 `EXPIRED`로).

### B-14 files remove·stat의 키 멱등 기록 (검토 N11, 결정 L-6)
`[사실]` `staged_action`은 기본 키 `(artifact_id, operation_key)`이고 `ck_staged_action_kind`가 `COPY`·`MOVE`만 허용하며 대상 열(`target_id` 등)이 NOT NULL이다(V2). `remove`·`stat`의 키 멱등과 입력 충돌 판정을 담을 곳이 없었고 U5의 쓰기 범위에 `store`·`app`이 빠져 있었다.
`[결정]`
- `remove`: 새 표 `staged_remove`(`V13`): `artifact_id CHAR(48) NOT NULL`, `operation_key VARCHAR(128) NOT NULL`, `input_digest CHAR(64) NOT NULL`(`uuid`+동작), `state VARCHAR(8) NOT NULL`(`RESERVED`·`SETTLED`), `settled_at TIMESTAMP(6)`, 기본 키 `(artifact_id, operation_key)`, `fk_staged_remove`는 `artifact(id)`(`staged_action`과 같음). `staged_action`을 넓히지 않는 이유: 그 표의 대상 열 NOT NULL·`COPY|MOVE` 제약을 풀려면 DBMS마다 문법이 다른 `ALTER COLUMN`이 필요하다. 키의 범위는 `staged_action`과 같이 파일(uuid)이다. 같은 `(uuid, 키)`로 `remove`를 다시 부르면 처음 결과를 돌려주고(이미 소비된 파일에서도), 같은 `(uuid, 키)`가 `copy`·`move`에 이미 쓰였으면 `OPERATION_INPUT_CONFLICT`다(두 표를 한 트랜잭션에서 본다, `app.artifact.StagedFileActions`).
- `stat`: 읽기라 멱등 판정에 키를 쓰지 않는다. 호출자의 `operationKey`는 감사 `FILES_STAT`의 `correlation_key`로만 남긴다(B-12).
- 담당: U5. 시험: S4-MC-03(`copy`), S4-MC-04(`stat`), S4-MC-13(`remove` 같은 키·같은 입력 두 번 = 같은 결과, 같은 키·`copy` = 충돌).

### B-15 작업 본문의 저장 방식 (검토 N17, 결정 L-7)
`[사실]` 사용자 확정 규칙: 본문(긴 텍스트·바이트)은 DB 열이 아니라 첨부 메타 표 + 파일이고, 첨부 표 pk만 연결하며 외래 키가 없다. 그런데 `V1__operation_journal.sql`은 `${long_text}` 열 10개를 가진다(`operation_command`: `command_text`, `caller_context`, `display_context`; `operation_source`: `launcher_command`, `argv_json`, `caller_context`, `display_context`; `operation_download`: `caller_context`, `display_context`; `operation_result`: `result_texts`). `docs/migration/migration-runner.md`의 「긴 텍스트 열」 절도 이를 「첨부 표 이전(D-0092)에서 따로 다룬다」고 미뤘다. 그 이전이 이 단위(U18)다.
`[결정]` **분류 기준**: 호출자가 쓰는 자유 서식이거나 4096바이트를 넘길 수 있는 값은 **본문**(첨부), 문법으로 상한이 닫혀 있고 4096바이트 이하인 식별자·경로·이름·요약은 **짧은 값**(열)이다.

| 열 | 표 | 상한과 근거 | 분류 | 처리 |
|---|---|---|---|---|
| `command_text` | `operation_command` | 32,768 바이트(`CommandText.MAX_BYTES`) | 본문 | 첨부 `COMMAND_TEXT` |
| `caller_context` | command·source·download | 8,192 문자(`CallerContext.MAX_CHARS`, UTF-16 단위, UTF-8로 최대 약 24 KiB) | 본문(자유 서식) | 첨부 `CALLER_CONTEXT` |
| `display_context` | command·source·download | 상한은 `Utf8Text`를 만드는 곳에서 읽어야 한다 `[미확인]` | 본문(소유자에게 보이는 문구) | 첨부 `DISPLAY_CONTEXT` |
| `launcher_command` | `operation_source` | `CommandText` 상한 32,768 바이트, Windows 수신기 줄 8,000자 | 본문(4096 초과 가능) | 첨부 `LAUNCHER_COMMAND` |
| `argv_json` | `operation_source` | 인자 목록 JSON, 상한은 `SourceArguments`에서 읽어야 한다 `[미확인]` | 본문 | 첨부 `ARGV_JSON` |
| `result_texts` | `operation_result` | 결과 문장 목록, 상한은 `ExecutionOutcome.texts` 생성처에서 읽어야 한다 `[미확인]` | 본문 | 첨부 `RESULT_TEXTS` |
| `cwd VARCHAR(512)` | `operation_source` | 512 바이트(`SourceInput.MAX_CWD_BYTES`) | 짧은 값 | 열 유지 |
| `remote_path VARCHAR(4096)` | upload·download·artifact·staged_action | 경로 문법 4096(`file-helper.py:33-39`, S3 4.4 2번) | 짧은 값(문법으로 닫힘, 충돌 키·표시에 쓰임) | 열 유지 |
| `file_name 255`, `request_key/owner_id/target_id 512`, `command_name 512`, `release_tarball 512`, 감사 `note 512` | 여러 표 | 문법·타입이 상한을 닫음 | 짧은 값 | 열 유지 |

- 「미확인」 세 칸의 상한은 U18이 도메인 타입에서 읽어 확정한다. 상한이 문법으로 닫혀 있고 4096바이트 이하임이 증명되는 열은 분류 기준에 따라 열로 남겨도 된다. 그 판정과 한 본문의 첨부 크기 상한(`AttachmentStore`가 거절하는 값, 구현이 정한다)을 S4-AT-06이 고정한다.
- **첨부 메타 표 `attachment`**(`V14`): `id CHAR(48) PK`(앱이 만든 48 hex), `role VARCHAR(16) NOT NULL`(위 여섯 값 중 하나, CHECK는 V1 방식), `size_bytes BIGINT NOT NULL`(0 이상, 상한 이하), `sha256 CHAR(64) NOT NULL`, `created_at TIMESTAMP(6) NOT NULL`. **경로 열이 없다**: 파일은 `BRIDGE_DATA_DIR/attachments/<id 앞 2자>/<id>`이고 경로는 id에서만 만든다(경로 주입 없음).
- **연결**: 위 열 10개를 지우고(`V15`) 각 표에 `<이름>_attachment_id CHAR(48) NOT NULL`을 더한다. 첨부 표 pk만 담고 **외래 키는 두지 않는다**(규칙). 한 본문이 여러 작업에서 같을 수 있어도 첨부는 작업마다 따로 만든다(내용 공유로 삭제 규칙이 복잡해지지 않게).
- **쓰기 순서**(내구성): 파일을 임시 이름으로 쓰고 `fsync` 후 원자적 이름 변경(`PrivateStorage`로 소유자 전용) → 한 트랜잭션에서 `attachment` 행과 작업 행을 넣는다. 중간에 죽으면 행 없는 파일(고아)이 남을 수 있으나 이력이 아니므로 시작할 때 행이 없는 파일 중 한 시간이 지난 것만 치운다(`AttachmentReaper`). **행이 있는 첨부는 어떤 경우에도 지우지 않는다**(작업 이력 고정 한도 금지·이력 삭제 금지).
- **읽기**: `AttachmentStore.read(id)`가 크기와 `sha256`을 확인한다. 파일이 없거나 해시가 다르면 `AttachmentUnreadableException`이다. 상태를 바꾸는 경로(`load`)는 실패하고, 이력·관측 같은 읽기 보기는 그 작업의 본문 자리를 「본문을 읽을 수 없음」으로 표시한다.
- **마이그레이션**: 3.0.0은 배포 전이라 `V1`~`V14`를 적용한 DB는 개발·CI의 일회용 DB뿐이다. `V15`는 대상 표가 비어 있어야 하고(비어 있지 않으면 `NOT NULL` 열 추가가 실패하며 시작을 멈춘다. 메시지가 DB를 새로 만들라고 안내한다), DBMS 공통 SQL로는 파일을 만들 수 없어 본문을 옮기는 단계를 두지 않는다. `portal-migrate`(U13)는 저장소 어댑터로 쓰므로 U18 뒤에는 자동으로 첨부를 쓴다.
- 시험: S4-AT-01(여섯 역할 왕복), S4-AT-02(`V1`~`V15` 뒤 열 10개가 없고 `attachment` 열 정의가 위와 같음), S4-AT-03(첨부 표에 외래 키가 없음, 다른 표도 첨부 표를 가리키는 외래 키가 없음), S4-AT-04(쓰기 도중 실패 시 행이 없고 고아 파일만 남음), S4-AT-05(해시 불일치 읽기), S4-AT-06(본문 열이 없고 짧은 값 열의 길이 상한이 분류표와 같음), S4-AT-07(행이 있는 첨부는 정리가 지우지 않음).

### B-16 Windows 대상 보완: `icacls`·`taskkill`과 명령 분류 (사용자 결정 U-4, 결정 L-9)
`[사실]`
- 2.x Windows 도우미는 새 파일에 비공개 DACL(상속 차단, 허용 주체 3개)을 걸고(`windows-file-native.cs:155-168`), 덮어쓸 때 기존 DACL·소유자·시각·필수 무결성 레이블을 보존했다(`:372-436`). 3단계는 SFTP로는 둘 다 못 한다고 판정했다(`stage3-design.md` 4.4절 20·23·24행 「불가」).
- `icacls`(https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/icacls, 2026-11-21 읽음): `/save <aclfile>`은 **DACL**을 파일에 저장하고 `/restore <aclfile>`은 저장한 DACL을 **디렉터리 인자** 아래 파일에 적용한다. `/setowner <user>`는 소유자를 바꾼다. `/setintegritylevel`은 무결성 ACE를 더한다. SID는 숫자 형식이면 앞에 `*`를 붙인다. 상속 차단의 철자는 표에 `/inheritancelevel: e|d|r`로 적혀 있으나 널리 쓰이는 형식은 `/inheritance:r`이라 `[미확인]`(S4-OS-02가 실제 Windows에서 확인).
- `taskkill`(https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/taskkill, 같은 날 읽음): `/pid <id>`로 프로세스를, `/t`로 그 프로세스가 시작한 자식까지, `/f`로 강제 종료한다.
`[결정]` 둘 다 **대상 호스트에서 SSH exec로 실행하는 고정 명령**이다(`FixedRemoteCommand`, `WindowsCommandWrapper`가 인코딩). 포털 본체는 로컬 프로세스를 실행하지 않는다(원칙 유지). 사용자 입력은 명령 줄에 들어가지 않는다: 경로는 `WindowsRemotePath` 검증을 거친 값이고, PID는 `^[1-9][0-9]{0,9}$`, SID는 상수 또는 같은 고정 PowerShell이 읽은 값이다.
1. **업로드 임시 파일의 비공개 권한**: SFTP로 임시 파일을 쓴 뒤 게시 전에 `icacls "<임시>" /inheritance:r /grant:r "*<계정 SID>:(F)" "*S-1-5-18:(F)" "*S-1-5-32-544:(F)"`(계정, SYSTEM, Administrators의 3주체, 2.x와 같은 수. 이름이 아닌 숫자 SID라 영어 이름에 의존하지 않는다, D-0140). 계정 SID는 접속한 계정의 `WindowsIdentity` 값을 읽는 고정 PowerShell이 준다. 실패하면 게시하기 전에 실패하고 임시 파일을 치운다(게시 뒤 실패가 아니다).
2. **덮어쓰기 때 보존**: 게시 직전에 `icacls "<대상>" /save "<ACL 파일>" /c`로 기존 DACL을 저장하고(ACL 파일은 소유자 전용 임시 폴더), 게시 뒤 `icacls "<상위 폴더>" /restore "<ACL 파일>" /c`로 적용한다. **`/save`·`/restore`는 DACL만 다루므로**(위 문서) 소유자와 무결성 레이블은 이 명령으로 보존되지 않는다. 소유자는 `/setowner`로, 레이블은 `/setintegritylevel`로 되돌릴 수 있는지 권한에 달려 있다 [미확인: 일반 SSH 계정이 /setowner로 기존 소유자를 되돌릴 수 있는지(WO 권한), S4-OS-04·05]. 결과는 보존 상태(`dacl`·`owner`·`label` 각각 `restored`·`skipped`·`unsupported`·`failed`)를 작업 결과 문장에 적는다. DACL 복원이 게시 뒤에 실패하면 「게시 뒤 실패는 결과 불명」(D-0035) 규칙을 따른다.
3. **취소·기한 초과의 원격 종료**: 고정 수신기(`WindowsCommandWrapper`)가 시작하며 자기 PID를 표식 줄로 알리고(출력 캡처에 섞이지 않게 실행기가 그 줄을 떼어 낸다), 취소·기한 초과 때 같은 연결의 새 exec 채널에서 `taskkill /pid <PID> /t /f`를 실행한다(`/t`는 자식까지, 문서). 결과는 기존 `signal_delivery`(`REQUESTED`·`NOT_SENT`)와 `cleanup`(`COMPLETE`·`INCOMPLETE`)에 담긴다: 지금 Windows의 `UNSUPPORTED`가 `REQUESTED`로 올라간다. 표식 줄을 주고받는 방식이 Win32-OpenSSH에서 안정적인지는 `[미확인]`이고(S4-OS-06) 안 되면 임시 파일에 PID를 쓰는 방식으로 바꾼다(결과 모양은 같다).
4. **명령 분류의 대칭화**: 2.x는 두 cmdlet(`Microsoft.PowerShell.Utility\Get-Date`, `Microsoft.PowerShell.Management\Get-Location`)만 `read-only`이고 나머지는 `safe`였다(`WindowsTargetPlatform.classify`의 Javadoc, `docs/windows-targets.ko.md`). Linux 대상은 `read-only < safe < destructive < privileged` 네 등급을 쓴다(`CommandClass`). 3.0은 Windows도 네 등급을 쓴다. `read-only`는 지금의 두 개에서 **늘리지 않는다**(자동 실행 범위를 넓히는 새 정책이 되므로). `destructive`·`privileged`를 더한다: `ssh.platform.WindowsCommandClassifier`가 데이터 파일 `windows-command-classes.json`(명령 이름 소문자 → 등급)을 읽고, 파이프라인·`;`로 이어진 명령은 부분마다 분류해 `CommandClass.max`로 합친다. 모르는 이름은 `safe`(Linux와 같음). 별칭(`rm`, `del`, `ri`, `rd`, `rmdir`, `kill` 등)은 같은 등급으로 모두 적는다. 문자열을 그대로 실행하는 것(`Invoke-Expression`, `iex`, `Invoke-Command`, `cmd`, `powershell`, `pwsh`, `Start-Process`, `wsl`)은 안을 볼 수 없으므로 `privileged`다.
   - 초기 목록(예시, 정본은 데이터 파일): `destructive` = `Remove-Item`, `Clear-Content`, `Clear-Disk`, `Format-Volume`, `Remove-Partition`, `Stop-Process`, `Stop-Service`, `Restart-Service`, `Stop-Computer`, `Restart-Computer`, `taskkill`, `shutdown`, `format`, `diskpart`, `rmdir`, `del`; `privileged` = `Set-ExecutionPolicy`, `New-LocalUser`, `Set-LocalUser`, `Add-LocalGroupMember`, `net`(user·localgroup), `runas`, `icacls`, `takeown`, `reg`, `sc`, `schtasks`, `Set-Service`, `Enable-PSRemoting`, `Set-ItemProperty`(레지스트리 경로), 위의 문자열 실행기. U20이 Linux 쪽 표의 항목과 1대1로 대응시켜 목록을 완성하고, 모든 이름에 시험 줄 하나씩을 둔다(`S4-OS-07`~`S4-OS-10`).
   - `deniesName`과 `commandLineGrammar`는 바꾸지 않는다.
5. **지원하지 않는 것과 이유**: 소유자·레이블 보존이 권한에 막히는 경우(2번), Win32-OpenSSH가 표식 줄을 못 전하는 경우(3번)는 해당 동작이 `unsupported`로 보고될 뿐 요청이 거절되지 않는다. 대상 파일 시스템이 ACL이 없는 종류(FAT·exFAT)이면 `icacls`가 실패해 업로드가 게시 전에 실패한다(이유를 결과 문장에 적음, B-17).

### B-17 파일 시스템·설치·PC 클라이언트 자동 시작 (사용자 결정 U-5, 결정 L-9)
`[사실]` 서버의 데이터 폴더가 지원하는 파일 저장소인지는 운영체제 이름이 아니라 저장소가 제공하는 속성 보기로 가른다: `posix` 보기가 있으면 `PosixPrivateStorage`, `acl` 보기가 있으면 `AclPrivateStorage`, 둘 다 없으면 `PrivateStorages`가 거절한다(`app.storage.PrivateStorage` Javadoc, `UnsafeStorageException`). Windows 대상의 볼륨이 NTFS인지는 SFTP로 알 수 없었다(`stage3-design.md` 4.4절 18행).
`[결정]`
1. **지원 파일 시스템과 거절 안내(장부 D-0145)**: 서버의 데이터 폴더는 `posix` 또는 `acl` 보기를 지원하는 파일 저장소여야 한다(Linux의 일반 로컬 파일 시스템, Windows의 NTFS). 지원하지 않으면 시작을 멈추고 메시지에 (a) 저장소 이름과 종류(`FileStore.name()`·`type()`), (b) 없는 보기, (c) 해결 방법(`BRIDGE_DATA_DIR`을 지원 종류의 저장소로 옮김)을 적는다. 그 밖에 H2 파일 DB를 쓰므로 네트워크 파일 시스템(NFS·SMB 등)은 파일 잠금·원자적 이름 변경이 믿을 수 없어 권하지 않는다: 종류 이름으로 알아볼 수 있으면 시작을 멈추지 않고 경고를 한 줄 남긴다 `[미확인: 종류 이름 목록과 H2의 잠금 동작은 CI(Linux ext4·tmpfs, Windows NTFS)로 확인]`. 어떤 구체 파일 시스템이 어떤 보기를 알리는지(`vfat`·`exfat`·`ReFS` 등)는 `[미확인]`이고 시험(S4-AS-06)이 확정한다. 이 판정은 새 `ErrorCode`가 아니라 시작 실패다.
2. **Windows 대상 볼륨**: NTFS만 허용하던 2.x 검사(`windows-file-native.cs:182`)는 옮기지 않는다. 대신 B-16의 `icacls`가 ACL이 없는 볼륨에서 실패하는 것을 거절 이유로 쓴다(결과 문장: 「대상 볼륨이 ACL을 지원하지 않는다」). `[미확인]` FAT에서 `icacls`의 실제 오류(S4-OS-03).
3. **PC 클라이언트 자동 시작**(feature-map Q4 B안: 상주 프로세스): Linux는 `systemd --user` 유닛(`WantedBy=default.target`, 설치 스크립트가 만든다). **Windows는 `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`의 값**으로 등록한다(`reg add`, 관리자 권한 불필요). 근거: Microsoft 「Run and RunOnce Registry Keys」(https://learn.microsoft.com/en-us/windows/win32/setupapi/run-and-runonce-registry-keys, 2026-11-21 읽음): 사용자용 `Run` 키는 그 사용자가 로그온할 때마다 프로그램을 실행하고, 값은 260자 이하의 명령줄이며, 시스템은 `Run`의 프로그램을 언제 실행할지(얼마나 빨리) 보장하지 않는다. 그래서 (a) 설치 경로가 명령줄 260자를 넘으면 설치 스크립트가 더 짧은 경로를 쓰거나 거절하고, (b) 훅 런처가 상주 프로세스가 없을 때 그것을 직접 띄우는 경로를 둔다(자동 시작이 늦거나 크래시한 경우의 대비). 작업 스케줄러(로그온 시 작업, 실패 시 재시작 설정이 가능)는 일반 사용자가 로그온 트리거 작업을 만들 수 있는지 확인하지 못해 `[미확인]` 고르지 않았다. 관련 코드: `portal-client/**/autostart/**`(U21).
4. **Linux 설치 스크립트(사용자 결정 U-5 「Debian·RHEL 계열까지」)**: 설치 스크립트는 `/etc/os-release`의 `ID`·`ID_LIKE`로 Debian 계열(`apt`)과 RHEL 계열(`dnf`)을 구분한다. 지원하지 않는 계열(SUSE `zypper`, Alpine `apk`, Arch `pacman`, RHEL 7 `yum`)은 이유와 함께 거절한다 — 이유는 원리적 한계가 아니라 지원 범위를 사용자가 정했기 때문이다. CI는 배포판별 설치 시험(Debian 12, Ubuntu 22.04·24.04, Rocky 또는 Alma 9, Fedora 최신)과 arm64 시험을 둔다. arm64 러너의 이름과 컨테이너 안에서 서비스 관리자를 어떻게 시험하는지는 U21이 GitHub 문서로 확정한다 `[미확인]`. Java 25 런타임을 배포판 패키지로 구할 수 있는지도 `[미확인]`이며 U21이 확인한다(구할 수 없으면 설치 스크립트가 JRE 묶음을 함께 배포하는 방식을 선택지로 올린다).
5. 시험: S4-IN-01(os-release 판별 표: 지원 계열·비지원 계열의 거절 메시지), S4-IN-02(배포판 설치 CI), S4-IN-03(arm64 설치 CI), S4-IN-04(Windows `Run` 값 등록과 260자 검사), S4-IN-05(Linux `systemd --user` 유닛), S4-IN-06(훅 런처가 상주 프로세스 없을 때 띄움).

## C. 확정된 결정과 보안 판단

### C.1 확정: RFB(VNC) 클라이언트는 `io.xpipe:vernacular` 1.21(R1)
소유자 결정(2026-11-21)이다. 이 문서에 미결 항목은 없다. 구현은 데스크톱 단위(이 문서 범위 밖)이며 이 문서의 U2~U9와 기준본 개정에는 영향이 없다. 조사에서 확인된 성질을 그 단위의 제약으로 둔다.
- MIT 라이선스다.
- `java.net.Socket`만 받는다. SSH direct-tcpip 채널에 붙이려면 Socket 어댑터나 로컬 포워드가 필요하다.
- Tight·ZRLE 인코딩을 지원하지 않는다. 서버가 보내는 인코딩 협상은 지원하는 것으로 한정한다.
- 유지보수자가 한 명이다.
- Java 25와 Xvnc 조합은 시험된 적이 없다. CI의 실제 접속 시험(Xvnc 대상)으로 확인한다. 통과 전에는 동작을 주장하지 않는다.
- 라이브러리 타입은 포트 밖으로 새지 않는다. 우리 인터페이스(RFB 클라이언트 포트) 뒤에 두어 교체할 수 있게 한다.
- 시험 계획(데스크톱 단위가 만든다): RFB 실제 접속 시험, Socket 어댑터 시험, 라이브러리 타입이 포트 밖에 없음을 단정하는 구조 시험.

### C.2 확정: 고위험 승인의 추가 확인 없음(Q1)과 로그인(Q2)
- Q2: 로그인은 OIDC + 토큰, OIDC는 계정 하나(`sub`), passkey 없음. A.3절과 B-1.6에 반영했다.
- Q1: 승인·거부·정리·환경 편집·정책 변경은 허용 계정의 로그인 세션(또는 관리자 토큰 Bearer)과 CSRF·Origin 검사만으로 받는다.
- 잔여 위험: 추가 확인이 없으므로 로그인된 브라우저를 쓸 수 있는 사람(잠기지 않은 PC, 세션 쿠키를 얻은 악성 확장)은 소유자와 똑같이 승인할 수 있다. 이 문서가 줄이는 수단은 쿠키 속성(`__Host-`, `HttpOnly`, `SameSite=Lax`: 사용자 결정 U-1, OIDC 콜백이 돌아오려면 Strict는 안 된다, B-4), 유휴 30분·절대 12시간 수명, CSRF·Origin 검사, 로그인 시도 속도 제한(B-4a), 모든 승인의 감사 기록이다. 승인 자체를 한 번 더 확인하는 수단은 없으며, 이 위험은 사용자가 고른 것이다. 기기 토큰은 승인 권한이 없으므로 이 위험을 넓히지 않는다.

### C.3 포털 서버가 Windows일 때의 원격 데스크톱 (사용자 결정 U-3: 지원, RDP 구현 방식은 미정)
사용자 결정 「RDP까지 지원」이다. 이전 계획 문장 「Windows 서버는 데스크톱 전부 미지원」은 **폐기**한다(0.4절).
- RFB(붙인 VNC 서버, 원격 Linux의 Xvnc)는 서버 OS와 무관하게 지원한다. 구현이 Java 클라이언트(C.1)라 서버가 Windows여도 같다.
- RDP는 **지원 대상이고 구현 방식은 미정이다(조사 뒤 확정)**. 2.x는 Linux 중계기에서 전용 Xvnc와 네이티브 FreeRDP 클라이언트를 실행했다(`docs/desktop.ko.md` 「Windows `rdp`」). 3.0은 포털 본체의 로컬 프로세스 실행 금지 원칙이 있어 같은 방식을 그대로 쓸 수 없다. 이 문서는 방식을 정하지 않는다: 후보·결정·승인이 나오면 이 절과 F.4절의 표 칸을 고치는 변경을 그 단위에 넣는다. 새 라이브러리가 필요하면 D.2절에 「승인 필요」로 올린다.
- 이 단위(U2~U9, U18~U21)의 기준본·설정·코드는 RDP 방식에 의존하지 않는다.

### C.4 미정: ssh-agent 인증 (사용자 결정 U-6)
사용자 결정 「라이브러리 조사 후 재질문」이다. 조사가 끝나지 않았으므로 이 문서는 방식도 지원 범위도 정하지 않는다. F.4절의 표 칸은 「미정」이다.

## D. 라이브러리

### D.1 승인 기록
| 이름 | 용도 | 판 | 승인 |
|---|---|---|---|
| `spring-boot-starter-security` | 보안 사슬, CSRF, 세션 | Spring Boot BOM 4.1.2 | 2026-11-21 「Spring Security 스타터」 |
| `spring-boot-starter-oauth2-client` | OIDC 로그인(OAuth2 Login, 발급자 discovery). Spring Security 프로젝트가 제공하는 OAuth2 클라이언트 지원이라 인가 코드 흐름·nonce·ID 토큰 검증을 직접 만들지 않는다(개발원칙 3.6) | Spring Boot BOM 4.1.2 | 2026-11-21 「Spring Security 스타터」와 관리자 로그인 「OIDC + 토큰」(리드 판정) |
| `io.xpipe:vernacular` | RFB(VNC) 클라이언트(데스크톱 단위, C.1) | 1.21 | 소유자 결정, 2026-11-21 |
| `spring-boot-starter-actuator` | `/health`, `HealthIndicator` | Spring Boot BOM 4.1.2 | 2026-11-21 「Spring Boot Actuator」 |
| `com.microsoft.playwright:playwright` | U7 브라우저 사례 시험(시험 의존만) | BOM 밖. 후보 1.65.1(Maven Central 최신 안정판, 2026-11-19 게시). U7 착수 때 그 시점의 최신 안정판으로 고정하고 이유를 빌드 파일에 적는다 | 2026-11-21 「Playwright for Java」 |
| Apache Commons Daemon procrun | Windows 서비스(7단계 패키징) | 1.6.1(Apache 배포 최신) | 2026-11-21 「Commons Daemon procrun」 |
| 이전 승인 | Java 25, Spring Boot 4.1.2, Spring AI BOM 2.0.2(MCP Java SDK), MINA sshd 2.20.1, H2, sqlite-jdbc(portal-migrate만) | - | 계획 정본 |

### D.2 승인 필요
이 문서의 U2~U9와 U18~U21이 쓰는 새 라이브러리는 D.1절이 모두 승인됐고, 이 개정이 새로 요구하는 라이브러리는 없다(재시작·정지, 감사, 첨부, Windows 대상 보완, 설치는 JDK·Spring Boot·이미 승인된 procrun으로 된다). 아래는 **아직 승인되지 않았고 이 문서가 넣었다고 가정하지 않는 것**이다.

| 항목 | 왜 필요한가 | 상태 |
|---|---|---|
| RDP 구현 방식의 라이브러리나 중계 구성 | C.3 | 조사 중, 방식 미정. 후보가 정해지면 판·라이선스·유지보수 상태와 함께 승인을 요청한다 |
| ssh-agent 인증의 라이브러리 | C.4 | 조사 중, 미정 |
| ACME 클라이언트(앱 내 TLS의 인증서 자동 발급, B-8a) | 선택 기능이고 3.0.0 범위에서 미정(F.6 항목 5) | 후보 미조사. 필요해지면 승인 요청 |

`spring-boot-starter-oauth2-resource-server`는 쓰지 않는다. 기기 토큰은 자체 발급 불투명 토큰(`pdt1.<id>.<secret>`, DB에는 SHA-256)이고 관리자·MCP 토큰은 설정의 고정값이라 JWT·introspection 검증이 필요 없다. Bearer 토큰은 `spring-boot-starter-security`가 주는 `AuthenticationFilter`·`AuthenticationManager`·`AuthenticationProvider` 구성요소에 우리 토큰 검증기를 끼워 받는다(A.3절).

다른 새 라이브러리는 이 단계에 없다. 계약 대조 시험은 지금처럼 시험 소스의 손 구현 스키마 검사기를 쓴다(새 JSON Schema 라이브러리를 들이지 않는다).

## E. 사례표

「고정 시험」은 그 사례를 단정하는 시험 클래스다. U1 행은 이 브랜치에서 고친 시험이고, 나머지는 각 단위가 만든다.

| 단위 | 사례 ID | 입력 | 기대 | 고정 시험 |
|---|---|---|---|---|
| U1 | S4-CB-10 | 도메인 봉투 `Envelope.success`·`failure` | `contractVersion`이 `"3.0"`이고 기준본 `envelope.schema.json`·도구 `outputSchema`를 위반하지 않음 | `ContractRecordsStructureTest` |
| U1 | S4-CB-11 | `targets`의 `GuiDescriptor` | `openRequest` 키가 없고 기준본 `guiDescriptor`(openRequest 없음)를 위반하지 않음 | `ContractRecordsStructureTest` |
| U1 | S4-CB-12 | 오류 코드 enum 8개 | 합집합 = 기준본 코드 − 2.x 비공개 4묶음 − `contract30Removals` + `REQUEST_CAPACITY_EXCEEDED`, `DesktopErrorCode` 69개, 합계 217 | `ErrorCodeBaselineTest` |
| U2 | S4-ST-01 | `settings-contract.json`의 `allowedKeys` 전부 | 각 키가 `BridgeProperties`의 한 성분에 바인딩되고(밑줄 → 점), 성분 쪽에서도 키 밖의 것이 없음(`JEV_API_KEY`는 비밀 파일 키로 따로 대조) | `BridgePropertiesKeysTest` |
| U2 | S4-ST-02 | `defaults`만 있는 환경 | 바인딩 값이 기준본 기본값과 같음 | `BridgePropertiesKeysTest` |
| U2 | S4-ST-03 | `BRIDGE_OIDC_OWNER_SUBJECT`에 쉼표로 두 값 | 시작 실패, 메시지에 키 이름, 값은 나오지 않음 | `BridgePropertiesValidationTest` |
| U2 | S4-ST-04 | 2.x 키 `BRIDGE_AUDIT_PATH`·`BRIDGE_ACCESS_AUD`가 든 환경(환경 변수와 env 파일 두 경로), 그리고 어느 목록에도 없는 `BRIDGE_FOO` | `LegacyKeyGuard`가 바인딩 전에 시작을 멈춘다(조용히 무시하지 않음). 앞의 둘은 기준본 `removedKeys`의 이유와 업그레이드 안내 절 이름을, 뒤의 것은 허용 키 밖이라는 사실을 메시지에 적고 값은 적지 않는다. `ignoreUnknownFields`에 기대지 않는다(A.1) | `LegacyKeyGuardTest` |
| U2 | S4-ST-05 | 비밀 키(`BRIDGE_MCP_TOKEN` 등) | `SecretValue`로 바인딩, `toString`·로그에 값 없음 | `BridgePropertiesValidationTest` |
| U2 | S4-ST-06 | `BRIDGE_TARGETS` JSON(Windows `pythonExecutable` 포함, 잘못된 JSON) | 앞은 대상 목록으로, 뒤는 시작 실패 | `BridgeTargetsConverterTest` |
| U2 | S4-AS-01 | 데이터 폴더만 준 설정으로 서버 조립 | H2 파일·`artifacts/`·`outputs/`·`known_hosts`가 그 아래에 생김, 시험용 기본값 빈(`AssemblyDefaults`)이 없음 | `SettingsAssemblyTest` |
| U2 | S4-AS-02 | 감사 기록 추가 | DB 표에 행이 더해지고, 갱신·삭제 SQL 경로가 없음(ArchUnit) | `JdbcAuditLogTest`, `ArchitectureRulesTest` |
| U2 | S4-AS-03 | 감사 표 쓰기 실패 | 기존 `AUDIT_FAILED` 경로(D3-14)로 바뀜 | `JdbcAuditLogTest` |
| U2 | S4-AS-04 | 주체 종류가 다른 감사 사건(작업·기기·주체 없음) 세 가지 | 각각 `audit_record`에 한 행씩 추가되고, `action`과 주체 종류가 맞지 않는 사건은 생성자에서 거절됨(B-12) | `AuditEventTest`, `JdbcAuditLogTest` |
| U2 | S4-AS-05 | 같은 출처의 로그인 실패 100건을 여러 스레드가 동시에, 15분 뒤 또 1건, 성공 한 번 뒤 1건 | 첫 묶음 행 하나(횟수 100, `first_at <= last_at`), 그 뒤의 실패는 새 행, 성공 뒤의 실패도 새 행. 횟수를 잃지 않음. 새 행 속도 상한을 넘는 실패는 `source = '*'` 행에 모임(B-12) | `JdbcRefusalStreakLogTest` |
| U2 | S4-AS-06 | 데이터 폴더가 `posix`·`acl` 보기가 없는 저장소 | 시작 실패, 메시지에 저장소 이름·종류·없는 보기·해결 방법(B-17) | `StorageLocationTest` |
| U2 | S4-CB-05 | 기준본 `settings-contract.json`과 제품의 허용 키 목록(`settings/settings-keys.json`) | 키 차이 0(허용 37, 제거 7) | `SettingsContractTest` |
| U3 | S4-WS-01 | MCP 포트에 `/api/targets`, 관리자 포트에 `POST /mcp` | 둘 다 404 | `TwoPortContextTest` |
| U3 | S4-WS-02 | 부모 빈 하나 | 두 자식이 같은 인스턴스를 봄 | `TwoPortContextTest` |
| U3 | S4-WS-03 | 포트 설정 39124·39125(시험은 임의 포트) | 각 자식이 자기 포트·`127.0.0.1`에서만 들음 | `TwoPortContextTest` |
| U3 | S4-WS-04 | 부모 닫기 | 두 포트 모두 연결 거절 | `TwoPortContextTest` |
| U3 | S4-WS-05 | 관리자 응답 머리글 | `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`(2.x 유지) | `TwoPortContextTest` |
| U3 | S4-WS-06 | 관리자 포트의 `POST /mcp`, MCP 포트의 `/oauth2/authorization/oidc`·`/login/token`·`/logout`; 두 자식 컨텍스트의 빈 | 모두 404. 관리자 자식에 MCP 서버 빈이 없고, MCP 자식에 `ClientRegistrationRepository`·관리자 `SecurityFilterChain`이 없음(A.2, 검토 N14) | `ChildContextIsolationTest` |
| U3 | S4-ER-01 | `RequestRefusedException`을 던지는 관리자 컨트롤러 | `application/problem+json`, `type`·`code`·`retryable`·`executionAttempted` | `AdminExceptionHandlerTest` |
| U3 | S4-ER-02 | 모든 `ErrorCode` 상수 | `HttpStatusTable`이 각자 상태를 줌(빠짐 0) | `HttpStatusTableTest` |
| U3 | S4-ER-03 | `ArtifactRefusal`(예산 초과) | MCP 봉투는 세 필드만, 관리자 `ProblemDetail`은 `detail`에 숫자 | `ResultPresenterTest` |
| U3 | S4-ER-04 | 같은 예외를 MCP 도구와 관리자 경로에서 | 같은 `code`·`retryable`·`executionAttempted` | `ResultPresenterTest` |
| U3 | S4-HE-01 | MCP 포트 `GET /health` | 200 `{"status":"UP"}`, 다른 키 없음, 인증 없음 | `HealthEndpointTest` |
| U3 | S4-HE-02 | 부모 `StorageHealthIndicator`가 DOWN | 503 `{"status":"DOWN"}`(부모 지표가 모임, B-5 미확인 1) | `HealthEndpointTest` |
| U3 | S4-HE-03 | 관리자 포트 `GET /health` | 404(관리자 자식에 Actuator 없음, B-5 미확인 2) | `HealthEndpointTest` |
| U3 | S4-HE-04 | `GET /health?x=1`, `POST /health` | 2.x처럼 경로 정확 일치만, 그 밖은 Actuator 기본 응답(질의는 무시되는지 시험으로 고정) | `HealthEndpointTest` |
| U4 | S4-SE-01 | MCP 포트에 MCP 토큰/관리자 토큰/기기 토큰 | 200/401/401 | `McpSecurityTest` |
| U4 | S4-SE-02 | 관리자 포트 Bearer 관리자 토큰으로 `owner` 경로 | 200, 세션 쿠키 없음 | `AdminSecurityTest` |
| U4 | S4-SE-03 | 토큰 모드 `POST /login/token` 맞는 토큰/틀린 토큰 | 세션 생성·id 바뀜/401 `UNAUTHENTICATED`·세션 없음. 틀린 값은 묶음 감사 행(`ADMIN_LOGIN_REFUSED`, 횟수 1)에 들어감(`audit_record`에는 행이 생기지 않음, B-4a·B-12) | `TokenLoginTest` |
| U4 | S4-SE-04 | OIDC 모드, 설정한 `sub`의 계정 | 로그인 성공, `OWNER` | `OidcLoginTest` |
| U4 | S4-SE-05 | OIDC 모드, 같은 발급자의 다른 `sub` | 세션 없음, 403(금지 화면 `text/html`), 묶음 감사 행 `ADMIN_LOGIN_REFUSED`(issuer·subject를 같은 행에 적음). 허용 계정의 로그인에서 `redirect_uri`는 `BRIDGE_ADMIN_ORIGIN`에서 만든 값이고 `Host`·`X-Forwarded-Host`를 바꿔도 같음(B-8a) | `OidcLoginTest` |
| U4 | S4-SE-06 | OIDC 모드, 다른 발급자의 같은 `sub` 문자열 | 거절 | `OidcLoginTest` |
| U4 | S4-SE-07 | 세션으로 `POST /api/{id}/approve`, CSRF 헤더 없음 | 403, 상태 변화 없음 | `AdminSecurityTest` |
| U4 | S4-SE-08 | 세션 + CSRF + `Origin` 다름/없음 | 403, 상태 변화 없음 | `AdminSecurityTest` |
| U4 | S4-SE-09 | 세션 + CSRF + 같은 Origin | 승인됨(추가 확인 없음, C.2) | `AdminSecurityTest` |
| U4 | S4-SE-10 | 세션 쿠키 속성, 그리고 OIDC 인가 요청을 담은 세션으로 제공자에서 돌아오는 콜백 | `__Host-portal-admin`, `Secure`, `HttpOnly`, `SameSite=Lax`, `Path=/`(사용자 결정 U-1). 콜백이 `authorization_request_not_found` 없이 완료됨 | `BrowserSessionTest`, `OidcLoginTest` |
| U4 | S4-SE-11 | 유휴 30분 지난 세션, 생성 12시간 지난 세션 | 둘 다 401, 다시 로그인 필요 | `BrowserSessionTest` |
| U4 | S4-SE-12 | `owner-token` 경로(유지보수)에 세션+CSRF | 401(관리자 토큰 Bearer만) | `AdminSecurityTest` |
| U4 | S4-SE-13 | 세션 쿠키를 붙인 교차 사이트 `GET`·`HEAD`(`Sec-Fetch-Site: cross-site`)를 기준본의 `owner` 이상 GET 항목 전부에 | 응답 전후로 저장소 상태가 같음(상태를 바꾸는 GET이 없음). Lax가 변경을 허용하지 않는다는 불변식(B-4) | `GetIsReadOnlyTest` |
| U4 | S4-SE-14 | 익명 `GET /api/session` 1000회 | 세션 0개, 응답에 `csrfToken` 없음, `authenticated:false`(검토 N15) | `AnonymousSessionTest` |
| U4 | S4-SE-15 | 같은 출처에서 틀린 토큰 연속 | 5회째 이후 429 `LOGIN_THROTTLED`+`Retry-After`(실패마다 두 배, 최대 15분), 감사는 묶음 행 하나, 성공하면 기록이 지워짐(B-4a) | `LoginThrottleTest` |
| U4 | S4-SE-16 | 익명 `GET /oauth2/authorization/oidc`를 상한(256)까지 | 상한에 닿으면 503 `LOGIN_BUSY`, 인증 전 세션의 비활성 한도는 5분, 로그인 성공이 수를 줄임 | `PreAuthSessionGuardTest` |
| U4 | S4-DT-01 | `POST /api/devices` `{name}` | 201, 응답에 토큰 한 번, 목록에는 토큰·해시 없음, 감사 `DEVICE_TOKEN_ISSUED` | `DeviceTokenApiTest` |
| U4 | S4-DT-02 | 기기 토큰으로 허용 16항목(`owner-or-device` 14 + 티켓 있는 다운로드 2) | 각각 인증 통과(티켓 항목은 토큰 없이도 통과) | `DeviceTokenScopeTest` |
| U4 | S4-DT-03 | 기기 토큰으로 승인·거부(`/api/{id}/approve|deny`)·환경·정책·용량·데스크톱·유지보수·`/api/devices`·정리 | 모두 403(유지보수는 401). 기준본에서 `owner`인 모든 항목이 거절됨 | `DeviceTokenScopeTest` |
| U4 | S4-DT-04 | 회수 뒤 같은 토큰을 같은 출처에서 여러 번 | 다음 요청부터 401, 묶음 감사 행 `DEVICE_TOKEN_REFUSED`(토큰 id, 횟수) 하나 | `DeviceTokenApiTest` |
| U4 | S4-DT-05 | 회수를 두 번 | 같은 결과, 회수 행 하나 | `DeviceTokenApiTest` |
| U4 | S4-DT-06 | 형식이 틀린 토큰, 없는 id | 401, 감사 행 없음 | `DeviceTokenScopeTest` |
| U4 | S4-DT-07 | 비밀 한 글자 바꾼 토큰 | 401(상수 시간 비교 경로) | `DeviceTokenServiceTest` |
| U4 | S4-DT-08 | 저장된 행 | 비밀 원문 열 없음, SHA-256 hex만 | `JdbcDeviceTokenStoreTest` |
| U4 | S4-DT-09 | MCP 포트에 기기 토큰 | 401 | `McpSecurityTest` |
| U5 | S4-MC-01 | `tools/list` | 이름 24개(공개 15, `show-*` 3, `ui-*` 6 중 노출 규칙대로), 접두는 Portal이 붙임 | `McpToolListContractTest` |
| U5 | S4-MC-02 | `files` 네 동작에 `operationKey` 없음 | 스키마 단계 거절(`required`) | `FilesToolTest` |
| U5 | S4-MC-03 | `files copy` 같은 키·같은 입력 두 번 / 같은 키·다른 대상 | 같은 결과 / `OPERATION_INPUT_CONFLICT` | `FilesToolTest` |
| U5 | S4-MC-04 | `files stat`에 키 | 결과는 키와 무관, 감사에 상관 키 | `FilesToolTest` |
| U5 | S4-MC-05 | `desktop-open`에 2.x `requestKey` | 스키마 거절(`additionalProperties: false`) | `DesktopOpenToolTest` |
| U5 | S4-MC-06 | `desktop-open` 같은 `operationKey` 두 번(재시작을 사이에 둔 경우도) | 같은 sessionId·epoch(새 lease 없음). 재시작을 사이에 두면 lease는 `EXPIRED`이고 다시 열지 않음(B-13) | `DesktopOpenToolTest` |
| U5 | S4-MC-07 | 닫힌·만료된 lease의 `operationKey`로 다시 open(재시작 뒤 포함) | 다시 열지 않고 닫힘·만료를 알림(`desktop_lease` 행 근거) | `DesktopOpenToolTest` |
| U5 | S4-MC-08 | 실패 응답 | `isError: true`, `structuredContent` = 봉투, `content[0].text` = 같은 JSON | `ToolResultWireTest` |
| U5 | S4-MC-09 | `initialize` | `serverInfo.name` 기준본과 같음, `version` = 프로젝트 판, `instructions` = 기준본(`{sourceUrl}` 치환) | `McpServerInfoTest` |
| U5 | S4-MC-10 | unknown 예산이 찬 상태의 `submit` | `OPERATION_RECONCILIATION_CAPACITY`, `retryable: true`, `executionAttempted: false` | `SubmitToolTest` |
| U5 | S4-MC-11 | `desktop-open` 같은 키·다른 대상(또는 다른 revision) | `OPERATION_INPUT_CONFLICT`, 새 lease 없음(B-13) | `DesktopOpenToolTest` |
| U5 | S4-MC-12 | `OPEN` lease가 있는 채 재시작 | 시작 복구가 그 행을 `EXPIRED`로 바꿈, 원격으로 보낸 것 없음 | `DesktopLeaseRecoveryTest` |
| U5 | S4-MC-13 | `files remove` 같은 키·같은 입력 두 번 / 같은 `(uuid, 키)`를 `copy`에 쓴 뒤 `remove` | 같은 결과(이미 소비된 파일에서도) / `OPERATION_INPUT_CONFLICT`(B-14) | `FilesToolTest` |
| U5 | S4-SR-01 | Linux 대상 `submit kind=source` | 실행되고 원문은 DB·로그·감사에 없음, 인자(`argv`)는 첨부 본문으로만 있음 | `SourceOperationRunnerTest` |
| U5 | S4-SR-02 | Windows 대상, `pythonExecutable` 있음/없음 | 그 해석기로 실행 / capability 거짓, 제출은 `TARGET_CAPABILITY_UNSUPPORTED` | `SourceOperationRunnerTest` |
| U5 | S4-SR-03 | 대기 중 SOURCE 작업, 재시작(계획 없음), 그 뒤 디스패처 | 대기 상태로 남았다가 시작할 때 보낸 것 없이 거절(`PRIVATE_PLAN`·`SOURCE_PLAN`, 결과 `NOT_DISPATCHED`), 재실행 없음 | `SourceRestartTest` |
| U5 | S4-SR-04 | 실행 중 재시작 | `UNKNOWN`(기존 T18) | `SourceRestartTest` |
| U5 | S4-SR-05 | 정산·만료 뒤 | 계획 등록부에서 지워짐 | `SourcePlanRegistryTest` |
| U5 | S4-SR-06 | 등록부 상한(요청 용량 × 16 KiB) | 넘는 접수는 기존 용량 코드로 거절 | `SourcePlanRegistryTest` |
| U5 | S4-SR-07 | Linux 대상에 `pythonExecutable`을 준 경우와 안 준 경우 | 준 경우 그 경로로, 안 준 경우 `/usr/bin/python3`로 실행(`SourceRuntime.Posix`). 상대 경로·제어 문자는 설정 단계에서 거절(`PosixPythonExecutable`) | `SourceRuntimeTest` |
| U5 | S4-SR-08 | `SourceInput` 저장과 읽기 | 원문 없음, 인자 JSON·런처는 첨부 본문(U18 뒤), 계획 해시·원문 해시·길이는 열 | `SourceInputStorageTest` |
| U5 | S4-CB-01 | 제품 `mcp/tools/*.json`·실제 `tools/list`와 기준본 | 이름·설명·`inputSchema` 차이 0(키 순서 무시) | `McpToolListContractTest` |
| U5 | S4-CB-02 | 도구 `outputSchema`·`annotations`·`_meta`와 기준본 | 차이 0 | `McpToolListContractTest` |
| U5 | S4-CB-03 | `server.json`의 리소스·`instructions` | 차이 0(`{sourceUrl}` 치환 뒤) | `McpServerInfoTest` |
| U6 | S4-AD-01 | 관리자 포트의 등록 경로 전부(Spring `RequestMappingHandlerMapping`) | 기준본 `admin-routes.json`의 관리자 항목과 메서드·경로 차이 0 | `AdminRoutesContractTest` |
| U6 | S4-AD-02 | 각 경로의 보안 범주 | 기준본 `auth30`과 같음(미인증·기기·세션 요청으로 확인) | `AdminRoutesContractTest` |
| U6 | S4-AD-03 | `/api/session/start`, `/api/session/logout` | 404(2.x 앞단 신원 세션 경로는 없음) | `AdminRoutesContractTest` |
| U6 | S4-AD-04 | `GET /api/outputs/{id}?offset&limit&format` | `OutputPage` 모양, MCP `observe` 출력 페이지와 같은 바이트 | `OutputApiTest` |
| U6 | S4-AD-05 | 조건부 경로(데스크톱·에이전트 CLI 등)의 서비스가 없을 때 | 404(2.x 「Registered only when」) | `AdminRoutesContractTest` |
| U6 | S4-AD-06 | 승인·거부 | 상태 전이와 감사 기록 | `ApprovalApiTest` |
| U6 | S4-AD-07 | 업로드 4경로(기기 토큰) | 2.x 크기 상한 그대로, UUID 반환 | `FileTransferApiTest` |
| U6 | S4-AD-08 | 2.x `{error:'…'}`를 기대하는 요청 | 본문은 `ProblemDetail` | `AdminExceptionHandlerTest` |
| U6 | S4-AD-09 | 기기 토큰으로 staged UUID 흐름 전체(`POST /api/artifacts` → 청크 `PUT` → `complete` → `GET`), 같은 `operationKey` 재호출, 다른 입력으로 재호출 | 각 단계 200과 `{uuid}`, 같은 UUID 회수 / `OPERATION_KEY_CONFLICT` 계열 거절. 그 UUID를 MCP `files stat`이 받음. Origin이 다르면 `ORIGIN_REJECTED` (B-2a) | `StagedFileApiTest` |
| U6 | S4-AD-10 | 기기 토큰으로 `POST /api/{id}/download`(승인된 다운로드 작업) / `POST /api/{id}/approve` | 링크 `{downloadUrl}` 반환 / 403 `FORBIDDEN`(검토 N2) | `DownloadLinkApiTest` |
| U6 | S4-AD-11 | `GET /downloads/{id}/{nonce}?ticket=…`를 쿠키·Bearer 없이 / 같은 티켓 두 번째 / `HEAD` 뒤 `GET` / 티켓 없음·만료·다른 파일의 티켓 | 200 / 두 번째는 거절 / `HEAD`는 소비하지 않음 / 모두 거절(B-2b) | `DownloadCapabilityTest` |
| U6 | S4-RC-01 | unknown 3건 | `GET /api/operations/reconciliation` = `{unknownCount:3, budget:32, saturated:false, operations:[3]}` | `ReconciliationApiTest` |
| U6 | S4-RC-02 | `reconcile completed`(결과가 `Executed`인 작업과 재시작 복구로 `NoResult`인 작업 둘 다) | `COMPLETED`, 정리 기록 `OwnerConfirmed`가 `operation_reconciliation`에 저장되고 다시 읽으면 같음, 정산 불요, 감사 `OPERATION_RECONCILED`, 관리자 `GET /api/{id}`에 `reconciliation` 필드(B-10a) | `OperationReconciliationTest` |
| U6 | S4-RC-08 | 실행기 결과(`ExecutionOutcome`)에 `OwnerConfirmed` 증거를 넣음 / `operation_reconciliation`에 값 밖의 `outcome`을 넣음 | 생성자가 거절 / `CHECK`가 거절(B-10a) | `OperationReconciliationStoreTest` |
| U6 | S4-RC-03 | `reconcile failed` / `never_ran` | `FAILED` / `CANCELLED`(`NOT_DISPATCHED`) | `OperationReconciliationTest` |
| U6 | S4-RC-04 | `UNKNOWN`이 아닌 작업 | 409 `INVALID_OPERATION_TRANSITION`, 변화 없음 | `ReconciliationApiTest` |
| U6 | S4-RC-05 | unknown 32건(예산 참) 뒤 한 건 정리 | 다음 스케줄 판단에서 대기 작업이 시작됨 | `ReconciliationSchedulingTest` |
| U6 | S4-RC-06 | 예산이 찬 동안 대기 작업 `observe` | `state: queued`, `diagnostic.policyReason = OPERATION_RECONCILIATION_CAPACITY` | `ReconciliationSchedulingTest` |
| U6 | S4-RC-07 | 정리 요청 | 원격 실행 요청 0건(재실행 없음) | `OperationReconciliationTest` |
| U6 | S4-CB-04 | 기준본 `admin-routes.json` 전체 | S4-AD-01·02가 모든 항목을 덮음(빠진 항목 0) | `AdminRoutesContractTest` |
| U7 | S4-UI-01 | 토큰 모드 첫 화면 | 토큰 입력 → 로그인 → 대기 목록 | `AdminUiPlaywrightTest` |
| U7 | S4-UI-02 | OIDC 모드 첫 화면 | 「로그인」 버튼이 `/oauth2/authorization/oidc`로 | `AdminUiPlaywrightTest` |
| U7 | S4-UI-03 | 기기 토큰 발급 | 토큰이 한 번 보이고, 새로 고치면 다시 보이지 않음, 회수 버튼 | `AdminUiPlaywrightTest` |
| U7 | S4-UI-04 | unknown 예산 80%·100% | 배너가 보이고 링크가 정리 목록으로 | `AdminUiPlaywrightTest` |
| U7 | S4-UI-05 | 정리 목록에서 한 건 「완료로 정리」 | 확인 대화상자 뒤 목록에서 빠짐 | `AdminUiPlaywrightTest` |
| U7 | S4-UI-06 | 바닥글 | 「Source code (AGPL-3.0-or-later)」가 `BRIDGE_SOURCE_URL`(빈 값이면 판 태그 URL)로 | `AdminUiPlaywrightTest` |
| U7 | S4-UI-07 | MCP Apps 카드 리소스 | URI `ui://ssh-approval/card-<sha256>.html`, CSP `_meta` 기준본과 같음 | `CardResourceTest` |
| U7 | S4-UI-08 | 로그인 전 화면 로드와 OIDC 실패 경로 | 로그인 전에 `/app.js`·`/assets/**`의 ES 모듈이 모두 200(`static`, 검토 N12), 제공자 오류는 `/login?error`에 거절 문구, 허용 계정이 아니면 403 금지 화면, 어느 쪽도 세션 쿠키 없음 | `AdminUiPlaywrightTest` |
| U8 | S4-RK-01 | Jev 꺼짐 | 위험 평가 호출 0, 자동 승인 없음 | `JevRiskEvaluatorTest` |
| U8 | S4-RK-02 | Jev 켜짐, 호출 상한 도달 | 상한 뒤 호출 0, 소유자 승인으로 넘어감 | `JevRiskEvaluatorTest` |
| U8 | S4-RK-03 | 정책 허용 + 위험 평가 거부 | 자동 승인 없음 | `CommandAutomationTest` |
| U8 | S4-RK-04 | `*_REF` 키, 로컬 저장소에 값 있음/없음 | 해석 / 시작 실패(값은 메시지에 없음) | `LocalFileSecretStoreTest` |
| U8 | S4-RK-05 | Vault 저장소 응답 실패 | 비밀 없이 진행하지 않음(조용한 대체 없음) | `VaultSecretStoreTest` |
| U8 | S4-RK-06 | 위험 평가 요청 본문 | 비밀이 가려진 명령만 보냄(redaction) | `JevRiskEvaluatorTest` |
| U9 | S4-RL-01 | 카탈로그 응답에 무결성 값 있음/없음 | 설치 진행 / `Unavailable`(대상에 바이트 0) | `NpmRegistryCatalogTest` |
| U9 | S4-RL-02 | `GET /api/version?refresh=1` | 새 판 확인 결과(네트워크 실패는 「확인 못 함」, 조용한 성공 없음) | `PortalReleaseCheckerTest` |
| U9 | S4-RL-03 | `POST /api/environment`에 허용 키 밖 | 거절, 파일 변화 없음 | `EnvironmentFileEditorTest` |
| U9 | S4-RL-04 | 환경 저장 뒤 재시작 요청(`RestartRequester`) | `Busy`면 거절(2.x와 같은 조건), `NoSupervisor`면 거절, 아니면 정상 정지 절차 뒤 종료 상태 75(B-11). 저장 전 백업과 표식 파일이 있음 | `EnvironmentRestartTest` |
| U9 | S4-RL-07 | 환경 저장 뒤 시작이 설정 검증 오류로 실패 | 첫 실패는 백업을 되돌리고 75로 끝남(감독자가 이전 환경으로 재시작), 되돌린 뒤에도 실패하면 78. 성공하면 표식 삭제(B-11) | `StartupFailureGuardTest` |
| U9 | S4-RL-05 | 선택 어댑터 미설정 | 설치 기본 흐름이 업체 API를 부르지 않음 | `OptionalAdapterAssemblyTest` |
| U9 | S4-RL-06 | 환경 파일 쓰기 도중 실패 | 원래 파일 그대로(원자적 교체) | `EnvironmentFileEditorTest` |
| U5·U6 | S4-CB-06 | hooks JSON 5개와 제품이 내보내는 hooks | `command`·매니페스트 `version` 밖의 필드 차이 0 | `HookContractTest` |
| U19 | S4-RS-01 | 재시작 요청 | 정리 순서(새 요청 거절 → 처리 중 마무리 → 컨텍스트 닫기) 뒤 종료 상태 75(B-11) | `RestartRequesterTest` |
| U19 | S4-RS-02 | 실행 중·시작 중·종료 중 작업, 승인 대기, 전송 중, 유지보수 중, 열린 desktop lease 각각이 있는 상태에서 재시작 요청 | 각각 `Refused(Busy)`. 모두 없으면 `Accepted` | `RestartGateTest` |
| U19 | S4-RS-03 | 시작 중 예외(표식 있음/없음/시도 수 1) | 표식+0이면 백업 복구·75, 아니면 78 | `StartupFailureGuardTest` |
| U19 | S4-RS-04 | systemd 유닛 파일 | `Restart=on-failure`, `SuccessExitStatus=143`, `RestartPreventExitStatus=78`, `TimeoutStopSec`가 B-11의 값과 같음 | `SystemdUnitFileTest` |
| U19 | S4-RS-05 | Windows 러너에 procrun `jvm` 모드로 서비스를 설치하고 재시작 요청 | 새 프로세스 id로 서비스가 다시 실행됨(`sc failure`·`failureflag` 설정 포함). 통과하기 전에는 Windows 재시작 지원을 주장하지 않음 `[미확인]` | `WindowsServiceRestartRealTest` |
| U19 | S4-RS-06 | `WindowsServiceEntry.stop` 호출 | `start`가 돌아오고 서비스가 정지 상태로 끝남(문서: jvm 모드에서 `start`는 `stop`까지 돌아오지 않음) | `WindowsServiceEntryTest` |
| U19 | S4-RS-07 | 감독자 없는 콘솔 실행에서 재시작 요청 | `Refused(NoSupervisor)`, 프로세스는 계속 실행 | `RestartRequesterTest` |
| U19 | S4-RS-08 | 서비스 정지 요청 | 같은 정지 절차 뒤 종료 상태 0, 다시 시작을 요구하지 않음 | `RestartRequesterTest` |
| U18 | S4-AT-01 | 명령·SOURCE·다운로드 작업을 만들고 다시 읽음 | 여섯 역할의 본문이 첨부로 저장되고 같은 값으로 돌아옴(B-15) | `AttachmentRoundTripTest` |
| U18 | S4-AT-02 | `V1`~`V15` 적용 뒤 열 정의 | 긴 텍스트 열 10개가 없고 `*_attachment_id`가 있으며 `attachment` 열이 B-15와 같음 | `AttachmentSchemaTest` |
| U18 | S4-AT-03 | 모든 표의 제약 | 첨부 표를 가리키는 외래 키가 없고 `attachment`에도 외래 키가 없음 | `AttachmentSchemaTest` |
| U18 | S4-AT-04 | 파일 쓰기 도중 실패, 행 삽입 도중 실패 | 행이 없고 고아 파일은 한 시간 뒤 정리됨, 작업 행 없음 | `AttachmentWriteOrderTest` |
| U18 | S4-AT-05 | 첨부 파일 삭제·변조 뒤 읽기 | 상태 전이 경로는 `AttachmentUnreadableException`, 이력 보기는 「본문을 읽을 수 없음」 | `AttachmentReadTest` |
| U18 | S4-AT-06 | 분류표의 짧은 값 열 | 길이 상한이 B-15 표와 같고 본문 열은 없음, 첨부 크기 상한을 넘는 본문은 거절 | `AttachmentSchemaTest` |
| U18 | S4-AT-07 | 정리(`AttachmentReaper`) | 행이 있는 첨부는 어떤 경우에도 지우지 않음 | `AttachmentReaperTest` |
| U20 | S4-OS-01 | Windows 대상 업로드(실제 Windows 서버) | 임시 파일에 `icacls`로 상속 차단과 3주체 권한이 걸린 뒤 게시(B-16) | `WindowsUploadAclRealTest` |
| U20 | S4-OS-02 | 같은 시험의 `/inheritance:r` 철자 | 실제 Windows에서 동작함(공식 문서 표는 `/inheritancelevel:`로 적혀 있음 `[미확인]`) | `WindowsUploadAclRealTest` |
| U20 | S4-OS-03 | ACL이 없는 볼륨에서 업로드 `[미확인: 시험 환경에 FAT 볼륨 필요]` | `icacls` 실패, 게시 전 실패, 결과 문장에 이유 | `WindowsUploadAclRealTest` |
| U20 | S4-OS-04 | 덮어쓰기 때 기존 DACL | `/save`·`/restore`로 DACL이 같음 | `WindowsOverwriteAclRealTest` |
| U20 | S4-OS-05 | 덮어쓰기 때 소유자·무결성 레이블 | 보존 상태가 `restored`·`unsupported` 등으로 결과 문장에 정직하게 적힘 `[미확인: 권한]` | `WindowsOverwriteAclRealTest` |
| U20 | S4-OS-06 | 실행 중인 Windows 명령을 취소·기한 초과 | `taskkill /pid <PID> /t /f`로 자식까지 종료되고 `signal_delivery = REQUESTED`, `cleanup = COMPLETE` | `WindowsCancelRealTest` |
| U20 | S4-OS-07 | `destructive` 목록의 모든 이름 한 줄씩 | `DESTRUCTIVE` | `WindowsCommandClassifierTest` |
| U20 | S4-OS-08 | `privileged` 목록의 모든 이름과 문자열 실행기 | `PRIVILEGED` | `WindowsCommandClassifierTest` |
| U20 | S4-OS-09 | 파이프·`;`로 이은 명령 | 부분 등급의 최댓값 | `WindowsCommandClassifierTest` |
| U20 | S4-OS-10 | 기존 `read-only` 두 개와 모르는 이름 | `READ_ONLY` 두 개 그대로(늘지 않음), 모르는 이름은 `SAFE` | `WindowsCommandClassifierTest` |
| U21 | S4-IN-01 | `/etc/os-release` 값 표(Debian·Ubuntu·RHEL·Rocky·Alma·Fedora·SUSE·Alpine·Arch) | 앞 여섯은 `apt`/`dnf`, 뒤는 이유가 담긴 거절 | `InstallDistroDetectionTest` |
| U21 | S4-IN-02 | 배포판별 설치 CI | 설치 → 시작 → `/health` UP → 정지가 배포판마다 성공 | CI 작업 |
| U21 | S4-IN-03 | arm64 설치 CI | 같은 흐름이 arm64에서 성공 | CI 작업 |
| U21 | S4-IN-04 | Windows 클라이언트 자동 시작 등록 | `HKCU\...\Run` 값이 생기고 명령줄이 260자 이하, 넘으면 거절 | `ClientAutostartTest` |
| U21 | S4-IN-05 | Linux 클라이언트 자동 시작 | `systemd --user` 유닛 파일 내용 | `ClientAutostartTest` |
| U21 | S4-IN-06 | 상주 프로세스가 없을 때 훅 런처 | 직접 띄움 | `HookLauncherTest` |
| U0r | S4-CB-20 | 기준본 `admin-routes.json` 전체 | 항목 60개, 모두 `auth30` 범주가 있고 중복·잘못된 정규식 없음, 마지막은 `owner` 포괄 항목(F.3) | `ContractBaselineIntegrityTest` |
| U0r | S4-CB-21 | 기준본의 `owner-or-device`·`download-capability` 항목 | 14개와 2개가 설계서 B-2의 목록과 같고 `approve|deny`는 `owner` | `ContractBaselineIntegrityTest` |
| U0r | S4-CB-22 | 기준본 `settings-contract.json` | 허용 키 37, 기본값 키는 모두 허용 키, 제거 키 7개와 이유, 모드 `["token","oidc"]` | `ContractBaselineIntegrityTest` |
| U0r | S4-CB-23 | `server.json`과 도구 15개 | `Contract 3.0.`로 시작하고 `{sourceUrl}`로 끝나며, 공개 15개의 `contractVersion` 상수가 `3.0`, `files`·`desktop-open`에 `operationKey` 필수 | `ContractBaselineIntegrityTest` |
| U0r | S4-CB-24 | 기준본 `webLayerCodes` | 코드 여섯 개가 B-7·A.3과 같은 순서이고 상태는 400~599(`ErrorCode` 열거형 217개 합계는 `ErrorCodeBaselineTest`가 지킴) | `ContractBaselineIntegrityTest` |

## 7. 사용자에게 미치는 영향(업그레이드 안내 입력)
업그레이드 안내(영·한, 계획 정본)는 B-1의 각 항목을 하나씩 적는다. 특히: 2.x 클라이언트·hook 호출 거절, `files`·`desktop-open`의 `operationKey` 필수, `targets`의 `gui.openRequest` 없음, `/api/artifacts`·`access` 모드·passkey 없음, 설정 키 변경(뺀 7개, 더한 4개, OIDC 소유자는 이메일이 아니라 계정 식별자 하나, 뺀 키가 환경에 남아 있으면 시작이 멈춘다), `/health` 본문, 관리자 오류 본문(`ProblemDetail`), 데이터 이전은 `portal-migrate`.
추가(U0r): 로그인은 세션 쿠키가 `SameSite=Lax`다. OIDC 모드의 다운로드 링크는 로그인 세션을 요구하지 않고 서명된 일회용 티켓만으로 열린다(2.x의 oidc 모드와 달라지고 2.x의 token 모드와 같아진다, B-2b). 로그인 실패는 같은 출처의 연속 실패를 한 행으로 묶어 감사하고 속도 제한이 있다(B-4a). 기기 토큰으로 `POST /api/files/downloads`·`POST /api/{id}/download`·staged UUID 경로를 쓸 수 있다(B-2). 설정 반영 재시작은 감독자가 종료 상태 75에서 다시 띄우도록 설정돼 있어야 한다: systemd 유닛과 Windows 서비스의 복구 설정(B-11). 작업 본문은 DB 열이 아니라 데이터 폴더의 `attachments/`에 파일로 저장된다(B-15, 데이터 폴더를 백업할 때 함께 복사해야 한다). Windows 대상의 업로드 임시 파일 권한과 덮어쓰기 DACL 보존은 대상의 `icacls`로 이루어지고 취소·기한 초과는 대상의 `taskkill`로 이루어진다(B-16). Linux 설치는 Debian 계열과 RHEL 계열만 지원한다(B-17).

## 8. 기준본을 더 바꿀 때의 규칙
- 기준본(`java/portal-web/src/test/resources/contract/**`)을 바꾸는 단위는 같은 커밋에서 `contract-baseline.md` 9절 변경표에 행을 더하고(항목, 이전, 이후, 근거 결정), 이 문서의 해당 절을 고친다. 변경표 밖의 차이는 두지 않는다.
- 새 오류 코드는 `envelope.schema.json`의 `x-portal-errorCodes.stage4Additions`(이 단계에 처음 생김)에 `[코드, 근거]`로 적는다.
- 기준본의 2.x 기록 필드(`source`, 2.x `auth`, `portalRules`의 2.x 줄 번호)는 고치지 않는다. 3.0 동작은 3.0 필드(`auth30` 등)나 3.0 규칙 문장으로 더한다.

## F. 2026-11-21 독립 검토(`cb8ff00` 기준) 반영 표

### F.1 검토 발견 N1~N21의 반영
상태: 「반영」은 이 브랜치의 설계서·기준본·시험이 바뀌었다는 뜻이다. 구현은 표의 단위가 한다. 「미정」은 이유를 적었다.

| N | 요지 | 이 문서 | 기준본·시험 | 상태 |
|---|---|---|---|---|
| N1 | `/api/artifacts` 4건 삭제로 staged UUID를 만들 경로가 없음 | B-2a, A.5(`StagedFileController`), 0.4 | `admin-routes.json`에 4항목 복원(`owner-or-device`), S4-AD-09 | 반영(L-1) |
| N2 | `approve\|deny\|download`가 `owner` 단일 항목이라 기기 토큰이 다운로드 URL을 못 받음 | B-2, A.5(`DownloadController`·`ApprovalController`) | 항목 분리(`approve\|deny` `owner`, `download` `owner-or-device`), S4-AD-10 | 반영(L-2) |
| N3 | `/downloads`의 `owner-or-device` 요구가 MCP Apps 카드 링크를 막음 | B-2b(2.x 보호 조건 그대로, 잔여 위험) | `download-capability`를 「티켓만」으로 정의(`contract-baseline.md` 9.2), S4-AD-11 | 반영(L-3) |
| N4 | SameSite=Strict 쿠키로는 OIDC 콜백이 실패 | B-4, C.2 | S4-SE-10(Lax), S4-SE-13 | 반영(U-1) |
| N5 | 프록시 뒤 OIDC `redirect_uri`·TLS·bind 설계 없음 | A.3(OIDC 등록 정보), B-8a | S4-SE-05(Host 변조에도 같은 redirect_uri) | 반영(L-4). 앱 내 TLS는 2.x에 없던 기능이라 미정(F.6 항목 5) |
| N6 | `V8` 번호 충돌 | A.1, F.2 | - | 반영(L-5) |
| N7 | `OwnerConfirmed`가 저장도 관측도 안 됨 | B-10a, A.5 | `V11__operation_reconciliation.sql`, S4-RC-02·08 | 반영(L-6) |
| N8 | `AuditEvent`가 작업 id 필수, `app/audit`가 쓰기 범위 밖 | B-12, A.1(`app.audit` 범위 추가) | `V9__audit_record.sql`, S4-AS-04 | 반영(L-6) |
| N9 | 로그인 거절 매번 감사와 「인증 없는 요청이 표를 채우지 못하게」의 모순 | B-4a, B-12 | S4-SE-03·05·15, S4-AS-05 | 반영(U-2) |
| N10 | desktop 「닫힌 키는 다시 못 연다」의 DB 기록 없음 | B-13, A.4 | `V12__desktop_lease.sql`, S4-MC-06·07·11·12 | 반영(L-6) |
| N11 | files `remove`·`stat` 키 멱등 저장소 없음, U5 범위에 store·app 없음 | B-14, A.4(쓰기 범위 추가) | `V13__staged_remove.sql`, S4-MC-03·04·13 | 반영(L-6) |
| N12 | static 패턴이 U7의 모듈·로그인·기기 토큰·정리 화면·OIDC 실패·403 화면을 못 덮음 | A.6 | 정적 항목 패턴 확장(`/devices`, `/reconciliation`, `/login`), `/assets/**` 항목 추가, S4-UI-08 | 반영 |
| N13 | 0이 아닌 종료 코드 재시작이 systemd 전제, procrun 동작 미정의 | B-11, A.8, A.10 | S4-RS-01~08 | 반영(L-8). procrun jvm 모드의 SCM 보고는 문서에 없어 `[미확인]`, S4-RS-05가 확정하고 통과 전엔 지원을 주장하지 않으며 대비책이 있다 |
| N14 | 부모·자식 구성에서 MCP·OAuth2 client 자동 구성이 반대쪽 자식에서 켜질 수 있음 | A.2 | S4-WS-06 | 반영 |
| N15 | 익명 `GET /api/session`·`/oauth2/authorization/oidc`가 상한 없는 메모리 세션을 만듦 | B-4, B-4a | `/api/session` 응답 모양 개정, S4-SE-14·16 | 반영 |
| N16 | S4-ST-04를 실현할 장치 없음 | A.1(`LegacyKeyGuard`) | `settings-contract.json`에 `removedKeys`·`keyPrefix`·`unknownKeyPolicy`, S4-ST-04, S4-CB-05 | 반영 |
| N17 | `V1`의 긴 텍스트 열 10개가 「본문은 DB 열 금지」 위반 | B-15, A.9(U18) | `V14__attachment.sql`, `V15__operation_body_attachment.sql`, S4-AT-01~07 | 반영(L-7). 세 칸의 길이 상한은 `[미확인]`(U18이 도메인 타입에서 읽어 확정) |
| N18 | `SourceInput` Javadoc(재시작 뒤 unknown)과 B-3(queued에서 NOT_DISPATCHED)이 다름, `SourceArguments` 메모리 vs `argv_json` 저장 | B-3 | S4-SR-03·08 | 반영. Javadoc 수정은 U5(`SourceInput.java`, A.4 쓰기 범위) |
| N19 | `feature-map.md`의 폐기된 설계 | 0.4 | `feature-map.md` 296·298·299·322행 수정 | 반영 |
| N20 | spike 시험이 아직 「2.0」을 단언 | - | `ContractEnvelope.java`(8·18줄), `McpContractSpikeTest.java`(27·148줄), `SpikeMcpConfiguration.java`(15줄)를 3.0으로 올림 | 반영 |
| N21 | 바뀐 기준본을 읽는 시험이 없음 | F.3 | `ContractBaselineIntegrityTest`(이 브랜치), 각 단위 시험(F.3) | 반영 |

### F.2 마이그레이션 번호표 (장부 번호 충돌 해소, 결정 L-5)
확인한 것: `origin/feature/v300-fix-store-domain`(`2c5e998`)은 `db/expand/V8__budget_gate.sql`을 쓰고 V9 이상은 없다. 같은 날 `git fetch` 뒤 `origin/feature/v300-*`·`origin/develop`의 `db/expand` 파일을 모두 조회했고(`git ls-tree`) V8을 쓰는 것은 그 브랜치 하나, V9 이상은 어느 브랜치에도 없다(옛 `db/migration/<dbms>/V1_1·V6_1`은 이전 Flyway 구조의 흔적이다). 마이그레이션 적용기는 번호가 1부터 빈 번호 없이 연속이어야 하고 적용된 최고 번호보다 낮은 미적용 번호를 거절하므로(`migration-runner.md`) **표의 순서가 병합 순서**다. 단위가 병렬로 개발돼도 병합은 번호 순이다. 번호를 바꾸는 것은 리드뿐이고 이 표를 같은 커밋에서 고친다.

| 번호 | 파일 | 단위 | 만드는 것 | 병합 순서 |
|---|---|---|---|---|
| V1~V7 | (develop에 있음) | - | 작업 이력, 첨부, 출력, 명령 정책, 에이전트 CLI, 유지보수·용량, 실행 시간선 | 완료 |
| V8 | `V8__budget_gate.sql` | F2(`feature/v300-fix-store-domain`) | 예산 판정 행 `budget_gate` | 1 |
| V9 | `V9__audit_record.sql` | U2 | `audit_record`(추가만), `audit_refusal_streak`(묶음 로그인 거절) | 2 |
| V10 | `V10__device_token.sql` | U4 | `device_token`, `device_token_revocation` | 3 |
| V11 | `V11__operation_reconciliation.sql` | U6 | `operation_reconciliation`(소유자 정리 기록) | 4 |
| V12 | `V12__desktop_lease.sql` | U5 | `desktop_lease`(desktop-open 키와 lease) | 5 |
| V13 | `V13__staged_remove.sql` | U5 | `staged_remove`(files remove 키 멱등) | 6 |
| V14 | `V14__attachment.sql` | U18 | `attachment`(첨부 메타) | 7 |
| V15 | `V15__operation_body_attachment.sql` | U18 | 긴 텍스트 열 10개 삭제, `*_attachment_id` 열 추가 | 8 |

- 이전 개정의 번호(`V8__audit_record`, `V9__device_token`)는 철회한다.
- U5와 U6는 병렬로 개발할 수 있으나 U6(V11)가 U5(V12·V13)보다 먼저 병합된다. U5가 먼저 끝나면 U6 병합을 기다린다.
- V15는 대상 표가 비어 있어야 한다(B-15). 배포 전이라 일회용 DB뿐이다.
- 번호표에 없는 마이그레이션을 만드는 단위는 먼저 리드에게 번호를 받는다.

### F.3 단위별 쓰기 범위와 기준본 시험의 담당
쓰기 범위는 패키지 단위이고 한 줄 형식은 `모듈/경로 — 변경 이유`다. 두 단위가 같은 파일을 쓰는 칸은 없다(아래 「겹침 확인」).

| 단위 | 쓰기 범위 |
|---|---|
| U2 | `portal-server/**/server/{config,assembly}/**`, `portal-server/src/main/resources/settings/**`, `portal-app/**/app/audit/**`, `portal-store/**/store/audit/**`, `V9` |
| U3 | `portal-server/**/server/{PortalServerApplication,McpWebConfiguration,AdminWebConfiguration}.java`·`health/**`, `portal-web/**/web/presentation/**`, 두 `build.gradle.kts`(Actuator) |
| U4 | `portal-web/**/web/security/**`, `portal-{domain,app,store}/**/device/**`, `V10`, `portal-web/build.gradle.kts`(Security) |
| U5 | `portal-web/**/web/mcp/**`, `portal-web/src/main/resources/mcp/**`, `portal-app/**/app/execution/Source*`, `app/target/TargetPlatform.java`, `portal-ssh/**/ssh/platform/*TargetPlatform.java`, `portal-domain/**/domain/target/PosixPythonExecutable.java`, `domain/operation/SourceInput.java`(Javadoc), `portal-app/**/app/artifact/**`, `portal-app/**/app/desktop/DesktopLease*`, `portal-store/**/store/{artifact,desktop}/**`, `V12`, `V13` |
| U6 | `portal-web/**/web/admin/**`, `portal-app/**/app/operation/OperationReconciliation*`, `app/history/**`, `portal-domain/**/domain/operation/{CompletionEvidence,ExecutionOutcome,Operation,OperationSnapshot,ReconciledAs}.java`, `portal-store/**/store/journal/{OperationMapper,OperationStorage,OperationReconciliation*,JdbcOperationJournal}.java`, `V11` |
| U7 | `portal-web/src/main/resources/static/**`, `portal-web/**/web/ui/**`, `portal-web/src/test/**/web/ui/**` |
| U8 | `portal-web/**/web/jev/**`, `portal-app/**/app/{risk,policy,secret}/**`, `portal-server/**/server/secret/**` |
| U9 | `portal-web/**/web/release/**`, `portal-server/**/server/config/EnvironmentFileEditor*`, 선택 어댑터 패키지 |
| U18 | `portal-domain/**/domain/attachment/**`, `portal-app/**/app/attachment/**`, `portal-store/**/store/{attachment,journal}/**`(`journal`은 본문 읽기·쓰기 부분만), `V14`, `V15`, `portal-store/src/test/**/migration/ProductScriptsTest.java`, `docs/migration/migration-runner.md`, `portal-server/**/server/assembly/**`(저장소 배선) |
| U19 | `portal-server/**/server/lifecycle/**`, `PortalServerApplication.java`(종료 배선만) |
| U20 | `portal-ssh/**/ssh/platform/**`, `portal-ssh/src/main/resources/**`, `portal-app/**/app/remote/**`, `portal-domain/**/domain/classify/**` |
| U21 | 설치 스크립트 폴더, `.github/workflows/**`(리드 승인 뒤), `portal-client/**/autostart/**` |

겹침 확인(같은 파일을 두 단위가 쓰는 곳과 규칙):
- `portal-store/**/store/journal/`: U6(`OperationMapper`·`OperationStorage`·정리 기록)와 U18(본문 읽기·쓰기)가 같은 두 파일을 쓴다. **U6 병합 뒤에 U18이 시작한다**(F.2의 V11 < V14·V15와 같은 순서). U18은 U6가 정한 모양을 바꾸지 않고 본문 부분만 바꾼다.
- `PortalServerApplication.java`: U3가 만들고 U19가 종료 배선을 더한다(U3 뒤).
- `portal-server/**/server/assembly/**`: U2가 소유하고 U18이 저장소 배선만 더한다(U2 뒤).
- `portal-ssh/**/ssh/platform/**`: U5가 `*TargetPlatform.java`의 `prepareSource` 서명만, U20이 나머지를 쓴다(U5 뒤 U20 또는 파일 단위로 나눈다. 같은 파일을 동시에 고치지 않는다).
- 공유 파일(`settings.gradle.kts`, 루트 `build.gradle.kts`, `portal-arch-checks`, 기준본 JSON)은 리드가 소유하고 8절 규칙으로만 바꾼다.

**기준본을 읽는 시험의 담당과 시점(검토 N21)**:

| 기준본 | 최소 시험 | 누가·언제 |
|---|---|---|
| 모든 JSON(경로 범주, 기기 토큰 범위, 설정 키, `server.json`, 도구 15개의 판, `webLayerCodes`) | `ContractBaselineIntegrityTest`(S4-CB-20~24) | **이 브랜치(U0r)가 둔다.** CI가 매번 읽는다 |
| `http/admin-routes.json` | `AdminRoutesContractTest`(S4-AD-01·02·04·05·CB-04: 서버가 실제로 등록한 경로와 대조) | U6가 마칠 때. 같은 커밋에서 `ContractBaselineIntegrityTest`의 S4-CB-20·21을 지운다 |
| `mcp/tools/*.json`, `mcp/server.json` | `McpToolListContractTest`·`McpServerInfoTest`(S4-CB-01~03) | U5가 마칠 때. S4-CB-23을 지운다 |
| `settings/settings-contract.json` | `SettingsContractTest`(S4-CB-05)·`LegacyKeyGuardTest` | U2가 마칠 때. S4-CB-22를 지운다 |
| `mcp/envelope.schema.json` | `ContractRecordsStructureTest`·`ErrorCodeBaselineTest`(있음) + 보안 응답 시험(U4) | 있음 / U4가 마칠 때 S4-CB-24를 지운다 |
| `hooks/*.json` | `HookContractTest`(S4-CB-06) | U5·U6가 마칠 때 |

### F.4 운영체제별 기능 지원 표
표기: 「지원」은 이 문서의 설계가 같은 기능을 낸다는 뜻이다(구현·시험은 표의 단위가 한다). 「미정」은 사용자 결정·조사 대기, 「미확인」은 이 문서가 사실을 확인하지 못한 것이다. 미지원 칸마다 이유를 적고 그것이 **원리적 한계**인지 **범위 결정**인지 가른다.

**표 1. 포털 서버의 운영체제(Linux / Windows)**

| 기능 | Linux 서버 | Windows 서버 | 근거·이유 |
|---|---|---|---|
| 두 포트 웹·MCP·관리자 API·UI | 지원 | 지원 | Java·Spring, 운영체제 무관 |
| 데이터 폴더를 소유자 전용으로 만들기 | 지원(POSIX 권한) | 지원(NTFS ACL) | `PrivateStorages`가 속성 보기로 고름(B-17) |
| 지원 파일 시스템 | `posix` 보기가 있는 로컬 저장소 | `acl` 보기가 있는 NTFS | 미지원: 보기가 없는 저장소(FAT·exFAT 등)는 소유자 전용으로 만들 방법이 없어 시작을 거절 — 원리적(소유자 전용을 보장할 수단이 없음). 어느 종류가 어느 보기를 알리는지는 미확인 |
| 네트워크 파일 시스템 | 권하지 않음(경고) | 권하지 않음(경고) | H2 파일 잠금·원자적 이름 변경 신뢰 불가. 범위 결정. 종류 이름 목록은 미확인 |
| 설정 반영 재시작 | 지원(systemd `Restart=on-failure`, 종료 상태 75) | 미확인(procrun jvm 모드의 SCM 복구, S4-RS-05). 안 되면 「서비스를 직접 다시 시작」 안내 | procrun 문서가 종료 코드·복구를 말하지 않음(B-11) |
| 서비스 정지 | 지원(SIGTERM, 종료 상태 143을 성공으로) | 지원(procrun `stop` 진입점) | B-11 |
| 서비스로 설치·자동 시작 | 지원(systemd) | 지원(procrun, `--Startup=auto`) | 승인된 Commons Daemon |
| 설치 스크립트의 배포판 | Debian 계열(apt)·RHEL 계열(dnf) | Windows 설치 스크립트(7단계) | 미지원: SUSE·Alpine·Arch·RHEL 7 — 범위 결정(사용자 U-5), 원리적 한계 아님 |
| CPU | x86-64 지원, arm64 지원(CI) | x64 지원 | Windows arm64는 미확인 |
| 앱 안 TLS | 미정(앞단이 TLS, 2.x와 같음) | 미정(같음) | B-8a, F.6 항목 5 |
| 로그인(OIDC·토큰)·승인·감사 | 지원 | 지원 | 운영체제 무관 |
| 원격 데스크톱 RFB(붙인 VNC 서버, 원격 Linux Xvnc) | 지원 | 지원 | Java 클라이언트(`vernacular`, C.1)라 서버 OS와 무관(사용자 U-3) |
| 원격 데스크톱 RDP | 지원 대상, 구현 방식 미정 | 지원 대상, 구현 방식 미정 | 사용자 결정 U-3, 조사 뒤 확정(C.3). 「Windows 서버는 데스크톱 미지원」은 폐기 |
| ssh-agent 인증 | 미정 | 미정 | 사용자 결정 U-6, 조사 뒤 재질문(C.4) |
| 로컬 프로세스 실행 | 하지 않음 | 하지 않음 | 개발 원칙(본체) |
| PC 클라이언트 자동 시작 | `systemd --user` | HKCU `Run` 값(260자 이하) | B-17. 작업 스케줄러는 일반 사용자 생성 가능 여부 미확인 |

**표 2. 접속 대상(Linux / Windows)**

| 기능 | Linux 대상 | Windows 대상 | 근거·이유 |
|---|---|---|---|
| 명령 실행 | 지원 | 지원(Windows PowerShell 5.1, 줄 8,000자 이하) | 8,000자 초과는 `WINDOWS_COMMAND_TOO_LARGE`: `-EncodedCommand` 길이 제한(2.x와 같음) — 원리적 |
| 명령 분류 4등급 | 지원 | 지원(B-16, `read-only`는 2개 그대로) | 이전의 「두 cmdlet만 read-only」를 대칭으로 올림 |
| SOURCE 실행(Python) | 지원(해석기 선택 설정, 기본 `/usr/bin/python3`) | 지원(해석기 절대 경로 필수) | B-3, D-0170·D-0172. Windows는 설치된 경로를 알 수 없어 필수 — 원리적 |
| 파일 업로드·다운로드 | 지원(SFTP) | 지원(SFTP) | - |
| 새 파일의 소유자 전용 권한 | 지원(`setStat` 0600) | 지원(`icacls`, U-4) | SFTP는 ACL을 못 건다. 이전에는 「불가」 |
| 덮어쓰기 때 권한 보존 | 지원(모드) | DACL 지원(`icacls /save·/restore`), 소유자·무결성 레이블은 미확인 | `/save`·`/restore`는 DACL만 다룸(문서). 소유자·레이블은 권한에 달림(S4-OS-05) |
| 덮어쓰기 때 소유자·그룹·xattr 보존 | 미지원 | 미확인 | Linux: 일반 계정 SFTP `setStat`는 uid·gid 변경에 root가 필요 — 원리적. 릴리스 노트에 동작 변경으로 적음 |
| 상위 폴더 `fsync` | 확장을 광고할 때만 | 같음 | SFTP 확장(`fsync@openssh.com`) 광고에 달림 |
| 하드링크 수·파일 종류 검사 | 심볼릭 링크·종류는 `lstat`로 지원 | 리파스 포인트 보고는 미확인, 하드링크 수는 미지원 | 하드링크 수는 SFTP 속성에 없음 — 원리적 |
| 권한 상승 | 지원(`sudo -n`) | 지원(기존 SSH 연결의 관리자 토큰 확인) | Windows는 새 자격 취득·UAC 우회를 하지 않음 — 범위 결정(보안) |
| 취소·기한 초과의 원격 프로세스 종료 | 지원 | 지원(`taskkill /pid /t /f`, U-4) | 이전 Windows는 `UNSUPPORTED`였음. PID 전달은 Win32-OpenSSH에서 미확인(S4-OS-06) |
| 에이전트 CLI 설치·로그인 진단 | 지원 | 지원(Node 22 이상 필요) | - |
| 원격 데스크톱 RFB | 지원(Xvnc 관리형, 또는 loopback 붙인 서버) | 지원(대상 loopback의 RFB 3.8·None, 신뢰된 대상만) | 같은 OS의 다른 로컬 사용자를 인증하지 않음 — 범위(2.x와 같음) |
| 원격 데스크톱 RDP | 해당 없음 | 지원 대상, 구현 방식 미정 | C.3 |
| 볼륨이 NTFS인지 검사 | 해당 없음 | 미지원(SFTP로 알 수 없음), `icacls` 실패로 대신 알림 | 원리적(SFTP 속성에 없음). 대체 통제는 B-17 |
| 허용 루트 경로 정책 | 없음 | 있음(루트 포함 비교) | 2.x 동작 유지 |
| ssh-agent 인증 | 미정 | 미정 | C.4 |

### F.5 사용자 결정·리드 판정이 반영된 위치
| 번호 | 반영 위치 |
|---|---|
| U-1 | 0.3a, B-4, C.2, 기준본 대조 S4-SE-10, `contract-baseline.md` 9.1(쿠키는 기준본 JSON 밖이므로 표 행 없음) |
| U-2 | 0.3a, A.3, B-4a, B-12, V9 `audit_refusal_streak`, S4-SE-03·05·15, S4-AS-05 |
| U-3 | 0.3a, 0.4, C.3, F.4 표 1(RDP·RFB 행) |
| U-4 | 0.3a, B-16, A.11(U20), F.4 표 2, S4-OS-01~06 |
| U-5 | 0.3a, B-17, A.12(U21), F.4 표 1, S4-IN-01~03 |
| U-6 | 0.3a, C.4, F.4(미정), F.6 |
| L-1 | 0.3b, B-2a, A.5, 기준본 `/api/artifacts` 복원 |
| L-2 | 0.3b, B-2, A.5, 기준본 분리 |
| L-3 | 0.3b, B-2b, `contract-baseline.md` 9.2 |
| L-4 | 0.3b, A.3, B-8a, F.6 항목 5 |
| L-5 | 0.3b, A.1, F.2 |
| L-6 | 0.3b, B-10a, B-12, B-13, B-14, F.2·F.3 |
| L-7 | 0.3b, B-15, A.9, F.2 |
| L-8 | 0.3b, B-11, A.8, A.10 |
| L-9 | 0.3b, B-3(SOURCE), B-16(Windows 분류), B-17(자동 시작·파일 시스템), F.4 |

### F.6 미정 항목과 사용자에게 물을 정책 선택
다음은 위 결정으로 덮이지 않아 이 문서가 임의로 정하지 않은 것이다. 설계서는 미정으로 두고 권장안을 적었다.

| 항목 | 선택지 | 권장 | 설계서에서의 상태 |
|---|---|---|---|
| 1. RDP 구현 방식(U-3) | 조사 중 | 조사 결과 뒤 | C.3 미정 |
| 2. ssh-agent 인증(U-6) | 조사 중 | 조사 결과 뒤 | C.4 미정 |
| 3. `POST /api/files/downloads`를 기기 토큰에 열기 | (가) 연다(2.x 클라이언트 `request-download` 유지, 승인 권한 없음) (나) 열지 않는다(에이전트는 MCP `submit kind=download`만) | (가) | 규칙 「굳이 편의기능 있는건 빼지마」에서 도출해 (가)로 적음. 되돌리려면 기준본 한 줄(`auth30`)과 S4-DT-02·03 |
| 4. 3.0 OIDC 모드의 다운로드 링크가 로그인 세션을 요구하지 않음(2.x oidc 모드와 다름) | (가) 티켓만(2.x token 모드와 같음) (나) oidc 모드에서만 세션 요구(MCP 카드의 링크가 열리지 않음) | (가) | B-2b에 (가)로 적음(리드 판정 L-3). 사용자에게 알림용 |
| 5. 앱 안 TLS를 3.0.0에 넣을지 | (가) 넣지 않음(2.x와 같음, 후속 minor) (나) PEM만(Spring Boot SSL 번들, 새 라이브러리 없음, 포트별 설정 키가 늘어 기준본 변경) (다) PEM+ACME(RFC 8555 라이브러리 승인 필요) | (가) | B-8a·F.4에 미정. 2.x에 없던 기능이므로 삭제가 아님. feature-map 230행에 선택 TLS 설계가 적혀 있어 사용자 확인이 필요 |
| 6. procrun에서 재시작이 안 될 때의 대비 | (가) 「직접 재시작」 안내(`NO_SUPERVISOR`)로 내림 (나) procrun을 `Java` 모드(자식 프로세스)로 바꿔 부모가 재시작 (다) 다른 서비스 래퍼 도입(새 도구, 승인 필요) | (가), S4-RS-05 결과를 본 뒤 | B-11에 (가)가 설계 |
| 7. 작업 본문 이전 방식(U18) | (가) 앞으로만 가는 `V14`·`V15`(표가 비어 있어야 함, 설계) (나) 배포 전이므로 `V1`을 직접 고침(번호는 줄지만 다른 브랜치가 가진 `V1`과 충돌, 개발 DB는 어차피 새로 만들어야 함) | (가) | B-15에 (가) |
| 8. 감사 묶음·속도 제한의 상수(연속 5회, 15분, 분당 60, 인증 전 세션 256, 5분) | (가) 코드 상수 (나) 설정 키 | (가) | B-4a·B-12에 (가). (나)로 바꾸면 설정 키가 늘어 기준본을 고침 |
| 9. Linux 설치의 Java 25 런타임 확보 방식 | 배포판 패키지 / JRE 묶음 동봉 | 확인 뒤 | B-17 미확인, U21이 조사 |
