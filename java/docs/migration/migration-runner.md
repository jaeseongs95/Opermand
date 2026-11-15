# 3.0.0 마이그레이션 적용기 (장부 SSH_PORTAL-D-0109·D-0088·D-0103)

사용자 원문: 「내가 그래서 쿼리빌더 만든거 아닌가?」, 「h2 기반이긴하지만 dbms 연동 가능하다. <- 이 정책 고수해.」, 「애초에 하위호환성 다 버리라고 했을텐데?」.
정본: 세 제품(AGS(Agent Governance Suite)·장부·SSH)의 「마이그레이션 적용기 공통 형식」 문서(판 3). 사례표: `regression-cases.part-b.md` 6.1절(S-D0109-01~).
구현: `portal-store`의 `io.github.jaeseongs95.portal.store.migration`(`MigrationRunner`, `MigrationScript`, `Migrations`, `ExpandOnStart`, `LongTextType`, `MigrationException`, `MigrationConfiguration`, `MigrationInitializerDetector`). 스크립트는 `portal-store/src/main/resources/db/expand/V1~V7`이다.

## Flyway를 쓰지 않는 이유

- 제품은 기본이 내장 H2이고, 사용자의 JDBC 드라이버로 다른 DBMS(PostgreSQL, MariaDB, MySQL, Oracle, Db2, SQL Server)에 연결할 수 있다(사용자 2026-11-12: 「h2 기반이긴하지만 dbms 연동 가능하다. <- 이 정책 고수해.」). DBMS 사이의 차이는 ANSI SQL과 `SqlBuilder`로 흡수한다(사용자 「내가 그래서 쿼리빌더 만든거 아닌가?」).
- Flyway는 자기가 아는 DBMS의 목록을 갖고 있고, 10판부터는 DBMS마다 부품(`flyway-database-postgresql`, `flyway-mysql` 등)을 따로 둔다. 제품이 사용자가 고를 수 있는 모든 DBMS의 부품을 싣거나, 부품이 없는 DBMS를 포기해야 한다. 이는 특정 벤더에 묶이지 않는다는 설계 원칙(벤더 독립)과 어긋난다.
- 표준 SQL만 쓰는 적용기는 그 목록이 필요 없다. 필요한 것은 ANSI 형의 이력 표, `CREATE SCHEMA`, 드라이버가 알려 주는 긴 텍스트 형(`getTypeInfo`)뿐이다. DBMS 이름을 아는 코드는 없다. 이력 표는 ANSI 형만 쓰고, 스크립트는 `${long_text}`를 바꾸는 것 외에는 고쳐 쓰지 않는다.
- 세 제품이 같은 적용기·같은 이력 표·같은 규칙을 쓴다(사용자 「결국 차별점은 "맞춤형" 이거든.」).
- 3.0.0은 하위 호환을 지키지 않는다. 옛 `flyway_schema_history`를 읽거나 옮기는 코드는 없다. 이 이력을 가진 2.x 계열 데이터의 이전은 `portal-migrate`의 몫이다.
- 코드의 설명: `MigrationRunner`의 javadoc과 `portal-store/build.gradle.kts`의 주석.
- 결정: 개발원칙 4.3절의 Flyway 서술과 달리, 위 사용자 원문에 따라 Flyway를 쓰지 않는다.

## 구성과 형식(공통 형식 판 3 그대로)

- 이력 표 `portal_meta.expand_history`·`portal_meta.contract_history`: `(version BIGINT, attempt INTEGER, script VARCHAR(255), digest CHAR(64), applied_at TIMESTAMP, outcome VARCHAR(10))`, 키 `(version, attempt)`, outcome은 SUCCEEDED·FAILED.
- 시도 한 번 = 트랜잭션 하나. 첫 문장이 `(version, attempt)` 행을 FAILED로 넣고(차지), 마지막 문장이 그 행 하나만 SUCCEEDED로 바꾼다. 실패하면 트랜잭션을 되돌리고 FAILED 행을 별도 트랜잭션에서 남긴다(DDL이 이미 차지 행을 커밋한 DBMS에서는 이미 있으므로 더하지 않는다). 다음 실행은 `attempt + 1`이다. 이 attempt는 그 실행이 적용할 스크립트(pending)를 정한 같은 이력 읽기에서 정한다(그 번호의 최대 attempt + 1, 없으면 1). 적용 직전에 이력을 다시 읽어 구하지 않는다. 다시 구하면, 이력을 읽은 뒤 상대가 같은 번호를 SUCCEEDED로 커밋했을 때 이 실행이 다음 attempt 행을 넣고 스크립트를 한 번 더 적용한다.
- 동시 실행: 같은 번호를 두 실행기가 넣으면 DB가 둘째를 첫째의 커밋까지 기다리게 하고 중복 키를 준다. 실행기에는 sleep·폴링·재시도가 없다. 중복 키를 받으면 이력을 한 번 다시 읽어, 그 번호가 SUCCEEDED이고 digest가 같으면 건너뛰고 아니면 `MIGRATION_FAILED`로 멈춘다(메시지: 상대가 실패했거나 아직 적용 중이다). 한계는 「한계와 운영자 복구」 절.
- 번호는 1부터 빈 번호 없이 연속이다. 아니면 DB에 손대기 전에 `MIGRATION_OUT_OF_ORDER`다. 적용된 최고 번호보다 낮은 미적용 번호도 같다.
- 바뀐 파일(`MIGRATION_CHANGED`)과 jar에 없는 번호(`MIGRATION_MISSING`)는 아무것도 적용하기 전에 멈추고 이력에 행을 더하지 않는다.
- 자리표시자 `${long_text}`는 `DatabaseMetaData.getTypeInfo()`로 고른 형으로 바뀐다(`LongTextType`, 공통 형식 판 3: 대형 문자 객체형(`CLOB`·`LONGVARCHAR` 계열)을 VARCHAR보다 먼저 고르고 `CREATE_PARAMS`가 있다는 이유로 빼지 않는다. 그다음 길이가 필요 없는 VARCHAR, 그다음 가장 큰 VARCHAR. 고른 형의 `CREATE_PARAMS`가 비었으면 길이 없이, 비지 않았으면 드라이버가 보고한 최대 `PRECISION`으로 길이를 적어 선언한다). digest는 치환 전 파일의 SHA-256이다. 설정 키는 없다.
- 시작할 때 DB 인코딩이 UTF-8인지 SQL 표준 질의로 확인한다(`verifyEncoding`).
- 스크립트 위치: `db/expand`(기동마다 적용), `db/contract`(명령으로만 적용. 3.0.0에는 contract 스크립트가 없다). DBMS별 폴더는 없다(D-0088).

## expand 실행 방식 선택

공통 형식 5절: contract는 명시 명령으로만 적용하고, expand를 누가 실행하는지는 제품의 DB 계정 구조로 정한다.

- 확인한 것(코드·설정): 저장소는 Boot의 `spring.datasource.*` 하나로 연결한다. 제품 코드에 DDL 계정과 앱 계정을 나누는 설정, `ROLE`·`GRANT`·`CREATE ROLE` 문은 없다(`java/` 아래 `*.sql`·`*.java`·`*.md`를 `GRANT`·`CREATE ROLE`로 검색, 해당 없음). 이전 Flyway도 같은 `DataSource`로 스키마를 바꿨다.
- 결정: 계정이 하나이므로 AGS 방식이다. 기동마다 expand를 적용한다(`ExpandOnStart`). 실패하면 시작을 멈춘다. 계정 구조는 바꾸지 않았다.
- 시작 순서: `MigrationInitializerDetector`가 Boot의 「데이터베이스 초기화를 기다리는 빈」 확장(`DatabaseInitializerDetector`, `META-INF/spring.factories`)으로 `ExpandOnStart`를 초기화기로 알려, JdbcTemplate·JdbcClient와 그 위의 빈이 expand 뒤에 만들어진다(S-D0109-17).
- contract: `Migrations.contract()`가 있고 기동은 부르지 않는다(S-D0109-16). 이를 부르는 명령줄은 아직 없다(contract 스크립트가 없고, 설치·업그레이드 명령군(feature-map의 `InstallCommand`·`UpgradeCommand`)이 정해진 뒤 연결한다). 미검증.
- 설치 문서: 3.0.0의 설치 문서는 아직 없어 계정 구조를 설치 문서로 대조하지 못했다. 미검증.

## 대응표(이식 출처와 AGS 원본)

출처: AGS(Agent Governance Suite)의 `MigrationRunner`(와 같은 패키지의 `MigrationScript`, `Migrations`, `LongTextType`). 정본은 공통 형식 문서이고, 클래스 구성·이름·메서드 흐름은 원본을 따랐다. 원본과 공통 형식이 다른 부분(이력 PK, 실패 행 삭제, 번호 규칙)은 아래 「공통 형식 반영분」이다.

| AGS 원본 | SSH | 비고 |
|---|---|---|
| `StoreException`·`StoreCode`(`MIGRATION_*`, `DB_ENCODING_MISMATCH`) | `MigrationException`·`MigrationException.Reason`(`MIGRATION_CHANGED`·`MIGRATION_MISSING`·`MIGRATION_OUT_OF_ORDER`·`MIGRATION_FAILED`·`DB_ENCODING_MISMATCH`) | 공개 계약의 오류 코드(`ErrorCode`)는 더하지 않았다. `DatabaseProductException`처럼 시작·설정 실패이므로 `IllegalStateException`의 하위형이다. 오류 카탈로그 형식이 정해지면 이름을 맞춘다. |
| `StoreErrors.map(SQLException, …)` | `MigrationException(MIGRATION_FAILED, …, cause)` | 연결·읽기 실패도 이 코드 |
| `DataSourceFactory.direct(config).getConnection()` | Spring `DataSource.getConnection()`(`spring.datasource.*`) | AGS의 `AgsDatabaseConfig`·`DataSourceFactory`·`JdbcDrivers`는 이식하지 않았다 |
| `DataSourceFactory.verifyEncoding(connection)` | `MigrationRunner.verifyEncoding(connection)` | 같은 질의 |
| `SqlBuilder.ofProductName`·`SqlBuilder.Database`(`ags-api`) | `portal.store.sql.SqlBuilder.ofProductName`·`Database` | 같은 이식본(sqlbuilder 단위). 적용기는 쓰지 않는다(차이 목록 12) |
| `Timestamps.toTimestamp(Instant)` | `Timestamp.valueOf(LocalDateTime.ofInstant(instant, UTC))` | UTC 필드 |
| `Digests.sha256Hex(bytes)` | `Sha256Digester.digest(bytes).hex()`(portal-domain) | 같은 SHA-256 |
| `Migrations(Clock, DataSourceFactory)`·`expand(config)`·`contract(config)` | `Migrations(Clock, DataSource)`·`expand()`·`contract()` | |
| `StorePreparation`·`PreparingDataSource`·`StorePhase`·`PhaseListener`·`PreparedStore*`·`StoreConfiguration` | (이식하지 않음) | 서버가 listener를 먼저 열고 DB를 뒤에 준비하는 AGS 전용 구조다. SSH는 Spring의 초기화 순서(`ExpandOnStart`)를 쓴다 |
| `ags_meta` 스키마 | `portal_meta` 스키마 | |
| `ScriptDialect` | (이식하지 않음) | 차이 목록 12 |

## 차이 목록

공통 형식 반영분(원본에는 아직 없음):

1. 이력 표에 `attempt` 열을 더하고 키를 `(version)`에서 `(version, attempt)`로 바꿨다.
2. 재시도할 때 FAILED 행을 지우던 `DELETE`를 없앴다(원본 `MigrationRunner.apply`). 재시도는 `attempt + 1` 행이다.
3. 마지막 문장의 `UPDATE`가 자기 시도의 행(`WHERE version = ? AND attempt = ?`)만 바꾼다(원본은 `WHERE version = ?`).
4. 번호가 1부터 연속이어야 한다(빈 번호·1이 아닌 시작을 `MIGRATION_OUT_OF_ORDER`로 거절. 원본은 빈 번호를 허용하고 낮은 미적용 번호만 거절). 이 검사는 DB에 손대기 전에 한다.
5. 중복 키를 받은 실행기가 이력을 다시 읽어 판정한다(SUCCEEDED·같은 digest면 건너뜀, 아니면 `MIGRATION_FAILED`). 원본은 `false`를 돌려주고 다음 스크립트로 계속한다.
6. 자리표시자가 `${LONG_TEXT}`가 아니라 `${long_text}`다.

SSH의 사정에 따른 차이:

7. 이력 표의 outcome 검사를 `IN ('SUCCEEDED','FAILED')`가 아니라 `CASE WHEN … END = 1`로 쓴다. 내장 H2 2.4.240은 상수 두 개 이상의 IN 목록을 만든 세션에 붙여 두므로 마이그레이션 연결이 닫힌 뒤의 첫 삽입이 실패한다(`NoInListInChecksTest`가 같은 이유로 스크립트에서 IN을 금지한다). CASE는 표준이고 뜻이 같다.
8. 위 대응표의 형 교체(예외, 연결, 시각, 해시, 스키마 이름)와 이식하지 않은 AGS 전용 클래스.
9. 인코딩 확인 질의가 `CHAR_LENGTH(CAST(? AS CHAR(10)))`가 아니라 `CHAR_LENGTH(TRIM(CAST(? AS CHAR(10))))`다. 내장 H2는 `CHAR(10)`을 10자로 공백 채움하므로 TRIM이 없으면 H2에서 두 글자가 10자로 세어져 UTF-8 DB를 거절한다(첫 실행에서 시험이 잡았다). TRIM은 표준 SQL이고 다른 DBMS의 결과는 같다.
10. `MigrationScript.of(version, file, bytes)` 팩토리를 더했다(시험이 스크립트를 만들 때와 `load`가 쓴다).
11. `ExpandOnStart`·`MigrationConfiguration`·`MigrationInitializerDetector`는 SSH가 Spring 초기화에 연결하려고 더한 것이다(원본에는 `StorePreparation`이 있다).
12. `ScriptDialect`(MariaDB·MySQL에서 `GENERATED BY DEFAULT AS IDENTITY`를 `AUTO_INCREMENT`로, 맨 `TIMESTAMP`를 `DATETIME(3)`으로 고쳐 쓰는 분기)를 이식하지 않았다. 제품 스크립트는 둘 다 쓰지 않으므로(식별 열이 없고 시각은 `TIMESTAMP(6)`) 이 분기가 바꾸는 것은 이력 표의 `TIMESTAMP`뿐이었고, 그것은 다른 DBMS에서만 일어나 시험할 수 없는(개발원칙 7.3) DBMS별 분기다. 이력 표는 ANSI SQL 그대로 쓴다. 이에 따라 적용기는 DBMS 이름(`getDatabaseProductName`, `SqlBuilder.ofProductName`)을 읽지 않는다. 결과의 한계는 「한계와 운영자 복구」 3.

## 긴 텍스트 열

- 목표는 0개(장부 D-0092: 본문을 DB 열에 두지 않음)이지만, 열의 의미를 바꿔야 해서 **바꾸지 않았다**. 이전 판의 벤더 스크립트(`V1_1`·`V6_1`)가 만들던 12개를 같은 이름·같은 NOT NULL 규칙으로 공통 스크립트(`V1`·`V6`)에 `${long_text}`로 옮겼다(시험 S-D0109-21이 12개의 집합을 고정한다).
  - `operation_command`: `command_text`, `caller_context`, `display_context`
  - `operation_source`: `launcher_command`, `argv_json`, `caller_context`, `display_context`
  - `operation_download`: `caller_context`, `display_context`
  - `operation_result`: `result_texts`
  - `operation_legacy`: `original_text`
  - `maintenance_reservation`: `baseline`(null 허용)
- 이 열들은 요청 본문(명령, 호출 맥락, 결과 문장, 2.x 원문, 유지보수 보존 기준)이다. 이를 첨부 메타 표와 파일로 옮기면 저장 계약이 바뀌므로(저장소 어댑터·사용 사례·사례표 전체) 첨부 표 이전(장부 D-0092)에서 따로 다룬다. 이 단위는 열을 그대로 둔다. 같은 이유로 DBMS별 폴더 제거(D-0088)는 이 열들을 `${long_text}`로 쓰는 것으로 끝냈다.
- 번호: 3.0.0은 배포 전이라 `V1_1`·`V6_1`을 없애고 `V1`·`V6` 안으로 합쳐 번호가 1~7로 연속이다(`V2`~`V5`·`V7`의 번호와 이름은 그대로).
- H2에서 `${long_text}`는 `CHARACTER LARGE OBJECT(2147483647)`이다. H2 2.4.240의 `getTypeInfo()`는 `CHARACTER LARGE OBJECT`에 `CREATE_PARAMS = LENGTH`와 `PRECISION = 2147483647`을 보고하고, 판 3 규칙은 이 형을 빼지 않으므로 그 최대 크기를 적어 선언한다. 이전 판의 H2 벤더 스크립트는 `CLOB`이었고, 지금과 열의 의미(NOT NULL, 큰 문자 객체)가 같다. H2가 이 선언을 받아들이는 것은 시험으로 확인했다: 이 선언으로 만든 열에 한글을 포함한 40만 자를 넣고 그대로 읽는다(S-D0109-13 (b)), 열 12개가 모두 이 형이다(S-D0109-21). 규칙이 판 3 이전에는 길이가 필요한 대형 문자 객체형을 빼고 `CHARACTER VARYING(1000000000)`을 골랐다.
- 지금 스크립트에는 `CLOB`·`TEXT` 같은 DBMS의 형 이름이 없다(S-D0109-21). 다른 DBMS의 `${long_text}` 결과(`text`, `LONGTEXT` 등)는 후보 목록 단위 시험(`LongTextTypeTest`)으로만 확인한다. 실제 다른 DBMS에서의 실행은 개발원칙 7.3에 따라 시험하지 않는다.

## 한계와 운영자 복구

1. DDL이 앞 트랜잭션을 커밋하는 DBMS(내장 H2, MariaDB, MySQL)에서의 동시 기동. 차지 행(FAILED)이 스크립트의 첫 DDL 문장과 함께 커밋되어 일찍 보이므로, 같은 순간에 시작한 두 번째 실행기는 DB 잠금에서 기다리지 않는다.
   - 두 번째 실행기가 이력을 읽을 때 차지 행이 이미 커밋되어 있으면 그 번호를 FAILED로 읽고 `attempt + 1`로 같은 DDL을 돌린다. 이미 만든 객체를 다시 만들려다 실패하면 `MIGRATION_FAILED`로 멈춘다.
   - 읽은 뒤에 첫 번째 실행기가 차지 행을 커밋하면 같은 키로 부딪혀 중복 키를 받고, 이력을 다시 읽는다. 첫 번째가 아직 적용 중이면 그 번호는 SUCCEEDED가 아니므로 역시 `MIGRATION_FAILED`로 멈춘다(메시지는 「실패했거나 아직 적용 중」).
   - 어느 쪽이든 스키마는 망가지지 않는다. SUCCEEDED는 스크립트의 마지막 문장 뒤에만 기록되므로 반쯤 적용된 번호가 적용된 것으로 읽히지 않는다. 멈춘 쪽에는 `MIGRATION_FAILED`가 나오고 이력에 FAILED 행이 남을 수 있다.
   - 운영자 복구: 인스턴스 하나를 먼저 기동해 마이그레이션이 끝난 뒤(로그에 시작 완료) 나머지를 기동한다. 멈춘 쪽은 그대로 다시 기동하면 된다. 첫 번째가 끝낸 번호는 SUCCEEDED로 읽혀 건너뛴다.
   - 트랜잭션 안에서 DDL을 되돌릴 수 있는 DBMS(예: PostgreSQL)에서는 차지 행이 스크립트가 끝날 때까지 커밋되지 않아 두 번째 실행기가 잠금에서 기다린다. 이 문장은 DBMS의 일반 동작에 근거하며 개발원칙 7.3에 따라 그 DBMS에서 시험하지 않았다. 미검증.
2. 중간에 실패한 DDL 스크립트. 앞의 DDL 문장이 이미 커밋되어 반쯤 만든 객체가 남는다. 다음 시도가 같은 DDL을 다시 돌리므로 「이미 있다」로 계속 실패하고, 이력에 `(n,1) FAILED`, `(n,2) FAILED`가 쌓인다(S-D0109-28).
   - 운영자 복구: (a) `MIGRATION_FAILED`의 원인(로그의 첫 실패)을 읽는다. (b) 그 스크립트가 만든 객체 중 이미 있는 것을 지운다(표·색인 등). (c) 다시 기동한다. 이력 표의 행은 지우거나 고치지 않는다. 다음 시도는 `attempt + 1` 행으로 남고, 성공하면 SUCCEEDED가 된다.
   - 스크립트 파일은 SUCCEEDED가 된 적이 없으므로 고칠 수 있고, 고쳐도 `MIGRATION_CHANGED`가 아니다.
   - 트랜잭션 DDL을 지원하는 DBMS에서는 실패한 스크립트가 통째로 되돌려지므로 이 복구가 필요 없다(1의 미검증과 같다).
3. MariaDB·MySQL의 `TIMESTAMP`는 SQL 표준의 `TIMESTAMP`와 다른 형이다(UTC로 저장해 세션 시간대로 보여 주고, 범위는 1970-01-01 00:00:01부터 2038년까지). 이력 표의 `applied_at`은 ANSI `TIMESTAMP`로 만들어지므로 그 DBMS에서는 이 범위 안의 값만 담는다(주입한 Clock의 현재 시각). 그 DBMS에서 시험하지 않았다(개발원칙 7.3). 미검증.

## 지운 시험

개발원칙 7.3 「다른 DBMS 지원 확인 시험을 하지 않는다(드라이버는 사용자 몫)」가 그 시험 자체를 금지하므로 지웠다. 시험을 줄이는 것이 아니다(PostgreSQL에서 같은 클래스를 다시 돌리는 시험이었다). 장부 D-0103.

| 지운 것 | 내용 | 근거 |
|---|---|---|
| Gradle 작업 `:portal-store:externalDbmsTest` | `portal.test.dbms.postgresql.url`·`.user`·`.password-file` 속성으로 `@StoreTest` 시험 전체를 PostgreSQL에서 다시 돌리는 작업(`check`·`build`에 붙지 않음) | 7.3 |
| 시험 지원 코드 `store/external/ExternalDbms`, `ExternalDbmsEnvironment`, `ExternalDbmsFlywayConfiguration`, `ExternalDbmsSchemaSession` | 외부 DB 접속·비밀번호 파일 읽기·시험 스키마 초기화·Flyway clean | 7.3, Flyway 제거 |
| 시험 클래스 `ExternalDbmsTest`(7개: S2-DB-16 ×7, 외부 접속 값·비밀번호 파일·스키마 이름·Flyway clean 보호) | 외부 시험 지원 코드의 단위 시험 | 지원 코드가 없어져 대상이 없다 |
| 시험 자원 `META-INF/services/org.junit.platform.launcher.LauncherSessionListener`, `META-INF/spring.factories`(시험) | 외부 시험의 세션 수신기와 환경 후처리기 등록 | 7.3 |
| 의존성 `testRuntimeOnly("org.postgresql:postgresql")`, `testRuntimeOnly("org.flywaydb:flyway-database-postgresql")`, `testImplementation("org.junit.platform:junit-platform-launcher")`(수신기용) | PostgreSQL 드라이버와 Flyway의 PostgreSQL 부품 | 7.3, Flyway 제거. 시험 실행기용 launcher는 루트 빌드가 이미 `testRuntimeOnly`로 둔다 |

- 다시 쓴 것(지우지 않음): `SchemaMigrationTest`(4개), `DbmsPortabilityTest`의 S2-DB-15(1개, Flyway의 적용 목록 대신 이력 표로), `NoInListInChecksTest`(새 폴더), `StoreTest`·`UseCaseTest`(Flyway 자동 설정 대신 적용기). 지운 `ExternalDbmsTest` 7개만큼 시험 수가 줄었고, 그 밖의 시험은 줄지 않았다.
- `SqlBuilderStoreTest`의 javadoc과 `portal-ssh/build.gradle.kts`(`realOpenSshTest` 설명이 `externalDbmsTest`를 예로 듦) 주석의 외부 DBMS 언급은 고쳤다.
- `sqlbuilder-cases.md`의 S-SQLB-10 행과 7·6절의 `externalDbmsTest`·DBMS별 폴더 문장은 이 단위에서 고쳤다. `stage2-design.md` 13.3a와 `stage3-design.md`는 2026-11-12 시점의 설계 기록이라 `externalDbmsTest`·Flyway를 그대로 적고 있다(과거 사실의 기록으로 두었다).

## 시험 방식(공통 시험)

- 같은 번호를 두 실행기로 동시에 적용하는 시험(S-D0109-06)은 시작 정렬에 의존하지 않는다. 시험의 holder 연결이 번호의 차지 행을 커밋하지 않고 잡고 있고, 두 실행기가 모두 그 문장에서 기다리는 것을 DB의 잠금 대기 조회로 확인한다(H2 `INFORMATION_SCHEMA.SESSIONS`의 `BLOCKER_ID`, `SESSION_ID()`: https://h2database.com/html/systemtables.html#sessions). 조건을 기다리고 기한(30초)이 지나면 시험이 실패한다. 고정 sleep은 없다. 결과는 SUCCEEDED 행 하나와 적용 한 번이다.
  - 관측 방법(H2 2.4.240에서 확인): `UPDATE`·`DELETE`의 행 잠금 대기는 `SESSIONS.SESSION_STATE = BLOCKED`와 `BLOCKER_ID`(holder의 세션 번호)로 보이지만, 커밋하지 않은 키를 가진 `INSERT`의 중복 키 대기는 `BLOCKER_ID` 없이 `RUNNING` 상태의 `EXECUTING_STATEMENT`로만 보인다. 차지는 `INSERT`이므로 시험은 「holder가 커밋하지 않은 변경을 가진 동안(`CONTAINS_UNCOMMITTED`) 다른 세션이 차지 `INSERT`를 실행 중」인 세션의 수를 잠금 대기로 센다. 이 `INSERT`는 holder의 트랜잭션이 끝나기 전에는 끝날 수 없다.
  - S-D0109-06·07의 스크립트는 DML(`INSERT`)이다. DDL은 H2에서 차지 행을 일찍 커밋하므로 이 잠금 대기가 생기지 않는다(「한계와 운영자 복구」 1).
- 이력을 읽은 뒤 상대가 먼저 적용하는 순서(S-D0109-25·26)는 시험의 데이터 원본 래퍼(`InterceptingDataSource`)가 둘째 실행기의 이력 읽기 직후 첫 `commit()`에서 latch로 멈추게 하고, 첫째를 끝까지 돌린 뒤 풀어 준다. 기한이 있는 latch만 쓰고 sleep은 없다. 래퍼는 호출을 통과시키기만 하고 대신 답하지 않는다.
- 바뀐 파일, 빈 번호, 실패 뒤 재시도(attempt 2, 3)도 같은 클래스 묶음에서 시험한다.
- 모든 시험은 실제 H2(호환 모드 없음)에서 돌고 모의 객체가 없다.
