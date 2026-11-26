# portal-migrate: 2.x 데이터를 3.0.0 저장소로 옮기는 명령

3.0.0은 2.x와 하위 호환을 지키지 않는다(본체에 2.x 형식을 읽는 코드가 없다). 2.x가 남긴 데이터는 이 문서의 별도 명령 `portal-migrate`가 옮긴다. 이 모듈은 `portal-store`와 `portal-domain`만 쓰고(규칙 A-DIR), `sqlite-jdbc`를 쓰는 유일한 모듈이며, 서버·클라이언트 어느 쪽도 이 모듈에 의존하지 않는다. 옮기는 것은 두 가지다: 작업 이력(2.x SQLite → 3.0 저장소)과 설정(2.x 환경 파일 → 3.0 설정 파일).

## 1. 이전 범위: 2.x가 남기는 데이터 종류 전부

2.x 서비스가 `BRIDGE_TRANSFER_ROOT`(기본 `/var/lib/ssh-mcp/transfers`)에 두는 파일, 환경 파일·프로필·비밀·로그·클라이언트 쪽 상태까지 모두 적었다. 명령은 데이터 폴더를 끝까지 나열해 보고서에 같은 분류로 찍는다. 알지 못하는 파일은 「UNRECOGNIZED」로 보고하고 건드리지 않는다.

분류는 셋이다. **옮김**(시험 이름을 적었다), **의도적으로 옮기지 않음**(이유), **선행 변경 필요**(다른 모듈에서 무엇이 먼저 있어야 하는가). 시험 이름은 `java/portal-migrate/src/test`의 클래스와 메서드다.

### 1.1 작업 이력 (SQLite)

| 2.x가 남기는 것 | 분류 | 시험 · 이유 · 선행 변경 |
|---|---|---|
| `operations.sqlite`의 `command` 작업(`operations`) | 옮김 | 3.0 작업 저널(`operation*` 표)로. 상태 대응은 3절. `MigrateImportTest.everyStateIsMovedWithItsCommandItsDecisionAndItsTimes`, `anOperationTheOwnerApprovedKeepsTheOwnerAsStarter`, `MigrateTextPreservationTest.*`, `MigrateScaleTest.moreOperationsThanTheTwoXJournalCouldHoldAreAllMoved` |
| 그 가운데 상태가 unknown이거나 실행 중이던 작업 | 옮김(상태 보존) | 재실행되지 않게 UNKNOWN으로. `MigrateImportTest.unknownOperationsStayUnknownAndNeedReconciliation`, `MigrateUnknownTest.*` |
| `operations.sqlite`의 `source` 작업 | 의도적으로 옮기지 않음 | 소스 실행은 비공개 계획을 따로 두었고 3.0은 그 계획을 다시 만들거나 재실행하지 않는다. 건수는 보고서에 나온다(`MigrateImportTest.operationsOfOtherKindsAreReportedAsNotMovedAndTheFolderIsListedCompletely`) |
| `operations.sqlite`의 `upload`·`download` 작업 | 의도적으로 옮기지 않음 | 대상 파일(아티팩트)은 2.x가 시간이 지나면 지웠다. 이력만 남기면 가리키는 곳이 없다. 건수는 보고서에 나온다(같은 시험) |
| `operations.sqlite`의 `cli-probe` 작업 | 의도적으로 옮기지 않음 | 2.x 내부 확인용이며 3.0에 같은 것이 없다. 건수는 보고서에 나온다(같은 시험) |
| `operations.sqlite`의 `operation_events`·`operation_legacy_budget`·`operation_schema` | 의도적으로 옮기지 않음 | 3.0은 옮긴 전이마다 자기 이벤트 행을 쓴다(시각은 2.x 사건의 시각). 이전 한도 표는 3.0에 대응이 없다. `operation_schema`는 버전 1인지만 확인한다(`MigrateConflictTest.aJournalWithAnotherSchemaStopsTheRunWithAnError`) |
| `command-automation.sqlite`의 `requests`(명령 승인 저널) | 옮김 | `operations.sqlite`에 같은 id가 있으면 그쪽을 쓰고 이쪽은 「겹침」으로 센다. 없는 id만 옮긴다. 요청 키가 없는 행은 2.x가 이전할 때 주던 키 `legacy:<파일>:<id>`를 쓴다. `MigrateImportTest.theRequestJournalsAreMovedAndAnOperationTheFormalJournalAlsoHoldsIsMovedOnce`, `MigrateConflictTest.anIdWithTwoKeysInTwoFilesIsReported` |
| `command-automation.sqlite`의 `policy` | 의도적으로 옮기지 않음 | 정책 규칙은 2.x 대상 revision(2.x 자바스크립트 객체의 해시)에 묶여 있고 3.0은 그 값을 재현할 수 없다. 옮겨도 어떤 대상에도 맞지 않는다. 3.0 관리자 화면에서 다시 설정한다 |
| `agent-cli.sqlite`의 `requests` | 옮김 | 2.x는 CLI 설치를 `command` 작업으로 실행했으므로 명령 작업으로. `MigrateImportTest.theRequestJournalsAreMovedAndAnOperationTheFormalJournalAlsoHoldsIsMovedOnce` |
| `agent-cli.sqlite`의 `remote_status` 열 | 의도적으로 옮기지 않음 | 원격 설치 상태는 대상 서버에서 다시 조회한다 |
| `maintenance.sqlite`(점검 예약) | 의도적으로 옮기지 않음 | 2.x 보존 절차의 일시 잠금이다. 3.0에서는 의미가 없다 |
| `capacity.sqlite`(런타임 용량 덮어쓰기) | 의도적으로 옮기지 않음 | 3.0 설정에서 용량을 다시 정한다 |
| 2.x가 이전 판에서 옮겨 둔 원본 보관 폴더 | 해당 없음 | 2.4.2가 더 오래된 판의 저장소를 옮길 때 만든 보관본이다. 이 명령은 읽지 않고 목록에 「UNRECOGNIZED」로 보인다 |

### 1.2 파일 (데이터 폴더)

| 2.x가 남기는 것 | 분류 | 시험 · 이유 · 선행 변경 |
|---|---|---|
| `command-outputs/`(출력 파일과 `manifest.sqlite`) | 의도적으로 옮기지 않음 | 2.x는 출력을 1시간만 보관했다. 이전 시점에는 이미 만료되었다. 3.0 출력은 `BRIDGE_DATA_DIR/outputs/` 아래 새로 쌓인다 |
| `<48 hex>.json`·`<48 hex>.part`·`orphans/`(전송 임시 파일) | 의도적으로 옮기지 않음 | 전송 임시 데이터이며 2.x가 시간 제한으로 지운다 |
| 감사 로그 `approval-bridge-audit.jsonl`(`BRIDGE_AUDIT_PATH`) | 의도적으로 옮기지 않음 | 3.0 감사는 DB 표다(`BRIDGE_AUDIT_PATH`가 없다). 2.x 파일은 그대로 보관한다. 형식을 바꿔 표로 옮기는 것은 3.0 감사 표(4단계 설계의 `JdbcAuditLog`)가 먼저 있어야 한다 |
| 메모리에만 있던 것(관리자 브라우저 세션, 데스크톱 세션, 전송 메타, SSH 호스트 키) | 해당 없음 | 2.x가 멈추면 사라진다. 2.x는 호스트 키를 파일에 두지 않았으므로 `known_hosts`로 옮길 것이 없다(3.0은 첫 접속 때 새로 기록한다, 지문을 고정한 프로필은 프로필 파일의 `trustedHostKey`가 그대로 쓰인다) |
| 알 수 없는 파일 | 보고만 | 「UNRECOGNIZED」로 나열하고 건드리지 않는다(`MigrateImportTest.operationsOfOtherKindsAreReportedAsNotMovedAndTheFolderIsListedCompletely`) |

### 1.3 설정·비밀·SSH 프로필

| 2.x가 남기는 것 | 분류 | 시험 · 이유 · 선행 변경 |
|---|---|---|
| 환경 파일 `/etc/ssh-mcp-approval-bridge.env`(`BRIDGE_*`, `JEV_API_KEY`, `SSH_MCP_*`)와 Docker용 비밀 환경 파일(같은 형식) | 옮김(`--env-file`·`--env-out`) | 3.0이 가진 키는 쓰인 그대로(비밀 포함) 새 파일로 옮기고, 3.0이 뺀 12개(`BRIDGE_COMMAND_AUTOMATION`·Jev 4개 포함)는 「제거됨」으로 보고하며, `BRIDGE_DATA_DIR`은 `--target`으로 채운다. 6절. `MigrateSettingsTest.theSettingsAreCarriedOverAsWrittenTheDroppedKeysAreLeftOutAndTheHistoryMovesWithThem` 외 `MigrateSettingsTest.*`, `MigrateSettingsFailureTest.*`, `SettingsContractTest.*` |
| 환경 파일의 `BRIDGE_ADMIN_AUTH_MODE=access`와 `BRIDGE_ACCESS_*`, `BRIDGE_OIDC_JWKS_URL`, `BRIDGE_OIDC_OWNER_EMAILS` | 의도적으로 옮기지 않음 | 3.0에 없는 키·모드다. 접속 모드는 token이 기본으로 적용되고 oidc로 바꾸려면 소유자 계정(`BRIDGE_OIDC_OWNER_SUBJECT`)과 `BRIDGE_OIDC_CLIENT_SECRET`을 운영자가 넣어야 한다(이메일을 계정 식별자로 바꿀 수 없다). 보고서의 「ACTION REQUIRED」로 알린다. `MigrateSettingsTest.the2xAccessModeHasNoEquivalent...`, `theOidcModeNeedsTheOwnerAccountAndTheClientSecretWhichOnlyTheOperatorCanGive` |
| 환경 파일의 `BRIDGE_TARGETS`·`BRIDGE_DEFAULT_TARGET`(대상 설정) | 옮김(쓰인 그대로) | 값을 바꾸지 않고 복사한다. 3.0 서버가 시작할 때 검증한다. 선행 변경 필요: 3.0의 `BridgeTargetsConverter`(4단계 U2)가 만들어져야 2.x JSON이 그대로 받아들여지는지 시험할 수 있다 |
| 환경 파일의 `SSH_MCP_*`(프로필 자격 증명) | 옮김(쓰인 그대로) | 3.0도 같은 이름을 읽는다(결정 D3-09). `MigrateSettingsTest.theSettingsAreCarriedOver...`(키 이름만 보고서에 나오고 값은 나오지 않음을 확인) |
| SSH 프로필 파일 `config.toml`(`BRIDGE_CONFIG_PATH`) | 옮길 필요 없음(제자리 사용) | 3.0 `TomlProfileLoader`가 같은 TOML 형식을 `BRIDGE_CONFIG_PATH` 그대로 읽는다. 명령은 환경 파일에서 그 경로를 보고, 일반 파일이고 비어 있지 않은지만 확인해 알린다(내용은 읽지 않는다). `MigrateSettingsTest.theNoteAboutTheSshProfileFileSaysItIsUsedWhereItIs`, `aMissingOrEmptyProfileFileIsNamedBecauseThe3ServerRefusesIt`. 선행 변경 필요 없음. 알려 둘 차이: 3.0은 프로필 이름 규칙이 더 엄격하고(영문자·숫자·밑줄로 시작, 80자 이내) 모르는 키는 거절하며 `[policy]` 표는 검사만 하고 쓰지 않는다. 이 검사를 이전 때 하려면 TOML 해석 라이브러리(BOM이 관리하는 `jackson-dataformat-toml`, `portal-ssh`가 이미 쓴다)가 이 모듈에 필요한데 새 라이브러리를 이 작업이 정하지 않아 넣지 않았다 |
| SSH 개인 키 파일(프로필의 `keyRef`), 인증서 | 옮길 필요 없음(제자리 사용) | 경로만 프로필에 있고 3.0도 같은 경로를 읽는다. 이 명령은 키를 읽지 않는다 |
| 비밀 저장소 폴더(`BRIDGE_SECRET_STORE_DIR`의 로컬 파일 기록), Vault 연결 키(`BRIDGE_VAULT_*`) | 선행 변경 필요 | Vault·참조 키는 환경 파일 키라 위처럼 옮겨진다. 로컬 파일 기록의 폴더는 읽지도 옮기지도 않는다: 3.0 `server.secret.LocalFileSecretStore`(4단계 U8, `portal-server`)가 만들어져 기록 형식이 정해져야 같은 형식이면 제자리 사용, 다르면 변환할 수 있다. 그때까지는 비밀을 3.0 도구로 다시 넣는다(보고서의 「ACTION REQUIRED」) |
| 관리자 환경 편집의 root 백업 `/var/lib/ssh-approval-env/backup-*.env` | 의도적으로 옮기지 않음 | 2.x 환경 파일의 이전 사본이다. 필요하면 `--env-file`로 그 파일을 변환한다 |
| systemd 단위, Docker Compose 파일, OIDC 앞단 프록시 설정(`deploy/oidc`) | 의도적으로 옮기지 않음 | 2.x 설치·배포 구성이다. 3.0은 앱이 OAuth2 로그인을 직접 하므로 앞단 프록시가 필요 없고, 설치 구성은 3.0 설치 명령(7단계)이 새로 쓴다 |
| 클라이언트 쪽 상태(설치된 `portal-files`·launcher·hook, 완료 수신함 SQLite) | 의도적으로 옮기지 않음 | 서버 데이터가 아니며 2.x 클라이언트·hook 호출은 3.0이 거절한다(업그레이드 안내). 3.0 클라이언트를 새로 설치한다 |
| 대상 서버 쪽 상태(원격에 설치한 에이전트 CLI, GUI 계정, 데스크톱 도우미) | 의도적으로 옮기지 않음 | 이 명령은 대상 서버에 접속하지 않는다. 필요하면 3.0에서 다시 설치·확인한다 |

환경 파일에 한해 이 명령은 비밀 값을 **읽는다**(복사하려면 읽어야 한다). 값은 해석하지 않고, 로그·보고서·오류에 한 글자도 찍지 않으며(키 이름과 옮김·제거됨만 나온다), 새 파일은 소유자만 쓸 수 있게 만든다(4절).

## 2. 실행 방법

```
java -jar portal-migrate.jar --source=<2.x 데이터 폴더> --target=<3.0 데이터 폴더> [--dry-run] [--unknown-budget=<n>] [--env-file=<2.x 환경 파일> --env-out=<새 설정 파일>]
```

- 2.x 서비스는 먼저 정상 종료한다. 저널 파일 옆에 `-journal`·`-wal`·`-shm`이 있거나 파일이 잠겨 있으면 명령은 읽지 않고 오류로 끝난다.
- `--target`은 3.0 데이터 폴더(서버의 `BRIDGE_DATA_DIR`)다. 내장 H2 저장소는 그 안의 파일 `portal`(`portal.mv.db`)이며 서버가 여는 `jdbc:h2:file:<BRIDGE_DATA_DIR>/portal`과 같고, 암호화된 작업 본문(`attachments/`)과 키링(`keyring/attachment.keys`)도 그 아래에 저장된다. 다른 DBMS는 `--spring.datasource.url=...`(그리고 username·password)로 지정하되, 실 이력 이전 시에도 암호화 첨부와 키링 위치를 지정하기 위해 `--target`이 필수다(환경 파일만 옮기거나 `--dry-run`일 때만 생략 가능).
- `--dry-run`은 아무것도 쓰지 않는다. 저장소가 없으면 만들지도 않고, 설정 파일도 만들지 않는다(폴더도). 저장소가 있으면 읽기 전용으로 DB 메타데이터(`operation` 표)만 조회해 충돌과 unknown 예산을 확인하며, 첨부 키링을 생성하거나 읽지 않는다. 설정 파일은 「쓰게 될 것 / 이미 같은 내용이 있음 / 다른 내용이 있어 그대로 둠」을 알린다.
- `--unknown-budget`은 3.0 서버가 쓸 unknown 예산이다(기본 32). 5절 참고.
- `--env-file`과 `--env-out`은 함께 준다. `--env-out`은 새 파일이어야 하고 `--env-file`과 같은 파일일 수 없다(철자가 달라도 같은 파일이면 거절). 기존 파일을 덮어쓰지 않는다. 데이터 폴더에 저널이 없어도 환경 파일만 옮길 수 있다.
- 종료 코드: 0 성공(옮김, 옮길 것 없음, 문제 없는 dry-run), 1 오류(실행 못 함), 2 충돌(그대로는 옮길 수 없음, 이번 실행이 쓴 것 없음).
- 보고서는 데이터 폴더 전체 목록과 분류, 원천 파일별 읽은 행 수와 SHA-256, 옮길 건수·unknown 건수, 옮기지 않는 종류별 건수, 2.x와 다르게 저장되는 항목, 설정 이전 요약(키 이름만), 운영자가 직접 해야 할 일(「ACTION REQUIRED」), 충돌 목록을 찍는다. 줄 끝은 어느 운영체제에서나 LF, 문자 인코딩은 UTF-8이다.

## 3. 상태 대응과 저장 규칙

- id·요청 키·명령·컨텍스트 텍스트는 2.x 값 그대로다. 요청 키가 3.0 규칙(16~128자, `A-Z a-z 0-9 _ . : -`)에 맞지 않거나, 명령에 숨은 문자가 있거나, 본문이 올바른 UTF-8이 아니면 고치지 않고 충돌로 보고한다(키 자동 변경 금지).
- 본문은 SQLite 값을 한 번에 엄격히 UTF-8로 읽는다. 청크로 나누어 읽지 않으므로 다중 바이트 문자가 경계에 걸리지 않는다(결함 D-0015).
- 3.0 도메인은 확정 결과를 만드는 전이만 허용한다. 실행된 작업은 queued, starting, running, 종결의 이벤트를 만들고, 각 전이의 시각은 2.x의 생성·시작·종료 시각이다.

| 2.x 상태 | 3.0 상태 | 비고 |
|---|---|---|
| completed | COMPLETED | 결과에 종료 코드 0의 증거가 있을 때만. 없으면 UNKNOWN으로 저장하고 보고서에 「stored differently from 2.x」로 적는다(D-0035) |
| failed | FAILED | 실행 전 거절은 거절 사유로, 실행된 작업은 종료 결과로 |
| cancelled | CANCELLED | |
| denied | DENIED | |
| expired, pending, accepted | EXPIRED | 2.x가 재시작 때 pending을 expired로 정리한 규칙과 같다 |
| unknown | UNKNOWN | 재조정이 필요한 상태 그대로 |
| evaluating, queued, starting, running, terminating | UNKNOWN | 실행됐을 수 있으므로 2.x가 재시작 때 한 처리와 같다. 재실행하지 않는다 |

버려지는 정보: 2.x 결정의 모델 평가 세부(3.0 이유는 `COMMAND_SET`·`READ_ONLY`·`TEST_SERVER_BYPASS`·`POLICY_DENIED`·`NO_MATCHING_RULE`뿐이다), 충돌 키(옮긴 작업은 자원을 막지 않는다. 2.x 이전도 그랬다), 관리자 별칭이 없는 2.x 대상 주소(`legacy-unbound`), 2.x 대상 revision(64자 16진수면 그대로, 아니면 0 64개). 소유자가 승인한 작업은 소유자 승인 전이(`OWNER`)를 거친다.

## 4. 실패와 재실행 동작

- 처리는 두 단계다. 1단계는 저장소 없이 2.x를 읽고 모든 행을 변환·검사한다(변환 불가 행, 한 id의 키 둘, 두 작업의 같은 키). 환경 파일을 주었으면 이 단계에서 같이 읽고 검사한다(2.x가 거절한 줄, 파일 크기, 인코딩). 문제가 하나라도 있으면 저장소를 만들거나 열지 않고, 설정 파일도 쓰지 않고 충돌로 끝난다.
- 저장소가 아직 없으면 저장소 없이 할 수 있는 마지막 확인(unknown 예산)을 만들기 전에 한다. 그 확인에 걸린 이전은 저장소도 데이터 폴더도 만들지 않는다.
- 2단계는 저장소의 스키마를 맞추고(저장소 자신의 expand 스크립트), 대상 저장소와 겹침을 확인한 뒤, 모든 쓰기를 한 트랜잭션에서 한다. 쓰는 중 어떤 이유로든 실패하면 이번 실행의 쓰기는 전부 되돌려진다. 쓰기는 `portal-store`의 `JdbcOperationJournal`·`JdbcClaimStore`로만 하며 직접 SQL은 없다.
- 설정 파일은 이력 이전이 문제 없이 끝난 뒤에만 쓴다. 같은 폴더에 소유자 전용 임시 파일을 만들어 내용을 쓰고 디스크에 내린 다음 이름을 바꾸어 놓으므로 반쪽짜리 파일이 최종 이름으로 보이는 일이 없고, 이름을 바꿀 때 기존 파일을 덮어쓰지 않는다. 쓰다 실패하면 임시 파일을 지우고 오류(종료 코드 1)로 끝난다. 이력은 이미 옮겨졌으므로 원인을 고치고 같은 명령을 다시 실행하면 이력은 건너뛰고(재실행 시 중복 0) 설정 파일만 쓴다(`MigrateSettingsFailureTest.aSettingsFileThatCannotBeWrittenFailsTheRunAfterTheHistoryIsMovedAndARerunRepairsIt`).
- 재실행: 새 파일이 이미 같은 내용이면 그대로 두고, 다른 내용이면(운영자가 소유자 계정 같은 값을 채웠을 수 있다) 덮어쓰지 않고 알린다. 지우면 다시 쓴다.
- 충돌 보고는 2.x 행(파일#id)과 부딪힌 3.0 작업을 함께 적는다. 예외 한 줄로 끝내지 않는다. 대상에 같은 요청 키가 같은 id·같은 계약으로 있으면 이전 실행이 옮긴 것으로 보고 다시 옮기지 않는다(재실행 시 중복 0). 같은 키에 다른 내용이거나 같은 id에 다른 키이면 충돌이다.
- 이력 건수에 고정 한도가 없다. 행은 하나씩 읽고(전체를 메모리에 두지 않는다), 한도 때문에 이력을 지우지 않는다. 2.x 저널은 1만 행·64 MiB에서 멈췄지만 이 명령은 그 이상도 옮긴다(`MigrateScaleTest`). 옮기지 않는 행은 조용히 건너뛰지 않고 종류별 건수와 이유를 보고서에 남긴다.
- 원천 파일(저널, 환경 파일, 프로필 파일)은 읽기 전용으로만 열고 고치지 않는다. 저널은 읽기 전후 SHA-256을 비교하고, 읽는 동안 바뀌면 오류로 끝나고 아무것도 쓰지 않는다.
- 운영체제 중립: 경로는 `java.nio.file`로만 다루고(구분자 직접 쓰기 없음, 값에 적는 경로는 `/`로 통일), 출력과 설정 파일은 UTF-8·LF로 쓰며, 시각은 2.x의 밀리초 값을 UTC 시각으로만 바꾸어 시간대를 쓰지 않는다. 비밀이 든 설정 파일은 파일 시스템이 주는 방식으로 소유자 전용으로 만든다: 권한 비트가 있으면 `rw-------`, 접근 제어 목록이 있으면 소유자 한 명의 전체 권한만 둔 목록(운영체제 이름이 아니라 파일 시스템이 제공하는 속성 보기로 고른다). 둘 다 없으면 쓰지 않고 오류로 끝난다. 시험은 Linux와 Windows 양쪽의 CI에서 같은 코드로 돈다(`OwnerOnlyFileTest`, `MigrateSettingsTest.aFileSavedWithWindowsLineEnds...`).

## 5. unknown 작업

3.0 저장소의 unknown 예산은 `OperationRowRepository.countLiveUnknown()`이 센다: `state = 'UNKNOWN'`인 모든 작업이며, 이전된 작업을 빼는 표시는 3.0 스키마에 없다(`operation_legacy` 표와 `legacy_import` 복구 사유는 D-0127에서 제거되었다). `JdbcClaimStore.claim`은 이 수가 예산(`ConcurrencyLimits.unknownBudgetLimit()`, 기본 `max(32, 전역 동시 실행 수)`) 이상이면 `Saturated(UNKNOWN_BUDGET)`으로 새 작업의 시작을 거절한다. 따라서 이전된 unknown 작업은 3.0의 새 작업 접수 예산을 소모한다. 2.4.2 장애(D-0152, D-0153)와 같은 막힘이 이전 직후에 생길 수 있으므로 명령이 미리 막는다.

- 이전 뒤 저장소의 unknown 수(기존 + 옮길 것)가 `--unknown-budget` 이상이면 충돌(UNKNOWN_BUDGET)로 끝나고 아무것도 쓰지 않는다. dry-run도 같은 판정을 한다.
- 해결은 둘 중 하나다. 2.x에서 unknown 작업을 정리한 뒤 옮기거나, 3.0 서버가 실제로 쓰는 더 큰 예산을 `--unknown-budget`으로 알린다. 값은 서버 설정과 같아야 한다.
- 근거 시험: `MigrateUnknownTest.movedUnknownOperationsUseTheBudgetOfTheStoresOwnClaim`(옮긴 unknown 2건 + 예산 2에서 저장소의 claim이 `UNKNOWN_BUDGET`으로 거절, 예산 3이면 시작).
- 후속(3.0 쪽 변경, 이 모듈이 고치지 않는다): 저장소가 이전된 행을 표시하고 예산에서 빼면(2.x가 `operation_legacy_budget`으로 한 것) 위 제한 없이 모든 unknown을 옮길 수 있다. 그러려면 `portal-store`에 이전 행 표시와 이전 전용 쓰기 포트가 필요하다.

## 6. 설정 이전 (환경 파일)

3.0이 읽는 환경 파일은 2.x와 같은 형식(`KEY=value`, `#` 주석, 앞뒤 공백 제거, 한 쌍의 같은 따옴표 제거)과 같은 키 이름이다. 4단계 계약 기준본(`settings-contract.json`)에서 달라진 것은 키 12개가 빠지고 4개가 더해진 것뿐이다(허용 키 32개). 명령은 2.4.2와 같은 규칙으로 파일을 읽는다(키는 `[A-Z_][A-Z0-9_]*`, 2.x가 받던 키와 `SSH_MCP_[A-Z0-9_]+`만, 64 KiB 이하, 올바른 UTF-8, 값에 제어 문자 없음). 2.x가 거절했을 줄은 추측하지 않고 충돌(`SETTINGS_ENTRY`)로 줄 번호와 함께 보고한다(값은 찍지 않는다).

| 키 | 처리 |
|---|---|
| 3.0에도 있는 키 전부(`SSH_MCP_*` 포함) | 쓰인 그대로 복사(따옴표·공백 보존). 같은 키가 여러 번이면 2.x처럼 마지막 값 |
| `BRIDGE_AUDIT_PATH` | 제거됨(감사는 DB 표) |
| `BRIDGE_TRANSFER_ROOT` | 제거됨(데이터 폴더 하나로 합침). 대신 `BRIDGE_DATA_DIR`을 `--target` 경로(`/` 구분)로 채운다. `--target`이 없으면 운영자가 정한다(「ACTION REQUIRED」) |
| `BRIDGE_ACCESS_TEAM_DOMAIN`, `BRIDGE_ACCESS_AUD`, `BRIDGE_ACCESS_OWNER_EMAILS` | 제거됨(access 모드 없음). `BRIDGE_ADMIN_AUTH_MODE=access`이면 그 줄도 빼고(기본 token 적용) 운영자에게 알린다 |
| `BRIDGE_OIDC_JWKS_URL` | 제거됨(발급자 discovery로 키를 찾는다) |
| `BRIDGE_OIDC_OWNER_EMAILS` | 제거됨. 3.0은 계정 식별자 하나(`BRIDGE_OIDC_OWNER_SUBJECT`)만 받는다. 이메일에서 만들 수 없으므로 `BRIDGE_ADMIN_AUTH_MODE=oidc`이면 운영자가 소유자 계정과 `BRIDGE_OIDC_CLIENT_SECRET`을 새 파일에 넣어야 한다고 알린다. 값은 지어내지 않는다 |
| `BRIDGE_COMMAND_AUTOMATION` | 제거됨(3.0은 결정적 규칙 판정이 항상 동작하며 전역 스위치가 없다) |
| `BRIDGE_JEV_ENABLED`, `BRIDGE_JEV_MAX_CALLS`, `BRIDGE_JEV_KEY_REF`, `JEV_API_KEY` | 제거됨(3.0은 모델 판정 Jev를 제외하고 결정적 규칙만 사용한다) |
| `BRIDGE_SOURCE_URL`(새 키) | 쓰지 않는다(비면 3.0이 이 판의 공개 저장소 태그를 쓴다) |

한계: 3.0 쪽의 설정 바인딩(`BridgeProperties`, `SecretEnvFileLoader`)은 4단계 U2에서 만들어지므로, 만들어진 파일이 그 바인딩에 실제로 묶이는지는 이 모듈의 시험이 보지 못한다. 키 목록은 계약 기준본과 `SettingsContractTest`로만 대조한다(2.4.2의 `allowedKeys` 사본 40개, 빠지는 12개, 더해지는 4개, 3.0 허용 키 32개). 새 설정 파일의 기본 위치도 3.0 설치 명령(7단계)이 정하므로 이 명령은 운영자가 준 `--env-out`에만 쓴다.

## 7. 3.0 쪽에서 필요한 변경과 한계

1. 이전 전용 쓰기 포트(`portal-store`): 지금은 도메인 전이표를 따라 queued, starting, running을 거치게 해 쓴다. 결과가 확정된 작업을 한 번에 복원하는 포트가 있으면 이벤트가 2.x의 두 행과 맞고 속도도 빠르다.
2. 이전 행 표시와 unknown 예산 제외(5절, `portal-store`).
3. 이전된 행의 대상 revision을 관찰 시 거절하지 않는 처리(`TARGET_REVISION_CONFLICT`)는 `portal-app` 쪽 결정이 필요하다(stage2-design D-09).
4. 서버의 H2 위치: 4단계 설계가 `BRIDGE_DATA_DIR` 아래 파일 `portal`로 정했고 이 명령이 그대로 따른다(`--target/portal.mv.db`). 서버 조립(U2)이 다른 위치로 바뀌면 `MigrateOptions`의 한 상수를 맞춘다.
5. 로컬 비밀 저장소(`BRIDGE_SECRET_STORE_DIR`) 기록 형식(4단계 U8): 정해지면 이 명령이 폴더를 옮기거나 변환한다.
6. `BRIDGE_TARGETS` JSON 변환기(4단계 U2)와 환경 파일 로더: 만들어지면 이 명령이 만든 파일을 실제 바인딩에 태우는 시험을 더한다.
7. 소유자 전용 파일 생성 규칙의 중복: `portal-app`의 `PrivateStorages`가 이미 같은 규칙(권한 비트·접근 제어 목록)을 갖지만 규칙 A-DIR(`ModuleTable`: migrate는 store·domain만)이 이 모듈의 사용을 막아 `OwnerOnlyFile`이 같은 일을 하는 작은 클래스(약 70줄)로 따로 있다. 규칙을 migrate가 쓸 수 있는 모듈로 옮기거나 `ModuleTable`이 migrate에 `app.storage`를 허용하면 이 클래스를 지운다.
8. 프로필 파일 검증(`jackson-dataformat-toml`): 이전 때 3.0의 프로필 이름 규칙으로 미리 검사하려면 필요하다. 이 모듈에는 넣지 않았다(1.3절).
9. 같은 id가 두 파일에 있으면 `operations.sqlite`(없으면 먼저 읽는 파일)를 쓴다. 내용이 다른 경우 어느 쪽이 맞는지 판정하지 않는다.
10. 감사 로그 파일(`approval-bridge-audit.jsonl`)을 3.0 감사 표로 옮기는 일은 감사 표(4단계 U2의 `JdbcAuditLog`와 그 마이그레이션 스크립트, 번호는 4단계 설계가 배정한다)가 먼저 있어야 한다. 이 모듈은 DB 마이그레이션 스크립트를 더하지 않는다.

## 8. 업그레이드 안내에 넣을 문장 초안

English:

> To keep your 2.x data, stop the 2.x service, then run `java -jar portal-migrate.jar --source=<2.x data folder> --target=<3.0 data folder> --env-file=<2.x environment file> --env-out=<new settings file> --dry-run`, read the report, and run it again without `--dry-run`. The command opens the 2.x files read only and never changes them. It moves the command history in one transaction, so a failure leaves nothing behind, and running it again moves nothing twice. It converts the settings: the keys 3.0 still has are copied exactly as written (secrets included, never printed) into the new file, which only you can read, seven keys that 3.0 dropped are listed as removed, and `BRIDGE_DATA_DIR` is set to the 3.0 data folder. Read the lines marked `ACTION REQUIRED`: the owner account and client secret of an OIDC login, and the secret store, are yours to set. The SSH profile file (`BRIDGE_CONFIG_PATH`) and the key files it names stay where they are; 3.0 reads the same format. It does not move policy rules, file-transfer history, command output files (kept for one hour only in 2.x) or the audit log. If the report says the unknown budget would be used up, reconcile the 2.x unknown operations first: the 3.0 server starts no new operation while that many operations are unknown.

한국어:

> 2.x 데이터를 옮기려면 2.x 서비스를 정상 종료한 뒤 `java -jar portal-migrate.jar --source=<2.x 데이터 폴더> --target=<3.0 데이터 폴더> --env-file=<2.x 환경 파일> --env-out=<새 설정 파일> --dry-run`으로 보고서를 확인하고, `--dry-run` 없이 다시 실행합니다. 이 명령은 2.x 파일을 읽기 전용으로만 열어 바꾸지 않습니다. 명령 이력은 한 트랜잭션으로 옮기므로 실패해도 남는 것이 없고 다시 실행해도 두 번 옮기지 않습니다. 설정도 변환합니다. 3.0에도 있는 키는 쓰인 그대로(비밀 포함, 화면에는 찍지 않음) 소유자만 읽을 수 있는 새 파일에 복사하고, 3.0이 뺀 키 7개는 「제거됨」으로 알리며, `BRIDGE_DATA_DIR`은 3.0 데이터 폴더로 채웁니다. `ACTION REQUIRED`로 표시된 줄을 읽으세요. OIDC 로그인의 소유자 계정과 클라이언트 비밀, 비밀 저장소는 직접 정해야 합니다. SSH 프로필 파일(`BRIDGE_CONFIG_PATH`)과 그 안에 적힌 키 파일은 제자리에 두며, 3.0이 같은 형식을 읽습니다. 정책 규칙, 파일 전송 이력, 명령 출력 파일(2.x는 1시간만 보관), 감사 로그는 옮기지 않습니다. 보고서가 unknown 예산을 다 쓰게 된다고 알리면 2.x의 unknown 작업을 먼저 정리합니다. 그만큼 unknown이면 3.0 서버가 새 작업을 시작하지 않습니다.

## 9. 시험

`java/portal-migrate/src/test`의 시험은 2.x CREATE TABLE 문으로 SQLite 파일을, 2.x 설치기 모양으로 환경 파일을 직접 만들고(값은 모두 시험이 지어낸 것), 옮긴 뒤 저장소 자신의 저널로 다시 읽거나 새 파일을 읽어 시험이 정한 값과 대조한다. 시험마다 자기 폴더를 쓰며 서로의 데이터에 의존하지 않는다.

| 요구 | 시험 |
|---|---|
| 정상 이전(상태별), 범위·목록 | `MigrateImportTest` |
| 재실행 중복 0 | `MigrateRerunAndDryRunTest.theSameFolderRunAgainMovesNothingAndTouchesNothing`, `aLaterRunWithMoreHistoryMovesOnlyTheNewOperations` |
| 충돌과 부분 기록 0 | `MigrateConflictTest` |
| 원문 바이트(청크 경계) | `MigrateTextPreservationTest` |
| unknown | `MigrateImportTest.unknownOperationsStayUnknownAndNeedReconciliation`, `MigrateUnknownTest` |
| dry-run | `MigrateRerunAndDryRunTest.aDryRun...` |
| 원천 파일 바이트 동일 | `MigrateImportTest.everyStateIsMovedWith...`, `MigrateRerunAndDryRunTest.aRealRunLeavesTheTwoXFiles...`, 충돌·dry-run 시험의 원천 지문 비교, `MigrateSettingsTest`의 환경 파일·프로필 파일 지문 비교 |
| 규모(1만 건 초과) | `MigrateScaleTest` |
| 저장소 위치(`BRIDGE_DATA_DIR/portal`) | `MigrateSettingsTest.theSettingsAreCarriedOver...`(`portal.mv.db`가 `--target` 안에 있음), `MigrationKit.open`이 서버와 같은 위치로 읽음 |
| 실패한 이전은 저장소·폴더를 남기지 않음 | `MigrateUnknownTest.aMigrationThatWouldUseTheUnknownBudgetUpIsRefusedAndWritesNothing`, `MigrateSettingsFailureTest.aMoveThatWouldUseTheUnknownBudgetUpWritesNoSettingsFileAndNoStore` |
| 설정 변환(그대로 복사, 제거된 키, `BRIDGE_DATA_DIR`) | `MigrateSettingsTest.theSettingsAreCarriedOver...`, `quotesStayAsWrittenAndALaterDuplicateWinsAsItDidIn2x`, `theSettingsAloneAreConvertedWhenTheFolderHasNoJournal` |
| 설정 변환의 키 표가 계약과 같음 | `SettingsContractTest` |
| OIDC·access 모드, 비밀 저장소, `--target` 없음 | `MigrateSettingsTest.theOidcMode...`, `the2xAccessMode...`, `aSecretStoreFolderIsNotRead...`, `withoutATargetFolderTheDataFolderIsLeftToTheOperator` |
| 비밀을 찍지 않음 | `MigrateSettingsTest`의 `assertNothingSecretWasPrinted`, `MigrateSettingsFailureTest`(줄 번호만 나오고 값은 나오지 않음) |
| 소유자 전용 파일(Linux 권한 비트, Windows 접근 제어 목록) | `OwnerOnlyFileTest`, `MigrateSettingsTest.theSettingsAreCarriedOver...`(`assertOwnerOnly`) |
| 설정 dry-run, 재실행, 손으로 채운 파일 보존 | `MigrateSettingsTest.aDryRunWritesNoSettingsFile...`, `aRerunLeavesTheSettingsFileAndTheStoreAsTheyAre`, `aSettingsFileTheOperatorCompletedIsNeverReplaced` |
| 설정 오류·충돌 시 아무것도 쓰지 않음 | `MigrateSettingsFailureTest.aLineThat2xRefusedStopsTheWholeRun...`, `aHistoryConflictWritesNoSettingsFileEither`, `aMissingOversizedOrNotUtf8EnvironmentFileIsAConflictNotAGuess`, `theEnvironmentFileCannotBeTheOutputAndTheTwoOptionsGoTogether` |
| 쓰기 실패 뒤 재실행으로 복구 | `MigrateSettingsFailureTest.aSettingsFileThatCannotBeWrittenFailsTheRun...` |
| 운영체제 중립(CRLF 입력, LF 출력) | `MigrateSettingsTest.aFileSavedWithWindowsLineEnds...` |
