# 일정 관리 탭 (schedule) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 로그인 유저가 자기 일정을 프로젝트 > 마일스톤(재귀) > 할 일 트리와 월 달력으로 관리하는 탭을 app-mvc + front에 추가한다.

**Architecture:** app-mvc에 blog와 같은 모양의 새 도메인 `com.woobeee.mvc.schedule`을 만든다 (Flyway V8, JPA 엔티티, 네이티브 SQL 리포지토리, 단일 컨트롤러, 코드-키 에러 봉투). 프론트는 `/schedule` 한 페이지에 트리 섹션 위 + 월 달력 섹션 아래로 그리고, 판단 로직은 전부 React-free `front/lib/`에 둔다.

**Tech Stack:** Java 25 / Spring Boot 4.0.5 / JPA(validate) / Flyway / PostgreSQL, Next.js 14 / React 18 / TypeScript / vitest / shadcn-ui

**Spec:** `docs/superpowers/specs/2026-09-01-schedule-tab-design.md`

## Global Constraints

- 테이블명은 `projects` / `milestones` / `tasks` — `schedule_` 접두사 없음. **DB FK 제약 금지** — 참조 무결성은 전부 서비스 계층에서 검증한다.
- JPA는 `validate` 전용. 엔티티와 V8 마이그레이션이 함께 가야 하고 `SchemaValidationTest`가 게이트다.
- 쿼리 규칙: PK/단일 컬럼 단건은 파생 메서드, 목록·재귀·집계는 `@Query(nativeQuery = true)`. QueryDSL 신규 사용 금지. 루프 안 단건 조회(N+1) 금지 — 배치 IN.
- 에러 봉투: `header.message`에 **코드 키**(`schedule_camelCase`)만 싣는다. 예외 메시지·내부 문구를 응답에 싣지 않는다. `ScheduleErrorCode` ↔ `front/lib/errors/error-messages.ts`(ko/en)는 양방향 일치해야 한다.
- 마일스톤 최대 깊이 5 (`MAX_MILESTONE_DEPTH = 5`, 서버·프론트 동일 값). 색 팔레트 12색은 서버 `ScheduleColors.PALETTE`와 프론트 `SCHEDULE_COLORS`가 동일해야 한다 (스펙 §4가 단일 출처).
- 프론트 판단 로직은 컴포넌트가 아니라 `front/lib/`의 React-free 모듈에 두고 `lib/*.test.ts`로 고정한다.
- 검증 명령: `./mvnw -pl core,app-mvc,app-webflux -am test` (PostgreSQL :9432 필요), `cd front && npm test && npm run build`. 기존 358개 그린 + 신규 테스트 그린을 유지해야 한다.
- 커밋 메시지는 기존 관례(`feat(schedule): ...`, `docs(schedule): ...`)를 따르고 Co-Authored-By 푸터를 붙인다.
- 로컬 인프라: `docker compose -f .docker-compose/docker-compose.yml up -d postgres-management redis minio` 가 떠 있어야 백엔드 테스트가 돈다.

---

### Task 1: `docs/schedule/PRD.md` — 인수 기준(AC) 표 먼저

**Files:**
- Create: `docs/schedule/PRD.md`

**Interfaces:**
- Produces: `SCHEDULE-AC-01` ~ `SCHEDULE-AC-20` — 이후 모든 태스크의 테스트가 이 ID를 메서드명/주석으로 참조한다.

레포 규칙 "동작/API 계약을 바꾸면 먼저 AC 표를 갱신하고 그 AC를 커버하는 테스트를 추가한다"를 새 도메인에서 처음부터 지킨다.

- [ ] **Step 1: PRD 작성**

```markdown
# schedule PRD — 일정 관리

## 개요

로그인한 유저가 **자기 일정만** 관리한다. 구조는 프로젝트 > 마일스톤(재귀, 깊이 ≤ 5) > 할 일.
할 일은 프로젝트 직속 또는 아무 깊이의 마일스톤 아래에 붙는다. 상태(시작전/진행중/완료)와
날짜 범위(종료 미정 허용)는 세 층 모두 각자 가진다. 할 일은 고유색을 가지고 달력에 표시된다.

- 백엔드: app-mvc `com.woobeee.mvc.schedule`, 베이스 경로 `/api/back/schedule`
- 프론트: `/schedule` — 트리 리스트(위) + 월 달력(아래), 상태 필터는 둘 다 적용
- DB FK 제약 없음: 참조 무결성은 서비스 계층이 검증한다
- 설계 근거: `docs/superpowers/specs/2026-09-01-schedule-tab-design.md`

## 인수 기준 (Acceptance Criteria)

| ID | 시나리오 | 기대 결과 |
| --- | --- | --- |
| SCHEDULE-AC-01 | 토큰 없이(또는 무효 토큰으로) `/api/back/schedule/*` 호출 | 401 + `schedule_unauthorized` 봉투 |
| SCHEDULE-AC-02 | `GET /tree` | 본인 `member_id` 프로젝트만, 프로젝트>마일스톤(재귀)>할 일 중첩 구조로 반환 |
| SCHEDULE-AC-03 | 남의 프로젝트(또는 그 하위)에 수정/삭제/생성 시도 | 404 + `schedule_projectNotFound` — 존재 여부를 흘리지 않는다 |
| SCHEDULE-AC-04 | 프로젝트 생성 시 status 생략 | `NOT_STARTED`로 저장 |
| SCHEDULE-AC-05 | 존재하지 않는 `projectId`/`parentId`/`milestoneId` 참조 | 404 + `schedule_projectNotFound`/`schedule_milestoneNotFound` |
| SCHEDULE-AC-06 | `parentId`·`milestoneId`가 요청의 프로젝트와 다른 프로젝트 소속 | 400 + `schedule_crossProject` |
| SCHEDULE-AC-07 | 마일스톤 깊이 5 초과 생성/이동 | 400 + `schedule_depthExceeded` |
| SCHEDULE-AC-08 | 마일스톤을 자기 자신/자기 자손 아래로 이동 | 400 + `schedule_cycle` |
| SCHEDULE-AC-09 | 할 일 생성 | 12색 팔레트 중 하나가 자동 배정되어 응답에 포함 |
| SCHEDULE-AC-10 | `#RRGGBB` 형식이 아닌 색으로 수정 | 400 + `schedule_invalidColor` |
| SCHEDULE-AC-11 | `endDate < startDate` | 400 + `schedule_invalidDateRange` |
| SCHEDULE-AC-12 | 프로젝트 삭제 | 하위 마일스톤·할 일 전부 함께 삭제, 한 트랜잭션 |
| SCHEDULE-AC-13 | 마일스톤 삭제 | 자기+자손 마일스톤과 거기 달린 할 일 전부 삭제 |
| SCHEDULE-AC-14 | `GET /tree`의 저장소 접근 | 조회 3회(프로젝트/마일스톤/할 일), 루프 내 단건 조회 없음 |
| SCHEDULE-AC-15 | `ScheduleErrorCode` ↔ `error-messages.ts` | ko/en 모두 양방향 일치 (없는 코드도, 죽은 키도 실패) |
| SCHEDULE-AC-16 | bean validation 실패(빈 name 등)·깨진 JSON | 400 + `schedule_badRequest` 봉투 |
| SCHEDULE-AC-17 | `filterTree(tree, status)` | 자기 상태가 일치하는 노드와 그 조상 체인만 남고, 조상은 `dimmed` 표시 |
| SCHEDULE-AC-18 | `calendarLayout` 월 경계 | 월 밖 구간은 잘리고 `continuesLeft`/`continuesRight`로 이어짐을 표시 |
| SCHEDULE-AC-19 | `calendarLayout` 종료 미정 할 일 | 시작일부터 월 말까지 깔리고 `openEnded` 플래그 |
| SCHEDULE-AC-20 | `formatDateRange` | 미정은 `미정`, 올해 날짜는 연도 생략(`08.20`), 다른 해는 `25.12.31` |
```

- [ ] **Step 2: 커밋**

```bash
git add docs/schedule/PRD.md
git commit -m "docs(schedule): PRD와 인수 기준 표 추가"
```

---

### Task 2: Flyway V8 + 엔티티 3개 + 스키마 검증

**Files:**
- Create: `app-mvc/src/main/resources/db/migration/V8__schedule.sql`
- Create: `app-mvc/src/main/java/com/woobeee/mvc/schedule/entity/ScheduleStatus.java`
- Create: `app-mvc/src/main/java/com/woobeee/mvc/schedule/entity/Projects.java`
- Create: `app-mvc/src/main/java/com/woobeee/mvc/schedule/entity/Milestones.java`
- Create: `app-mvc/src/main/java/com/woobeee/mvc/schedule/entity/Tasks.java`
- Test: 기존 `app-mvc/src/test/java/com/woobeee/mvc/SchemaValidationTest.java` (수정 없음 — `@EntityScan("com.woobeee.mvc")`이 새 엔티티를 자동으로 집는다)

**Interfaces:**
- Produces: 엔티티 `Projects`/`Milestones`/`Tasks` — 정적 팩토리 `create(...)`, 도메인 메서드 `update(...)`, `Tasks.create`는 색을 받는다. 이후 태스크의 시그니처:
  - `Projects.create(Long memberId, String name, ScheduleStatus status, LocalDate startDate, LocalDate endDate)`
  - `Projects.update(String name, ScheduleStatus status, LocalDate startDate, LocalDate endDate)`
  - `Milestones.create(Long projectId, Long parentId, String name, ScheduleStatus status, LocalDate startDate, LocalDate endDate)`
  - `Milestones.update(Long parentId, String name, ScheduleStatus status, LocalDate startDate, LocalDate endDate)`
  - `Tasks.create(Long projectId, Long milestoneId, String name, ScheduleStatus status, LocalDate startDate, LocalDate endDate, String color)`
  - `Tasks.update(Long milestoneId, String name, ScheduleStatus status, LocalDate startDate, LocalDate endDate, String color)`
  - 모두 `@Getter`

- [ ] **Step 1: V8 마이그레이션 작성** (identity 전략·타입 표기는 `V2__game.sql`과 동일하게)

```sql
CREATE TABLE projects (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    member_id BIGINT NOT NULL,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED'
        CHECK (status IN ('NOT_STARTED', 'IN_PROGRESS', 'DONE')),
    start_date DATE,
    end_date DATE,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6),
    CHECK (end_date IS NULL OR start_date IS NULL OR end_date >= start_date)
);

CREATE TABLE milestones (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    project_id BIGINT NOT NULL,
    parent_id BIGINT,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED'
        CHECK (status IN ('NOT_STARTED', 'IN_PROGRESS', 'DONE')),
    start_date DATE,
    end_date DATE,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6),
    CHECK (end_date IS NULL OR start_date IS NULL OR end_date >= start_date)
);

CREATE TABLE tasks (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    project_id BIGINT NOT NULL,
    milestone_id BIGINT,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED'
        CHECK (status IN ('NOT_STARTED', 'IN_PROGRESS', 'DONE')),
    start_date DATE,
    end_date DATE,
    color VARCHAR(7) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6),
    CHECK (end_date IS NULL OR start_date IS NULL OR end_date >= start_date)
);

CREATE INDEX idx_projects_member_id ON projects (member_id);
CREATE INDEX idx_milestones_project_id ON milestones (project_id);
CREATE INDEX idx_tasks_project_id ON tasks (project_id);
```

FK 제약을 두지 않는 것은 사용자 결정이다(스펙 §1). `ON DELETE CASCADE`도 없다 — 삭제는 Task 5의 서비스가 명시적으로 한다.

- [ ] **Step 2: ScheduleStatus enum**

```java
package com.woobeee.mvc.schedule.entity;

public enum ScheduleStatus {
    NOT_STARTED,
    IN_PROGRESS,
    DONE
}
```

- [ ] **Step 3: 엔티티 3개 작성** (`Member.java` 스타일: `@NoArgsConstructor(PROTECTED)` + private `@Builder` 생성자 + 정적 팩토리, 연관관계 대신 raw `Long` FK)

`Projects.java`:

```java
package com.woobeee.mvc.schedule.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "projects")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Projects {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long memberId;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScheduleStatus status;

    private LocalDate startDate;

    private LocalDate endDate;

    @Column(nullable = false)
    private int sortOrder;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Builder
    private Projects(Long memberId, String name, ScheduleStatus status,
                     LocalDate startDate, LocalDate endDate, int sortOrder) {
        this.memberId = memberId;
        this.name = name;
        this.status = status;
        this.startDate = startDate;
        this.endDate = endDate;
        this.sortOrder = sortOrder;
    }

    public static Projects create(Long memberId, String name, ScheduleStatus status,
                                  LocalDate startDate, LocalDate endDate) {
        return Projects.builder()
                .memberId(memberId)
                .name(name)
                .status(status == null ? ScheduleStatus.NOT_STARTED : status)
                .startDate(startDate)
                .endDate(endDate)
                .sortOrder(0)
                .build();
    }

    public void update(String name, ScheduleStatus status, LocalDate startDate, LocalDate endDate) {
        this.name = name;
        this.status = status;
        this.startDate = startDate;
        this.endDate = endDate;
    }
}
```

`Milestones.java` — 동일 골격에 필드만 다르다:

```java
package com.woobeee.mvc.schedule.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "milestones")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Milestones {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    /** NULL = 프로젝트 직속. 셀프 참조(재귀 트리). */
    private Long parentId;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScheduleStatus status;

    private LocalDate startDate;

    private LocalDate endDate;

    @Column(nullable = false)
    private int sortOrder;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Builder
    private Milestones(Long projectId, Long parentId, String name, ScheduleStatus status,
                       LocalDate startDate, LocalDate endDate, int sortOrder) {
        this.projectId = projectId;
        this.parentId = parentId;
        this.name = name;
        this.status = status;
        this.startDate = startDate;
        this.endDate = endDate;
        this.sortOrder = sortOrder;
    }

    public static Milestones create(Long projectId, Long parentId, String name,
                                    ScheduleStatus status, LocalDate startDate, LocalDate endDate) {
        return Milestones.builder()
                .projectId(projectId)
                .parentId(parentId)
                .name(name)
                .status(status == null ? ScheduleStatus.NOT_STARTED : status)
                .startDate(startDate)
                .endDate(endDate)
                .sortOrder(0)
                .build();
    }

    public void update(Long parentId, String name, ScheduleStatus status,
                       LocalDate startDate, LocalDate endDate) {
        this.parentId = parentId;
        this.name = name;
        this.status = status;
        this.startDate = startDate;
        this.endDate = endDate;
    }
}
```

`Tasks.java`:

```java
package com.woobeee.mvc.schedule.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "tasks")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tasks {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 마일스톤 몇 단 아래에 있든 유저의 전체 할 일(달력)을 조인 한 번에 가져오기 위한 중복 보유. */
    @Column(nullable = false)
    private Long projectId;

    /** NULL = 프로젝트 직속. */
    private Long milestoneId;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScheduleStatus status;

    private LocalDate startDate;

    private LocalDate endDate;

    @Column(nullable = false, length = 7)
    private String color;

    @Column(nullable = false)
    private int sortOrder;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Builder
    private Tasks(Long projectId, Long milestoneId, String name, ScheduleStatus status,
                  LocalDate startDate, LocalDate endDate, String color, int sortOrder) {
        this.projectId = projectId;
        this.milestoneId = milestoneId;
        this.name = name;
        this.status = status;
        this.startDate = startDate;
        this.endDate = endDate;
        this.color = color;
        this.sortOrder = sortOrder;
    }

    public static Tasks create(Long projectId, Long milestoneId, String name,
                               ScheduleStatus status, LocalDate startDate, LocalDate endDate,
                               String color) {
        return Tasks.builder()
                .projectId(projectId)
                .milestoneId(milestoneId)
                .name(name)
                .status(status == null ? ScheduleStatus.NOT_STARTED : status)
                .startDate(startDate)
                .endDate(endDate)
                .color(color)
                .sortOrder(0)
                .build();
    }

    public void update(Long milestoneId, String name, ScheduleStatus status,
                       LocalDate startDate, LocalDate endDate, String color) {
        this.milestoneId = milestoneId;
        this.name = name;
        this.status = status;
        this.startDate = startDate;
        this.endDate = endDate;
        this.color = color;
    }
}
```

- [ ] **Step 4: 마이그레이션 적용 + 스키마 검증 실행**

`DefaultCategoriesSeedTest`가 flyway를 켜고 돌므로 V8이 로컬 Postgres(:9432)에 적용되고, 이어 `SchemaValidationTest`가 엔티티↔스키마 정합을 검증한다:

```bash
./mvnw -pl app-mvc -am test -Dtest='DefaultCategoriesSeedTest,SchemaValidationTest'
```

Expected: 둘 다 PASS. (컬럼 타입·NOT NULL이 엔티티와 어긋나면 여기서 부팅 실패로 잡힌다.)

- [ ] **Step 5: 커밋**

```bash
git add app-mvc/src/main/resources/db/migration/V8__schedule.sql app-mvc/src/main/java/com/woobeee/mvc/schedule/entity/
git commit -m "feat(schedule): V8 마이그레이션과 schedule 엔티티 추가"
```

---

### Task 3: 에러 계약 — ScheduleErrorCode + Advice + 프론트 메시지 지도

**Files:**
- Create: `app-mvc/src/main/java/com/woobeee/mvc/schedule/exception/ScheduleErrorCode.java`
- Create: `app-mvc/src/main/java/com/woobeee/mvc/schedule/exception/ScheduleException.java`
- Create: `app-mvc/src/main/java/com/woobeee/mvc/schedule/exception/ScheduleControllerAdvice.java`
- Modify: `front/lib/errors/error-messages.ts` (ko 블록과 en 블록 양쪽)
- Test: `app-mvc/src/test/java/com/woobeee/mvc/schedule/exception/ScheduleErrorCodeTest.java`

**Interfaces:**
- Produces: `ScheduleErrorCode` — `status()`, `code()`, `asException()`. `ScheduleException extends RuntimeException` — `errorCode()`. 이후 서비스(Task 5)가 `ScheduleErrorCode.PROJECT_NOT_FOUND.asException()` 형태로 던진다.

- [ ] **Step 1: 실패하는 테스트 작성** — `GameErrorCodeTest`와 같은 3검사 (AC-15)

```java
package com.woobeee.mvc.schedule.exception;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleErrorCodeTest {

    /** surefire 는 모듈 디렉터리에서 돈다. */
    private static final Path FRONT_MESSAGES =
            Path.of("..", "front", "lib", "errors", "error-messages.ts");

    /** SCHEDULE-AC-15 */
    @Test
    void everyCodeIsDistinct() {
        List<String> codes = Arrays.stream(ScheduleErrorCode.values())
                .map(ScheduleErrorCode::code).toList();

        assertThat(codes).doesNotHaveDuplicates();
    }

    /** SCHEDULE-AC-15 — 코드는 접두사로 도메인을 드러낸다. */
    @Test
    void everyCodeIsNamespacedToTheScheduleDomain() {
        assertThat(Arrays.stream(ScheduleErrorCode.values()).map(ScheduleErrorCode::code))
                .allSatisfy(code -> assertThat(code).startsWith("schedule_"));
    }

    /** SCHEDULE-AC-15 — enum ↔ TS 지도 양방향 대조. 한 방향만 보면 죽은 키를 못 잡는다. */
    @Test
    void theFrontMapAndTheEnumAgreeInBothDirections() throws IOException {
        Assumptions.assumeTrue(Files.exists(FRONT_MESSAGES), "front/ is not checked out");
        String source = Files.readString(FRONT_MESSAGES, StandardCharsets.UTF_8);

        int koStart = source.indexOf("ko: {");
        int enStart = source.indexOf("en: {");
        assertThat(koStart).isNotNegative();
        assertThat(enStart).isGreaterThan(koStart);

        Set<String> declared = Arrays.stream(ScheduleErrorCode.values())
                .map(ScheduleErrorCode::code)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(scheduleKeysIn(source.substring(koStart, enStart)))
                .as("ko: keys must match ScheduleErrorCode exactly")
                .containsExactlyInAnyOrderElementsOf(declared);
        assertThat(scheduleKeysIn(source.substring(enStart)))
                .as("en: keys must match ScheduleErrorCode exactly")
                .containsExactlyInAnyOrderElementsOf(declared);
    }

    private static Set<String> scheduleKeysIn(String block) {
        Matcher matcher = Pattern.compile("\"\\s*(schedule_[A-Za-z0-9_]+)\\s*\"\\s*:").matcher(block);
        Set<String> keys = new LinkedHashSet<>();
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }
}
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./mvnw -pl app-mvc -am test -Dtest='ScheduleErrorCodeTest'`
Expected: COMPILATION ERROR — `ScheduleErrorCode` 미존재.

- [ ] **Step 3: ScheduleErrorCode + ScheduleException 구현**

```java
package com.woobeee.mvc.schedule.exception;

import org.springframework.http.HttpStatus;

/**
 * schedule API 가 실패 응답에 싣는 코드 목록. GameErrorCode 와 같은 계약 방식이다:
 * front/lib/api.ts 는 실패 응답의 header.message 를 코드로 읽고
 * front/lib/errors/error-messages.ts 에서 문구를 찾는다. 값을 추가하면 그 파일에도
 * 함께 추가해야 한다 (ScheduleErrorCodeTest 가 양방향으로 강제한다).
 */
public enum ScheduleErrorCode {
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "schedule_unauthorized", "Access token is required"),
    MEMBER_NOT_FOUND(HttpStatus.UNAUTHORIZED, "schedule_memberNotFound", "Member not found"),

    PROJECT_NOT_FOUND(HttpStatus.NOT_FOUND, "schedule_projectNotFound", "Project not found or not yours"),
    MILESTONE_NOT_FOUND(HttpStatus.NOT_FOUND, "schedule_milestoneNotFound", "Milestone not found"),
    TASK_NOT_FOUND(HttpStatus.NOT_FOUND, "schedule_taskNotFound", "Task not found"),

    CROSS_PROJECT(HttpStatus.BAD_REQUEST, "schedule_crossProject", "Referenced node belongs to another project"),
    DEPTH_EXCEEDED(HttpStatus.BAD_REQUEST, "schedule_depthExceeded", "Milestone depth may not exceed 5"),
    CYCLE(HttpStatus.BAD_REQUEST, "schedule_cycle", "A milestone cannot move under itself or its descendant"),
    INVALID_DATE_RANGE(HttpStatus.BAD_REQUEST, "schedule_invalidDateRange", "endDate must not be before startDate"),
    INVALID_COLOR(HttpStatus.BAD_REQUEST, "schedule_invalidColor", "Color must be #RRGGBB"),

    BAD_REQUEST(HttpStatus.BAD_REQUEST, "schedule_badRequest", "Malformed request"),
    UNEXPECTED(HttpStatus.INTERNAL_SERVER_ERROR, "schedule_unexpected", "Unexpected server error");

    private final HttpStatus status;
    private final String code;
    private final String reason;

    ScheduleErrorCode(HttpStatus status, String code, String reason) {
        this.status = status;
        this.code = code;
        this.reason = reason;
    }

    public HttpStatus status() {
        return status;
    }

    /** 응답 본문의 header.message 로 나가는 값. */
    public String code() {
        return code;
    }

    /** 로그와 예외 메시지용 영어 설명. 응답에는 나가지 않는다. */
    public String reason() {
        return reason;
    }

    public ScheduleException asException() {
        return new ScheduleException(this);
    }
}
```

```java
package com.woobeee.mvc.schedule.exception;

public class ScheduleException extends RuntimeException {
    private final ScheduleErrorCode errorCode;

    public ScheduleException(ScheduleErrorCode errorCode) {
        super(errorCode.reason());
        this.errorCode = errorCode;
    }

    public ScheduleErrorCode errorCode() {
        return errorCode;
    }
}
```

- [ ] **Step 4: ScheduleControllerAdvice 구현** (`GameExceptionHandler` 모양의 MVC 버전)

```java
package com.woobeee.mvc.schedule.exception;

import com.woobeee.core.api.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;

/**
 * schedule API 의 실패 응답을 ApiResponse 봉투 + 코드 키로 맞춘다.
 * app-mvc 의 알려진 결함(header.message 에 영어 문장 → 프론트 코드 키와 불일치)을
 * 새 도메인에서 반복하지 않기 위한 것으로, app-webflux 의 GameExceptionHandler 를 옮긴 모양이다.
 */
@RestControllerAdvice(basePackages = "com.woobeee.mvc.schedule")
@Slf4j
public class ScheduleControllerAdvice {

    @ExceptionHandler(ScheduleException.class)
    public ResponseEntity<ApiResponse<LocalDateTime>> handleScheduleException(ScheduleException ex) {
        log.debug("schedule api rejected a request: {}", ex.getMessage());
        return envelope(ex.errorCode());
    }

    /** bean validation 실패와 깨진 JSON — 상태는 400, 코드는 폴백. */
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<LocalDateTime>> handleBadRequest(Exception ex) {
        log.debug("schedule api rejected a malformed request: {}", ex.getMessage());
        return envelope(ScheduleErrorCode.BAD_REQUEST);
    }

    /** 그 밖의 모든 것. 예외 메시지는 절대 본문에 싣지 않는다 — 진단은 로그에서 한다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<LocalDateTime>> handleUnexpected(Exception ex) {
        log.error("schedule api failed unexpectedly", ex);
        return envelope(ScheduleErrorCode.UNEXPECTED);
    }

    private ResponseEntity<ApiResponse<LocalDateTime>> envelope(ScheduleErrorCode errorCode) {
        return ResponseEntity
                .status(errorCode.status())
                .body(ApiResponse.fail(errorCode.status(), errorCode.code()));
    }
}
```

- [ ] **Step 5: error-messages.ts에 12개 키 추가** — ko 블록의 `game_unexpected` 줄 뒤에:

```ts
        // 일정 (app-mvc schedule) — 코드 목록은 ScheduleErrorCode.java 와 1:1 이다.
        "schedule_unauthorized": "로그인이 필요합니다.",
        "schedule_memberNotFound": "회원 정보를 찾을 수 없습니다. 다시 로그인해 주세요.",
        "schedule_projectNotFound": "프로젝트를 찾을 수 없습니다.",
        "schedule_milestoneNotFound": "마일스톤을 찾을 수 없습니다.",
        "schedule_taskNotFound": "할 일을 찾을 수 없습니다.",
        "schedule_crossProject": "다른 프로젝트의 항목은 참조할 수 없습니다.",
        "schedule_depthExceeded": "마일스톤은 5단계까지만 만들 수 있습니다.",
        "schedule_cycle": "마일스톤을 자기 자신이나 하위 마일스톤 아래로 옮길 수 없습니다.",
        "schedule_invalidDateRange": "종료일은 시작일보다 빠를 수 없습니다.",
        "schedule_invalidColor": "색상은 #RRGGBB 형식이어야 합니다.",
        "schedule_badRequest": "요청 내용을 다시 확인해 주세요.",
        "schedule_unexpected": "서버에서 문제가 발생했습니다. 잠시 후 다시 시도해 주세요.",
```

en 블록의 대응 위치에:

```ts
        // Schedule (app-mvc) — mirrors ScheduleErrorCode.java one-for-one.
        "schedule_unauthorized": "Please log in.",
        "schedule_memberNotFound": "Member not found. Please sign in again.",
        "schedule_projectNotFound": "Project not found.",
        "schedule_milestoneNotFound": "Milestone not found.",
        "schedule_taskNotFound": "Task not found.",
        "schedule_crossProject": "You cannot reference an item from another project.",
        "schedule_depthExceeded": "Milestones can be nested at most 5 levels deep.",
        "schedule_cycle": "A milestone cannot be moved under itself or its descendant.",
        "schedule_invalidDateRange": "The end date cannot be before the start date.",
        "schedule_invalidColor": "Color must be in #RRGGBB format.",
        "schedule_badRequest": "Please check your request and try again.",
        "schedule_unexpected": "Something went wrong on the server. Please try again shortly.",
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./mvnw -pl app-mvc -am test -Dtest='ScheduleErrorCodeTest'` 그리고 `cd front && npm test`
Expected: 백엔드 3개 PASS, 프론트 타입 검사 + 기존 스위트 그린.

- [ ] **Step 7: 커밋**

```bash
git add app-mvc/src/main/java/com/woobeee/mvc/schedule/exception/ app-mvc/src/test/java/com/woobeee/mvc/schedule/ front/lib/errors/error-messages.ts
git commit -m "feat(schedule): 에러 코드 계약과 봉투 어드바이스, 프론트 메시지 지도 추가"
```

---

### Task 4: 리포지토리 3개 (네이티브 SQL) + 실 Postgres 테스트

**Files:**
- Create: `app-mvc/src/main/java/com/woobeee/mvc/schedule/repository/ProjectRepository.java`
- Create: `app-mvc/src/main/java/com/woobeee/mvc/schedule/repository/MilestoneRepository.java`
- Create: `app-mvc/src/main/java/com/woobeee/mvc/schedule/repository/TaskRepository.java`
- Test: `app-mvc/src/test/java/com/woobeee/mvc/schedule/repository/ScheduleRepositoryTest.java`

**Interfaces:**
- Produces (Task 5의 서비스가 쓰는 시그니처):
  - `ProjectRepository.findAllForMember(Long memberId): List<Projects>`
  - `MilestoneRepository.findAllForProjects(List<Long> projectIds): List<Milestones>`
  - `MilestoneRepository.findAllForProject(Long projectId): List<Milestones>`
  - `MilestoneRepository.findSelfAndDescendantIds(Long milestoneId): List<Long>`
  - `MilestoneRepository.deleteAllByIds(List<Long> ids)` / `deleteAllForProject(Long projectId)` (`@Modifying`)
  - `TaskRepository.findAllForProjects(List<Long> projectIds): List<Tasks>`
  - `TaskRepository.deleteAllForProject(Long projectId)` / `deleteAllForMilestones(List<Long> milestoneIds)` (`@Modifying`)
  - 단건은 상속받은 `findById` 파생 메서드.

- [ ] **Step 1: 실패하는 테스트 작성** — 실 Postgres(:9432), flyway 활성, `@Transactional` 롤백. FK가 없으므로 member 행 없이 임의 `member_id`로 삽입 가능하다.

```java
package com.woobeee.mvc.schedule.repository;

import com.woobeee.mvc._common.config.QuerydslConfig;
import com.woobeee.mvc.schedule.entity.Milestones;
import com.woobeee.mvc.schedule.entity.Projects;
import com.woobeee.mvc.schedule.entity.ScheduleStatus;
import com.woobeee.mvc.schedule.entity.Tasks;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig
@EnableAutoConfiguration
@EntityScan(basePackages = "com.woobeee.mvc")
@EnableJpaRepositories(basePackages = "com.woobeee.mvc.schedule.repository")
@Import(QuerydslConfig.class)
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:9432/market",
        "spring.datasource.username=root",
        "spring.datasource.password=123456789",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.datasource.type=org.apache.commons.dbcp2.BasicDataSource",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
class ScheduleRepositoryTest {

    @Autowired ProjectRepository projectRepository;
    @Autowired MilestoneRepository milestoneRepository;
    @Autowired TaskRepository taskRepository;

    private Projects project(long memberId) {
        return projectRepository.save(
                Projects.create(memberId, "p", ScheduleStatus.NOT_STARTED, null, null));
    }

    /** SCHEDULE-AC-02 — 목록 조회는 소유자 것만 가져온다. */
    @Test
    void findAllForMemberReturnsOnlyThatMembersProjects() {
        Projects mine = project(101L);
        project(202L);

        List<Projects> found = projectRepository.findAllForMember(101L);

        assertThat(found).extracting(Projects::getId).containsExactly(mine.getId());
    }

    /** SCHEDULE-AC-13 — 재귀 CTE 가 자기+자손 전부를 모은다. */
    @Test
    void findSelfAndDescendantIdsWalksTheWholeSubtree() {
        Projects p = project(1L);
        Milestones root = milestoneRepository.save(
                Milestones.create(p.getId(), null, "root", null, null, null));
        Milestones child = milestoneRepository.save(
                Milestones.create(p.getId(), root.getId(), "child", null, null, null));
        Milestones grandChild = milestoneRepository.save(
                Milestones.create(p.getId(), child.getId(), "grandchild", null, null, null));
        // 다른 가지 — 딸려 오면 안 된다
        milestoneRepository.save(Milestones.create(p.getId(), null, "other", null, null, null));

        List<Long> ids = milestoneRepository.findSelfAndDescendantIds(root.getId());

        assertThat(ids).containsExactlyInAnyOrder(root.getId(), child.getId(), grandChild.getId());
    }

    /** SCHEDULE-AC-13 — 마일스톤 일괄 삭제와 그 밑 할 일 삭제. */
    @Test
    void deleteAllByIdsAndDeleteAllForMilestonesRemoveTheSubtree() {
        Projects p = project(1L);
        Milestones root = milestoneRepository.save(
                Milestones.create(p.getId(), null, "root", null, null, null));
        Milestones child = milestoneRepository.save(
                Milestones.create(p.getId(), root.getId(), "child", null, null, null));
        Tasks task = taskRepository.save(
                Tasks.create(p.getId(), child.getId(), "t", null, null, null, "#ef4444"));

        List<Long> ids = milestoneRepository.findSelfAndDescendantIds(root.getId());
        taskRepository.deleteAllForMilestones(ids);
        milestoneRepository.deleteAllByIds(ids);

        assertThat(taskRepository.findById(task.getId())).isEmpty();
        assertThat(milestoneRepository.findAllForProject(p.getId())).isEmpty();
    }

    /** SCHEDULE-AC-12 — 프로젝트 단위 일괄 삭제. */
    @Test
    void projectScopedDeletesRemoveEverythingUnderTheProject() {
        Projects p = project(1L);
        Milestones m = milestoneRepository.save(
                Milestones.create(p.getId(), null, "m", null, null, null));
        taskRepository.save(Tasks.create(p.getId(), m.getId(), "in-milestone", null, null, null, "#ef4444"));
        taskRepository.save(Tasks.create(p.getId(), null, "direct", null, null, null, "#ef4444"));

        taskRepository.deleteAllForProject(p.getId());
        milestoneRepository.deleteAllForProject(p.getId());

        assertThat(taskRepository.findAllForProjects(List.of(p.getId()))).isEmpty();
        assertThat(milestoneRepository.findAllForProject(p.getId())).isEmpty();
    }
}
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./mvnw -pl app-mvc -am test -Dtest='ScheduleRepositoryTest'`
Expected: COMPILATION ERROR — 리포지토리 미존재.

- [ ] **Step 3: 리포지토리 구현**

`ProjectRepository.java`:

```java
package com.woobeee.mvc.schedule.repository;

import com.woobeee.mvc.schedule.entity.Projects;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProjectRepository extends JpaRepository<Projects, Long> {

    @Query(value = "SELECT * FROM projects WHERE member_id = :memberId ORDER BY sort_order, id",
            nativeQuery = true)
    List<Projects> findAllForMember(@Param("memberId") Long memberId);
}
```

`MilestoneRepository.java`:

```java
package com.woobeee.mvc.schedule.repository;

import com.woobeee.mvc.schedule.entity.Milestones;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MilestoneRepository extends JpaRepository<Milestones, Long> {

    @Query(value = "SELECT * FROM milestones WHERE project_id IN (:projectIds) ORDER BY sort_order, id",
            nativeQuery = true)
    List<Milestones> findAllForProjects(@Param("projectIds") List<Long> projectIds);

    @Query(value = "SELECT * FROM milestones WHERE project_id = :projectId ORDER BY sort_order, id",
            nativeQuery = true)
    List<Milestones> findAllForProject(@Param("projectId") Long projectId);

    /** 자기 자신을 포함한 자손 전체의 id. 명시적 캐스케이드 삭제와 순환 검사가 쓴다. */
    @Query(value = """
            WITH RECURSIVE descendants AS (
                SELECT id FROM milestones WHERE id = :milestoneId
                UNION ALL
                SELECT m.id FROM milestones m JOIN descendants d ON m.parent_id = d.id
            )
            SELECT id FROM descendants
            """, nativeQuery = true)
    List<Long> findSelfAndDescendantIds(@Param("milestoneId") Long milestoneId);

    @Modifying
    @Query(value = "DELETE FROM milestones WHERE id IN (:ids)", nativeQuery = true)
    void deleteAllByIds(@Param("ids") List<Long> ids);

    @Modifying
    @Query(value = "DELETE FROM milestones WHERE project_id = :projectId", nativeQuery = true)
    void deleteAllForProject(@Param("projectId") Long projectId);
}
```

`TaskRepository.java`:

```java
package com.woobeee.mvc.schedule.repository;

import com.woobeee.mvc.schedule.entity.Tasks;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TaskRepository extends JpaRepository<Tasks, Long> {

    @Query(value = "SELECT * FROM tasks WHERE project_id IN (:projectIds) ORDER BY sort_order, id",
            nativeQuery = true)
    List<Tasks> findAllForProjects(@Param("projectIds") List<Long> projectIds);

    @Modifying
    @Query(value = "DELETE FROM tasks WHERE project_id = :projectId", nativeQuery = true)
    void deleteAllForProject(@Param("projectId") Long projectId);

    @Modifying
    @Query(value = "DELETE FROM tasks WHERE milestone_id IN (:milestoneIds)", nativeQuery = true)
    void deleteAllForMilestones(@Param("milestoneIds") List<Long> milestoneIds);
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./mvnw -pl app-mvc -am test -Dtest='ScheduleRepositoryTest'`
Expected: 4개 PASS.

- [ ] **Step 5: 커밋**

```bash
git add app-mvc/src/main/java/com/woobeee/mvc/schedule/repository/ app-mvc/src/test/java/com/woobeee/mvc/schedule/repository/
git commit -m "feat(schedule): 네이티브 SQL 리포지토리와 실 Postgres 테스트 추가"
```

---

### Task 5: 서비스 — 검증·캐스케이드·트리 조립 + 단위 테스트

**Files:**
- Create: `app-mvc/src/main/java/com/woobeee/mvc/schedule/service/ScheduleColors.java`
- Create: `app-mvc/src/main/java/com/woobeee/mvc/schedule/service/ScheduleMemberResolver.java`
- Create: `app-mvc/src/main/java/com/woobeee/mvc/schedule/service/ScheduleService.java` (인터페이스)
- Create: `app-mvc/src/main/java/com/woobeee/mvc/schedule/service/ScheduleServiceImpl.java`
- Create: `app-mvc/src/main/java/com/woobeee/mvc/schedule/api/request/PostProjectRequest.java`, `PutProjectRequest.java`, `PostMilestoneRequest.java`, `PutMilestoneRequest.java`, `PostTaskRequest.java`, `PutTaskRequest.java`
- Create: `app-mvc/src/main/java/com/woobeee/mvc/schedule/api/response/GetScheduleTreeResponse.java`, `ProjectResponse.java`, `MilestoneResponse.java`, `TaskResponse.java`
- Test: `app-mvc/src/test/java/com/woobeee/mvc/schedule/service/ScheduleServiceImplTest.java`

**Interfaces:**
- Consumes: Task 2 엔티티, Task 3 예외, Task 4 리포지토리.
- Produces (Task 6 컨트롤러가 쓰는 시그니처):
  - `ScheduleService.getTree(String loginId): GetScheduleTreeResponse`
  - `createProject(String loginId, PostProjectRequest): ProjectResponse` / `updateProject(String loginId, Long id, PutProjectRequest): ProjectResponse` / `deleteProject(String loginId, Long id)`
  - `createMilestone(String loginId, PostMilestoneRequest): MilestoneResponse` / `updateMilestone(String loginId, Long id, PutMilestoneRequest): MilestoneResponse` / `deleteMilestone(String loginId, Long id)`
  - `createTask(String loginId, PostTaskRequest): TaskResponse` / `updateTask(String loginId, Long id, PutTaskRequest): TaskResponse` / `deleteTask(String loginId, Long id)`

- [ ] **Step 1: 요청/응답 record 작성** (`api/request/`, `api/response/`)

```java
// PostProjectRequest.java
package com.woobeee.mvc.schedule.api.request;

import com.woobeee.mvc.schedule.entity.ScheduleStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record PostProjectRequest(
        @NotBlank @Size(max = 200) String name,
        ScheduleStatus status,
        LocalDate startDate,
        LocalDate endDate
) {}
```

```java
// PutProjectRequest.java
package com.woobeee.mvc.schedule.api.request;

import com.woobeee.mvc.schedule.entity.ScheduleStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record PutProjectRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull ScheduleStatus status,
        LocalDate startDate,
        LocalDate endDate
) {}
```

```java
// PostMilestoneRequest.java
package com.woobeee.mvc.schedule.api.request;

import com.woobeee.mvc.schedule.entity.ScheduleStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record PostMilestoneRequest(
        @NotNull Long projectId,
        Long parentId,
        @NotBlank @Size(max = 200) String name,
        ScheduleStatus status,
        LocalDate startDate,
        LocalDate endDate
) {}
```

```java
// PutMilestoneRequest.java — 이동(parentId 변경)을 포함한다. 프로젝트 간 이동은 없다.
package com.woobeee.mvc.schedule.api.request;

import com.woobeee.mvc.schedule.entity.ScheduleStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record PutMilestoneRequest(
        Long parentId,
        @NotBlank @Size(max = 200) String name,
        @NotNull ScheduleStatus status,
        LocalDate startDate,
        LocalDate endDate
) {}
```

```java
// PostTaskRequest.java
package com.woobeee.mvc.schedule.api.request;

import com.woobeee.mvc.schedule.entity.ScheduleStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record PostTaskRequest(
        @NotNull Long projectId,
        Long milestoneId,
        @NotBlank @Size(max = 200) String name,
        ScheduleStatus status,
        LocalDate startDate,
        LocalDate endDate
) {}
```

```java
// PutTaskRequest.java — color 는 null 이면 기존 값 유지.
package com.woobeee.mvc.schedule.api.request;

import com.woobeee.mvc.schedule.entity.ScheduleStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record PutTaskRequest(
        Long milestoneId,
        @NotBlank @Size(max = 200) String name,
        @NotNull ScheduleStatus status,
        LocalDate startDate,
        LocalDate endDate,
        String color
) {}
```

```java
// ProjectResponse.java
package com.woobeee.mvc.schedule.api.response;

import com.woobeee.mvc.schedule.entity.Projects;

import java.time.LocalDate;

public record ProjectResponse(
        Long id, String name, String status, LocalDate startDate, LocalDate endDate
) {
    public static ProjectResponse from(Projects p) {
        return new ProjectResponse(p.getId(), p.getName(), p.getStatus().name(),
                p.getStartDate(), p.getEndDate());
    }
}
```

```java
// MilestoneResponse.java
package com.woobeee.mvc.schedule.api.response;

import com.woobeee.mvc.schedule.entity.Milestones;

import java.time.LocalDate;

public record MilestoneResponse(
        Long id, Long projectId, Long parentId, String name, String status,
        LocalDate startDate, LocalDate endDate
) {
    public static MilestoneResponse from(Milestones m) {
        return new MilestoneResponse(m.getId(), m.getProjectId(), m.getParentId(), m.getName(),
                m.getStatus().name(), m.getStartDate(), m.getEndDate());
    }
}
```

```java
// TaskResponse.java
package com.woobeee.mvc.schedule.api.response;

import com.woobeee.mvc.schedule.entity.Tasks;

import java.time.LocalDate;

public record TaskResponse(
        Long id, Long projectId, Long milestoneId, String name, String status,
        LocalDate startDate, LocalDate endDate, String color
) {
    public static TaskResponse from(Tasks t) {
        return new TaskResponse(t.getId(), t.getProjectId(), t.getMilestoneId(), t.getName(),
                t.getStatus().name(), t.getStartDate(), t.getEndDate(), t.getColor());
    }
}
```

```java
// GetScheduleTreeResponse.java — 프론트 lib/schedule.ts 의 타입과 1:1 이다.
package com.woobeee.mvc.schedule.api.response;

import java.time.LocalDate;
import java.util.List;

public record GetScheduleTreeResponse(List<ProjectNode> projects) {

    public record ProjectNode(
            Long id, String name, String status, LocalDate startDate, LocalDate endDate,
            List<MilestoneNode> milestones, List<TaskNode> tasks
    ) {}

    public record MilestoneNode(
            Long id, String name, String status, LocalDate startDate, LocalDate endDate,
            List<MilestoneNode> milestones, List<TaskNode> tasks
    ) {}

    public record TaskNode(
            Long id, Long milestoneId, String name, String status,
            LocalDate startDate, LocalDate endDate, String color
    ) {}
}
```

- [ ] **Step 2: ScheduleColors + ScheduleMemberResolver 작성**

```java
package com.woobeee.mvc.schedule.service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * 할 일 고유색. 값 목록은 front/lib/schedule.ts 의 SCHEDULE_COLORS 와 동일해야 한다
 * (스펙 2026-07-31 §4 가 단일 출처). tailwind 500 계열 12색.
 */
public final class ScheduleColors {
    public static final List<String> PALETTE = List.of(
            "#ef4444", "#f97316", "#f59e0b", "#84cc16", "#22c55e", "#14b8a6",
            "#06b6d4", "#3b82f6", "#6366f1", "#8b5cf6", "#d946ef", "#ec4899");

    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private ScheduleColors() {
    }

    public static String randomColor() {
        return PALETTE.get(ThreadLocalRandom.current().nextInt(PALETTE.size()));
    }

    public static boolean isValidHex(String color) {
        return color != null && HEX.matcher(color).matches();
    }
}
```

```java
package com.woobeee.mvc.schedule.service;

import com.woobeee.mvc.auth.entity.Member;
import com.woobeee.mvc.auth.repository.MemberRepository;
import com.woobeee.mvc.schedule.exception.ScheduleErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * blog 의 AuthMemberResolver 와 같은 역할이지만 schedule 의 예외를 던진다 —
 * blog 도메인 예외에 의존을 만들지 않기 위해 따로 둔다 (auth 의 MemberRepository 의존은
 * blog 와 같은 방향이라 허용).
 */
@Component
@RequiredArgsConstructor
public class ScheduleMemberResolver {
    private final MemberRepository memberRepository;

    public Long requireMemberId(String loginId) {
        if (!StringUtils.hasText(loginId)) {
            throw ScheduleErrorCode.UNAUTHORIZED.asException();
        }
        return memberRepository.findByEmail(loginId)
                .map(Member::getId)
                .orElseThrow(ScheduleErrorCode.MEMBER_NOT_FOUND::asException);
    }
}
```

- [ ] **Step 3: 실패하는 서비스 테스트 작성** — Mockito, `PostServiceImplTest` 스타일. AC 커버리지가 이 태스크의 본체다.

```java
package com.woobeee.mvc.schedule.service;

import com.woobeee.mvc.schedule.api.request.PostMilestoneRequest;
import com.woobeee.mvc.schedule.api.request.PostProjectRequest;
import com.woobeee.mvc.schedule.api.request.PostTaskRequest;
import com.woobeee.mvc.schedule.api.request.PutMilestoneRequest;
import com.woobeee.mvc.schedule.api.request.PutTaskRequest;
import com.woobeee.mvc.schedule.api.response.GetScheduleTreeResponse;
import com.woobeee.mvc.schedule.api.response.TaskResponse;
import com.woobeee.mvc.schedule.entity.Milestones;
import com.woobeee.mvc.schedule.entity.Projects;
import com.woobeee.mvc.schedule.entity.ScheduleStatus;
import com.woobeee.mvc.schedule.entity.Tasks;
import com.woobeee.mvc.schedule.exception.ScheduleErrorCode;
import com.woobeee.mvc.schedule.exception.ScheduleException;
import com.woobeee.mvc.schedule.repository.MilestoneRepository;
import com.woobeee.mvc.schedule.repository.ProjectRepository;
import com.woobeee.mvc.schedule.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduleServiceImplTest {

    private static final String LOGIN = "me@example.com";
    private static final long MEMBER_ID = 7L;

    @Mock ProjectRepository projectRepository;
    @Mock MilestoneRepository milestoneRepository;
    @Mock TaskRepository taskRepository;
    @Mock ScheduleMemberResolver memberResolver;

    @InjectMocks ScheduleServiceImpl service;

    private Projects ownedProject(long id) {
        Projects p = Projects.create(MEMBER_ID, "p", ScheduleStatus.NOT_STARTED, null, null);
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    private Projects foreignProject(long id) {
        Projects p = Projects.create(999L, "p", ScheduleStatus.NOT_STARTED, null, null);
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    private Milestones milestone(long id, long projectId, Long parentId) {
        Milestones m = Milestones.create(projectId, parentId, "m", null, null, null);
        ReflectionTestUtils.setField(m, "id", id);
        return m;
    }

    private void loggedIn() {
        when(memberResolver.requireMemberId(LOGIN)).thenReturn(MEMBER_ID);
    }

    /** SCHEDULE-AC-03 — 남의 프로젝트는 없는 프로젝트와 같은 얼굴을 한다. */
    @Test
    void writingIntoAnotherMembersProjectLooksLikeNotFound() {
        loggedIn();
        when(projectRepository.findById(10L)).thenReturn(Optional.of(foreignProject(10L)));

        assertThatThrownBy(() -> service.createTask(LOGIN,
                new PostTaskRequest(10L, null, "t", null, null, null)))
                .isInstanceOfSatisfying(ScheduleException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ScheduleErrorCode.PROJECT_NOT_FOUND));
    }

    /** SCHEDULE-AC-04 — status 를 생략하면 NOT_STARTED. */
    @Test
    void creatingAProjectWithoutStatusDefaultsToNotStarted() {
        loggedIn();
        when(projectRepository.save(any(Projects.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.createProject(LOGIN,
                new PostProjectRequest("새 프로젝트", null, null, null));

        assertThat(response.status()).isEqualTo("NOT_STARTED");
    }

    /** SCHEDULE-AC-11 — 종료일이 시작일보다 빠르면 거부. */
    @Test
    void endDateBeforeStartDateIsRejected() {
        loggedIn();

        assertThatThrownBy(() -> service.createProject(LOGIN,
                new PostProjectRequest("p", null,
                        LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 1))))
                .isInstanceOfSatisfying(ScheduleException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ScheduleErrorCode.INVALID_DATE_RANGE));
    }

    /** SCHEDULE-AC-06 — 다른 프로젝트의 마일스톤을 부모로 지정할 수 없다. */
    @Test
    void aParentFromAnotherProjectIsRejected() {
        loggedIn();
        when(projectRepository.findById(10L)).thenReturn(Optional.of(ownedProject(10L)));
        when(milestoneRepository.findById(55L)).thenReturn(Optional.of(milestone(55L, 20L, null)));

        assertThatThrownBy(() -> service.createMilestone(LOGIN,
                new PostMilestoneRequest(10L, 55L, "m", null, null, null)))
                .isInstanceOfSatisfying(ScheduleException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ScheduleErrorCode.CROSS_PROJECT));
    }

    /** SCHEDULE-AC-07 — 깊이 5 를 넘는 생성은 거부된다. (1~5 체인 밑에 6번째) */
    @Test
    void creatingASixthLevelMilestoneIsRejected() {
        loggedIn();
        when(projectRepository.findById(10L)).thenReturn(Optional.of(ownedProject(10L)));
        Milestones level5 = milestone(5L, 10L, 4L);
        when(milestoneRepository.findById(5L)).thenReturn(Optional.of(level5));
        when(milestoneRepository.findAllForProject(10L)).thenReturn(List.of(
                milestone(1L, 10L, null), milestone(2L, 10L, 1L), milestone(3L, 10L, 2L),
                milestone(4L, 10L, 3L), level5));

        assertThatThrownBy(() -> service.createMilestone(LOGIN,
                new PostMilestoneRequest(10L, 5L, "level6", null, null, null)))
                .isInstanceOfSatisfying(ScheduleException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ScheduleErrorCode.DEPTH_EXCEEDED));
    }

    /** SCHEDULE-AC-08 — 마일스톤을 자기 자손 아래로 옮길 수 없다. */
    @Test
    void movingAMilestoneUnderItsOwnDescendantIsRejected() {
        loggedIn();
        Milestones root = milestone(1L, 10L, null);
        Milestones child = milestone(2L, 10L, 1L);
        when(milestoneRepository.findById(1L)).thenReturn(Optional.of(root));
        when(milestoneRepository.findById(2L)).thenReturn(Optional.of(child));
        when(projectRepository.findById(10L)).thenReturn(Optional.of(ownedProject(10L)));
        when(milestoneRepository.findAllForProject(10L)).thenReturn(List.of(root, child));

        assertThatThrownBy(() -> service.updateMilestone(LOGIN, 1L,
                new PutMilestoneRequest(2L, "root", ScheduleStatus.NOT_STARTED, null, null)))
                .isInstanceOfSatisfying(ScheduleException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ScheduleErrorCode.CYCLE));
    }

    /** SCHEDULE-AC-09 — 생성된 할 일의 색은 팔레트에서 나온다. */
    @Test
    void aNewTaskGetsAColorFromThePalette() {
        loggedIn();
        when(projectRepository.findById(10L)).thenReturn(Optional.of(ownedProject(10L)));
        when(taskRepository.save(any(Tasks.class))).thenAnswer(inv -> inv.getArgument(0));

        TaskResponse response = service.createTask(LOGIN,
                new PostTaskRequest(10L, null, "t", null, null, null));

        assertThat(ScheduleColors.PALETTE).contains(response.color());
    }

    /** SCHEDULE-AC-10 — #RRGGBB 가 아닌 색은 거부된다. */
    @Test
    void anInvalidHexColorIsRejected() {
        loggedIn();
        Tasks task = Tasks.create(10L, null, "t", null, null, null, "#ef4444");
        ReflectionTestUtils.setField(task, "id", 3L);
        when(taskRepository.findById(3L)).thenReturn(Optional.of(task));
        when(projectRepository.findById(10L)).thenReturn(Optional.of(ownedProject(10L)));

        assertThatThrownBy(() -> service.updateTask(LOGIN, 3L,
                new PutTaskRequest(null, "t", ScheduleStatus.DONE, null, null, "red")))
                .isInstanceOfSatisfying(ScheduleException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ScheduleErrorCode.INVALID_COLOR));
    }

    /** SCHEDULE-AC-12 — 프로젝트 삭제는 할 일 → 마일스톤 → 프로젝트 순서로 전부 지운다. */
    @Test
    void deletingAProjectCascadesExplicitly() {
        loggedIn();
        Projects p = ownedProject(10L);
        when(projectRepository.findById(10L)).thenReturn(Optional.of(p));

        service.deleteProject(LOGIN, 10L);

        InOrder order = inOrder(taskRepository, milestoneRepository, projectRepository);
        order.verify(taskRepository).deleteAllForProject(10L);
        order.verify(milestoneRepository).deleteAllForProject(10L);
        order.verify(projectRepository).delete(p);
    }

    /** SCHEDULE-AC-13 — 마일스톤 삭제는 자손 id 를 모아 그 밑 할 일부터 지운다. */
    @Test
    void deletingAMilestoneRemovesItsSubtree() {
        loggedIn();
        Milestones root = milestone(1L, 10L, null);
        when(milestoneRepository.findById(1L)).thenReturn(Optional.of(root));
        when(projectRepository.findById(10L)).thenReturn(Optional.of(ownedProject(10L)));
        when(milestoneRepository.findSelfAndDescendantIds(1L)).thenReturn(List.of(1L, 2L, 3L));

        service.deleteMilestone(LOGIN, 1L);

        InOrder order = inOrder(taskRepository, milestoneRepository);
        order.verify(taskRepository).deleteAllForMilestones(List.of(1L, 2L, 3L));
        order.verify(milestoneRepository).deleteAllByIds(List.of(1L, 2L, 3L));
    }

    /** SCHEDULE-AC-02 + SCHEDULE-AC-14 — 트리는 3회 조회로 조립되고 중첩이 맞다. */
    @Test
    void treeIsAssembledFromThreeBatchQueries() {
        loggedIn();
        Projects p = ownedProject(10L);
        when(projectRepository.findAllForMember(MEMBER_ID)).thenReturn(List.of(p));
        Milestones root = milestone(1L, 10L, null);
        Milestones child = milestone(2L, 10L, 1L);
        when(milestoneRepository.findAllForProjects(List.of(10L))).thenReturn(List.of(root, child));
        Tasks direct = Tasks.create(10L, null, "direct", null, null, null, "#ef4444");
        ReflectionTestUtils.setField(direct, "id", 100L);
        Tasks nested = Tasks.create(10L, 2L, "nested", null, null, null, "#3b82f6");
        ReflectionTestUtils.setField(nested, "id", 101L);
        when(taskRepository.findAllForProjects(List.of(10L))).thenReturn(List.of(direct, nested));

        GetScheduleTreeResponse tree = service.getTree(LOGIN);

        assertThat(tree.projects()).hasSize(1);
        var projectNode = tree.projects().get(0);
        assertThat(projectNode.tasks()).extracting(GetScheduleTreeResponse.TaskNode::name)
                .containsExactly("direct");
        assertThat(projectNode.milestones()).hasSize(1);
        var rootNode = projectNode.milestones().get(0);
        assertThat(rootNode.milestones()).hasSize(1);
        assertThat(rootNode.milestones().get(0).tasks())
                .extracting(GetScheduleTreeResponse.TaskNode::name).containsExactly("nested");
        // 루프 내 단건 조회 금지 — findById 는 트리 조립에 쓰이지 않는다
        verify(milestoneRepository, never()).findById(any());
        verify(taskRepository, never()).findById(any());
    }

    /** SCHEDULE-AC-05 — 없는 프로젝트 참조. */
    @Test
    void referencingAMissingProjectIsNotFound() {
        loggedIn();
        when(projectRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createMilestone(LOGIN,
                new PostMilestoneRequest(10L, null, "m", null, null, null)))
                .isInstanceOfSatisfying(ScheduleException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ScheduleErrorCode.PROJECT_NOT_FOUND));
    }

    /** SCHEDULE-AC-06 — 할 일의 milestoneId 가 다른 프로젝트 소속이면 거부. */
    @Test
    void aTaskPointingAtAnotherProjectsMilestoneIsRejected() {
        loggedIn();
        when(projectRepository.findById(10L)).thenReturn(Optional.of(ownedProject(10L)));
        when(milestoneRepository.findById(55L)).thenReturn(Optional.of(milestone(55L, 20L, null)));

        assertThatThrownBy(() -> service.createTask(LOGIN,
                new PostTaskRequest(10L, 55L, "t", null, null, null)))
                .isInstanceOfSatisfying(ScheduleException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ScheduleErrorCode.CROSS_PROJECT));
    }
}
```

- [ ] **Step 4: 실패 확인**

Run: `./mvnw -pl app-mvc -am test -Dtest='ScheduleServiceImplTest'`
Expected: COMPILATION ERROR — 서비스 미존재.

- [ ] **Step 5: ScheduleService 인터페이스 + Impl 구현**

인터페이스는 Interfaces 블록의 10개 메서드 그대로. Impl 골격:

```java
package com.woobeee.mvc.schedule.service;

import com.woobeee.mvc.schedule.api.request.*;
import com.woobeee.mvc.schedule.api.response.*;
import com.woobeee.mvc.schedule.entity.Milestones;
import com.woobeee.mvc.schedule.entity.Projects;
import com.woobeee.mvc.schedule.entity.Tasks;
import com.woobeee.mvc.schedule.exception.ScheduleErrorCode;
import com.woobeee.mvc.schedule.repository.MilestoneRepository;
import com.woobeee.mvc.schedule.repository.ProjectRepository;
import com.woobeee.mvc.schedule.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class ScheduleServiceImpl implements ScheduleService {

    static final int MAX_MILESTONE_DEPTH = 5;

    private final ProjectRepository projectRepository;
    private final MilestoneRepository milestoneRepository;
    private final TaskRepository taskRepository;
    private final ScheduleMemberResolver memberResolver;

    /* ===== 공통 검증 ===== */

    /** 소유하지 않은(또는 없는) 프로젝트는 같은 404 로 응답해 존재 여부를 흘리지 않는다. */
    private Projects ownedProject(Long memberId, Long projectId) {
        return projectRepository.findById(projectId)
                .filter(p -> p.getMemberId().equals(memberId))
                .orElseThrow(ScheduleErrorCode.PROJECT_NOT_FOUND::asException);
    }

    private static void validateDates(LocalDate start, LocalDate end) {
        if (start != null && end != null && end.isBefore(start)) {
            throw ScheduleErrorCode.INVALID_DATE_RANGE.asException();
        }
    }

    /** parentId(또는 milestoneId)가 이 프로젝트의 마일스톤인지. null 이면 통과. */
    private Milestones requireMilestoneInProject(Long milestoneId, Long projectId) {
        Milestones milestone = milestoneRepository.findById(milestoneId)
                .orElseThrow(ScheduleErrorCode.MILESTONE_NOT_FOUND::asException);
        if (!milestone.getProjectId().equals(projectId)) {
            throw ScheduleErrorCode.CROSS_PROJECT.asException();
        }
        return milestone;
    }

    /** 프로젝트의 마일스톤 전체를 부모 체인 지도로. 깊이·순환 검사가 쓴다. */
    private Map<Long, Long> parentIndex(Long projectId) {
        Map<Long, Long> parents = new HashMap<>();
        for (Milestones m : milestoneRepository.findAllForProject(projectId)) {
            parents.put(m.getId(), m.getParentId());
        }
        return parents;
    }

    /** 루트 직속 = 1. */
    private static int depthOf(Long milestoneId, Map<Long, Long> parents) {
        int depth = 0;
        Long cursor = milestoneId;
        while (cursor != null) {
            depth++;
            cursor = parents.get(cursor);
            if (depth > parents.size() + 1) {
                // 데이터가 이미 망가져 순환이면 무한 루프 대신 실패시킨다
                throw ScheduleErrorCode.CYCLE.asException();
            }
        }
        return depth;
    }

    /** subtree 의 상대 높이(자기 자신 = 1). */
    private static int heightOf(Long milestoneId, Map<Long, Long> parents) {
        Map<Long, List<Long>> children = new HashMap<>();
        parents.forEach((id, parent) -> children.computeIfAbsent(parent, k -> new ArrayList<>()).add(id));
        return heightWalk(milestoneId, children);
    }

    private static int heightWalk(Long id, Map<Long, List<Long>> children) {
        int max = 0;
        for (Long child : children.getOrDefault(id, List.of())) {
            max = Math.max(max, heightWalk(child, children));
        }
        return max + 1;
    }
    // ... 이하 각 공개 메서드는 Step 3 테스트가 요구하는 동작을 최소로 구현
}
```

공개 메서드 구현 규칙 (테스트가 고정하는 동작):

- `createProject`: `validateDates` → `memberResolver.requireMemberId` → `projectRepository.save(Projects.create(...))` → `ProjectResponse.from`.
- `updateProject`: `validateDates` → `ownedProject` → `p.update(...)` → `ProjectResponse.from(p)`.
- `deleteProject`: `ownedProject` → `taskRepository.deleteAllForProject(id)` → `milestoneRepository.deleteAllForProject(id)` → `projectRepository.delete(p)` (이 순서, AC-12).
- `createMilestone`: `validateDates` → `ownedProject(r.projectId())` → parentId 있으면 `requireMilestoneInProject(parentId, projectId)` 후 `depthOf(parentId, parentIndex(projectId)) + 1 > MAX_MILESTONE_DEPTH` 이면 `DEPTH_EXCEEDED` → save.
- `updateMilestone(id, r)`: 대상 조회(`MILESTONE_NOT_FOUND`) → 그 `projectId`로 `ownedProject` → `validateDates` → parentId 변경 시: 자기 자신이면 `CYCLE`; `requireMilestoneInProject`; `parentIndex`에서 새 부모의 조상 체인을 걷다 자기 id 를 만나면 `CYCLE`; `depthOf(newParent) + heightOf(자기)` > 5 면 `DEPTH_EXCEEDED` → `m.update(...)`.
- `deleteMilestone`: 대상 조회 → `ownedProject` → `ids = findSelfAndDescendantIds(id)` → `taskRepository.deleteAllForMilestones(ids)` → `milestoneRepository.deleteAllByIds(ids)` (이 순서, AC-13).
- `createTask`: `validateDates` → `ownedProject(r.projectId())` → milestoneId 있으면 `requireMilestoneInProject` → `Tasks.create(..., ScheduleColors.randomColor())` → save (AC-09).
- `updateTask(id, r)`: 대상 조회(`TASK_NOT_FOUND`) → `ownedProject(task.projectId)` → `validateDates` → `r.color()` 가 null 이 아니고 `!ScheduleColors.isValidHex` 면 `INVALID_COLOR`; null 이면 기존 색 유지 → milestoneId 있으면 `requireMilestoneInProject` → `t.update(...)`.
- `deleteTask`: 대상 조회 → `ownedProject` → `taskRepository.delete(t)`.
- `getTree`: `requireMemberId` → `findAllForMember` → 비면 빈 트리; 아니면 projectIds 로 `findAllForProjects` 두 번(마일스톤/할 일) → 메모리에서 조립: `Map<Long(parentId), List<Milestones>>`, `Map<Long(milestoneId), List<Tasks>>` (null 키 = 직속) → 재귀로 `MilestoneNode` 구성. `findById` 사용 금지 (AC-14).

- [ ] **Step 6: 테스트 통과 확인**

Run: `./mvnw -pl app-mvc -am test -Dtest='ScheduleServiceImplTest'`
Expected: 13개 전부 PASS.

- [ ] **Step 7: 커밋**

```bash
git add app-mvc/src/main/java/com/woobeee/mvc/schedule/ app-mvc/src/test/java/com/woobeee/mvc/schedule/
git commit -m "feat(schedule): 검증·명시적 캐스케이드·트리 조립을 담은 서비스 추가"
```

---

### Task 6: ScheduleController + MockMvc 테스트

**Files:**
- Create: `app-mvc/src/main/java/com/woobeee/mvc/schedule/controller/ScheduleController.java`
- Test: `app-mvc/src/test/java/com/woobeee/mvc/schedule/controller/ScheduleControllerTest.java`

**Interfaces:**
- Consumes: Task 5의 `ScheduleService` 시그니처 전부.
- Produces: `/api/back/schedule/*` HTTP 계약 — Task 10의 `scheduleAPI`가 이 경로·봉투를 호출한다.

- [ ] **Step 1: 실패하는 테스트 작성** — standalone MockMvc + advice

```java
package com.woobeee.mvc.schedule.controller;

import com.woobeee.mvc.schedule.api.response.GetScheduleTreeResponse;
import com.woobeee.mvc.schedule.exception.ScheduleControllerAdvice;
import com.woobeee.mvc.schedule.exception.ScheduleErrorCode;
import com.woobeee.mvc.schedule.service.ScheduleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ScheduleControllerTest {

    @Mock ScheduleService scheduleService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ScheduleController(scheduleService))
                .setControllerAdvice(new ScheduleControllerAdvice())
                .build();
    }

    /** SCHEDULE-AC-02 — 트리가 봉투에 담겨 나간다. */
    @Test
    void treeIsWrappedInTheEnvelope() throws Exception {
        when(scheduleService.getTree("me@example.com"))
                .thenReturn(new GetScheduleTreeResponse(List.of()));

        mockMvc.perform(get("/api/back/schedule/tree").header("loginId", "me@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.data.projects").isArray());
    }

    /** SCHEDULE-AC-01 — 필터가 loginId 를 못 심으면 401 + 코드 키 봉투. */
    @Test
    void aMissingLoginIdBecomesTheUnauthorizedEnvelope() throws Exception {
        when(scheduleService.getTree(isNull()))
                .thenThrow(ScheduleErrorCode.UNAUTHORIZED.asException());

        mockMvc.perform(get("/api/back/schedule/tree"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.isSuccessful").value(false))
                .andExpect(jsonPath("$.header.message").value("schedule_unauthorized"));
    }

    /** SCHEDULE-AC-16 — bean validation 실패는 schedule_badRequest 봉투. */
    @Test
    void aBlankNameBecomesTheBadRequestEnvelope() throws Exception {
        mockMvc.perform(post("/api/back/schedule/projects")
                        .header("loginId", "me@example.com")
                        .contentType("application/json")
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value("schedule_badRequest"));
    }

    /** SCHEDULE-AC-03 — 서비스의 PROJECT_NOT_FOUND 가 404 봉투로 나간다. */
    @Test
    void projectNotFoundBecomesA404Envelope() throws Exception {
        when(scheduleService.getTree("me@example.com"))
                .thenThrow(ScheduleErrorCode.PROJECT_NOT_FOUND.asException());

        mockMvc.perform(get("/api/back/schedule/tree").header("loginId", "me@example.com"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.message").value("schedule_projectNotFound"));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./mvnw -pl app-mvc -am test -Dtest='ScheduleControllerTest'`
Expected: COMPILATION ERROR — 컨트롤러 미존재.

- [ ] **Step 3: 컨트롤러 구현** (`CategoryController` 스타일 — `loginId` 헤더는 필터가 주입)

```java
package com.woobeee.mvc.schedule.controller;

import com.woobeee.core.api.ApiResponse;
import com.woobeee.mvc.schedule.api.request.*;
import com.woobeee.mvc.schedule.api.response.*;
import com.woobeee.mvc.schedule.service.ScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/back/schedule")
@Tag(name = "Schedule Controller", description = "일정 관리 컨트롤러")
@RequiredArgsConstructor
public class ScheduleController {
    private final ScheduleService scheduleService;

    @GetMapping("/tree")
    @Operation(summary = "일정 트리 조회", description = "내 프로젝트>마일스톤>할 일 전체 트리를 조회합니다.")
    public ApiResponse<GetScheduleTreeResponse> getTree(
            @RequestHeader(name = "loginId", required = false) String loginId) {
        return ApiResponse.success(scheduleService.getTree(loginId), "Schedule tree retrieved");
    }

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "프로젝트 생성")
    public ApiResponse<ProjectResponse> createProject(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @Valid @RequestBody PostProjectRequest request) {
        return ApiResponse.createSuccess(scheduleService.createProject(loginId, request), "Project created");
    }

    @PutMapping("/projects/{projectId}")
    @Operation(summary = "프로젝트 수정")
    public ApiResponse<ProjectResponse> updateProject(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long projectId,
            @Valid @RequestBody PutProjectRequest request) {
        return ApiResponse.success(scheduleService.updateProject(loginId, projectId, request), "Project updated");
    }

    @DeleteMapping("/projects/{projectId}")
    @Operation(summary = "프로젝트 삭제", description = "하위 마일스톤·할 일을 함께 삭제합니다.")
    public ApiResponse<Void> deleteProject(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long projectId) {
        scheduleService.deleteProject(loginId, projectId);
        return ApiResponse.success("Project deleted");
    }

    @PostMapping("/milestones")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "마일스톤 생성")
    public ApiResponse<MilestoneResponse> createMilestone(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @Valid @RequestBody PostMilestoneRequest request) {
        return ApiResponse.createSuccess(scheduleService.createMilestone(loginId, request), "Milestone created");
    }

    @PutMapping("/milestones/{milestoneId}")
    @Operation(summary = "마일스톤 수정")
    public ApiResponse<MilestoneResponse> updateMilestone(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long milestoneId,
            @Valid @RequestBody PutMilestoneRequest request) {
        return ApiResponse.success(scheduleService.updateMilestone(loginId, milestoneId, request), "Milestone updated");
    }

    @DeleteMapping("/milestones/{milestoneId}")
    @Operation(summary = "마일스톤 삭제", description = "자손 마일스톤·할 일을 함께 삭제합니다.")
    public ApiResponse<Void> deleteMilestone(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long milestoneId) {
        scheduleService.deleteMilestone(loginId, milestoneId);
        return ApiResponse.success("Milestone deleted");
    }

    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "할 일 생성", description = "색은 서버가 팔레트에서 자동 배정합니다.")
    public ApiResponse<TaskResponse> createTask(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @Valid @RequestBody PostTaskRequest request) {
        return ApiResponse.createSuccess(scheduleService.createTask(loginId, request), "Task created");
    }

    @PutMapping("/tasks/{taskId}")
    @Operation(summary = "할 일 수정")
    public ApiResponse<TaskResponse> updateTask(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long taskId,
            @Valid @RequestBody PutTaskRequest request) {
        return ApiResponse.success(scheduleService.updateTask(loginId, taskId, request), "Task updated");
    }

    @DeleteMapping("/tasks/{taskId}")
    @Operation(summary = "할 일 삭제")
    public ApiResponse<Void> deleteTask(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long taskId) {
        scheduleService.deleteTask(loginId, taskId);
        return ApiResponse.success("Task deleted");
    }
}
```

- [ ] **Step 4: 테스트 통과 확인 + app-mvc 전체 그린**

Run: `./mvnw -pl app-mvc -am test`
Expected: 신규 4개 포함 전부 PASS (known-gap 제외 기본 세트).

- [ ] **Step 5: 커밋**

```bash
git add app-mvc/src/main/java/com/woobeee/mvc/schedule/controller/ app-mvc/src/test/java/com/woobeee/mvc/schedule/controller/
git commit -m "feat(schedule): /api/back/schedule 컨트롤러 추가"
```

---

### Task 7: 백엔드 문서 갱신

**Files:**
- Modify: `docs/api/README.md` — schedule 절 추가
- Modify: `docs/ARCHITECTURE.md` — app-mvc 도메인 목록에 schedule 절 추가
- Modify: `CLAUDE.md` — "API 엔드포인트" 표의 app-mvc 행에 schedule 추가

- [ ] **Step 1: `docs/api/README.md`에 표 추가** (기존 문서의 표 형식을 그대로 따른다 — 파일을 먼저 읽고 형식을 맞출 것)

내용: `GET /api/back/schedule/tree`(로그인), `POST/PUT/DELETE /api/back/schedule/projects*`(로그인), `.../milestones*`(로그인), `.../tasks*`(로그인). ADMIN 전용 없음.

- [ ] **Step 2: `docs/ARCHITECTURE.md` app-mvc 절에 schedule 도메인 요약 추가** — 패키지 구조, FK 없음(서비스 검증), 명시적 캐스케이드, `/tree` 단일 조회.

- [ ] **Step 3: `CLAUDE.md` API 표에 한 행 추가**

```markdown
| app-mvc | schedule | `/api/back/schedule` | 일정 트리/프로젝트/마일스톤/할 일 — 전부 로그인 필수, 본인 것만 |
```

- [ ] **Step 4: 커밋**

```bash
git add docs/api/README.md docs/ARCHITECTURE.md CLAUDE.md
git commit -m "docs(schedule): API 표와 아키텍처 문서에 schedule 도메인 반영"
```

---

### Task 8: front — `lib/schedule.ts` (타입·팔레트·필터·날짜 포맷) + 테스트

**Files:**
- Create: `front/lib/schedule.ts`
- Test: `front/lib/schedule.test.ts`

**Interfaces:**
- Produces (Task 9~11이 쓰는 시그니처):
  - 타입 `ScheduleStatus`, `StatusFilter`, `ScheduleTask`, `ScheduleMilestone`, `ScheduleProject`, `ScheduleTree`
  - `SCHEDULE_COLORS: readonly string[]` (서버 `ScheduleColors.PALETTE`와 동일 12색)
  - `MAX_MILESTONE_DEPTH = 5`, `STATUS_LABELS`, `isValidHexColor(v: string): boolean`
  - `filterTree(tree: ScheduleTree, filter: StatusFilter): FilteredTree` — 노드에 `dimmed` 플래그
  - `collectTasks(tree: ScheduleTree): ScheduleTask[]` — 달력용 평탄화
  - `formatDateRange(start: string | null, end: string | null, today?: Date): string`

- [ ] **Step 1: 실패하는 테스트 작성**

```ts
import { describe, expect, it } from "vitest"
import {
    SCHEDULE_COLORS,
    MAX_MILESTONE_DEPTH,
    collectTasks,
    filterTree,
    formatDateRange,
    isValidHexColor,
    type ScheduleTree,
} from "./schedule"

const tree: ScheduleTree = {
    projects: [
        {
            id: 1, name: "DM", status: "IN_PROGRESS", startDate: "2026-08-20", endDate: "2026-09-04",
            tasks: [
                { id: 100, milestoneId: null, name: "직속 완료", status: "DONE", startDate: null, endDate: null, color: "#ef4444" },
            ],
            milestones: [
                {
                    id: 10, name: "POC", status: "IN_PROGRESS", startDate: null, endDate: null,
                    tasks: [
                        { id: 101, milestoneId: 10, name: "진행중 일", status: "IN_PROGRESS", startDate: "2026-08-26", endDate: "2026-09-01", color: "#3b82f6" },
                    ],
                    milestones: [
                        {
                            id: 11, name: "하위", status: "NOT_STARTED", startDate: null, endDate: null,
                            tasks: [], milestones: [],
                        },
                    ],
                },
            ],
        },
        {
            id: 2, name: "POSCO", status: "DONE", startDate: null, endDate: null,
            tasks: [], milestones: [],
        },
    ],
}

describe("filterTree", () => {
    it("ALL 은 원본 구조를 그대로 유지한다", () => {
        const filtered = filterTree(tree, "ALL")
        expect(filtered.projects).toHaveLength(2)
        expect(filtered.projects[0].dimmed).toBe(false)
    })

    // SCHEDULE-AC-17
    it("상태가 일치하는 노드와 조상 체인만 남기고, 조상은 dimmed 처리한다", () => {
        const filtered = filterTree(tree, "IN_PROGRESS")

        // DONE 뿐인 POSCO 는 사라진다
        expect(filtered.projects.map((p) => p.id)).toEqual([1])
        const dm = filtered.projects[0]
        // DM 자신이 IN_PROGRESS 라 dimmed 아님
        expect(dm.dimmed).toBe(false)
        // 직속 DONE 할 일은 사라진다
        expect(dm.tasks).toHaveLength(0)
        // POC 는 자신이 일치, 하위 NOT_STARTED 마일스톤은 사라진다
        expect(dm.milestones).toHaveLength(1)
        expect(dm.milestones[0].milestones).toHaveLength(0)
        expect(dm.milestones[0].tasks.map((t) => t.id)).toEqual([101])
    })

    it("자신은 불일치지만 일치하는 자손이 있으면 dimmed 로 남는다", () => {
        const filtered = filterTree(tree, "NOT_STARTED")

        // DM(IN_PROGRESS) 은 NOT_STARTED 자손(마일스톤 11) 때문에 dimmed 로 남는다
        expect(filtered.projects.map((p) => p.id)).toEqual([1])
        expect(filtered.projects[0].dimmed).toBe(true)
        expect(filtered.projects[0].milestones[0].dimmed).toBe(true)
        expect(filtered.projects[0].milestones[0].milestones[0].dimmed).toBe(false)
    })
})

describe("collectTasks", () => {
    it("직속과 중첩 마일스톤의 할 일을 전부 평탄화한다", () => {
        expect(collectTasks(tree).map((t) => t.id).sort()).toEqual([100, 101])
    })
})

describe("formatDateRange", () => {
    const today = new Date(2026, 8, 1) // 2026-09-01

    // SCHEDULE-AC-20
    it("올해 날짜는 연도를 생략한다", () => {
        expect(formatDateRange("2026-08-20", "2026-08-31", today)).toBe("08.20 ~ 08.31")
    })

    it("다른 해는 YY. 접두를 붙인다", () => {
        expect(formatDateRange("2025-12-31", "2026-01-02", today)).toBe("25.12.31 ~ 01.02")
    })

    it("종료 미정은 미정으로 표기한다", () => {
        expect(formatDateRange("2026-08-24", null, today)).toBe("08.24 ~ 미정")
    })

    it("시작 없이 종료만 있으면 종료만 보여준다", () => {
        expect(formatDateRange(null, "2026-08-31", today)).toBe("~ 08.31")
    })

    it("둘 다 없으면 빈 문자열", () => {
        expect(formatDateRange(null, null, today)).toBe("")
    })
})

describe("palette", () => {
    it("팔레트는 12색이고 전부 유효한 hex 다", () => {
        expect(SCHEDULE_COLORS).toHaveLength(12)
        for (const c of SCHEDULE_COLORS) expect(isValidHexColor(c)).toBe(true)
    })

    it("isValidHexColor 는 #RRGGBB 만 허용한다", () => {
        expect(isValidHexColor("#ef4444")).toBe(true)
        expect(isValidHexColor("#EF4444")).toBe(true)
        expect(isValidHexColor("red")).toBe(false)
        expect(isValidHexColor("#fff")).toBe(false)
        expect(isValidHexColor("#ef44441")).toBe(false)
    })

    it("깊이 상한은 서버와 같은 5", () => {
        expect(MAX_MILESTONE_DEPTH).toBe(5)
    })
})
```

- [ ] **Step 2: 실패 확인**

Run: `cd front && npx vitest run lib/schedule.test.ts`
Expected: FAIL — 모듈 없음.

- [ ] **Step 3: 구현**

```ts
// front/lib/schedule.ts — 일정 탭의 React-free 판단 로직.
// 컴포넌트에는 판단을 두지 않는다 (vitest 가 node 환경이라 컴포넌트는 검증 밖이다).

export type ScheduleStatus = "NOT_STARTED" | "IN_PROGRESS" | "DONE"
export type StatusFilter = ScheduleStatus | "ALL"

export interface ScheduleTask {
    id: number
    milestoneId: number | null
    name: string
    status: ScheduleStatus
    startDate: string | null // "YYYY-MM-DD"
    endDate: string | null
    color: string
}

export interface ScheduleMilestone {
    id: number
    name: string
    status: ScheduleStatus
    startDate: string | null
    endDate: string | null
    milestones: ScheduleMilestone[]
    tasks: ScheduleTask[]
}

export interface ScheduleProject {
    id: number
    name: string
    status: ScheduleStatus
    startDate: string | null
    endDate: string | null
    milestones: ScheduleMilestone[]
    tasks: ScheduleTask[]
}

export interface ScheduleTree {
    projects: ScheduleProject[]
}

// 서버 ScheduleColors.PALETTE 와 동일해야 한다 (스펙 §4 가 단일 출처).
export const SCHEDULE_COLORS = [
    "#ef4444", "#f97316", "#f59e0b", "#84cc16", "#22c55e", "#14b8a6",
    "#06b6d4", "#3b82f6", "#6366f1", "#8b5cf6", "#d946ef", "#ec4899",
] as const

export const MAX_MILESTONE_DEPTH = 5

export const STATUS_LABELS: Record<ScheduleStatus, string> = {
    NOT_STARTED: "시작전",
    IN_PROGRESS: "진행중",
    DONE: "완료",
}

export function isValidHexColor(value: string): boolean {
    return /^#[0-9a-fA-F]{6}$/.test(value)
}

export interface FilteredMilestone extends Omit<ScheduleMilestone, "milestones"> {
    dimmed: boolean
    milestones: FilteredMilestone[]
}

export interface FilteredProject extends Omit<ScheduleProject, "milestones"> {
    dimmed: boolean
    milestones: FilteredMilestone[]
}

export interface FilteredTree {
    projects: FilteredProject[]
}

function filterMilestone(m: ScheduleMilestone, filter: StatusFilter): FilteredMilestone | null {
    const children = m.milestones
        .map((child) => filterMilestone(child, filter))
        .filter((child): child is FilteredMilestone => child !== null)
    const tasks = filter === "ALL" ? m.tasks : m.tasks.filter((t) => t.status === filter)
    const selfMatches = filter === "ALL" || m.status === filter

    if (!selfMatches && children.length === 0 && tasks.length === 0) return null
    return { ...m, milestones: children, tasks, dimmed: !selfMatches }
}

/** 자기 상태가 일치하는 노드 + 그 조상 체인만 남긴다. 조상은 dimmed. (SCHEDULE-AC-17) */
export function filterTree(tree: ScheduleTree, filter: StatusFilter): FilteredTree {
    const projects = tree.projects
        .map((p) => {
            const milestones = p.milestones
                .map((m) => filterMilestone(m, filter))
                .filter((m): m is FilteredMilestone => m !== null)
            const tasks = filter === "ALL" ? p.tasks : p.tasks.filter((t) => t.status === filter)
            const selfMatches = filter === "ALL" || p.status === filter

            if (!selfMatches && milestones.length === 0 && tasks.length === 0) return null
            return { ...p, milestones, tasks, dimmed: !selfMatches }
        })
        .filter((p): p is FilteredProject => p !== null)
    return { projects }
}

/** 달력용 평탄화 — 직속·중첩 가리지 않고 모든 할 일. */
export function collectTasks(tree: ScheduleTree): ScheduleTask[] {
    const out: ScheduleTask[] = []
    const walk = (milestones: ScheduleMilestone[]) => {
        for (const m of milestones) {
            out.push(...m.tasks)
            walk(m.milestones)
        }
    }
    for (const p of tree.projects) {
        out.push(...p.tasks)
        walk(p.milestones)
    }
    return out
}

function formatOne(iso: string, todayYear: number): string {
    const [y, m, d] = iso.split("-")
    return Number(y) === todayYear ? `${m}.${d}` : `${y.slice(2)}.${m}.${d}`
}

/** SCHEDULE-AC-20 — "08.20 ~ 미정" 표기. 올해는 연도 생략, 다른 해는 YY. 접두. */
export function formatDateRange(
    start: string | null,
    end: string | null,
    today: Date = new Date(),
): string {
    const year = today.getFullYear()
    if (!start && !end) return ""
    if (start && !end) return `${formatOne(start, year)} ~ 미정`
    if (!start && end) return `~ ${formatOne(end, year)}`
    return `${formatOne(start!, year)} ~ ${formatOne(end!, year)}`
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd front && npx vitest run lib/schedule.test.ts`
Expected: 전부 PASS. 이어 `npm test` (tsc 포함) 그린 확인.

- [ ] **Step 5: 커밋**

```bash
git add front/lib/schedule.ts front/lib/schedule.test.ts
git commit -m "feat(front): 일정 트리 필터·날짜 포맷·팔레트 모듈 추가"
```

---

### Task 9: front — `lib/schedule-calendar.ts` (월 달력 레이아웃) + 테스트

**Files:**
- Create: `front/lib/schedule-calendar.ts`
- Test: `front/lib/schedule-calendar.test.ts`

**Interfaces:**
- Consumes: `ScheduleTask` (Task 8).
- Produces (Task 11의 달력 컴포넌트가 쓰는 시그니처):
  - `calendarLayout(tasks: ScheduleTask[], year: number, month: number): CalendarWeek[]` — `month`는 1~12
  - `CalendarWeek { days: (number | null)[]; segments: CalendarSegment[] }` — `days`는 일요일 시작 7칸, 그 달이 아니면 null
  - `CalendarSegment { taskId, name, color, lane, startCol, span, continuesLeft, continuesRight, openEnded }`

- [ ] **Step 1: 실패하는 테스트 작성**

```ts
import { describe, expect, it } from "vitest"
import { calendarLayout } from "./schedule-calendar"
import type { ScheduleTask } from "./schedule"

function task(partial: Partial<ScheduleTask> & { id: number }): ScheduleTask {
    return {
        milestoneId: null, name: `t${partial.id}`, status: "NOT_STARTED",
        startDate: null, endDate: null, color: "#ef4444",
        ...partial,
    }
}

// 2026년 8월: 1일 = 토요일, 31일 = 월요일 → 6주 그리드
describe("calendarLayout (2026-08)", () => {
    it("그리드는 일요일 시작이고 달 밖 칸은 null 이다", () => {
        const weeks = calendarLayout([], 2026, 8)
        expect(weeks).toHaveLength(6)
        expect(weeks[0].days).toEqual([null, null, null, null, null, null, 1])
        expect(weeks[5].days).toEqual([30, 31, null, null, null, null, null])
    })

    it("주 안에 완전히 들어가는 할 일은 한 세그먼트다", () => {
        // 8/3(월) ~ 8/5(수) → 둘째 주, col 1~3
        const weeks = calendarLayout([task({ id: 1, startDate: "2026-08-03", endDate: "2026-08-05" })], 2026, 8)
        const segs = weeks[1].segments
        expect(segs).toHaveLength(1)
        expect(segs[0]).toMatchObject({
            taskId: 1, startCol: 1, span: 3,
            continuesLeft: false, continuesRight: false, openEnded: false,
        })
        // 다른 주에는 없다
        expect(weeks[0].segments).toHaveLength(0)
        expect(weeks[2].segments).toHaveLength(0)
    })

    it("주를 넘는 할 일은 주마다 잘리고 continues 플래그가 붙는다", () => {
        // 8/6(목) ~ 8/11(화): 둘째 주 col4~6 + 셋째 주 col0~2
        const weeks = calendarLayout([task({ id: 1, startDate: "2026-08-06", endDate: "2026-08-11" })], 2026, 8)
        expect(weeks[1].segments[0]).toMatchObject({ startCol: 4, span: 3, continuesLeft: false, continuesRight: true })
        expect(weeks[2].segments[0]).toMatchObject({ startCol: 0, span: 3, continuesLeft: true, continuesRight: false })
    })

    // SCHEDULE-AC-18 — 월 경계 잘림
    it("월 밖 구간은 잘리고 continuesLeft/Right 로 표시된다", () => {
        const weeks = calendarLayout([task({ id: 1, startDate: "2026-07-25", endDate: "2026-09-05" })], 2026, 8)
        // 첫 주: 8/1(토, col 6)부터, 왼쪽으로 이어짐
        expect(weeks[0].segments[0]).toMatchObject({ startCol: 6, span: 1, continuesLeft: true })
        // 마지막 주: 8/31(월, col 1)까지, 오른쪽으로 이어짐
        const last = weeks[5].segments[0]
        expect(last).toMatchObject({ startCol: 0, span: 2, continuesRight: true })
    })

    // SCHEDULE-AC-19 — 종료 미정
    it("종료 미정은 시작일부터 월 말까지 깔리고 openEnded 다", () => {
        const weeks = calendarLayout([task({ id: 1, startDate: "2026-08-30", endDate: null })], 2026, 8)
        const last = weeks[5].segments[0]
        expect(last).toMatchObject({ startCol: 0, span: 2, openEnded: true })
    })

    it("시작 없이 종료만 있으면 종료일 하루짜리다", () => {
        const weeks = calendarLayout([task({ id: 1, startDate: null, endDate: "2026-08-05" })], 2026, 8)
        expect(weeks[1].segments[0]).toMatchObject({ startCol: 3, span: 1 })
    })

    it("날짜가 아예 없는 할 일은 달력에 나오지 않는다", () => {
        const weeks = calendarLayout([task({ id: 1 })], 2026, 8)
        expect(weeks.every((w) => w.segments.length === 0)).toBe(true)
    })

    it("겹치는 할 일은 서로 다른 lane 을 받고, 빈 lane 은 재사용된다", () => {
        const weeks = calendarLayout([
            task({ id: 1, startDate: "2026-08-03", endDate: "2026-08-07" }), // 월~금, col 1~5
            task({ id: 2, startDate: "2026-08-04", endDate: "2026-08-06" }), // 화~목, col 2~4 — 1과 겹침
            task({ id: 3, startDate: "2026-08-08", endDate: "2026-08-08" }), // 토, col 6 — 같은 주지만 1·2가 끝난 뒤
        ], 2026, 8)
        const lanes = new Map(weeks[1].segments.map((s) => [s.taskId, s.lane]))
        expect(lanes.get(1)).toBe(0)
        expect(lanes.get(2)).toBe(1)
        // 3번 차례에는 lane 0 이 col 5 에서 끝나 있으므로 재사용된다
        expect(lanes.get(3)).toBe(0)
    })

    it("월 범위와 무관한 할 일은 나오지 않는다", () => {
        const weeks = calendarLayout([task({ id: 1, startDate: "2026-07-01", endDate: "2026-07-31" })], 2026, 8)
        expect(weeks.every((w) => w.segments.length === 0)).toBe(true)
    })
})
```

- [ ] **Step 2: 실패 확인**

Run: `cd front && npx vitest run lib/schedule-calendar.test.ts`
Expected: FAIL — 모듈 없음.

- [ ] **Step 3: 구현**

```ts
// front/lib/schedule-calendar.ts — 월 달력에 할 일 막대를 배치하는 순수 계산.
// 날짜는 전부 "YYYY-MM-DD" 문자열을 로컬 자정 기준으로 다룬다 (UTC 파싱 함정 회피).
import type { ScheduleTask } from "./schedule"

export interface CalendarSegment {
    taskId: number
    name: string
    color: string
    /** 같은 주 안에서 세로로 쌓이는 줄 번호 (0부터). */
    lane: number
    /** 주 안의 시작 칸 (0 = 일요일). */
    startCol: number
    /** 차지하는 칸 수 (1~7). */
    span: number
    /** 이 주 이전(왼쪽)에서 이어져 온다. */
    continuesLeft: boolean
    /** 이 주 이후(오른쪽)로 이어진다. */
    continuesRight: boolean
    /** 종료일 미정 — 월 말에서 화살표로 열어 둔다. */
    openEnded: boolean
}

export interface CalendarWeek {
    /** 일~토 7칸. 그 달의 날짜가 아니면 null. */
    days: (number | null)[]
    segments: CalendarSegment[]
}

function toLocalDate(iso: string): Date {
    const [y, m, d] = iso.split("-").map(Number)
    return new Date(y, m - 1, d)
}

function dayDiff(a: Date, b: Date): number {
    return Math.round((b.getTime() - a.getTime()) / 86_400_000)
}

/** month 는 1~12. 일요일 시작 주 단위 그리드에 할 일 막대를 배치한다. */
export function calendarLayout(tasks: ScheduleTask[], year: number, month: number): CalendarWeek[] {
    const first = new Date(year, month - 1, 1)
    const last = new Date(year, month, 0)
    const gridStart = new Date(year, month - 1, 1 - first.getDay())
    const weekCount = Math.ceil((first.getDay() + last.getDate()) / 7)

    const weeks: CalendarWeek[] = []
    for (let w = 0; w < weekCount; w++) {
        const days: (number | null)[] = []
        for (let c = 0; c < 7; c++) {
            const date = new Date(gridStart)
            date.setDate(gridStart.getDate() + w * 7 + c)
            days.push(date.getMonth() === month - 1 ? date.getDate() : null)
        }
        weeks.push({ days, segments: [] })
    }

    // 시작일 순으로 배치해야 lane 배정이 결정적이다
    const placeable = tasks
        .filter((t) => t.startDate !== null || t.endDate !== null)
        .map((t) => {
            const start = t.startDate ? toLocalDate(t.startDate) : toLocalDate(t.endDate!)
            const end = t.endDate ? toLocalDate(t.endDate) : last
            return { task: t, start, end, openEnded: t.endDate === null && t.startDate !== null }
        })
        .filter(({ start, end }) => start <= last && end >= first && end >= start)
        .sort((a, b) => a.start.getTime() - b.start.getTime() || a.task.id - b.task.id)

    // 주별 lane 점유 현황: lanes[w][lane] = 마지막으로 점유한 col
    const laneEnds: number[][] = weeks.map(() => [])

    for (const { task, start, end, openEnded } of placeable) {
        const clipStart = start < first ? first : start
        const clipEnd = end > last ? last : end

        let cursor = new Date(clipStart)
        while (cursor <= clipEnd) {
            const offset = dayDiff(gridStart, cursor)
            const w = Math.floor(offset / 7)
            const startCol = offset % 7
            const weekRemain = 7 - startCol
            const remainDays = dayDiff(cursor, clipEnd) + 1
            const span = Math.min(weekRemain, remainDays)

            // first-fit lane: 이 주에서 startCol 이전에 끝난 lane 재사용
            let lane = 0
            while (laneEnds[w][lane] !== undefined && laneEnds[w][lane] >= startCol) lane++
            laneEnds[w][lane] = startCol + span - 1

            weeks[w].segments.push({
                taskId: task.id,
                name: task.name,
                color: task.color,
                lane,
                startCol,
                span,
                continuesLeft: dayDiff(start, cursor) > 0,
                continuesRight: dayDiff(cursor, end) + 1 > span,
                openEnded,
            })

            cursor = new Date(cursor)
            cursor.setDate(cursor.getDate() + span)
        }
    }

    return weeks
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd front && npx vitest run lib/schedule-calendar.test.ts`
Expected: 전부 PASS. lane 재사용·경계 테스트가 실패하면 `laneEnds` 비교(`>=`)와 `continuesRight` 계산부터 본다.

- [ ] **Step 5: 커밋**

```bash
git add front/lib/schedule-calendar.ts front/lib/schedule-calendar.test.ts
git commit -m "feat(front): 월 달력 막대 배치 계산 모듈 추가"
```

---

### Task 10: front — `scheduleAPI` 클라이언트

**Files:**
- Modify: `front/lib/api.ts` — `postsAPI` 다음에 `scheduleAPI` 추가 (import 에 `ScheduleTree` 등 추가)

**Interfaces:**
- Consumes: `apiRequest`, `isApiSuccessful`, `ApiResponse` (기존), Task 8 타입.
- Produces (Task 11이 쓰는 시그니처): `scheduleAPI.getTree(): Promise<ScheduleTree>`, `createProject(body)`, `updateProject(id, body)`, `deleteProject(id)`, `createMilestone(body)`, `updateMilestone(id, body)`, `deleteMilestone(id)`, `createTask(body)`, `updateTask(id, body)`, `deleteTask(id)`

- [ ] **Step 1: 구현** — `postsAPI` 패턴 그대로. 파일 상단 import 에 추가:

```ts
import type { ScheduleStatus, ScheduleTree } from "@/lib/schedule"
```

`postsAPI` 정의 뒤에:

```ts
export interface ProjectBody {
    name: string
    status?: ScheduleStatus
    startDate?: string | null
    endDate?: string | null
}

export interface MilestoneBody extends ProjectBody {
    projectId?: number
    parentId?: number | null
}

export interface TaskBody extends ProjectBody {
    projectId?: number
    milestoneId?: number | null
    color?: string
}

async function scheduleRequest<T>(endpoint: string, method: string, body?: unknown): Promise<T> {
    const response = await apiRequest(endpoint, {
        method,
        ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
    })

    const json: ApiResponse<T> = await response.json()
    if (!isApiSuccessful(json)) {
        throw new Error(json.header?.message || "일정 요청에 실패했습니다.")
    }
    return json.data
}

export const scheduleAPI = {
    getTree: (): Promise<ScheduleTree> =>
        scheduleRequest("/api/back/schedule/tree", "GET"),

    createProject: (body: ProjectBody) =>
        scheduleRequest("/api/back/schedule/projects", "POST", body),
    updateProject: (projectId: number, body: ProjectBody) =>
        scheduleRequest(`/api/back/schedule/projects/${projectId}`, "PUT", body),
    deleteProject: (projectId: number) =>
        scheduleRequest(`/api/back/schedule/projects/${projectId}`, "DELETE"),

    createMilestone: (body: MilestoneBody) =>
        scheduleRequest("/api/back/schedule/milestones", "POST", body),
    updateMilestone: (milestoneId: number, body: MilestoneBody) =>
        scheduleRequest(`/api/back/schedule/milestones/${milestoneId}`, "PUT", body),
    deleteMilestone: (milestoneId: number) =>
        scheduleRequest(`/api/back/schedule/milestones/${milestoneId}`, "DELETE"),

    createTask: (body: TaskBody) =>
        scheduleRequest("/api/back/schedule/tasks", "POST", body),
    updateTask: (taskId: number, body: TaskBody) =>
        scheduleRequest(`/api/back/schedule/tasks/${taskId}`, "PUT", body),
    deleteTask: (taskId: number) =>
        scheduleRequest(`/api/back/schedule/tasks/${taskId}`, "DELETE"),
}
```

주의: `apiRequest`는 `Content-Type: application/json`을 자동 주입하고 401 재발급·에러 alert 를 알아서 한다 — 여기서 중복 처리하지 않는다.

- [ ] **Step 2: 타입 검사 + 기존 스위트 그린 확인**

Run: `cd front && npm test`
Expected: PASS (새 코드는 아직 호출부가 없다 — tsc 가 시그니처만 확인).

- [ ] **Step 3: 커밋**

```bash
git add front/lib/api.ts
git commit -m "feat(front): scheduleAPI 클라이언트 추가"
```

---

### Task 11: front — /schedule 페이지 + 트리·달력·다이얼로그 컴포넌트 + 헤더 탭

**Files:**
- Create: `front/app/schedule/page.tsx`
- Create: `front/components/schedule/schedule-tree.tsx`
- Create: `front/components/schedule/schedule-calendar.tsx`
- Create: `front/components/schedule/schedule-item-dialog.tsx`
- Modify: `front/components/header.tsx` — 기술블로그 탭 뒤에 일정 탭 (로그인 시에만)

**Interfaces:**
- Consumes: Task 8~10 전부 (`filterTree`, `collectTasks`, `formatDateRange`, `STATUS_LABELS`, `SCHEDULE_COLORS`, `calendarLayout`, `scheduleAPI`).
- Produces: 최종 사용자 화면. 판단 로직 추가 금지 — 이 파일들은 조립과 fetch 이펙트만 갖는다.

- [ ] **Step 1: 헤더 탭 추가** — `header.tsx`의 lucide import 에 `CalendarDays` 추가, `useAuth()` 구조분해에 `isAuthenticated`는 이미 있음. 기술블로그 `</Button>` 바로 뒤에:

```tsx
            {isAuthenticated ? (
                <Button asChild variant="ghost" size="sm" className="h-9 px-2.5">
                  <Link href="/schedule">
                    <CalendarDays className="h-4 w-4" />
                    <span className="hidden sm:inline">일정</span>
                  </Link>
                </Button>
            ) : null}
```

- [ ] **Step 2: `schedule-item-dialog.tsx`** — 프로젝트/마일스톤/할 일 공용 생성·수정 다이얼로그

핵심 구조 (판단은 props 로 받은 lib 함수·상수만 사용):

```tsx
"use client"

import { useEffect, useState } from "react"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { SCHEDULE_COLORS, STATUS_LABELS, isValidHexColor, type ScheduleStatus } from "@/lib/schedule"

export type ItemKind = "project" | "milestone" | "task"

export interface ItemDraft {
    name: string
    status: ScheduleStatus
    startDate: string | null
    endDate: string | null
    color?: string // task 수정에서만
}

interface Props {
    open: boolean
    kind: ItemKind
    title: string // "새 프로젝트", "할 일 수정" 등 — 호출부가 정한다
    initial: ItemDraft
    showColor: boolean // task 수정에서만 true (생성 색은 서버가 배정)
    onSubmit: (draft: ItemDraft) => Promise<void>
    onClose: () => void
}

const STATUSES: ScheduleStatus[] = ["NOT_STARTED", "IN_PROGRESS", "DONE"]

export default function ScheduleItemDialog({ open, kind, title, initial, showColor, onSubmit, onClose }: Props) {
    const [draft, setDraft] = useState<ItemDraft>(initial)
    const [saving, setSaving] = useState(false)

    useEffect(() => {
        if (open) setDraft(initial)
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [open])

    const invalidRange = Boolean(draft.startDate && draft.endDate && draft.endDate < draft.startDate)
    const invalidColor = showColor && draft.color !== undefined && !isValidHexColor(draft.color)
    const disabled = saving || draft.name.trim() === "" || invalidRange || invalidColor

    const submit = async () => {
        setSaving(true)
        try {
            await onSubmit({ ...draft, name: draft.name.trim() })
            onClose()
        } finally {
            setSaving(false)
        }
    }

    return (
        <Dialog open={open} onOpenChange={(v) => { if (!v) onClose() }}>
            <DialogContent className="sm:max-w-md">
                <DialogHeader><DialogTitle>{title}</DialogTitle></DialogHeader>
                <div className="space-y-4">
                    <div className="space-y-2">
                        <Label htmlFor="schedule-name">이름</Label>
                        <Input id="schedule-name" value={draft.name} maxLength={200}
                               onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
                    </div>
                    <div className="space-y-2">
                        <Label>상태</Label>
                        <Select value={draft.status}
                                onValueChange={(v) => setDraft({ ...draft, status: v as ScheduleStatus })}>
                            <SelectTrigger><SelectValue /></SelectTrigger>
                            <SelectContent>
                                {STATUSES.map((s) => (
                                    <SelectItem key={s} value={s}>{STATUS_LABELS[s]}</SelectItem>
                                ))}
                            </SelectContent>
                        </Select>
                    </div>
                    <div className="grid grid-cols-2 gap-3">
                        <div className="space-y-2">
                            <Label htmlFor="schedule-start">시작일</Label>
                            <Input id="schedule-start" type="date" value={draft.startDate ?? ""}
                                   onChange={(e) => setDraft({ ...draft, startDate: e.target.value || null })} />
                        </div>
                        <div className="space-y-2">
                            <Label htmlFor="schedule-end">종료일 (비우면 미정)</Label>
                            <Input id="schedule-end" type="date" value={draft.endDate ?? ""}
                                   onChange={(e) => setDraft({ ...draft, endDate: e.target.value || null })} />
                        </div>
                    </div>
                    {invalidRange ? (
                        <p className="text-sm text-destructive">종료일은 시작일보다 빠를 수 없습니다.</p>
                    ) : null}
                    {showColor ? (
                        <div className="space-y-2">
                            <Label>색상</Label>
                            <div className="flex flex-wrap gap-2">
                                {SCHEDULE_COLORS.map((c) => (
                                    <button key={c} type="button" aria-label={`색 ${c}`}
                                            className={`h-6 w-6 rounded-full border-2 ${draft.color === c ? "border-foreground" : "border-transparent"}`}
                                            style={{ backgroundColor: c }}
                                            onClick={() => setDraft({ ...draft, color: c })} />
                                ))}
                            </div>
                            <Input value={draft.color ?? ""} placeholder="#RRGGBB"
                                   onChange={(e) => setDraft({ ...draft, color: e.target.value })} />
                            {invalidColor ? (
                                <p className="text-sm text-destructive">색상은 #RRGGBB 형식이어야 합니다.</p>
                            ) : null}
                        </div>
                    ) : null}
                </div>
                <DialogFooter>
                    <Button variant="outline" onClick={onClose} disabled={saving}>취소</Button>
                    <Button onClick={submit} disabled={disabled}>{saving ? "저장 중..." : "저장"}</Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    )
}
```

날짜 입력은 네이티브 `<Input type="date">`를 쓴다. 스펙은 Popover+Calendar 를 언급했지만 그 조합은 상태·포커스 배선이 커지는 데 비해 검증 이득이 없다 — 값 형식(`YYYY-MM-DD`)이 같아 lib·API 계약에는 어떤 차이도 없다. (스펙에서 의도적으로 좁힌 부분: 구현 후 스펙 §5에 한 줄 반영한다.)

- [ ] **Step 3: `schedule-tree.tsx`** — 재귀 렌더 + 행별 드롭다운

```tsx
"use client"

import { useState } from "react"
import { ChevronDown, ChevronRight, MoreHorizontal, Plus } from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
    DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { formatDateRange, STATUS_LABELS, type FilteredMilestone, type FilteredProject, type FilteredTree, type ScheduleStatus, type ScheduleTask } from "@/lib/schedule"

export interface TreeCallbacks {
    onAddMilestone: (projectId: number, parentId: number | null) => void
    onAddTask: (projectId: number, milestoneId: number | null) => void
    onEditProject: (project: FilteredProject) => void
    onEditMilestone: (projectId: number, milestone: FilteredMilestone) => void
    onEditTask: (projectId: number, task: ScheduleTask) => void
    onDeleteProject: (projectId: number) => void
    onDeleteMilestone: (milestoneId: number) => void
    onDeleteTask: (taskId: number) => void
}

const STATUS_BADGE_CLASS: Record<ScheduleStatus, string> = {
    NOT_STARTED: "bg-muted text-muted-foreground",
    IN_PROGRESS: "bg-blue-500/15 text-blue-600 dark:text-blue-400",
    DONE: "bg-green-500/15 text-green-600 dark:text-green-400",
}

function StatusBadge({ status }: { status: ScheduleStatus }) {
    return <Badge variant="outline" className={`border-transparent ${STATUS_BADGE_CLASS[status]}`}>{STATUS_LABELS[status]}</Badge>
}

function TaskRow({ projectId, task, cb }: { projectId: number; task: ScheduleTask; cb: TreeCallbacks }) {
    const done = task.status === "DONE"
    return (
        <li className="flex items-center gap-2 rounded-md px-2 py-1.5 hover:bg-muted/50">
            <span className="h-2.5 w-2.5 shrink-0 rounded-full" style={{ backgroundColor: task.color }} />
            <span className={`flex-1 truncate text-sm ${done ? "text-muted-foreground line-through" : ""}`}>{task.name}</span>
            <StatusBadge status={task.status} />
            <span className="hidden text-xs text-muted-foreground sm:inline">{formatDateRange(task.startDate, task.endDate)}</span>
            <DropdownMenu>
                <DropdownMenuTrigger asChild>
                    <Button variant="ghost" size="icon" className="h-7 w-7"><MoreHorizontal className="h-4 w-4" /></Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end">
                    <DropdownMenuItem onClick={() => cb.onEditTask(projectId, task)}>수정</DropdownMenuItem>
                    <DropdownMenuItem className="text-destructive" onClick={() => cb.onDeleteTask(task.id)}>삭제</DropdownMenuItem>
                </DropdownMenuContent>
            </DropdownMenu>
        </li>
    )
}

function MilestoneRow({ projectId, milestone, depth, cb }: {
    projectId: number; milestone: FilteredMilestone; depth: number; cb: TreeCallbacks
}) {
    const [open, setOpen] = useState(true)
    return (
        <li>
            <div className={`flex items-center gap-2 rounded-md px-2 py-1.5 hover:bg-muted/50 ${milestone.dimmed ? "opacity-50" : ""}`}>
                <button type="button" onClick={() => setOpen(!open)} className="text-muted-foreground">
                    {open ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
                </button>
                <span className="flex-1 truncate text-sm font-medium">{milestone.name}</span>
                <StatusBadge status={milestone.status} />
                <span className="hidden text-xs text-muted-foreground sm:inline">{formatDateRange(milestone.startDate, milestone.endDate)}</span>
                <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                        <Button variant="ghost" size="icon" className="h-7 w-7"><MoreHorizontal className="h-4 w-4" /></Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end">
                        <DropdownMenuItem onClick={() => cb.onAddTask(projectId, milestone.id)}>할 일 추가</DropdownMenuItem>
                        <DropdownMenuItem onClick={() => cb.onAddMilestone(projectId, milestone.id)}>하위 마일스톤 추가</DropdownMenuItem>
                        <DropdownMenuItem onClick={() => cb.onEditMilestone(projectId, milestone)}>수정</DropdownMenuItem>
                        <DropdownMenuItem className="text-destructive" onClick={() => cb.onDeleteMilestone(milestone.id)}>삭제</DropdownMenuItem>
                    </DropdownMenuContent>
                </DropdownMenu>
            </div>
            {open ? (
                <ul className="ml-5 border-l pl-2">
                    {milestone.tasks.map((t) => <TaskRow key={t.id} projectId={projectId} task={t} cb={cb} />)}
                    {milestone.milestones.map((m) => (
                        <MilestoneRow key={m.id} projectId={projectId} milestone={m} depth={depth + 1} cb={cb} />
                    ))}
                </ul>
            ) : null}
        </li>
    )
}

export default function ScheduleTree({ tree, cb }: { tree: FilteredTree; cb: TreeCallbacks }) {
    return (
        <ul className="space-y-4">
            {tree.projects.map((p) => (
                <li key={p.id} className={`rounded-lg border p-3 ${p.dimmed ? "opacity-50" : ""}`}>
                    <div className="flex items-center gap-2">
                        <span className="flex-1 truncate font-semibold">{p.name}</span>
                        <StatusBadge status={p.status} />
                        <span className="hidden text-xs text-muted-foreground sm:inline">{formatDateRange(p.startDate, p.endDate)}</span>
                        <Button variant="ghost" size="icon" className="h-7 w-7" aria-label="항목 추가"
                                onClick={() => cb.onAddTask(p.id, null)}>
                            <Plus className="h-4 w-4" />
                        </Button>
                        <DropdownMenu>
                            <DropdownMenuTrigger asChild>
                                <Button variant="ghost" size="icon" className="h-7 w-7"><MoreHorizontal className="h-4 w-4" /></Button>
                            </DropdownMenuTrigger>
                            <DropdownMenuContent align="end">
                                <DropdownMenuItem onClick={() => cb.onAddMilestone(p.id, null)}>마일스톤 추가</DropdownMenuItem>
                                <DropdownMenuItem onClick={() => cb.onEditProject(p)}>수정</DropdownMenuItem>
                                <DropdownMenuItem className="text-destructive" onClick={() => cb.onDeleteProject(p.id)}>삭제</DropdownMenuItem>
                            </DropdownMenuContent>
                        </DropdownMenu>
                    </div>
                    <ul className="mt-2 space-y-0.5">
                        {p.tasks.map((t) => <TaskRow key={t.id} projectId={p.id} task={t} cb={cb} />)}
                        {p.milestones.map((m) => (
                            <MilestoneRow key={m.id} projectId={p.id} milestone={m} depth={1} cb={cb} />
                        ))}
                    </ul>
                </li>
            ))}
        </ul>
    )
}
```

- [ ] **Step 4: `schedule-calendar.tsx`** — `calendarLayout` 결과를 그리기만 한다

```tsx
"use client"

import { useState } from "react"
import { ChevronLeft, ChevronRight } from "lucide-react"
import { Button } from "@/components/ui/button"
import { calendarLayout } from "@/lib/schedule-calendar"
import type { ScheduleTask } from "@/lib/schedule"

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"]
const LANE_HEIGHT = 22
const MAX_VISIBLE_LANES = 4

export default function ScheduleCalendar({ tasks, onTaskClick }: {
    tasks: ScheduleTask[]
    onTaskClick: (taskId: number) => void
}) {
    const now = new Date()
    const [year, setYear] = useState(now.getFullYear())
    const [month, setMonth] = useState(now.getMonth() + 1) // 1~12

    const weeks = calendarLayout(tasks, year, month)

    const move = (delta: number) => {
        const d = new Date(year, month - 1 + delta, 1)
        setYear(d.getFullYear())
        setMonth(d.getMonth() + 1)
    }

    return (
        <section className="rounded-lg border">
            <div className="flex items-center justify-between border-b px-3 py-2">
                <h2 className="text-sm font-semibold">{year}년 {month}월</h2>
                <div className="flex gap-1">
                    <Button variant="ghost" size="icon" className="h-7 w-7" aria-label="이전 달" onClick={() => move(-1)}>
                        <ChevronLeft className="h-4 w-4" />
                    </Button>
                    <Button variant="ghost" size="icon" className="h-7 w-7" aria-label="다음 달" onClick={() => move(1)}>
                        <ChevronRight className="h-4 w-4" />
                    </Button>
                </div>
            </div>
            <div className="grid grid-cols-7 border-b text-center text-xs text-muted-foreground">
                {WEEKDAYS.map((d) => <div key={d} className="py-1">{d}</div>)}
            </div>
            {weeks.map((week, wi) => (
                <div key={wi} className="relative grid grid-cols-7 border-b last:border-b-0"
                     style={{ minHeight: `${28 + MAX_VISIBLE_LANES * LANE_HEIGHT}px` }}>
                    {week.days.map((day, di) => (
                        <div key={di} className="border-r p-1 text-xs text-muted-foreground last:border-r-0">
                            {day ?? ""}
                        </div>
                    ))}
                    {week.segments.filter((s) => s.lane < MAX_VISIBLE_LANES).map((seg) => (
                        <button
                            key={`${seg.taskId}-${seg.startCol}`}
                            type="button"
                            onClick={() => onTaskClick(seg.taskId)}
                            className="absolute flex items-center truncate rounded px-1.5 text-xs text-white"
                            style={{
                                top: `${24 + seg.lane * LANE_HEIGHT}px`,
                                left: `calc(${(seg.startCol / 7) * 100}% + 2px)`,
                                width: `calc(${(seg.span / 7) * 100}% - 4px)`,
                                height: `${LANE_HEIGHT - 4}px`,
                                backgroundColor: seg.color,
                                borderTopLeftRadius: seg.continuesLeft ? 0 : undefined,
                                borderBottomLeftRadius: seg.continuesLeft ? 0 : undefined,
                                borderTopRightRadius: seg.continuesRight || seg.openEnded ? 0 : undefined,
                                borderBottomRightRadius: seg.continuesRight || seg.openEnded ? 0 : undefined,
                            }}
                            title={seg.name}
                        >
                            <span className="truncate">{seg.name}{seg.openEnded && !seg.continuesRight ? " →" : ""}</span>
                        </button>
                    ))}
                </div>
            ))}
        </section>
    )
}
```

- [ ] **Step 5: `app/schedule/page.tsx`** — 게이팅·fetch·필터 탭·두 섹션 조립. 다이얼로그 상태는 "무엇을 편집/생성 중인가" 하나로 관리한다.

```tsx
"use client"

import { useCallback, useEffect, useState } from "react"
import { useRouter } from "next/navigation"
import { Plus } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Skeleton } from "@/components/ui/skeleton"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import ScheduleTree from "@/components/schedule/schedule-tree"
import ScheduleCalendar from "@/components/schedule/schedule-calendar"
import ScheduleItemDialog, { type ItemDraft, type ItemKind } from "@/components/schedule/schedule-item-dialog"
import { useAuth } from "@/hooks/use-auth"
import { buildAuthHref } from "@/lib/auth-redirect"
import { scheduleAPI } from "@/lib/api"
import {
    collectTasks, filterTree, STATUS_LABELS,
    type FilteredMilestone, type FilteredProject, type ScheduleTask, type ScheduleTree as Tree, type StatusFilter,
} from "@/lib/schedule"

const SCHEDULE_PATH = "/schedule"
const EMPTY_DRAFT: ItemDraft = { name: "", status: "NOT_STARTED", startDate: null, endDate: null }

/** 어떤 다이얼로그가 열려 있는가. null 이면 닫힘. */
type DialogState =
    | { kind: "project"; mode: "create" }
    | { kind: "project"; mode: "edit"; id: number }
    | { kind: "milestone"; mode: "create"; projectId: number; parentId: number | null }
    | { kind: "milestone"; mode: "edit"; projectId: number; id: number }
    | { kind: "task"; mode: "create"; projectId: number; milestoneId: number | null }
    | { kind: "task"; mode: "edit"; projectId: number; id: number }
    | null

export default function SchedulePage() {
    const router = useRouter()
    const { loading, isAuthenticated } = useAuth()
    const [tree, setTree] = useState<Tree | null>(null)
    const [treeState, setTreeState] = useState<"loading" | "ready" | "failed">("loading")
    const [filter, setFilter] = useState<StatusFilter>("ALL")
    const [dialog, setDialog] = useState<DialogState>(null)
    const [dialogInitial, setDialogInitial] = useState<ItemDraft>(EMPTY_DRAFT)

    const fetchTree = useCallback(async () => {
        try {
            setTreeState("loading")
            setTree(await scheduleAPI.getTree())
            setTreeState("ready")
        } catch {
            setTreeState("failed")
        }
    }, [])

    useEffect(() => {
        if (loading) return
        if (!isAuthenticated) {
            router.replace(buildAuthHref("/login", SCHEDULE_PATH))
            return
        }
        void fetchTree()
    }, [loading, isAuthenticated, router, fetchTree])

    if (loading || !isAuthenticated) {
        return <div className="mx-auto max-w-4xl p-6"><Skeleton className="h-40 w-full" /></div>
    }

    const filtered = tree ? filterTree(tree, filter) : null
    const allTasks = tree ? collectTasks(tree) : []
    const calendarTasks = filter === "ALL" ? allTasks : allTasks.filter((t) => t.status === filter)

    const findTask = (taskId: number): { projectId: number; task: ScheduleTask } | null => {
        if (!tree) return null
        for (const p of tree.projects) {
            const hit = collectTasks({ projects: [p] }).find((t) => t.id === taskId)
            if (hit) return { projectId: p.id, task: hit }
        }
        return null
    }

    const openEditTask = (projectId: number, task: ScheduleTask) => {
        setDialogInitial({ name: task.name, status: task.status, startDate: task.startDate, endDate: task.endDate, color: task.color })
        setDialog({ kind: "task", mode: "edit", projectId, id: task.id })
    }

    const submit = async (draft: ItemDraft) => {
        if (!dialog) return
        const base = { name: draft.name, status: draft.status, startDate: draft.startDate, endDate: draft.endDate }
        if (dialog.kind === "project") {
            if (dialog.mode === "create") await scheduleAPI.createProject(base)
            else await scheduleAPI.updateProject(dialog.id, base)
        } else if (dialog.kind === "milestone") {
            if (dialog.mode === "create") await scheduleAPI.createMilestone({ ...base, projectId: dialog.projectId, parentId: dialog.parentId })
            else {
                const current = tree?.projects.flatMap(function walk(p): { id: number; parentId: number | null }[] {
                    const list: { id: number; parentId: number | null }[] = []
                    const visit = (ms: typeof p.milestones, parentId: number | null) => {
                        for (const m of ms) { list.push({ id: m.id, parentId }); visit(m.milestones, m.id) }
                    }
                    visit(p.milestones, null)
                    return list
                }).find((m) => m.id === dialog.id)
                await scheduleAPI.updateMilestone(dialog.id, { ...base, parentId: current?.parentId ?? null })
            }
        } else {
            if (dialog.mode === "create") await scheduleAPI.createTask({ ...base, projectId: dialog.projectId, milestoneId: dialog.milestoneId })
            else {
                const found = findTask(dialog.id)
                await scheduleAPI.updateTask(dialog.id, { ...base, milestoneId: found?.task.milestoneId ?? null, color: draft.color })
            }
        }
        await fetchTree()
    }

    const remove = async (kind: ItemKind, id: number) => {
        if (!window.confirm(kind === "project" ? "프로젝트와 하위 항목이 모두 삭제됩니다. 계속할까요?"
                : kind === "milestone" ? "마일스톤과 하위 항목이 모두 삭제됩니다. 계속할까요?"
                : "할 일을 삭제할까요?")) return
        if (kind === "project") await scheduleAPI.deleteProject(id)
        else if (kind === "milestone") await scheduleAPI.deleteMilestone(id)
        else await scheduleAPI.deleteTask(id)
        await fetchTree()
    }

    return (
        <main className="mx-auto max-w-4xl space-y-6 p-4 sm:p-6">
            <div className="flex items-center justify-between gap-2">
                <h1 className="text-xl font-bold">일정</h1>
                <Button size="sm" onClick={() => { setDialogInitial(EMPTY_DRAFT); setDialog({ kind: "project", mode: "create" }) }}>
                    <Plus className="mr-1 h-4 w-4" />새 프로젝트
                </Button>
            </div>

            <Tabs value={filter} onValueChange={(v) => setFilter(v as StatusFilter)}>
                <TabsList>
                    <TabsTrigger value="ALL">전체</TabsTrigger>
                    <TabsTrigger value="NOT_STARTED">{STATUS_LABELS.NOT_STARTED}</TabsTrigger>
                    <TabsTrigger value="IN_PROGRESS">{STATUS_LABELS.IN_PROGRESS}</TabsTrigger>
                    <TabsTrigger value="DONE">{STATUS_LABELS.DONE}</TabsTrigger>
                </TabsList>
            </Tabs>

            {treeState === "loading" ? (
                <div className="space-y-3">
                    <Skeleton className="h-24 w-full" />
                    <Skeleton className="h-24 w-full" />
                </div>
            ) : treeState === "failed" ? (
                <Alert variant="destructive">
                    <AlertDescription>
                        일정을 불러오지 못했습니다.{" "}
                        <button type="button" className="underline" onClick={() => void fetchTree()}>다시 시도</button>
                    </AlertDescription>
                </Alert>
            ) : filtered && filtered.projects.length > 0 ? (
                <ScheduleTree
                    tree={filtered}
                    cb={{
                        onAddMilestone: (projectId, parentId) => { setDialogInitial(EMPTY_DRAFT); setDialog({ kind: "milestone", mode: "create", projectId, parentId }) },
                        onAddTask: (projectId, milestoneId) => { setDialogInitial(EMPTY_DRAFT); setDialog({ kind: "task", mode: "create", projectId, milestoneId }) },
                        onEditProject: (p: FilteredProject) => {
                            setDialogInitial({ name: p.name, status: p.status, startDate: p.startDate, endDate: p.endDate })
                            setDialog({ kind: "project", mode: "edit", id: p.id })
                        },
                        onEditMilestone: (projectId, m: FilteredMilestone) => {
                            setDialogInitial({ name: m.name, status: m.status, startDate: m.startDate, endDate: m.endDate })
                            setDialog({ kind: "milestone", mode: "edit", projectId, id: m.id })
                        },
                        onEditTask: openEditTask,
                        onDeleteProject: (id) => void remove("project", id),
                        onDeleteMilestone: (id) => void remove("milestone", id),
                        onDeleteTask: (id) => void remove("task", id),
                    }}
                />
            ) : (
                <div className="rounded-lg border border-dashed p-10 text-center text-sm text-muted-foreground">
                    {filter === "ALL" ? "아직 프로젝트가 없습니다. 새 프로젝트로 시작해 보세요." : "이 상태의 항목이 없습니다."}
                </div>
            )}

            {treeState === "ready" ? (
                <ScheduleCalendar
                    tasks={calendarTasks}
                    onTaskClick={(taskId) => {
                        const found = findTask(taskId)
                        if (found) openEditTask(found.projectId, found.task)
                    }}
                />
            ) : null}

            {dialog ? (
                <ScheduleItemDialog
                    open kind={dialog.kind}
                    title={`${dialog.mode === "create" ? "새 " : ""}${dialog.kind === "project" ? "프로젝트" : dialog.kind === "milestone" ? "마일스톤" : "할 일"}${dialog.mode === "edit" ? " 수정" : ""}`}
                    initial={dialogInitial}
                    showColor={dialog.kind === "task" && dialog.mode === "edit"}
                    onSubmit={submit}
                    onClose={() => setDialog(null)}
                />
            ) : null}
        </main>
    )
}
```

- [ ] **Step 6: 검증**

Run: `cd front && npm test && npm run build`
Expected: tsc·vitest·빌드 전부 PASS. (컴포넌트는 vitest 밖이다 — 레포의 알려진 한계. 판단은 전부 Task 8~9의 lib 테스트가 이미 덮었다.)

- [ ] **Step 7: 수동 확인** (인프라·양 서버·프론트 기동 후)

`docker compose` + `./mvnw -pl app-mvc spring-boot:run` + `cd front && npm run dev` → 로그인 → 헤더에 "일정" 탭 → 프로젝트/마일스톤(중첩)/할 일 생성 → 상태 필터 전환 → 달력에 색 막대·종료 미정 화살표 → 막대 클릭 수정 → 삭제 확인.

- [ ] **Step 8: 스펙에 날짜 입력 방식 변경 한 줄 반영 + 커밋**

`docs/superpowers/specs/2026-09-01-schedule-tab-design.md` §5의 "날짜는 `Popover`+`Calendar`" 를 "날짜는 네이티브 `<input type=date>`" 로 수정.

```bash
git add front/app/schedule/ front/components/schedule/ front/components/header.tsx docs/superpowers/specs/2026-09-01-schedule-tab-design.md
git commit -m "feat(front): 일정 탭 — 트리·달력·다이얼로그와 헤더 탭 추가"
```

---

### Task 12: 최종 검증

**Files:** 없음 (검증만)

- [ ] **Step 1: 백엔드 전체**

Run: `./mvnw -pl core,app-mvc,app-webflux -am test`
Expected: 기존 358 + 신규(에러코드 3, 리포지토리 4, 서비스 13, 컨트롤러 4) 전부 PASS, known-gap 5개는 여전히 제외.

- [ ] **Step 2: core 웹 스택 무의존**

Run: `./mvnw -pl core dependency:tree | grep -E "starter-webmvc|starter-webflux|tomcat-embed|reactor-netty" && echo "FAIL: web stack leaked into core" || echo "OK"`
Expected: `OK`

- [ ] **Step 3: 프론트 전체**

Run: `cd front && npm test && npm run build`
Expected: PASS.

- [ ] **Step 4: 미커밋 잔여물 확인 후 종료**

Run: `git status`
Expected: clean. 남은 게 있으면 해당 태스크의 커밋에 합친다.
