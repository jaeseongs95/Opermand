# 3.0.0 SqlBuilder 이식 사례표 (S-SQLB)

작성 기준: 시험을 만들기 전에 이 표를 먼저 쓴다(개발원칙 7.1). 열은 대상·입력·기대·판정이다. 「실행 결과」는 구현 뒤 9절에 채운다.
저장 정책(사용자 2026-11-12): 「h2 기반이긴하지만 dbms 연동 가능하다. <- 이 정책 고수해.」 H2·PostgreSQL·MariaDB(·MySQL) 모두 같은 SQL 문장 구조로 돌고, 달라지는 것은 행 수 제한 구문 하나다.
사용자 지시(2026-11-15): 「sqlBuilder 좋은말할때 써라.」 SQL을 이어 붙이거나 Java에서 결과를 잘라 행 수를 제한하는 자리는 `SqlBuilder`로 바꾼다.

## 1. 출처와 이식 범위

- 출처: AGS(Agent Governance Suite)의 SqlBuilder, 커밋 1ad1b8e7 기준. 경로 `java/ags-api/src/main/java/io/github/jaeseongs95/ags/service/database/SqlBuilder.java`(168줄)와 같은 패키지 test의 `SqlBuilderTest.java`, `SqlBuilderConnectionTest.java`. AGS와 포털 3.0.0은 둘 다 AGPL-3.0-or-later이고 소유자가 같다.
- 포털 위치: `portal-store`의 `io.github.jaeseongs95.portal.store.sql.SqlBuilder`. 세 제품(SSH 포털·장부·AGS)이 같은 SqlBuilder를 쓴다는 사용자 결정(「내가 sql builder도 다 똑같이 쓰라는 이유도 그 이유야.」「결국 차별점은 "맞춤형" 이거든.」)에 따라 클래스 구조·API·`Database` enum 7개·행 수 제한 분기·공식 문서 링크 주석이 AGS 원본 1ad1b8e7과 똑같다. 원본과 다른 곳은 예외 타입 하나뿐이다(패키지 이름과, 존재하지 않는 `StoreCode`를 가리키던 주석 문구는 포털에 맞게 고쳤다).
  - 한때 개인판 범위를 이유로 Oracle·Db2·SQL Server를 뺐다가(커밋 b1594bf, 2dc6836) 리드 정정으로 되돌렸다. 되돌린 커밋이 정본이다.
- 예외: AGS의 `StoreException`·`StoreCode`는 포털의 `DatabaseProductException`과 그 `Reason`으로 바뀐다.
  - 판단: 포털의 공개 오류 코드(`ErrorCode` 8개 범주)는 Contract 2.0의 와이어 값이고 `ErrorCodeBaselineTest`가 개수를 고정한다. 제품 판별 실패·미지원·불일치는 요청 거절이 아니라 시작·설정 실패이므로 `ArtifactStoreCorruptedException`(`IllegalStateException` + `Reason` enum, `PortalException` 아님)과 같은 방식으로 저장소 안의 예외로 둔다. 공개 코드 집합과 `ErrorCodeBaselineTest`는 바뀌지 않는다.
  - 메시지는 `Reason`과 닫힌 집합의 값(제품 enum 이름)만 담는다. 원인 예외는 `cause`로만 단다.
- `SqlBuilder`는 트랜잭션을 열지 않는다(개발원칙 4.4). 호출자의 트랜잭션 연결에 합류하거나 연결을 잠시 빌린다.

## 2. SqlBuilder 자체(단위, 실제 H2 `DataSource`)

| ID | 대상 | 입력 | 기대 | 판정 |
|---|---|---|---|---|
| S-SQLB-01 | 제품별 행 수 제한 구문(`generateLimit(Database, parameter)`) | 7개 제품, 자리표시자 `:count` | H2·PostgreSQL·Oracle·Db2 `FETCH FIRST :count ROWS ONLY`; SQL Server·MariaDB `OFFSET 0 ROWS FETCH NEXT :count ROWS ONLY`; MySQL `LIMIT :count` | 문자열 일치 |
| S-SQLB-02 | Statement 조립(`query`·`orGroup`·`and`·`orderBy`·`append`·`limit`·`build`·`applyLimit`) | `orGroup` 개수 0·1·2·3, 조건 하나, 정렬, 이름 있는 자리표시자와 위치 `?` | 개수 0은 `(1 = 0)`, 나머지는 `(a OR b …)`; 값은 항상 자리표시자; `limit`은 H2에서 `FETCH FIRST :limit ROWS ONLY`를 끝에 붙인다 | 전체 SQL 문자열 일치 |
| S-SQLB-03 | 제품 판별은 처음 한 번 | 8개 스레드가 동시에 `generateLimit("?")`, 풀 8개의 실제 H2 | 모두 `FETCH FIRST ? ROWS ONLY`, 메타데이터 호출 1번 | 반환값, 호출 횟수 |
| S-SQLB-04 | 판별이 잠금 안에서 연결을 기다리지 않는다 | 풀 1개를 트랜잭션이 쥔 채 다른 스레드가 판별을 기다림 | 트랜잭션 안 호출은 같은 연결로 판별을 끝내고, 기다리던 호출도 이어서 끝난다. 교착 없음 | 10초 안에 완료, 메타데이터 호출 1번 |
| S-SQLB-05 | 미지원 제품 거부 | 제품 이름이 지원 목록에 없음(`DatabaseDriver.UNKNOWN`, 실제 H2 연결의 제품 이름만 바꿈) | `DatabaseProductException`의 `Reason`이 `UNSUPPORTED_DATABASE_PRODUCT`; 결과를 저장하지 않아 다음 호출도 다시 판별하고 같은 거부 | `Reason`, 메타데이터 호출 2번 |
| S-SQLB-06 | 판별 실패 거부 | 연결 메타데이터 읽기가 `SQLException`을 던짐 | `Reason`이 `STORE_UNREACHABLE`, `cause`가 원래 `SQLException`; 결과를 저장하지 않아 다음 호출은 다시 판별해 성공 | `Reason`, `cause`, 재호출 성공 |
| S-SQLB-07 | 제품 불일치 거부 | 설정 URL `jdbc:postgresql://…`와 연결 제품 `PostgreSQL`(일치)·`H2`(불일치)·지원 밖 URL | 일치는 통과; 불일치는 `DATABASE_PRODUCT_MISMATCH`이고 메시지에 `POSTGRESQL`과 `H2` 둘 다; 지원 밖 URL은 `UNSUPPORTED_DATABASE_PRODUCT` | `Reason`, 메시지 |
| S-SQLB-08 | 인스턴스 `requireSame(productName)` | 실제 `DataSource`(H2)에 `H2`·`PostgreSQL` | `H2`는 통과, `PostgreSQL`은 `DATABASE_PRODUCT_MISMATCH` | `Reason` |
| S-SQLB-09 | URL로 제품 선택(`ofJdbcUrl`) | `h2`·`postgresql`·`oracle`·`db2`·`sqlserver`·`mariadb`·`mysql` URL과 제품 이름 `PostgreSQL` | 각각 대응 `Database` | 값 일치 |
| S-SQLB-10 | 실제 DBMS에서 행 수 제한 실행(`SqlBuilderStoreTest`, `@StoreTest`) | 슬라이스의 실제 `DataSource`에 행 5개를 넣고 `query("SELECT …").orderBy(…).limit(:limit)`에 3 | 3행만 읽고 그 행은 정렬 앞쪽 3행. 같은 슬라이스에서 연결이 알리는 URL과 제품 이름으로 `requireSame(url, product)`·`requireSame(product)`가 통과한다. 기본 실행은 H2, `externalDbmsTest`는 PostgreSQL에서 같은 시험이 돈다 | 읽은 행 목록, 예외 없음 |

## 3. AgentCliHistoryReader (자리 1)

기존 구현은 문장에 LIMIT가 없고(공통 구문이 아니라는 이유) 추출기가 한 페이지보다 한 행 더 읽고 멈췄으며, 키셋 조건을 문자열 상수로 이어 붙였다. 바꾼 뒤에는 `SqlBuilder`가 문장을 짓고 행 수는 SQL이 제한한다(페이지 크기 + 1행을 요청해 다음 페이지 유무만 판단한다).

| ID | 대상 | 입력 | 기대 | 판정 |
|---|---|---|---|---|
| S-SQLB-11 | 문장 모양 | 첫 페이지, 키셋 페이지(커서 있음) | 첫 페이지는 `… AND o.profile = :profile ORDER BY o.created_at DESC, o.id DESC FETCH FIRST :limit ROWS ONLY`; 키셋 페이지는 `ORDER BY` 앞에 ` AND (o.created_at < :createdAt OR (o.created_at = :createdAt AND o.id < :id))`; 값은 모두 자리표시자 | 전체 SQL 문자열 일치(H2) |
| S-SQLB-12 | 페이지 경계: 정확히 한 페이지 | 한 바인딩에 요청 20개 | 20개를 돌려주고 `next`는 `End` | 항목 수, `next` |
| S-SQLB-13 | 페이지 경계: 한 페이지 + 1 | 요청 21개 | 첫 페이지 20개와 `next = After(20번째의 시각, id)`; 둘째 페이지는 1개이고 `End`; 두 페이지에서 21개가 한 번씩 | 항목, `next`, 합집합 |
| S-SQLB-14 | 키셋 경계: 같은 시각 행이 페이지를 가른다 | 요청 22개, 모두 같은 `created_at`, 20번째·21번째 행이 같은 시각 | 둘째 페이지는 `id`가 더 작은 행만, 건너뛰거나 되풀이한 행 없음 | 항목 순서와 합집합 |
| S-SQLB-15 | 기존 동작 보존 | 기존 RC-A-027(현재 바인딩만), RC-A-028(키셋 20개 페이지·같은 시각·중간 도착), RC-A-029(알 수 없는 커서 거절·다른 범위 빈 페이지·`End`) | 바꾸기 전과 같은 결과 | 기존 시험이 `baseline.log`와 `green.log`에서 모두 통과 |

## 4. JdbcHistoryQuery (자리 2, 결정: 바꾸지 않는다)

`JdbcHistoryQuery`와 `OperationRowRepository`는 SQL 문자열을 이어 붙이지 않고 행 수 제한 구문도 쓰지 않는다. 행 수 제한은 Spring Data JDBC 파생 쿼리의 `Pageable` 인자이고, 방언의 구문은 Spring Data JDBC가 고른다(`OperationRowRepository` 머리 주석). 따라서 「dialect's own limit syntax」는 손으로 만든 방언 분기가 아니라 Spring Data의 것이다. 그대로 둔다.

| ID | 대상 | 입력 | 기대 | 판정 |
|---|---|---|---|---|
| S-SQLB-16 | 기존 동작 보존 | 기존 `JdbcHistoryQueryTest`, `JdbcOperationQueueViewTest` | 바꾸기 전과 같은 결과 | `baseline.log`와 `green.log`에서 모두 통과 |

## 5. 다른 자리 조사(결과는 구현 뒤 9절에 파일:줄로 적는다)

| ID | 대상 | 기대 | 판정 |
|---|---|---|---|
| S-SQLB-17 | `portal-*/src/main`에서 SQL 문자열을 이어 붙이거나 `IN (`·`" AND "`·`" OR "`를 쓰는 곳 | `AgentCliHistoryReader` 하나뿐이며 바뀌었다. `@Query` 문자열은 고정 상수다 | `grep` 목록(9절) |
| S-SQLB-18 | Java에서 결과를 잘라 행 수를 제한하는 곳 | `AgentCliHistoryReader`의 추출기 하나뿐이며 바뀌었다. `JdbcHistoryQuery`·`JdbcOperationQueueView`의 `subList`는 SQL로 이미 제한된 한 페이지 + 1행을 페이지로 나누는 것이다 | 목록(9절) |
| S-SQLB-19 | DBMS 제품명으로 분기하는 곳 | `SqlBuilder` 밖에는 없다(`getDatabaseProductName`·`DatabaseDriver` 사용처) | `grep` 0건(9절) |
| S-SQLB-20 | 시작 시 설정 URL과 실제 연결 제품을 대조하는 자리 | 포털에는 `DataSource`를 시작에서 점검하는 자리가 없다. 만들지 않고 보고만 한다 | 9절 |

## 6. 건드리지 않는 것

- Flyway DBMS별 폴더(장부 D-0088)는 이번 범위가 아니다.
- 저장소에 `@Transactional`·`TransactionTemplate`을 새로 두지 않는다. `SqlBuilder`는 트랜잭션을 열지 않는다.

## 7. 실 DBMS 확인

- `portal-store`의 `externalDbmsTest`는 `@StoreTest` 슬라이스를 PostgreSQL에서 돌린다. S-SQLB-10과 S-SQLB-12~15는 `@StoreTest` 슬라이스를 쓰므로 그 경로에서 PostgreSQL로도 돈다. S-SQLB-11의 문장 모양 단언은 H2 기준이다(PostgreSQL에서는 같은 문장이 실제로 실행되는지를 S-SQLB-12~15가 확인한다).
- 이 작업의 구현 담당은 PostgreSQL 비밀번호 폴더에 접근하지 않으므로(stage2-design 13.3a) 실행하지 않는다. 실행은 메인이 한다. MariaDB·MySQL은 이 PC에 없어 CI 서비스 컨테이너가 생기기 전까지 미검증이다.

## 8. 시험 절차

- 바꾸기 전: 영향받는 기존 시험(`JdbcAgentCliRequestStoreTest`, `JdbcHistoryQueryTest`, `JdbcOperationQueueViewTest`)을 돌려 `baseline.log`로 남긴다.
- 새 시험을 먼저 만들고 구현 전에 돌려 `red.log`(컴파일 실패 red면 그렇다고 적는다)를 남긴다. 구현 뒤 `green.log`, 마지막에 `:portal-store:buildDependents --warning-mode all`을 `bd.log`로 남긴다.

## 9. 실행 결과(2026-11-15, 로그는 `D:\codex\build\ssh-portal-v300-sqlbuilder-logs\`)

| 로그 | 내용 | 결과 |
|---|---|---|
| `baseline.log` | 바꾸기 전 `JdbcAgentCliRequestStoreTest` 7, `JdbcHistoryQueryTest` 4, `JdbcOperationQueueViewTest` 4 | 15개 통과, skip 0 |
| `red.log` | 새 시험만 추가한 상태(`SqlBuilder`·`DatabaseProductException`·`AgentCliHistoryReader#statement` 없음) | 컴파일 실패 red. 시험이 아직 없는 심볼을 가리켜 `cannot find symbol` |
| `green.log` | `*sql.*`·`JdbcAgentCliRequestStoreTest`·`JdbcHistoryQueryTest`·`JdbcOperationQueueViewTest` | 모두 통과, skip 0, javac 경고 0 |
| `bd.log` | `:portal-store:buildDependents :portal-arch-checks:test --warning-mode all` | BUILD SUCCESSFUL. store 314개(skip 7, 기존 NTFS 가정), server 95개(skip 0), arch-checks 74개(skip 0), 실패 0 |
| `green-4products.log`, `bd-4products.log` | 한때 4개 제품으로 줄였던 상태의 기록(리드 정정으로 되돌리기 전) | 참고용 |

- S-SQLB-11은 구현 전에는 `statement` 메서드가 없어 컴파일 실패 red였고, 구현 뒤 통과한다. S-SQLB-12~14와 S-SQLB-15(RC-A-027~029)는 동작 보존 사례라서 바꾸기 전 구현에서도 통과해야 하는 사례이고, 바꾼 뒤에도 통과한다.
- S-SQLB-10(`SqlBuilderStoreTest`)과 S-SQLB-12~15는 `@StoreTest`를 쓰므로 `externalDbmsTest`에서 PostgreSQL로도 돈다. 이 작업에서는 실행하지 않았다(미검증, 7절). MariaDB·MySQL 실 DBMS도 미검증이다.

### 자리 조사 목록(S-SQLB-17~20, 범위: `java/portal-*/src/main`)

| 파일:줄 | 내용 | 처리와 이유 |
|---|---|---|
| `portal-store/.../cli/AgentCliHistoryReader.java:88`, `:97-102` | 키셋 조건을 문자열로 이어 붙이고 추출기로 Java에서 한 페이지 + 1행에서 멈춤 | 바꿈. `SqlBuilder`의 `query`·`and`·`orderBy`·`limit`로 문장을 짓고 행 수는 SQL이 제한한다 |
| `portal-store/.../cli/AgentCliHistoryReader.java:91` | `keys.subList(0, PAGE_SIZE)` | 그대로. SQL이 이미 한 페이지 + 1행으로 제한한 결과에서 마지막 한 행(다음 페이지 유무 표시용)을 떼는 것이다 |
| `portal-store/.../journal/JdbcHistoryQuery.java:27-30`, `:63-83` | 「dialect's own limit syntax」는 Spring Data JDBC 파생 쿼리의 `Pageable`(`PageRequest.ofSize`)이다. 손으로 쓴 SQL도 방언 분기도 없다 | 그대로. 행 수 제한 구문은 Spring Data JDBC 방언이 고른다(`OperationRowRepository` 머리 주석: `@Query` 문자열은 한도를 지원하지 않는다). 키셋 조건을 두 파생 쿼리로 나눠 합치는 것은 파생 쿼리가 OR 조건을 못 써서이고, 합치는 `rows`는 이미 각 쿼리에서 제한된 행이다 |
| `portal-store/.../journal/JdbcHistoryQuery.java:51` | `rows.subList(0, pageSize)` | 그대로. 위와 같은 이유(다음 페이지 유무 행을 뗌) |
| `portal-store/.../journal/JdbcOperationQueueView.java:57`, `:101` | `PageRequest.ofSize` | 그대로. Spring Data 파생 쿼리의 `Pageable` |
| `portal-store/.../journal/OperationRowRepository.java:32` | `@Query` 고정 상수의 `NOT IN (SELECT ...)` | 그대로. 값이 없는 고정 ANSI 문장이다 |
| `portal-store/.../*/…RowRepository.java`의 나머지 `@Query` | 모두 고정 상수 문장(`UPDATE … WHERE version = :expected`, `DELETE`, `SELECT … ORDER BY`) | 그대로. 이어 붙임·행 수 제한·제품 분기가 없다 |
| `portal-app/.../history/HistoryService.java:73` | `listed.subList(0, min(PAGE_SIZE, listed.size()))` | 그대로. 저장소가 아닌 사용 사례 계층에서, 이미 걸러진 항목에서 응답 크기 한도와 페이지 크기로 나누는 업무 규칙이다(RC-B-394, RC-B-399). DB 행 수 제한이 아니다 |
| `portal-*/src/main` 전체 | 제품명 분기(`getDatabaseProductName`, `DatabaseDriver`, `getTypeInfo`, `setMaxRows`) | `SqlBuilder` 밖에는 0건(`grep` 확인). 새로 들어온 곳은 `SqlBuilder`뿐이다 |
| 시작 시 설정 URL ↔ 실제 연결 제품 대조 | 포털에는 `DataSource`를 시작에서 점검하는 자리가 없다(`ArtifactStartupCheck`·`OutputStartupCheck`는 파일 쪽만 본다) | 만들지 않았다. `SqlBuilder.requireSame(jdbcUrl, productName)`은 이식되어 있고 `SqlBuilderStoreTest`가 실제 연결로 호출한다. 시작 점검에 연결하는 것은 별도 결정이다 |
