# 3.0.0 Java 전환 4단계 설계 (설정 바인딩 · 두 포트 웹 · 보안 · MCP 도구 · 관리자 API · UI · 연동)

상태: 설계 확정본(U0). 구현 단위 U2~U9의 담당은 이 문서를 기준으로 삼는다. 계약 기준본 개정(U1)은 B-1절의 변경 목록과 같다.
기준: 브랜치 `feature/v300-stage4-contract`(`develop` `f3a0efe`에서 분기). 형식 본보기는 `stage3-design.md`(이 문서에서 `S3`)다.

## 0. 읽는 법

### 0.1 표기
- `[사실]` 직접 읽은 것이다. 근거는 `파일:줄` 또는 클래스 이름이다. 2.x 경로는 저장소 루트 기준(태그 `v2.4.2`, `a44c4d1`), Java 경로는 `java/` 기준이다.
- `[결정]` 이 문서가 정한 것이다. 근거를 같은 줄이나 바로 아래에 적는다.
- `[미확인]` 공식 문서·소스로 확인하지 못한 것이다. 각 항목은 그것을 확인하는 사례 ID를 함께 적는다. 구현 담당은 그 사례를 먼저 시험으로 만들고, 결과가 다르면 이 문서를 고치는 변경을 같은 단위에 넣는다.
- 패키지 접두는 `io.github.jaeseongs95.portal`이고 본문에서는 `web.…`·`app.…`·`server.…`로 줄인다.
- 제품 설계 기준: 저장소는 공개이고, 제품은 불특정 사용자가 자기 호스트에 설치한다. 특정 클라우드·터널·DNS 업체는 기본값·설계 근거가 아니다. 업체 API 연동은 벤더 중립 포트 뒤의 선택 구현이다.
- 사례 ID는 `S4-<영역>-<번호>`다. 영역: ST 설정, AS 조립, WS 웹 골격, ER 오류 표현, HE health, SE 보안, DT 기기 토큰, MC MCP 도구, SR SOURCE 실행, AD 관리자 API, RC 소유자 정리, UI 화면, RK 위험 평가·자동화·비밀, RL 릴리스·환경 편집·선택 어댑터, CB 계약 기준본 대조.

### 0.2 읽은 입력
- 계획 정본(4단계 절, 2026-11-15 결정 1~7, Q2 보완), `contract-baseline.md` 전체, `feature-map.md`(4.1 판정 C2, 포털 웹·서버·클라이언트 행), `stage3-design.md` 11절, `foundation-cases.md` 4절(`PortsSpikeTest` Q-01~Q-07), `official-docs-spring.md` Q1.
- 기준본 JSON 전부(`contract/mcp/**`, `http/admin-routes.json`, `settings/settings-contract.json`, `hooks/**`)와 이를 읽는 시험 세 개(`ContractRecordsStructureTest`, `ErrorCodeBaselineTest`, `SettingsVectors`).
- Java: `server.assembly.*` 전부, `app.execution.{OperationRunner,CommandOperationRunner}`, `app.admission.AdmissionGate`, `app.artifact.ArtifactRefusal`, `app.target.TargetPlatform`, `domain.operation.{Operation,ReconciliationEvidence,CompletionEvidence,SourceInput}`, `domain.schedule.ConcurrencyLimits`, `domain.contract.{ContractVersion,Envelope,GuiDescriptor,GuiOpenRequest,WireFormat}`, `domain.error.DesktopErrorCode`.
- 2.x: `src/application/desktop.mjs:12-35`(open 요청 키 발급·검증), `bridge.mjs:12-15`(감사·전송 경로 기본값), `scripts/portal-files.mjs`(클라이언트가 부르는 경로).

### 0.3 2026-11-21 사용자 결정(이 문서가 따르는 것)
| 항목 | 사용자 선택(원문) | 이 문서의 반영 |
|---|---|---|
| 관리 화면 로그인 | 「OIDC + 토큰 (Recommended)」 | U4: 로그인은 OIDC(Spring Security OAuth2 Login)와 고정 관리자 토큰 두 가지다 |
| OAuth 로그인 제한 | 「계정 1개만 허용 (Recommended)」 | 설정에 적은 소유자 계정 하나(제공자의 사용자 식별자 `sub`)만 로그인된다. 같은 제공자의 다른 계정은 거절하고 감사에 남긴다. 키 `BRIDGE_OIDC_OWNER_SUBJECT`(B-1) |
| passkey | 「passkey는 전부 제거 유지」 | 로그인·승인 모두 passkey가 없다. WebAuthn 라이브러리를 들이지 않는다 |
| 고위험 승인의 추가 확인 | 「로그인 세션만 (추가 확인 없음)」 | 승인·거부·정리 같은 고위험 요청은 허용 계정의 로그인 세션 + CSRF 토큰 + Origin 검사로 받는다. 잔여 위험은 C절 |
| 라이브러리 | 「Spring Security 스타터, Playwright for Java, Commons Daemon procrun, Spring Boot Actuator」 | D절 「승인 기록」. `/health`는 Actuator `HealthIndicator`로 낸다(개발원칙 3.6) |
| RFB 클라이언트 | 「후보 조사 후 다시 질문 (Recommended)」 | C절 미결. 데스크톱 단위(이 문서 범위 밖)의 입력이다 |

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

## 1. 단위와 순서

| 단위 | 맡는 것 | 앞선 단위 |
|---|---|---|
| U2 | 설정 바인딩(`BridgeProperties`)·서버 조립(시험용 기본값 교체)·감사 DB 표 | U1 |
| U3 | 두 포트 웹 골격(부모+자식 컨텍스트)·공통 오류 변환·`/health` | U2 |
| U4 | 보안: 로그인(OIDC·토큰)·MCP bearer·기기 토큰·브라우저 세션·CSRF·Origin | U3 |
| U5 | MCP 도구 24개·SOURCE 실행기 | U4 |
| U6 | 관리자 HTTP API(기기 토큰 관리·소유자 정리 포함) | U4 |
| U7 | 정적 관리 UI·MCP Apps 카드·AGPL 소스 안내 | U6(화면이 부르는 API) |
| U8 | 위험 평가(Jev)·명령 자동화·비밀 해석기 | U3 |
| U9 | 릴리스 카탈로그·환경 편집·선택 어댑터 | U4(환경 편집 API의 인증) |

U5·U6·U8은 U4 뒤에 병렬로 할 수 있다. 쓰기 범위는 패키지로 나눈다(A절 각 단위의 「쓰기 범위」). 공유 파일(`settings.gradle.kts`, 루트 `build.gradle.kts`, `portal-arch-checks`, 기준본 JSON)은 리드가 소유하고, 단위가 기준본을 바꿀 때는 8절 규칙을 따른다.

의존 방향(모든 단위 공통, `foundation-cases.md` A-DIR): `domain ← app ← (store, ssh, desktop, web) ← server`. 새 포트는 `app`에, 어댑터는 `store`·`web`·`server`에 둔다. `web`은 `store`·`ssh`를 보지 않는다. Actuator `HealthIndicator`는 `app` 포트를 읽는 어댑터라 `server`(조립 모듈)에 둔다.

## A. 단위별 설계

### A.1 U2 설정 바인딩·서버 조립
목표: `server.assembly.AssemblyDefaults`의 시험용 기본값을 3.0 설정 계약(B-1.6)의 바인딩으로 바꾼다. S3 11.4 「설정 바인딩」 항목을 닫는다.

- `server.config.BridgeProperties` `[결정]`: `@ConfigurationProperties(prefix = "bridge")` `@Validated` record. 환경 변수 이름의 밑줄마다 점이 되도록 중첩한다(`BRIDGE_MCP_TOKEN` → `bridge.mcp.token`, `BRIDGE_ADMIN_AUTH_MODE` → `bridge.admin.auth.mode`). 근거: 계획 정본 「env 이름」(Boot relaxed binding은 대시를 지우므로 중첩으로 맞춘다).
  - 하위 record 예: `Mcp(port, token)`, `Admin(port, token, origin, Auth(mode))`, `Oidc(issuer, Client(id, secret), Owner(subject))`, `Ui(enabled, Tool(prefix), Ticket(key))`, `Data(dir)`, `Config(path)`, `Transfer(maxBytes→max.bytes …)`, `Source(url)`, `Jev(…)`, `Vault(…)`.
  - 비밀 값(`*_TOKEN`, `*_KEY`, `*_SECRET`)은 문자열이 아니라 기존 `SecretValue`로 받는다(로그·`toString`에 값이 나오지 않음).
- `server.config.BridgeTargetsConverter`: `BRIDGE_TARGETS`(JSON)를 받는 `@ConfigurationPropertiesBinding` 변환기 하나. Windows 대상의 Python 해석기 경로(`pythonExecutable`)도 여기서 받는다(B-3).
- `server.config.SecretEnvFileLoader`(`EnvironmentPostProcessor`): 비밀 env 파일을 읽는다(feature-map 이름 그대로).
- `server.assembly.SettingsAssembly`: `BridgeProperties`를 `SshSettings`, `ConcurrencyLimits`, `AdmissionLimits`, `ApprovalPolicy`, `ExecutionLimits`, `StorageSettings`, `ConnectionFiles`, `TargetSource`, `ConfiguredTargets`로 옮기는 `@Bean` 모음. `AssemblyDefaults`는 지운다(시험용 값은 시험 소스로 옮긴다).
  - 데이터 폴더 `[결정]`: `BRIDGE_DATA_DIR` 하나 아래에 H2 파일(`portal`), `artifacts/`, `outputs/`, `known_hosts`를 둔다. 2.x의 `BRIDGE_AUDIT_PATH`·`BRIDGE_TRANSFER_ROOT`는 없앤다(B-1.6). 근거: 감사는 DB 표(2026-11-15 결정 5)라 파일 경로가 필요 없고, 저장 위치를 한 곳으로 모으면 백업·이전·권한 검사가 한 폴더로 끝난다(S3 D3-10 「기본은 데이터 디렉터리 아래」와 같은 방향). 기본값은 없다(설치 명령이 쓴다, `StorageSettings` 주석과 같은 원칙).
  - DB: `spring.datasource.url`이 비어 있으면 `jdbc:h2:file:<dataDir>/portal`. 다른 DBMS는 Boot 표준 키(`SPRING_DATASOURCE_*`)로 준다(새 `BRIDGE_*` 키를 만들지 않는다).
- 감사 DB 표 `[결정]`: `store.audit.JdbcAuditLog`(`AuditLog` 구현)와 `db/expand/V8__audit_record.sql`. 추가만 하고, 커밋 뒤 반환하며, 읽기·수정·삭제 경로가 없고, 고정 한도가 없다. 비밀·명령 원문·본문은 열에 넣지 않는다(본문은 DB 열 금지 기준). `JsonlAuditLog`·`AuditFile`·`AuditFileLocation`은 지운다.
- 쓰기 범위: `portal-server/src/**/server/{config,assembly}/**`, `portal-store/src/**/store/audit/**`, `portal-store/src/main/resources/db/expand/V8__audit_record.sql`.
- 완료 사례: S4-ST-01~06, S4-AS-01~03, S4-CB-05.

### A.2 U3 두 포트 웹 골격·공통 오류 변환·`/health`
- `server.PortalServerApplication` `[결정]`: 부모 컨텍스트는 비웹(`WebApplicationType.NONE`)이고 domain·app·store·ssh·desktop 빈을 둔다. 자식 웹 컨텍스트 두 개를 `SpringApplicationBuilder.child(…)`로 띄운다(B-8).
- `server.McpWebConfiguration`(자식 1): `bridge.mcp.port`, Spring AI MCP 서버(webmvc), MCP 보안 사슬, Actuator health.
- `server.AdminWebConfiguration`(자식 2): `bridge.admin.port`, 관리자 MVC 컨트롤러, 정적 UI, 관리자 보안 사슬. Actuator 자동 구성은 제외한다.
- `web.presentation.RefusalResult`, `web.presentation.ResultPresenter`, `web.presentation.HttpStatusTable`, `web.presentation.AdminExceptionHandler`(`ResponseEntityExceptionHandler` 하나): B-7.
- `server.health.StorageHealthIndicator`(DB 연결·데이터 폴더 쓰기 가능), `server.health.AdmissionHealthIndicator`(유지보수 창이 닫혀 있지 않음). B-5.
- 쓰기 범위: `portal-server/src/**/server/{PortalServerApplication.java,McpWebConfiguration.java,AdminWebConfiguration.java,health/**}`, `portal-web/src/**/web/presentation/**`, `portal-server/build.gradle.kts`(Actuator), `portal-web/build.gradle.kts`.
- 완료 사례: S4-WS-01~05, S4-ER-01~04, S4-HE-01~04.

### A.3 U4 보안
- `web.security.AdminAuthMode`(`TOKEN`, `OIDC`), `web.security.AdminAuthMethod`(interface: `mode()`와 그 방식이 거는 보안 설정), 구현 `TokenAdminAuthMethod`, `OidcAdminAuthMethod`. 설정 모드와 `mode()`가 같은 구현을 `List<AdminAuthMethod>`에서 고른다. 공통 코드에 모드 `if`·`switch`를 두지 않는다(feature-map 메인 판정 유지). feature-map의 `OidcJwtAuthMethod`(2.x 프록시 JWT 검증)와 `OidcLoginConfig`는 `OidcAdminAuthMethod` 하나로 합친다. 2.x는 앞단 프록시가 준 JWT를 검증했으나 3.0은 앱이 직접 OAuth2 Login을 한다(Q2 「Spring Security 로그인」).
- `web.security.OwnerSubjectPolicy`: OIDC 로그인 결과의 `iss`가 `BRIDGE_OIDC_ISSUER`와 같고 `sub`가 `BRIDGE_OIDC_OWNER_SUBJECT`와 정확히 같을 때만 소유자 권한을 준다. 다르면 세션을 버리고 403 화면으로 보내며 감사 `ADMIN_LOGIN_REFUSED`(issuer·subject, 비밀 없음)를 남긴다. 설정에 쉼표나 공백이 든 값(여러 계정)은 시작 단계에서 오류로 멈춘다.
- `web.security.TokenLoginFilter`: `POST /login/token`(폼 필드 `token`)이 `BRIDGE_ADMIN_TOKEN`과 상수 시간 비교로 같으면 세션을 만든다.
- `web.security.AdminSecurityConfiguration`: `@Order`+`securityMatcher` 다중 `SecurityFilterChain`(계획 정본 공식 문서 반영).
  1. Bearer 사슬(`Authorization: Bearer` 헤더가 있는 요청): 무상태, CSRF 없음. `BearerTokenIntrospector`(`OpaqueTokenIntrospector` 구현)가 관리자 토큰이면 `OWNER`, 기기 토큰이면 `DEVICE` 권한을 준다. Origin 헤더가 있으면 `BRIDGE_ADMIN_ORIGIN`과 같아야 한다.
  2. 브라우저 사슬(그 밖): 세션, CSRF(세션 저장, 헤더 `X-CSRF-TOKEN`), 안전하지 않은 메서드는 Origin 일치(`web.security.AdminRequestPolicy`). 로그인은 모드별 `AdminAuthMethod`가 건다.
  3. 경로별 권한은 `admin-routes.json`의 `auth30`(B-1.5) 범주 그대로다. `owner-token` 범주(유지보수 6경로)는 1번 사슬의 관리자 토큰만 통과한다.
- `web.security.McpSecurityConfiguration`(MCP 자식): `McpTokenIntrospector`가 `BRIDGE_MCP_TOKEN`만 받는다. 관리자 토큰·기기 토큰은 MCP 포트에서 거절된다.
- `web.security.BrowserSessionAdapter`: B-4.
- `web.security.UiTicketSigner`: MCP Apps 카드용 HMAC 티켓(2.x `tickets.mjs`, 이름 그대로).
- 기기 토큰: `domain.device.{DeviceTokenId,DeviceToken,DeviceTokenSecretDigest}`, `app.device.{DeviceTokenService,DeviceTokenStore}`(포트), `store.device.JdbcDeviceTokenStore`, `db/expand/V9__device_token.sql`. B-2.
- 쓰기 범위: `portal-web/src/**/web/security/**`, `portal-{domain,app,store}/src/**/device/**`, `V9__device_token.sql`, `portal-web/build.gradle.kts`(Security 스타터).
- 완료 사례: S4-SE-01~12, S4-DT-01~09.

### A.4 U5 MCP 도구 24개·SOURCE 실행기
- `web.mcp.McpServerConfiguration`: 서버 정보(이름 `SSH Approval Portal`, 판 = 프로젝트 판), `instructions`(기준본 문장 + `{sourceUrl}` 치환, B-9), 리소스(카드), 도구 등록.
- `web.mcp.PublicToolDefinitions`(공개 15개), `web.mcp.UiToolDefinitions`(`show-*` 3개, `ui-*` 6개), `web.mcp.ArtifactChunkCodec`, `web.mcp.CardResource`.
  - 도구 정의 `[결정]`: 도구별 `description`·`inputSchema`·`outputSchema`·`annotations`는 제품의 main 자원 `mcp/tools/<이름>.json`에 두고 Spring AI의 프로그램 방식 도구 명세로 등록한다. record에서 스키마를 생성하지 않는다. 근거: 기준본과 키 순서 무시 동일이 완료 조건이고(S4-CB-01), 생성기는 Zod 유래 스키마의 세부(`(?![\s\S])`, `anyOf` null 등)를 같은 모양으로 내지 않는다. 시험 기준본(`src/test/resources/contract`)과 제품 정의(`src/main/resources/mcp`)는 역할이 다른 두 파일이고, S4-CB-01이 둘을 대조한다.
  - 입력 검증: 스키마 검증 뒤의 도구별 규칙(`portalRules`)은 `app` 유스케이스 입력 record의 생성자와 도구 어댑터에 둔다. 규칙 위반은 `ok=false` 봉투다.
- `files`: `operationKey`가 네 동작 모두 필수다(B-1.2). `copy`·`move`·`remove`는 같은 키·같은 입력이면 처음 결과를 돌려주고, 같은 키·다른 입력이면 `OPERATION_INPUT_CONFLICT`다. `stat`은 읽기라 키를 멱등 판정에 쓰지 않고 감사 기록의 상관 키로만 남긴다.
- `desktop-open`: `operationKey` 필수(B-1.3). 같은 키·같은 대상·같은 판이면 같은 lease를 돌려주고, 닫혔거나 만료된 키는 다시 열지 않는다(2.x 설명의 「A closed/expired key cannot reopen」 의미 유지).
- SOURCE 실행기: B-3. `app.execution.SourceOperationRunner`, `app.execution.SourcePlanRegistry`.
- 쓰기 범위: `portal-web/src/**/web/mcp/**`, `portal-web/src/main/resources/mcp/**`, `portal-app/src/**/app/execution/Source*`, `portal-app/src/**/app/target/TargetPlatform.java`(서명), `portal-ssh/src/**/ssh/platform/*TargetPlatform.java`.
- 완료 사례: S4-MC-01~10, S4-SR-01~06, S4-CB-01~03.

### A.5 U6 관리자 HTTP API
- `web.admin.*Controller`: feature-map의 `AdminApiControllers`·`FileTransferController`·`DownloadController`를 경로 묶음별 컨트롤러로 나눈다(요청·대기·작업 조회, 파일 업로드·다운로드, 용량, 데스크톱, 에이전트 CLI, 환경, 명령 정책, 유지보수, 출력). `ArtifactController`는 만들지 않는다(`/api/artifacts` 제거, B-1.5).
- `web.admin.DeviceTokenController`: `GET /api/devices`, `POST /api/devices`, `POST /api/devices/{id}/revoke`(B-2).
- `web.admin.ReconciliationController`: `GET /api/operations/reconciliation`, `POST /api/operations/{id}/reconcile`(B-10).
- `app.operation.OperationReconciliation`: 정리 대상 목록과 예산(사용 중 unknown 수, 예산), 소유자 정리. `domain.operation.CompletionEvidence.OwnerConfirmed(Instant confirmedAt)`을 더한다(소유자가 대상을 확인해 완료라고 판단한 증거).
- 오류 응답: 모두 `ResultPresenter`를 거친 `application/problem+json`(B-7).
- 쓰기 범위: `portal-web/src/**/web/admin/**`, `portal-app/src/**/app/operation/OperationReconciliation*`, `portal-domain/src/**/domain/operation/CompletionEvidence.java`.
- 완료 사례: S4-AD-01~08, S4-RC-01~07, S4-CB-04.

### A.6 U7 정적 관리 UI·카드
- `web.ui.StaticPageConfiguration`: ES 모듈을 classpath `static/`에서 그대로 서빙한다(번들러 없음, 계획 정본). 경로는 기준본의 정적 경로 항목이다.
- 화면에 더하는 것: 로그인 화면(모드에 맞는 버튼 또는 토큰 입력), 기기 토큰 화면(발급 시 한 번 보여 줌, 회수), 정리 배너·목록(B-10), 바닥글의 소스 안내(B-9).
- `web.presentation.CapabilityTickets`, `web.mcp.CardResource`: MCP Apps 카드(기준본 `server.json` 리소스).
- 시험: Playwright for Java(승인됨)로 브라우저 사례(S4-UI-*)를 돌린다.
- 쓰기 범위: `portal-web/src/main/resources/static/**`, `portal-web/src/**/web/ui/**`, `portal-web/src/test/**/web/ui/**`.
- 완료 사례: S4-UI-01~07.

### A.7 U8 위험 평가·명령 자동화·비밀
- `web.jev.JevRiskEvaluator`(`app.risk.RiskEvaluator` 구현, `RestClient`), 호출 상한 `BRIDGE_JEV_MAX_CALLS`.
- 명령 자동화: 기존 `app.policy`의 정책 저장과 `BRIDGE_COMMAND_AUTOMATION` 스위치를 잇는다. 자동 승인은 정책·위험 평가 둘 다 허용일 때만이다(2.x 의미 유지).
- 비밀: `app.secret.SecretResolver` 포트, `server.secret.LocalFileSecretStore`(`BRIDGE_SECRET_STORE_DIR`), `server.secret.VaultSecretStore`(`RestClient`, `BRIDGE_VAULT_*`). `*_REF` 키는 이 해석기를 거친다.
- 쓰기 범위: `portal-web/src/**/web/jev/**`, `portal-app/src/**/app/{risk,policy,secret}/**`, `portal-server/src/**/server/secret/**`.
- 완료 사례: S4-RK-01~06.

### A.8 U9 릴리스 카탈로그·환경 편집·선택 어댑터
- `web.release.NpmRegistryCatalog`(`app.agentcli.AgentReleaseCatalog` 구현, `RestClient`): S3 11.4 「릴리스 카탈로그 어댑터」를 닫는다. `AssemblyDefaults.agentReleaseCatalog()`의 `Unavailable`을 대신한다. 무결성 값이 없는 릴리스는 받지 않는다(지금의 「알 수 없는 출처의 바이트를 설치하지 않음」 유지).
- `web.release.PortalReleaseChecker`: `GET /api/version`(제품 새 판 확인).
- 환경 편집: `server.config.EnvironmentFileEditor`(허용 키만, B-1.6의 `allowedKeys`), 반영 재시작은 `ExitCodeGenerator`의 0이 아닌 고정 종료 코드(계획 정본 「재시작 종료 코드」).
- 선택 어댑터: DNS 레코드·원본 인증서 포트(`DnsRecordProvisioner`, `OriginCertificateSource`) 뒤의 업체 구현은 선택이고 설치 기본 흐름에 들어가지 않는다. `AccessProvisioner`·`ConfigureAccessCommand`는 만들지 않는다(access 모드 없음).
- 쓰기 범위: `portal-web/src/**/web/release/**`, `portal-server/src/**/server/config/EnvironmentFileEditor*`, 선택 어댑터 패키지.
- 완료 사례: S4-RL-01~06.

## B. 판정

### B-1 새 계약 판과 전체 변경 목록
`[결정]` 계약 판은 `"3.0"`이다. 근거: SSH_PORTAL-D-0112(쓰기 도구 키 결정 위반)와 2026-11-15 결정 1(하위 호환 없음, 계약 판을 올린다). 아래가 2.4.2 기준본과의 차이 전부다. U1은 이 목록만 기준본에 반영했고, 목록 밖의 차이는 없다(`contract-baseline.md` 9절 변경표와 같은 내용).

1. **계약 판**: `server.json`의 `contractVersion`·`instructions` 첫 문장(`Contract 3.0.`), `envelope.schema.json`의 `$id`·`title`·`contractVersion.const`, 공개 도구 15개의 `outputSchema` `contractVersion.const`, `show-*`·`ui-*` 9개 `portalRules.outputSchema`의 「Not the Contract 3.0 envelope」 문구(`show-ui-probe`는 「does NOT use the Contract 3.0 envelope」), `admin-routes.json` `GET /api/operations/by-key` 응답 모양의 `contractVersion:'3.0'`, `server.json`의 `contractVersionSource`·`instructionsSource`(설계 위치와 2.4.2 값을 함께 적음). 도메인 `ContractVersion.V3_0("3.0")`.
2. **`files`**: `required`에 `operationKey`를 더한다(네 동작 모두). 설명의 「Copy/move require operationKey, …」를 「Every action requires a stable operationKey; copy/move also require …」로 고친다. `portalRules.inputSchema`의 `stat|remove` 허용 필드에 `operationKey`를 더한다.
3. **`desktop-open`**: 입력 `requestKey`(서버 발급 형식)를 없애고 `operationKey`(공통 키 형식 `^[A-Za-z0-9_.:-]{16,128}(?![\s\S])`)를 `required`에 둔다. 설명을 호출자 키로 고친다. `desktop-close` 설명의 「The original open requestKey」를 「The original open operationKey」로 고친다(뜻은 같고 이름만). `targets`의 `gui.openRequest`를 없앤다(서버 발급 키가 없으므로). 오류 코드 `DESKTOP_OPEN_REQUEST_CAPACITY`·`DESKTOP_OPEN_REQUEST_GENERATION`·`DESKTOP_OPEN_REQUEST_EXPIRED`는 3.0에서 내지 않으므로 `envelope.schema.json`의 `x-portal-errorCodes.contract30Removals`에 적고 `DesktopErrorCode`에서 지운다(72 → 69). 도메인 `GuiOpenRequest`·`WireFormat.OPEN_REQUEST_KEY`·`GuiDescriptor.openRequest`를 지운다.
   - 이름만 바꾸는 안(서버 발급 키를 `operationKey`라 부름)을 고르지 않은 이유: `operationKey`는 「호출자가 정하고 불확실할 때 같은 값으로 다시 보내는 키」다(`submit` 설명 「never replay with a new key」). 서버가 만료와 프로세스 세대를 넣어 발급하는 키에 같은 이름을 붙이면 재시작 뒤 같은 키가 무효가 되어 그 의미가 깨진다. 3.0은 작업 기록이 DB에 남으므로 호출자 키만으로 같은 lease를 찾을 수 있다.
4. **`instructions`의 소스 안내**: 끝에 「Corresponding source (AGPL-3.0-or-later): {sourceUrl}」 한 문장을 더한다. `{sourceUrl}`은 대조 때 실제 설정값으로 바꾼다(B-9).
5. **관리자 HTTP(`admin-routes.json`)**:
   - 지움: `/api/artifacts` 4항목(2026-11-15 결정 1), `/api/session/start`·`/api/session/logout`(2.x 앞단 신원으로 세션을 여는 경로, 3.0은 로그인 경로가 세션을 연다).
   - 더함: `POST /login/token`, `GET /oauth2/authorization/oidc`, `GET /login/oauth2/code/oidc`, `POST /logout`(로그인), `GET /api/devices`, `POST /api/devices`, `POST /api/devices/{id}/revoke`(기기 토큰, B-2), `GET /api/operations/reconciliation`, `POST /api/operations/{id}/reconcile`(정리, B-10).
   - 모든 항목에 `auth30`(3.0 인증 범주)을 더한다. 기존 `auth`는 2.4.2 동작 기록으로 남긴다. 범주 정의는 `contract-baseline.md` 9.2절.
   - 항목 수: 51 → 45(지움 6) → 54(더함 9).
   - `GET /health`: 응답을 Actuator 모양(`{"status":"UP"}`, 실패 503 `{"status":"DOWN"}`)으로 바꾼다(B-5). 2.x가 인증 없이 내던 판·인증 모드·수용 상태는 내지 않는다. 2.4.2 응답 문장은 같은 필드 뒤에 기록으로 남긴다.
   - 오류 본문: 관리자 HTTP의 4xx·5xx 본문은 RFC 9457 `application/problem+json`이다(B-7). 2.x의 `{error:'…'}`는 `auth`·`responseShape`의 2.4.2 기록으로만 남는다.
6. **설정(`settings-contract.json`)**: 허용 키에서 `BRIDGE_ACCESS_TEAM_DOMAIN`, `BRIDGE_ACCESS_AUD`, `BRIDGE_ACCESS_OWNER_EMAILS`(access 모드), `BRIDGE_OIDC_OWNER_EMAILS`(이메일 대신 계정 식별자), `BRIDGE_OIDC_JWKS_URL`(OAuth2 Login은 발급자 discovery로 키를 찾음), `BRIDGE_AUDIT_PATH`(감사는 DB 표), `BRIDGE_TRANSFER_ROOT`(데이터 폴더 하나)를 뺀다. `BRIDGE_DATA_DIR`, `BRIDGE_OIDC_OWNER_SUBJECT`, `BRIDGE_OIDC_CLIENT_SECRET`, `BRIDGE_SOURCE_URL`을 더한다. 기본값에 `BRIDGE_SOURCE_URL: ""`(빈 값 = 이 판의 공개 저장소 태그)를 더한다. `BRIDGE_ADMIN_AUTH_MODE`가 받는 값은 `token`·`oidc`다(기본 `token`). 이 값 목록을 새 최상위 키 `adminAuthModes: ["token","oidc"]`로 적는다(2.4.2 기준본에는 값 목록이 없었다).
7. **hooks**: JSON은 바꾸지 않는다. 대조 규칙만 바꾼다: 플러그인 매니페스트의 `version`은 제품 판을 따르므로 `serverInfo.version`처럼 대조에서 뺀다(`contract-baseline.md` 9.3절).
8. **바꾸지 않는 것**(확인 목록): 도구 이름·개수(24), 나머지 도구의 입력, 하위 쓰기 도구(`cancel`, `artifact-chunk`, `artifact-finish`, `desktop-input`, `desktop-close`)에 키를 더하지 않음(2026-11-15 결정 1), `desktop-input`의 요청 단위 `requestKey`, 봉투 구조와 `error.code` 형식, 위 6번 밖의 설정 키와 기본값, 포트 기본값, hook 이벤트·필드, 클라이언트 명령 이름 `portal-files`(설명 문장에 나옴).

### B-2 기기 토큰
`[결정]` 근거 요약: 계획 정본 결정 5(「owner가 관리 화면에서 한 번 승인하면 기기별로 범위가 제한된 토큰을 발급하고, 회수할 수 있다」), feature-map C2 초안, 「에이전트 자기 승인 금지」.

- 발급: 소유자가 관리 화면에서 이름을 주고 `POST /api/devices`(`auth30: owner`)를 부른다. 발급이 곧 그 한 번의 승인이다. 응답에 토큰을 **한 번만** 담는다. 클라이언트는 표준 입력으로 받아 사용자 전용 파일에 둔다(클라이언트 단위의 일).
  - 인증 없는 「기기가 먼저 등록을 요청하고 소유자가 승인하는」 흐름은 두지 않는다. 인증 없는 경로·대기 표·폴링을 더 열어야 하고, 개인판(소유자 한 명)에는 화면에서 바로 발급하는 것으로 충분하다.
- 형식: `pdt1.<id>.<secret>`. `id`는 128비트, `secret`은 256비트 무작위 값을 base64url(패딩 없음)로 쓴다. Bearer의 token68 문자 집합 안이다.
- 저장: 표 `device_token(id, name, secret_sha256, created_at, created_by)`, 회수는 표 `device_token_revocation(token_id, revoked_at, revoked_by)`에 행을 더한다(물리 삭제·갱신 없음, append-only). 비밀은 SHA-256만 둔다. 256비트 무작위 값이라 느린 해시가 필요 없다. 비교는 `MessageDigest.isEqual`.
- 검증: 관리자 포트 Bearer 사슬의 `BearerTokenIntrospector`가 형식으로 기기 토큰을 가려 `id`로 찾고, 회수 행이 없고 해시가 같으면 `DEVICE` 권한을 준다. 캐시하지 않으므로 회수는 다음 요청부터 듣는다. MCP 포트에서는 받지 않는다.
- 허용 경로(10항목): `auth30: owner-or-device` 8개 = `GET /api/targets`, 업로드 4개(`POST /api/files/uploads`, `GET /api/files/uploads/{id}`, `POST …/chunks`, `POST …/finish`), 작업 읽기 3개(`GET /api/{id}`, `GET /api/{id}?observe=1`, `GET /api/operations/by-key`). `auth30: download-capability` 2개 = 다운로드 받기(`GET`·`HEAD /downloads/{id}/{token}`, 경로의 일회용 토큰도 필요).
  - C2 초안의 「ACK·수신함」은 PC 클라이언트 안의 H2 수신함 동작이라 서버 경로가 없다(`portal-files.mjs`의 `ack`는 로컬 수신함을 고친다).
  - 승인·거부, 환경·정책·용량·데스크톱·에이전트 CLI·유지보수, 다운로드 요청 만들기(`POST /api/files/downloads`)는 받지 않는다. 다운로드 요청은 에이전트가 MCP `submit kind=download`로 한다. 기기 토큰은 에이전트가 도는 PC에 있으므로 승인 권한이 없어야 자기 승인을 막는다. 소유자 CLI 명령(대기 목록·승인·거부)은 관리자 토큰으로 부른다.
- 회수: `POST /api/devices/{id}/revoke`(`owner`). 이미 회수된 것은 같은 결과(멱등).
- 감사: `DEVICE_TOKEN_ISSUED`(id·이름), `DEVICE_TOKEN_REVOKED`(id), `DEVICE_TOKEN_REFUSED`(회수된 토큰이 쓰인 경우, id). 형식이 틀리거나 없는 id는 401만 내고 감사에 남기지 않는다(인증 없는 요청이 감사 표를 채우지 못하게).
- 목록: `GET /api/devices`는 id·이름·발급 시각·회수 시각을 낸다. 비밀·해시는 내지 않는다.

### B-3 SOURCE 실행기
`[결정]` `app.execution.SourceOperationRunner`(`OperationRunner`, `kind() = SOURCE`)를 더한다. S3 11.4 「SOURCE 실행기 미정」과 「`prepareSource`의 Windows 서명」을 함께 닫는다.
- 원문 보관: `SourceInput`은 원문을 저장하지 않는다(그 Javadoc: 원문은 실행 중인 프로세스의 비공개 계획에만 있다). 그대로 따른다. `app.execution.SourcePlanRegistry`가 접수 때 작업 id별로 `SourceText`·`SourceArguments`를 메모리에 두고, 실행기가 꺼내며, 정산·만료 때 지운다. 크기 상한은 요청 용량 × 16 KiB다.
- 재시작: 계획이 사라지므로 대기 중이던 SOURCE 작업은 보낸 것 없이 거절된다(`NOT_DISPATCHED`). 실행 중이던 것은 지금처럼 `UNKNOWN`이다. 원문을 파일로 남겨 재시작 뒤 실행하는 안은 고르지 않았다. 확인할 수 없는 계획을 다시 돌리지 않는다는 2.x의 의미를 바꾸게 되고, 원문이 디스크에 남는다.
- 해석기: `TargetPlatform.prepareSource`에 `SourceRuntime`(sealed: `Posix`, `WindowsPython(WindowsPythonExecutable)`) 매개변수를 더한다. 값은 대상 설정(`BRIDGE_TARGETS`의 Windows `pythonExecutable`)에서 온다. 지금의 「매개변수가 없어 Windows는 거절」 우회를 없앤다.
- 실행: 대상 해석·출력 예약·`RemoteExecutor` 호출은 `CommandOperationRunner`와 같다. 같은 부분은 패키지 내부 클래스 하나(`RemoteCommandRun`)로 묶어 두 실행기가 쓴다.
- 거절 코드: 계획이 없을 때 쓸 기존 코드를 고르고, 맞는 것이 없으면 새 코드 하나를 더하며 기준본 `stage4Additions`에 행을 더한다(8절 규칙).

### B-4 브라우저 세션 저장
`[결정]` 서블릿 컨테이너의 메모리 `HttpSession`을 쓴다. Spring Session(JDBC 저장)은 새 의존이고, 개인판은 프로세스 하나라 공유 저장이 필요 없다. 재시작하면 다시 로그인한다.
- 쿠키: 이름 `__Host-portal-admin`, `Secure`, `HttpOnly`, `SameSite=Strict`, `Path=/`. 관리자 Origin이 https라는 기존 규칙(`originPattern`)과 맞는다.
- 수명: 유휴 30분, 절대 12시간. 절대 수명은 서블릿 표준에 없으므로 `BrowserSessionAdapter`가 세션 생성 시각으로 검사한다. 로그인 때 세션 id를 바꾼다(Spring Security 기본 session fixation 보호).
- CSRF: Spring Security `CsrfFilter`, 세션 저장 토큰, 요청 헤더 `X-CSRF-TOKEN`. 화면은 `GET /api/session`의 응답에서 토큰을 받는다.
- Origin: 브라우저 사슬의 안전하지 않은 메서드는 `Origin == BRIDGE_ADMIN_ORIGIN`(2.x `admin-policy.mjs`의 규칙 유지).
- MCP 포트는 세션을 만들지 않는다(`SessionCreationPolicy.STATELESS`).

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
  - `ProblemDetail`: `type = urn:ssh-approval-portal:error:<CODE>`, `title = <CODE>`, 확장 속성 `code`·`retryable`·`executionAttempted`. HTTP 상태는 `HttpStatusTable` 한 곳에서 정한다. 모든 `ErrorCode` 상수가 상태를 가지는지 시험한다(S4-ER-02).
  - `ArtifactRefusal`의 숫자(요청 바이트·예산·여유 공간)는 MCP 봉투에 넣지 않는다(봉투 `errorData`는 세 필드만, `additionalProperties: false`). 관리자 `ProblemDetail`의 확장 속성 `detail`로만 낸다. S3 11.4 「ArtifactRefusal 값을 오류 봉투로 옮기는 일」을 이렇게 닫는다.
- 컨트롤러는 예외를 잡지 않는다. 관리자 쪽은 `AdminExceptionHandler` 하나, MCP 쪽은 도구 어댑터의 공통 감싸개 하나가 `ResultPresenter`를 부른다.

### B-8 두 포트 컨텍스트 구조
`[결정]` 부모(비웹) + 자식 웹 컨텍스트 두 개. 근거: 1단계 `PortsSpikeTest` Q-01~05(경로 집합이 컨텍스트로 갈라지고 부모 빈이 공유됨)와 Q 비교(커넥터 추가 방식은 경로 제한을 포트 번호 검사로 해야 함).
- 포트: Environment가 공유되므로 `server.port`를 쓰지 않는다. 각 자식 구성의 `WebServerFactoryCustomizer<ConfigurableServletWebServerFactory>`가 `BridgeProperties`(부모 빈)에서 포트와 주소를 넣는다. 기본 주소는 `127.0.0.1`(2.x와 같음).
- 보안 사슬·`@ControllerAdvice`·MCP 서버 빈은 자식에만 있다. 부모에는 웹 빈이 없다(공식 문서 「Web components must be contained within the child context」).
- 종료: 부모를 닫으면 두 포트가 닫힌다(Q-05). 설정 반영 재시작은 `ExitCodeGenerator`(B-9 아님, A.8).

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

## C. 미결(사용자)와 확정된 보안 판단

### C.1 미결(사용자)
| 번호 | 질문 | 상태 | 영향 범위 |
|---|---|---|---|
| R1 | RFB 클라이언트를 라이브러리로 할지 직접 구현할지 | 「후보 조사 후 다시 질문」(2026-11-21). 리드가 후보 조사를 맡았다 | 데스크톱 단위. 이 문서의 U2~U9와 기준본 개정에는 영향이 없다 |

### C.2 확정: 고위험 승인의 추가 확인 없음(Q1)과 로그인(Q2)
- Q2: 로그인은 OIDC + 토큰, OIDC는 계정 하나(`sub`), passkey 없음. A.3절과 B-1.6에 반영했다.
- Q1: 승인·거부·정리·환경 편집·정책 변경은 허용 계정의 로그인 세션(또는 관리자 토큰 Bearer)과 CSRF·Origin 검사만으로 받는다.
- 잔여 위험: 추가 확인이 없으므로 로그인된 브라우저를 쓸 수 있는 사람(잠기지 않은 PC, 세션 쿠키를 얻은 악성 확장)은 소유자와 똑같이 승인할 수 있다. 이 문서가 줄이는 수단은 쿠키 속성(`__Host-`, `HttpOnly`, `SameSite=Strict`), 유휴 30분·절대 12시간 수명, CSRF·Origin 검사, 모든 승인의 감사 기록이다. 승인 자체를 한 번 더 확인하는 수단은 없으며, 이 위험은 사용자가 고른 것이다. 기기 토큰은 승인 권한이 없으므로 이 위험을 넓히지 않는다.

## D. 라이브러리

### D.1 승인 기록
| 이름 | 용도 | 판 | 승인 |
|---|---|---|---|
| `spring-boot-starter-security` | 보안 사슬, CSRF, 세션 | Spring Boot BOM 4.1.2 | 2026-11-21 「Spring Security 스타터」 |
| `spring-boot-starter-actuator` | `/health`, `HealthIndicator` | Spring Boot BOM 4.1.2 | 2026-11-21 「Spring Boot Actuator」 |
| `com.microsoft.playwright:playwright` | U7 브라우저 사례 시험(시험 의존만) | BOM 밖. 후보 1.65.1(Maven Central 최신 안정판, 2026-11-19 게시). U7 착수 때 그 시점의 최신 안정판으로 고정하고 이유를 빌드 파일에 적는다 | 2026-11-21 「Playwright for Java」 |
| Apache Commons Daemon procrun | Windows 서비스(7단계 패키징) | 1.6.1(Apache 배포 최신) | 2026-11-21 「Commons Daemon procrun」 |
| 이전 승인 | Java 25, Spring Boot 4.1.2, Spring AI BOM 2.0.2(MCP Java SDK), MINA sshd 2.20.1, H2, sqlite-jdbc(portal-migrate만) | - | 계획 정본 |

### D.2 승인 필요
| 이름 | 용도 | 대안 | 비고 |
|---|---|---|---|
| `spring-boot-starter-oauth2-client` | OIDC 로그인(OAuth2 Login, 발급자 discovery) | 없음에 가깝다. 직접 구현하면 인가 코드 흐름·nonce·ID 토큰 검증을 손으로 만들어야 한다(개발원칙 3.6 위반) | 「Spring Security 스타터」 승인에 들어가는지 확인이 필요하다. 판은 Boot BOM |
| `spring-boot-starter-oauth2-resource-server` | MCP bearer·관리자 토큰·기기 토큰의 `OpaqueTokenIntrospector` 사슬(계획 정본 공식 문서 반영) | `spring-boot-starter-security`만으로 `AuthenticationFilter`+`AuthenticationManager`를 직접 조립(Bearer 헤더 파싱·오류 응답을 손으로 만듦) | 위와 같음. 판은 Boot BOM |

위 두 가지가 승인 범위 밖이면 U4는 대안(직접 조립)으로 진행하고 이 표를 고친다. 다른 새 라이브러리는 이 단계에 없다. 계약 대조 시험은 지금처럼 시험 소스의 손 구현 스키마 검사기를 쓴다(새 JSON Schema 라이브러리를 들이지 않는다).

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
| U2 | S4-ST-04 | 2.x 키 `BRIDGE_AUDIT_PATH`·`BRIDGE_ACCESS_AUD`가 든 환경 | 허용 키 밖이라 시작 실패(조용히 무시하지 않음), 업그레이드 안내 절 이름을 알림 | `BridgePropertiesValidationTest` |
| U2 | S4-ST-05 | 비밀 키(`BRIDGE_MCP_TOKEN` 등) | `SecretValue`로 바인딩, `toString`·로그에 값 없음 | `BridgePropertiesValidationTest` |
| U2 | S4-ST-06 | `BRIDGE_TARGETS` JSON(Windows `pythonExecutable` 포함, 잘못된 JSON) | 앞은 대상 목록으로, 뒤는 시작 실패 | `BridgeTargetsConverterTest` |
| U2 | S4-AS-01 | 데이터 폴더만 준 설정으로 서버 조립 | H2 파일·`artifacts/`·`outputs/`·`known_hosts`가 그 아래에 생김, 시험용 기본값 빈(`AssemblyDefaults`)이 없음 | `SettingsAssemblyTest` |
| U2 | S4-AS-02 | 감사 기록 추가 | DB 표에 행이 더해지고, 갱신·삭제 SQL 경로가 없음(ArchUnit) | `JdbcAuditLogTest`, `ArchitectureRulesTest` |
| U2 | S4-AS-03 | 감사 표 쓰기 실패 | 기존 `AUDIT_FAILED` 경로(D3-14)로 바뀜 | `JdbcAuditLogTest` |
| U2 | S4-CB-05 | 기준본 `settings-contract.json`과 제품의 허용 키 목록 | 키 차이 0 | `SettingsContractTest` |
| U3 | S4-WS-01 | MCP 포트에 `/api/targets`, 관리자 포트에 `POST /mcp` | 둘 다 404 | `TwoPortContextTest` |
| U3 | S4-WS-02 | 부모 빈 하나 | 두 자식이 같은 인스턴스를 봄 | `TwoPortContextTest` |
| U3 | S4-WS-03 | 포트 설정 39124·39125(시험은 임의 포트) | 각 자식이 자기 포트·`127.0.0.1`에서만 들음 | `TwoPortContextTest` |
| U3 | S4-WS-04 | 부모 닫기 | 두 포트 모두 연결 거절 | `TwoPortContextTest` |
| U3 | S4-WS-05 | 관리자 응답 머리글 | `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`(2.x 유지) | `TwoPortContextTest` |
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
| U4 | S4-SE-03 | 토큰 모드 `POST /login/token` 맞는 토큰/틀린 토큰 | 세션 생성·id 바뀜/401, 틀린 값은 감사 `ADMIN_LOGIN_REFUSED` | `TokenLoginTest` |
| U4 | S4-SE-04 | OIDC 모드, 설정한 `sub`의 계정 | 로그인 성공, `OWNER` | `OidcLoginTest` |
| U4 | S4-SE-05 | OIDC 모드, 같은 발급자의 다른 `sub` | 세션 없음, 403, 감사 `ADMIN_LOGIN_REFUSED`(issuer·subject) | `OidcLoginTest` |
| U4 | S4-SE-06 | OIDC 모드, 다른 발급자의 같은 `sub` 문자열 | 거절 | `OidcLoginTest` |
| U4 | S4-SE-07 | 세션으로 `POST /api/{id}/approve`, CSRF 헤더 없음 | 403, 상태 변화 없음 | `AdminSecurityTest` |
| U4 | S4-SE-08 | 세션 + CSRF + `Origin` 다름/없음 | 403, 상태 변화 없음 | `AdminSecurityTest` |
| U4 | S4-SE-09 | 세션 + CSRF + 같은 Origin | 승인됨(추가 확인 없음, C.2) | `AdminSecurityTest` |
| U4 | S4-SE-10 | 세션 쿠키 속성 | `__Host-portal-admin`, `Secure`, `HttpOnly`, `SameSite=Strict`, `Path=/` | `BrowserSessionTest` |
| U4 | S4-SE-11 | 유휴 30분 지난 세션, 생성 12시간 지난 세션 | 둘 다 401, 다시 로그인 필요 | `BrowserSessionTest` |
| U4 | S4-SE-12 | `owner-token` 경로(유지보수)에 세션+CSRF | 401(관리자 토큰 Bearer만) | `AdminSecurityTest` |
| U4 | S4-DT-01 | `POST /api/devices` `{name}` | 201, 응답에 토큰 한 번, 목록에는 토큰·해시 없음, 감사 `DEVICE_TOKEN_ISSUED` | `DeviceTokenApiTest` |
| U4 | S4-DT-02 | 기기 토큰으로 허용 10항목 | 각각 인증 통과 | `DeviceTokenScopeTest` |
| U4 | S4-DT-03 | 기기 토큰으로 승인·거부·환경·정책·용량·데스크톱·유지보수·`POST /api/files/downloads`·`/api/devices` | 모두 403(유지보수는 401) | `DeviceTokenScopeTest` |
| U4 | S4-DT-04 | 회수 뒤 같은 토큰 | 다음 요청부터 401, 감사 `DEVICE_TOKEN_REFUSED` | `DeviceTokenApiTest` |
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
| U5 | S4-MC-06 | `desktop-open` 같은 `operationKey` 두 번 | 같은 sessionId·epoch(새 lease 없음) | `DesktopOpenToolTest` |
| U5 | S4-MC-07 | 닫힌 lease의 `operationKey`로 다시 open | 다시 열지 않고 닫힘을 알림 | `DesktopOpenToolTest` |
| U5 | S4-MC-08 | 실패 응답 | `isError: true`, `structuredContent` = 봉투, `content[0].text` = 같은 JSON | `ToolResultWireTest` |
| U5 | S4-MC-09 | `initialize` | `serverInfo.name` 기준본과 같음, `version` = 프로젝트 판, `instructions` = 기준본(`{sourceUrl}` 치환) | `McpServerInfoTest` |
| U5 | S4-MC-10 | unknown 예산이 찬 상태의 `submit` | `OPERATION_RECONCILIATION_CAPACITY`, `retryable: true`, `executionAttempted: false` | `SubmitToolTest` |
| U5 | S4-SR-01 | Linux 대상 `submit kind=source` | 실행되고 원문은 DB·로그·감사에 없음 | `SourceOperationRunnerTest` |
| U5 | S4-SR-02 | Windows 대상, `pythonExecutable` 있음/없음 | 그 해석기로 실행 / capability 거짓, 제출은 `TARGET_CAPABILITY_UNSUPPORTED` | `SourceOperationRunnerTest` |
| U5 | S4-SR-03 | 대기 중 SOURCE 작업, 재시작(계획 없음) | 보낸 것 없이 거절(`NOT_DISPATCHED`), 재실행 없음 | `SourceRestartTest` |
| U5 | S4-SR-04 | 실행 중 재시작 | `UNKNOWN`(기존 T18) | `SourceRestartTest` |
| U5 | S4-SR-05 | 정산·만료 뒤 | 계획 등록부에서 지워짐 | `SourcePlanRegistryTest` |
| U5 | S4-SR-06 | 등록부 상한(요청 용량 × 16 KiB) | 넘는 접수는 기존 용량 코드로 거절 | `SourcePlanRegistryTest` |
| U5 | S4-CB-01 | 제품 `mcp/tools/*.json`·실제 `tools/list`와 기준본 | 이름·설명·`inputSchema` 차이 0(키 순서 무시) | `McpToolListContractTest` |
| U5 | S4-CB-02 | 도구 `outputSchema`·`annotations`·`_meta`와 기준본 | 차이 0 | `McpToolListContractTest` |
| U5 | S4-CB-03 | `server.json`의 리소스·`instructions` | 차이 0(`{sourceUrl}` 치환 뒤) | `McpServerInfoTest` |
| U6 | S4-AD-01 | 관리자 포트의 등록 경로 전부(Spring `RequestMappingHandlerMapping`) | 기준본 `admin-routes.json`의 관리자 항목과 메서드·경로 차이 0 | `AdminRoutesContractTest` |
| U6 | S4-AD-02 | 각 경로의 보안 범주 | 기준본 `auth30`과 같음(미인증·기기·세션 요청으로 확인) | `AdminRoutesContractTest` |
| U6 | S4-AD-03 | `/api/artifacts`, `/api/session/start` | 404 | `AdminRoutesContractTest` |
| U6 | S4-AD-04 | `GET /api/outputs/{id}?offset&limit&format` | `OutputPage` 모양, MCP `observe` 출력 페이지와 같은 바이트 | `OutputApiTest` |
| U6 | S4-AD-05 | 조건부 경로(데스크톱·에이전트 CLI 등)의 서비스가 없을 때 | 404(2.x 「Registered only when」) | `AdminRoutesContractTest` |
| U6 | S4-AD-06 | 승인·거부 | 상태 전이와 감사 기록 | `ApprovalApiTest` |
| U6 | S4-AD-07 | 업로드 4경로(기기 토큰) | 2.x 크기 상한 그대로, UUID 반환 | `FileTransferApiTest` |
| U6 | S4-AD-08 | 2.x `{error:'…'}`를 기대하는 요청 | 본문은 `ProblemDetail` | `AdminExceptionHandlerTest` |
| U6 | S4-RC-01 | unknown 3건 | `GET /api/operations/reconciliation` = `{unknownCount:3, budget:32, saturated:false, operations:[3]}` | `ReconciliationApiTest` |
| U6 | S4-RC-02 | `reconcile completed` | `COMPLETED`, 증거 `OwnerConfirmed`, 정산 불요, 감사 `OPERATION_RECONCILED` | `OperationReconciliationTest` |
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
| U8 | S4-RK-01 | Jev 꺼짐 | 위험 평가 호출 0, 자동 승인 없음 | `JevRiskEvaluatorTest` |
| U8 | S4-RK-02 | Jev 켜짐, 호출 상한 도달 | 상한 뒤 호출 0, 소유자 승인으로 넘어감 | `JevRiskEvaluatorTest` |
| U8 | S4-RK-03 | 정책 허용 + 위험 평가 거부 | 자동 승인 없음 | `CommandAutomationTest` |
| U8 | S4-RK-04 | `*_REF` 키, 로컬 저장소에 값 있음/없음 | 해석 / 시작 실패(값은 메시지에 없음) | `LocalFileSecretStoreTest` |
| U8 | S4-RK-05 | Vault 저장소 응답 실패 | 비밀 없이 진행하지 않음(조용한 대체 없음) | `VaultSecretStoreTest` |
| U8 | S4-RK-06 | 위험 평가 요청 본문 | 비밀이 가려진 명령만 보냄(redaction) | `JevRiskEvaluatorTest` |
| U9 | S4-RL-01 | 카탈로그 응답에 무결성 값 있음/없음 | 설치 진행 / `Unavailable`(대상에 바이트 0) | `NpmRegistryCatalogTest` |
| U9 | S4-RL-02 | `GET /api/version?refresh=1` | 새 판 확인 결과(네트워크 실패는 「확인 못 함」, 조용한 성공 없음) | `PortalReleaseCheckerTest` |
| U9 | S4-RL-03 | `POST /api/environment`에 허용 키 밖 | 거절, 파일 변화 없음 | `EnvironmentFileEditorTest` |
| U9 | S4-RL-04 | 환경 저장 뒤 재시작 요청 | 0이 아닌 고정 종료 코드 | `EnvironmentRestartTest` |
| U9 | S4-RL-05 | 선택 어댑터 미설정 | 설치 기본 흐름이 업체 API를 부르지 않음 | `OptionalAdapterAssemblyTest` |
| U9 | S4-RL-06 | 환경 파일 쓰기 도중 실패 | 원래 파일 그대로(원자적 교체) | `EnvironmentFileEditorTest` |
| U5·U6 | S4-CB-06 | hooks JSON 5개와 제품이 내보내는 hooks | `command`·매니페스트 `version` 밖의 필드 차이 0 | `HookContractTest` |

## 7. 사용자에게 미치는 영향(업그레이드 안내 입력)
업그레이드 안내(영·한, 계획 정본)는 B-1의 각 항목을 하나씩 적는다. 특히: 2.x 클라이언트·hook 호출 거절, `files`·`desktop-open`의 `operationKey` 필수, `targets`의 `gui.openRequest` 없음, `/api/artifacts`·`access` 모드·passkey 없음, 설정 키 변경(뺀 7개, 더한 4개, OIDC 소유자는 이메일이 아니라 계정 식별자 하나), `/health` 본문, 관리자 오류 본문(`ProblemDetail`), 데이터 이전은 `portal-migrate`.

## 8. 기준본을 더 바꿀 때의 규칙
- 기준본(`java/portal-web/src/test/resources/contract/**`)을 바꾸는 단위는 같은 커밋에서 `contract-baseline.md` 9절 변경표에 행을 더하고(항목, 이전, 이후, 근거 결정), 이 문서의 해당 절을 고친다. 변경표 밖의 차이는 두지 않는다.
- 새 오류 코드는 `envelope.schema.json`의 `x-portal-errorCodes.stage4Additions`(이 단계에 처음 생김)에 `[코드, 근거]`로 적는다.
- 기준본의 2.x 기록 필드(`source`, 2.x `auth`, `portalRules`의 2.x 줄 번호)는 고치지 않는다. 3.0 동작은 3.0 필드(`auth30` 등)나 3.0 규칙 문장으로 더한다.
