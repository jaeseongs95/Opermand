# portal-migrate: 2.x 데이터를 3.0.0 저장소로 옮기는 명령

3.0.0은 2.x와 하위 호환을 지키지 않는다(본체에 2.x 형식을 읽는 코드가 없다). 2.x가 남긴 데이터는 이 문서의 별도 명령 `portal-migrate`가 옮긴다. 이 모듈은 `portal-store`와 `portal-domain`만 쓰고(규칙 A-DIR), `sqlite-jdbc`를 쓰는 유일한 모듈이며, 서버·클라이언트 어느 쪽도 이 모듈에 의존하지 않는다.

## 1. 이전 범위: 2.x가 남기는 것 전부

2.x 서비스가 `BRIDGE_TRANSFER_ROOT`(기본 `/var/lib/ssh-mcp/transfers`)에 두는 파일과, 그 밖의 설정·비밀·로그를 모두 적었다. 명령은 데이터 폴더를 끝까지 나열해 보고서에 같은 분류로 찍는다. 알지 못하는 파일은 「UNRECOGNIZED」로 보고하고 건드리지 않는다.

| 2.x가 남기는 것 | 이전 | 이유와 업그레이드 안내에 적을 문장 |
|---|---|---|
| `operations.sqlite`의 `command` 작업(`operations`) | 이전함 | 3.0 작업 저널(`operation*` 표)로 옮긴다. 상태 대응은 3절 |
| `operations.sqlite`의 `source` 작업 | 이전하지 않음 | 소스 실행은 비공개 계획을 따로 두었고 3.0은 그 계획을 다시 만들거나 재실행하지 않는다. 「2.x의 소스 실행 이력은 옮기지 않으며, 보고서에 건수가 나옵니다.」 |
| `operations.sqlite`의 `upload`·`download` 작업 | 이전하지 않음 | 대상 파일(아티팩트)은 2.x가 시간이 지나면 지웠다. 이력만 남기면 가리키는 곳이 없다. 「파일 전송 이력은 옮기지 않습니다. 전송된 파일은 2.x에서 이미 만료·삭제되었습니다.」 |
| `operations.sqlite`의 `cli-probe` 작업 | 이전하지 않음 | 2.x 내부 확인용이며 3.0에 같은 것이 없다 |
| `operations.sqlite`의 `operation_events`·`operation_legacy_budget`·`operation_schema` | 이전하지 않음 | 3.0은 옮긴 전이마다 자기 이벤트 행을 쓴다(시각은 2.x 사건의 시각). 2.x의 이전 한도 표는 3.0에 대응이 없다 |
| `command-automation.sqlite`의 `requests`(명령 승인 저널) | 이전함 | `operations.sqlite`에 같은 id가 있으면 그쪽을 쓰고 이쪽은 「겹침」으로 센다. 없는 id만 명령 작업으로 옮긴다. 요청 키가 없는 행은 2.x가 이전할 때 주던 키 `legacy:<파일>:<id>`를 쓴다 |
| `command-automation.sqlite`의 `policy`(명령 자동화 정책) | 이전하지 않음 | 정책 규칙은 2.x 대상 revision(2.x 자바스크립트 객체의 해시)에 묶여 있고 3.0은 그 값을 재현할 수 없다. 옮겨도 어떤 대상에도 맞지 않는다. 「명령 자동화 정책은 3.0 관리자 화면에서 다시 설정합니다.」 |
| `agent-cli.sqlite`의 `requests` | 이전함 | 2.x는 CLI 설치를 `command` 작업으로 실행했으므로 명령 작업으로 옮긴다 |
| `agent-cli.sqlite`의 `remote_status` 열 | 이전하지 않음 | 원격 설치 상태는 대상 서버에서 다시 조회한다 |
| `maintenance.sqlite`(점검 예약) | 이전하지 않음 | 2.x 보존 절차의 일시 잠금이다. 3.0에서는 의미가 없다 |
| `capacity.sqlite`(런타임 용량 덮어쓰기) | 이전하지 않음 | 3.0 설정에서 용량을 다시 정한다 |
| `command-outputs/`(출력 파일과 `manifest.sqlite`) | 이전하지 않음 | 2.x는 출력을 1시간만 보관했다. 이전 시점에는 이미 만료되었다 |
| `<48 hex>.json`·`<48 hex>.part`·`orphans/`(전송 임시 파일) | 이전하지 않음 | 전송 임시 데이터이며 2.x가 시간 제한으로 지운다 |
| 환경 파일 `/etc/ssh-mcp-approval-bridge.env`(`BRIDGE_*`)와 Docker용 `docker-secrets.env` | 이전하지 않음 | 3.0 설정 이름이 4단계에서 확정되어야 대응표를 만들 수 있다. 비밀 파일은 열지 않는다. 「설정은 업그레이드 안내의 키 대응표대로 새로 씁니다(2.x 키와는 호환되지 않습니다).」 |
| SSH 프로필 `config.toml`, 키 파일, `known_hosts` | 이전하지 않음 | 이 명령은 비밀·키를 읽지 않는다. 3.0 형식으로 새로 둔다 |
| 비밀 저장소(`BRIDGE_SECRET_STORE_DIR`) | 이전하지 않음 | 위와 같다 |
| 감사 로그 `approval-bridge-audit.jsonl` | 이전하지 않음 | 2.x 형식의 JSONL이다. 3.0은 자기 감사 파일을 새로 쓰고, 2.x 파일은 그대로 보관한다 |
| 메모리에만 있던 것(관리자 브라우저 세션, 데스크톱 세션, 전송 메타) | 해당 없음 | 2.x가 멈추면 사라진다 |

환경 파일·SSH 프로필 변환은 3.0 설정 이름이 정해지기 전에는 만들 수 없어 이 명령의 범위 밖에 두었다(장부 D-0113의 설정 부분은 4단계 이후 후속으로 이어진다).

## 2. 실행 방법

```
java -jar portal-migrate.jar --source=<2.x 데이터 폴더> --target=<3.0 데이터 폴더> [--dry-run] [--unknown-budget=<n>]
```

- 2.x 서비스는 먼저 정상 종료한다. 저널 파일 옆에 `-journal`·`-wal`·`-shm`이 있거나 파일이 잠겨 있으면 명령은 읽지 않고 오류로 끝난다.
- `--target` 아래 `store/`에 내장 H2 저장소를 만든다. 다른 DBMS는 `--spring.datasource.url=...`(그리고 username·password)로 지정하며, 이때 `--target`은 필요 없다. 서버의 H2 위치가 4단계에서 확정되면 `--target`의 기본 배치를 그에 맞춘다.
- `--dry-run`은 대상에 아무것도 쓰지 않는다. 저장소가 없으면 만들지도 않는다. 있으면 읽기 전용으로 열어 충돌을 확인한다.
- `--unknown-budget`은 3.0 서버가 쓸 unknown 예산이다(기본 32). 5절 참고.
- 종료 코드: 0 성공(옮김, 옮길 것 없음, 문제 없는 dry-run), 1 오류(실행 못 함), 2 충돌(그대로는 옮길 수 없음, 대상에 쓴 것 없음).
- 보고서는 데이터 폴더 전체 목록과 분류, 원천 파일별 읽은 행 수와 SHA-256, 옮길 건수·unknown 건수, 옮기지 않는 종류별 건수, 2.x와 다르게 저장되는 항목, 충돌 목록을 찍는다.

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

- 처리는 두 단계다. 1단계는 저장소 없이 2.x를 읽고 모든 행을 변환·검사한다(변환 불가 행, 한 id의 키 둘, 두 작업의 같은 키). 문제가 하나라도 있으면 저장소를 만들거나 열지 않고 충돌로 끝난다.
- 2단계는 저장소의 스키마를 맞추고(저장소 자신의 expand 스크립트), 대상 저장소와 겹침을 확인한 뒤, 모든 쓰기를 한 트랜잭션에서 한다. 쓰는 중 어떤 이유로든 실패하면 이번 실행의 쓰기는 전부 되돌려진다. 쓰기는 `portal-store`의 `JdbcOperationJournal`·`JdbcClaimStore`로만 하며 직접 SQL은 없다.
- 충돌 보고는 2.x 행(파일#id)과 부딪힌 3.0 작업을 함께 적는다. 예외 한 줄로 끝내지 않는다. 대상에 같은 요청 키가 같은 id·같은 계약으로 있으면 이전 실행이 옮긴 것으로 보고 다시 옮기지 않는다(재실행 시 중복 0). 같은 키에 다른 내용이거나 같은 id에 다른 키이면 충돌이다.
- 이력 건수에 고정 한도가 없다. 행은 하나씩 읽고(전체를 메모리에 두지 않는다), 한도 때문에 이력을 지우지 않는다. 2.x 저널은 1만 행·64 MiB에서 멈췄지만 이 명령은 그 이상도 옮긴다(`MigrateScaleTest`).
- 원천 파일은 읽기 전용 연결로만 열고, 읽기 전후 SHA-256을 비교한다. 읽는 동안 바뀌면 오류로 끝나고 아무것도 쓰지 않는다. 원천은 수정·삭제하지 않는다.

## 5. unknown 작업

3.0 저장소의 unknown 예산은 `OperationRowRepository.countLiveUnknown()`이 센다: `state = 'UNKNOWN'`인 모든 작업이며, 이전된 작업을 빼는 표시는 3.0 스키마에 없다(`operation_legacy` 표와 `legacy_import` 복구 사유는 D-0127에서 제거되었다). `JdbcClaimStore.claim`은 이 수가 예산(`ConcurrencyLimits.unknownBudgetLimit()`, 기본 `max(32, 전역 동시 실행 수)`) 이상이면 `Saturated(UNKNOWN_BUDGET)`으로 새 작업의 시작을 거절한다. 따라서 이전된 unknown 작업은 3.0의 새 작업 접수 예산을 소모한다. 2.4.2 장애(D-0152, D-0153)와 같은 막힘이 이전 직후에 생길 수 있으므로 명령이 미리 막는다.

- 이전 뒤 저장소의 unknown 수(기존 + 옮길 것)가 `--unknown-budget` 이상이면 충돌(UNKNOWN_BUDGET)로 끝나고 아무것도 쓰지 않는다. dry-run도 같은 판정을 한다.
- 해결은 둘 중 하나다. 2.x에서 unknown 작업을 정리한 뒤 옮기거나, 3.0 서버가 실제로 쓰는 더 큰 예산을 `--unknown-budget`으로 알린다. 값은 서버 설정과 같아야 한다.
- 근거 시험: `MigrateUnknownTest.movedUnknownOperationsUseTheBudgetOfTheStoresOwnClaim`(옮긴 unknown 2건 + 예산 2에서 저장소의 claim이 `UNKNOWN_BUDGET`으로 거절, 예산 3이면 시작).
- 후속(3.0 쪽 변경, 이 모듈이 고치지 않는다): 저장소가 이전된 행을 표시하고 예산에서 빼면(2.x가 `operation_legacy_budget`으로 한 것) 위 제한 없이 모든 unknown을 옮길 수 있다. 그러려면 `portal-store`에 이전 행 표시와 이전 전용 쓰기 포트가 필요하다.

## 6. 3.0 쪽에서 필요한 변경과 한계

1. 이전 전용 쓰기 포트: 지금은 도메인 전이표를 따라 queued, starting, running을 거치게 해 쓴다. 결과가 확정된 작업을 한 번에 복원하는 포트가 있으면 이벤트가 2.x의 두 행과 맞고 속도도 빠르다.
2. 이전 행 표시와 unknown 예산 제외(5절).
3. 이전된 행의 대상 revision을 관찰 시 거절하지 않는 처리(`TARGET_REVISION_CONFLICT`)는 `portal-app` 쪽 결정이 필요하다(stage2-design D-09).
4. 서버의 H2 저장소 위치가 정해지면 `--target` 아래 배치(`store/portal`)를 맞춘다.
5. 환경 파일·SSH 프로필 변환은 4단계 설정 이름이 확정된 뒤의 후속이다.
6. 같은 id가 두 파일에 있으면 `operations.sqlite`(없으면 먼저 읽는 파일)를 쓴다. 내용이 다른 경우 어느 쪽이 맞는지 판정하지 않는다.

## 7. 업그레이드 안내에 넣을 문장 초안

English:

> To keep your 2.x history, stop the 2.x service, then run `java -jar portal-migrate.jar --source=<2.x data folder> --target=<3.0 data folder> --dry-run`, read the report, and run it again without `--dry-run`. The command opens the 2.x files read only and never changes them; it moves the command history in one transaction, so a failure leaves nothing behind, and running it again moves nothing twice. It does not move policy rules, settings, secrets, SSH profiles, file-transfer history, command output files (kept for one hour only in 2.x) or the audit log; set these up again in 3.0 as the upgrade guide says. If the report says the unknown budget would be used up, reconcile the 2.x unknown operations first: the 3.0 server starts no new operation while that many operations are unknown.

한국어:

> 2.x 이력을 옮기려면 2.x 서비스를 정상 종료한 뒤 `java -jar portal-migrate.jar --source=<2.x 데이터 폴더> --target=<3.0 데이터 폴더> --dry-run`으로 보고서를 확인하고, `--dry-run` 없이 다시 실행합니다. 이 명령은 2.x 파일을 읽기 전용으로만 열어 바꾸지 않으며, 명령 이력을 한 트랜잭션으로 옮기므로 실패해도 남는 것이 없고 다시 실행해도 두 번 옮기지 않습니다. 정책 규칙, 설정, 비밀, SSH 프로필, 파일 전송 이력, 명령 출력 파일(2.x는 1시간만 보관), 감사 로그는 옮기지 않으니 업그레이드 안내대로 3.0에서 다시 설정합니다. 보고서가 unknown 예산을 다 쓰게 된다고 알리면 2.x의 unknown 작업을 먼저 정리합니다. 그만큼 unknown이면 3.0 서버가 새 작업을 시작하지 않습니다.

## 8. 시험

`java/portal-migrate/src/test`의 시험은 2.x CREATE TABLE 문으로 SQLite 파일을 직접 만들고, 옮긴 뒤 저장소 자신의 저널로 다시 읽어 시험이 정한 값과 대조한다. 시험마다 자기 폴더를 쓰며 서로의 데이터에 의존하지 않는다.

| 요구 | 시험 |
|---|---|
| 정상 이전(상태별), 범위·목록 | `MigrateImportTest` |
| 재실행 중복 0 | `MigrateRerunAndDryRunTest.theSameFolderRunAgainMovesNothingAndTouchesNothing`, `aLaterRunWithMoreHistoryMovesOnlyTheNewOperations` |
| 충돌과 부분 기록 0 | `MigrateConflictTest` |
| 원문 바이트(청크 경계) | `MigrateTextPreservationTest` |
| unknown | `MigrateImportTest.unknownOperationsStayUnknownAndNeedReconciliation`, `MigrateUnknownTest` |
| dry-run | `MigrateRerunAndDryRunTest.aDryRun...` |
| 원천 파일 바이트 동일 | `MigrateImportTest.everyStateIsMovedWith...`, `MigrateRerunAndDryRunTest.aRealRunLeavesTheTwoXFiles...`, 충돌·dry-run 시험의 원천 지문 비교 |
| 규모(1만 건 초과) | `MigrateScaleTest` |
