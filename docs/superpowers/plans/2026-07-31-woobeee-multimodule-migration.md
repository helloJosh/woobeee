# woobeee 멀티모듈 이관 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `art-market-place`의 auth/blog/_common/front 코드를 신규 리포 `woobeee`의 Maven 멀티모듈(core / app-mvc / app-webflux / front)로 이관하고, product·cart를 폐기하며, 게임용 WebFlux 앱 골격까지 세워 빌드·테스트가 통과하는 상태로 만든다.

**Architecture:** parent POM(`packaging=pom`) 아래 `core`(웹 스택 무의존 라이브러리) · `app-mvc`(Tomcat+JPA, auth+blog) · `app-webflux`(Netty+R2DBC, game 골격) 3개 Maven 모듈. 두 앱은 하나의 PostgreSQL과 하나의 Redis를 공유하고, 토큰 계약(`AuthTokenType` 키 규칙 + `TokenMetadata`)을 `core`에서 공유해 app-mvc가 발급한 토큰을 app-webflux가 검증한다. 스키마는 Flyway가 단일 관리(app-mvc 소유), JPA는 `validate`. Next.js `front`가 rewrites로 두 백엔드를 단일 오리진으로 노출한다.

**Tech Stack:** Java 25, Spring Boot 4.0.5, Maven 3.9.9, PostgreSQL 18, Redis, Flyway 11.14.1, QueryDSL 5.1.0(blog 잔존), AWS SDK v2 S3(MinIO), Next.js 14 + React 18 + TypeScript + Tailwind

**설계 근거:** `docs/superpowers/specs/2026-07-31-multimodule-game-blog-restructure-design.md`

## Global Constraints

- 소스 리포: `/Users/administrator/Documents/projects/art-market-place` (읽기 전용 — **절대 수정하지 않는다**). 대상 리포: `/Users/administrator/Documents/projects/woobeee` (branch `main`).
- Java 25, `maven.compiler.release=25`. Spring Boot parent `4.0.5`.
- Boot 4 아티팩트 명명 주의: MVC 스타터는 `spring-boot-starter-webmvc`(`-web` 아님), MVC 테스트는 `spring-boot-starter-webmvc-test`, WebFlux는 `spring-boot-starter-webflux`.
- `core`는 `spring-boot-starter-webmvc` / `spring-boot-starter-webflux` 를 **의존하지 않는다**. `org.springframework:spring-web`(HttpStatus 제공, 두 스택 공통 기반)만 허용한다.
- 패키지 베이스: `com.woobeee.core` / `com.woobeee.mvc.{_common,auth,blog}` / `com.woobeee.game`.
- 폐기 대상: `product/**`, `cart/**` 백엔드 코드와 그 테스트, `docs/product/`, `docs/cart/`. 이관하지 않는다.
- 기존 HTTP 경로는 **바꾸지 않는다**: auth `= /api/auth/**`, blog `= /api/back/**`. (spec §2의 `/api/blog/*` 표기는 실제 매핑과 다르므로 rewrites 쪽을 실제 경로에 맞춘다 — Task 5 참조.)
- 포트: app-mvc `8000`, app-webflux `8001`, front `3000`, PostgreSQL `9432`, Redis `9379`, MinIO `9000/9001`.
- DB/Redis 자격증명은 기존 값 유지: PostgreSQL `market` / `root` / `123456789`, Redis 비밀번호 `root!23`.
- 매 Task 끝에 커밋한다. 커밋 메시지는 Conventional Commits.
- 검증 명령이 실패하면 완료로 처리하지 않는다. 실패 시 원인 요약 후 사용자에게 방향(수정 지속/부분 롤백/전체 롤백)을 확인한다.

### 패키지 이동 매핑 (전 Task 공통 참조)

| 원본 (art-market-place) | 대상 (woobeee) |
| --- | --- |
| `com.woobeee.artmarketplace.auth.token.**` | `com.woobeee.core.token.**` |
| `com.woobeee.artmarketplace.blog.api.ApiResponse` | `com.woobeee.core.api.ApiResponse` (**이 버전 채택**) |
| `com.woobeee.artmarketplace.auth.api.ApiResponse` | 삭제 → `com.woobeee.core.api.ApiResponse` 사용 |
| `com.woobeee.artmarketplace.auth.**` (token/api.ApiResponse 제외) | `com.woobeee.mvc.auth.**` |
| `com.woobeee.artmarketplace.blog.**` (api.ApiResponse, config 제외) | `com.woobeee.mvc.blog.**` |
| `com.woobeee.artmarketplace._common.**` | `com.woobeee.mvc._common.**` |
| `com.woobeee.artmarketplace.blog.config.RedisConfig` | `com.woobeee.mvc._common.config.RedisConfig` |
| `com.woobeee.artmarketplace.blog.config.InitConfig` | **삭제** (전체가 `if (false)` — 죽은 코드) |
| `com.woobeee.artmarketplace.product.config.StorageProperties` | `com.woobeee.mvc._common.storage.StorageProperties` |
| `com.woobeee.artmarketplace.product.config.StorageConfig` | `com.woobeee.mvc._common.storage.StorageConfig` |
| `com.woobeee.artmarketplace.ArtMarketPlaceApplication` | `com.woobeee.mvc.WoobeeeMvcApplication` |
| `com.woobeee.artmarketplace.product.**`, `cart.**` | **폐기** |

---

## File Structure

```text
woobeee/
├── pom.xml                                   parent, packaging=pom, modules: core, app-mvc, app-webflux
├── mvnw · mvnw.cmd · .mvn/wrapper/           art-market-place에서 복사
├── .gitignore · .gitattributes               복사 후 멀티모듈용으로 조정
├── CLAUDE.md                                 멀티모듈·game/blog 전제로 전면 개정
├── core/
│   ├── pom.xml                               jar, 웹 스택 무의존
│   └── src/
│       ├── main/java/com/woobeee/core/
│       │   ├── api/ApiResponse.java          공통 응답 봉투 (blog superset)
│       │   └── token/
│       │       ├── TokenStore.java · TokenGenerator.java
│       │       ├── UuidTokenGenerator.java · RedisTokenStore.java
│       │       └── dto/{AuthTokenType,TokenMetadata,TokenSnapshot}.java
│       └── test/java/com/woobeee/core/
│           ├── api/ApiResponseTest.java       헤더 계약
│           └── token/dto/AuthTokenTypeTest.java  Redis 키 계약 (두 앱 공유)
├── app-mvc/
│   ├── pom.xml                               Boot jar, starter-webmvc + JPA + Flyway
│   └── src/
│       ├── main/java/com/woobeee/mvc/
│       │   ├── WoobeeeMvcApplication.java
│       │   ├── _common/config/{CorsConfig,QuerydslConfig,RedisConfig}.java
│       │   ├── _common/storage/{StorageProperties,StorageConfig}.java
│       │   ├── _common/filter/{AccessTokenLoginIdHeaderFilter,MutableHttpServletRequest}.java
│       │   ├── auth/{api,config,controller,entity,exception,repository,service}/**
│       │   └── blog/{api,controller,entity,exception,repository,service,support}/**
│       ├── main/resources/
│       │   ├── application.yaml               port 8000, ddl-auto=validate, flyway on
│       │   └── db/migration/V1__auth_blog.sql Flyway 단일 소스
│       └── test/
│           ├── java/com/woobeee/mvc/
│           │   ├── WoobeeeMvcApplicationTests.java · SchemaValidationTest.java
│           │   └── auth/{controller,service}/**  (4개 기존 테스트 이관)
│           └── resources/mockito-extensions/org.mockito.plugins.MockMaker
├── app-webflux/
│   ├── pom.xml                               Boot jar, starter-webflux + R2DBC + reactive Redis
│   └── src/
│       ├── main/java/com/woobeee/game/
│       │   ├── WoobeeeGameApplication.java
│       │   ├── security/{ReactiveTokenVerifier,GameAuthWebFilter,GamePrincipal}.java
│       │   └── api/GameController.java        /api/game/health · /api/game/me
│       ├── main/resources/application.yaml    port 8001, r2dbc, flyway.enabled=false
│       └── test/java/com/woobeee/game/api/GameControllerTest.java  WebTestClient
├── front/                                    art-market-place/front 통째 복사 + rewrites
├── db/                                        (사용하지 않음 — 마이그레이션은 app-mvc 리소스에 둔다)
├── docs/
│   ├── superpowers/specs/ · plans/
│   ├── ARCHITECTURE.md · FRONTEND.md · DESIGN.md
│   ├── _global/{PRD.md,adr/} · _common/adr/ · auth/ · blog/ · front/
│   └── game/PRD.md                            게임 도메인 자리표시(후속 spec 링크)
└── .docker-compose/docker-compose.yml         postgres · redis · minio (kafka 제거)
```

**마이그레이션 파일 위치 결정:** spec §3 디렉토리 스케치는 루트 `db/migration/`을 그렸지만, Flyway 기본 탐색 경로는 `classpath:db/migration`이다. 마이그레이션 소유자가 app-mvc이므로 `app-mvc/src/main/resources/db/migration/`에 둔다(추가 설정 불필요). 루트 `db/`는 만들지 않는다.

---

## Task 1: 리포 골격 — parent POM + core 모듈 (공유 계약)

**Files:**
- Create: `/Users/administrator/Documents/projects/woobeee/pom.xml`
- Create: `woobeee/core/pom.xml`
- Create: `woobeee/core/src/main/java/com/woobeee/core/api/ApiResponse.java`
- Create: `woobeee/core/src/main/java/com/woobeee/core/token/TokenStore.java`
- Create: `woobeee/core/src/main/java/com/woobeee/core/token/TokenGenerator.java`
- Create: `woobeee/core/src/main/java/com/woobeee/core/token/UuidTokenGenerator.java`
- Create: `woobeee/core/src/main/java/com/woobeee/core/token/RedisTokenStore.java`
- Create: `woobeee/core/src/main/java/com/woobeee/core/token/dto/AuthTokenType.java`
- Create: `woobeee/core/src/main/java/com/woobeee/core/token/dto/TokenMetadata.java`
- Create: `woobeee/core/src/main/java/com/woobeee/core/token/dto/TokenSnapshot.java`
- Test: `woobeee/core/src/test/java/com/woobeee/core/token/dto/AuthTokenTypeTest.java`
- Test: `woobeee/core/src/test/java/com/woobeee/core/api/ApiResponseTest.java`
- Copy: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`, `.gitattributes`, `.gitignore`

**Interfaces:**
- Consumes: 없음 (첫 Task)
- Produces:
  - `com.woobeee.core.api.ApiResponse<T>` — `record ApiResponse<T>(Header header, T data)`, `record Header(boolean isSuccessful, String message, int resultCode)`. 정적 팩토리: `success(T,String)`, `success(String)`, `createSuccess(T,String)`, `createSuccess(String)`, `deleteSuccess(String)`, `fail(HttpStatus,String)`.
  - `com.woobeee.core.token.dto.AuthTokenType` — enum `ACCESS`(TTL 15분) / `REFRESH`(TTL 30일). 메서드: `Duration ttl()`, `String redisKey(String token)`, `String reverseKey(Long memberId, String device)`.
  - `com.woobeee.core.token.dto.TokenMetadata` — `record TokenMetadata(Long memberId, String role, String device, String ip)`.
  - `com.woobeee.core.token.dto.TokenSnapshot` — `record TokenSnapshot(TokenMetadata metadata, long ttlSeconds)`.
  - `com.woobeee.core.token.TokenStore` — `void save(String, AuthTokenType, TokenMetadata)`, `Optional<TokenSnapshot> find(String, AuthTokenType)`, `void delete(String, AuthTokenType)`.
  - `com.woobeee.core.token.TokenGenerator` — `String nextToken()`.
  - `com.woobeee.core.token.RedisTokenStore` — `@Repository`, 생성자 인자 `StringRedisTemplate`.
  - Maven 좌표: `com.woobeee:core:0.0.1-SNAPSHOT`.

- [ ] **Step 1: 대상 리포 확인 및 빌드 래퍼 복사**

```bash
cd /Users/administrator/Documents/projects/woobeee
git status --short                 # 깨끗해야 한다
git branch --show-current          # main

SRC=/Users/administrator/Documents/projects/art-market-place
mkdir -p .mvn/wrapper
cp "$SRC/mvnw" "$SRC/mvnw.cmd" .
cp "$SRC/.mvn/wrapper/maven-wrapper.properties" .mvn/wrapper/
cp "$SRC/.gitattributes" .
cp "$SRC/.gitignore" .
chmod +x mvnw
```

- [ ] **Step 2: `.gitignore` 를 멀티모듈용으로 조정**

`front/node_modules/` · `front/.next/` 줄은 그대로 두고, 파일 끝에 아래를 추가한다(모듈별 target은 이미 `**/target/`로 커버됨).

```gitignore

# 멀티모듈 로컬 산출물
*/logs/
front/tsconfig.tsbuildinfo
front/pnpm-lock.yaml
```

- [ ] **Step 3: parent POM 작성**

`woobeee/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.0.5</version>
        <relativePath/>
    </parent>

    <groupId>com.woobeee</groupId>
    <artifactId>woobeee</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>woobeee</name>
    <description>Multimodule app: game(WebFlux) + blog(MVC)</description>
    <packaging>pom</packaging>

    <!-- 모듈은 생기는 순서대로 추가한다. 존재하지 않는 디렉토리를 선언하면
         Maven이 "Child module ... does not exist" 로 리액터 전체를 거부하므로,
         app-mvc는 Task 2, app-webflux는 Task 4에서 각각 추가한다. -->
    <modules>
        <module>core</module>
    </modules>

    <properties>
        <java.version>25</java.version>
        <maven.compiler.release>25</maven.compiler.release>
        <lombok.version>1.18.44</lombok.version>
        <querydsl.version>5.1.0</querydsl.version>
        <awssdk.version>2.25.24</awssdk.version>
        <postgresql.version>42.7.3</postgresql.version>
        <commons-dbcp2.version>2.13.0</commons-dbcp2.version>
        <google-api-client.version>2.7.2</google-api-client.version>
        <springdoc.version>3.0.2</springdoc.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>com.woobeee</groupId>
                <artifactId>core</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.querydsl</groupId>
                <artifactId>querydsl-jpa</artifactId>
                <version>${querydsl.version}</version>
                <classifier>jakarta</classifier>
            </dependency>
            <dependency>
                <groupId>software.amazon.awssdk</groupId>
                <artifactId>s3</artifactId>
                <version>${awssdk.version}</version>
            </dependency>
            <dependency>
                <groupId>org.postgresql</groupId>
                <artifactId>postgresql</artifactId>
                <version>${postgresql.version}</version>
            </dependency>
            <dependency>
                <groupId>org.apache.commons</groupId>
                <artifactId>commons-dbcp2</artifactId>
                <version>${commons-dbcp2.version}</version>
            </dependency>
            <dependency>
                <groupId>com.google.api-client</groupId>
                <artifactId>google-api-client</artifactId>
                <version>${google-api-client.version}</version>
            </dependency>
            <dependency>
                <groupId>org.springdoc</groupId>
                <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
                <version>${springdoc.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <version>${lombok.version}</version>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <configuration>
                        <release>${maven.compiler.release}</release>
                        <annotationProcessorPaths>
                            <path>
                                <groupId>org.projectlombok</groupId>
                                <artifactId>lombok</artifactId>
                                <version>${lombok.version}</version>
                            </path>
                        </annotationProcessorPaths>
                    </configuration>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
</project>
```

- [ ] **Step 4: core POM 작성**

`woobeee/core/pom.xml` — 웹 스택 스타터를 넣지 않는다. `spring-web`은 `ApiResponse.fail(HttpStatus, ...)` 때문에 필요하며 MVC/WebFlux 공통 기반이라 허용된다.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.woobeee</groupId>
        <artifactId>woobeee</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <artifactId>core</artifactId>
    <name>core</name>
    <description>Web-stack-agnostic shared contracts: ApiResponse, token store</description>
    <packaging>jar</packaging>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-web</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-annotations</artifactId>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 5: core 실패 테스트 작성 — Redis 키 계약**

이 계약이 app-mvc와 app-webflux가 토큰을 공유하는 유일한 접점이므로 테스트로 고정한다.

`core/src/test/java/com/woobeee/core/token/dto/AuthTokenTypeTest.java`:

```java
package com.woobeee.core.token.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AuthTokenTypeTest {

    @Test
    void accessTokenKeyContractIsStableAcrossApps() {
        assertThat(AuthTokenType.ACCESS.redisKey("tok-1"))
                .isEqualTo("auth:token:access:tok-1");
        assertThat(AuthTokenType.ACCESS.reverseKey(7L, "ios"))
                .isEqualTo("auth:user-token:access:7:ios");
        assertThat(AuthTokenType.ACCESS.ttl()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void refreshTokenKeyContractIsStableAcrossApps() {
        assertThat(AuthTokenType.REFRESH.redisKey("tok-2"))
                .isEqualTo("auth:token:refresh:tok-2");
        assertThat(AuthTokenType.REFRESH.reverseKey(9L, "web"))
                .isEqualTo("auth:user-token:refresh:9:web");
        assertThat(AuthTokenType.REFRESH.ttl()).isEqualTo(Duration.ofDays(30));
    }
}
```

`core/src/test/java/com/woobeee/core/api/ApiResponseTest.java`:

```java
package com.woobeee.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ApiResponseTest {

    @Test
    void successCarriesOkResultCode() {
        ApiResponse<String> response = ApiResponse.success("payload", "fetched");

        assertThat(response.header().isSuccessful()).isTrue();
        assertThat(response.header().message()).isEqualTo("fetched");
        assertThat(response.header().resultCode()).isEqualTo(200);
        assertThat(response.data()).isEqualTo("payload");
    }

    @Test
    void createSuccessCarriesCreatedResultCode() {
        ApiResponse<String> response = ApiResponse.createSuccess("payload", "created");

        assertThat(response.header().resultCode()).isEqualTo(201);
    }

    @Test
    void deleteSuccessCarriesNoContentResultCodeAndNullData() {
        ApiResponse<Object> response = ApiResponse.deleteSuccess("deleted");

        assertThat(response.header().resultCode()).isEqualTo(204);
        assertThat(response.data()).isNull();
    }

    @Test
    void failCarriesErrorCodeAndTimestamp() {
        var response = ApiResponse.fail(HttpStatus.NOT_FOUND, "missing");

        assertThat(response.header().isSuccessful()).isFalse();
        assertThat(response.header().resultCode()).isEqualTo(404);
        assertThat(response.data()).isNotNull();
    }
}
```

- [ ] **Step 6: 테스트가 실패하는지 확인**

```bash
cd /Users/administrator/Documents/projects/woobeee
./mvnw -q -pl core -am test
```

Expected: 컴파일 실패 — `package com.woobeee.core.token.dto does not exist`, `package com.woobeee.core.api does not exist`.

- [ ] **Step 7: core 소스 작성 — 토큰 계약**

`core/src/main/java/com/woobeee/core/token/dto/AuthTokenType.java`:

```java
package com.woobeee.core.token.dto;

import java.time.Duration;

public enum AuthTokenType {
    ACCESS(Duration.ofMinutes(15)),
    REFRESH(Duration.ofDays(30));

    private final Duration ttl;

    AuthTokenType(Duration ttl) {
        this.ttl = ttl;
    }

    public Duration ttl() {
        return ttl;
    }

    public String redisKey(String token) {
        return "auth:token:" + name().toLowerCase() + ":" + token;
    }

    public String reverseKey(Long memberId, String device) {
        return "auth:user-token:" + name().toLowerCase() + ":" + memberId + ":" + device;
    }
}
```

`core/src/main/java/com/woobeee/core/token/dto/TokenMetadata.java`:

```java
package com.woobeee.core.token.dto;

public record TokenMetadata(
        Long memberId,
        String role,
        String device,
        String ip
) {
}
```

`core/src/main/java/com/woobeee/core/token/dto/TokenSnapshot.java`:

```java
package com.woobeee.core.token.dto;

public record TokenSnapshot(
        TokenMetadata metadata,
        long ttlSeconds
) {
}
```

`core/src/main/java/com/woobeee/core/token/TokenGenerator.java`:

```java
package com.woobeee.core.token;

public interface TokenGenerator {
    String nextToken();
}
```

`core/src/main/java/com/woobeee/core/token/UuidTokenGenerator.java`:

```java
package com.woobeee.core.token;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class UuidTokenGenerator implements TokenGenerator {
    @Override
    public String nextToken() {
        return UUID.randomUUID().toString();
    }
}
```

`core/src/main/java/com/woobeee/core/token/TokenStore.java`:

```java
package com.woobeee.core.token;

import com.woobeee.core.token.dto.AuthTokenType;
import com.woobeee.core.token.dto.TokenMetadata;
import com.woobeee.core.token.dto.TokenSnapshot;
import java.util.Optional;

public interface TokenStore {
    void save(String token, AuthTokenType tokenType, TokenMetadata metadata);

    Optional<TokenSnapshot> find(String token, AuthTokenType tokenType);

    void delete(String token, AuthTokenType tokenType);
}
```

`core/src/main/java/com/woobeee/core/token/RedisTokenStore.java` — 원본 `art-market-place/src/main/java/com/woobeee/artmarketplace/auth/token/RedisTokenStore.java` 를 그대로 옮기고 import 만 `com.woobeee.core.token.dto.*` 로 바꾼다. 전문:

```java
package com.woobeee.core.token;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.woobeee.core.token.dto.AuthTokenType;
import com.woobeee.core.token.dto.TokenMetadata;
import com.woobeee.core.token.dto.TokenSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RedisTokenStore implements TokenStore {
    private final StringRedisTemplate redisTemplate;

    @Override
    public void save(String token, AuthTokenType tokenType, TokenMetadata metadata) {
        String key = tokenType.redisKey(token);
        String reverseKey = tokenType.reverseKey(metadata.memberId(), metadata.device());

        redisTemplate.opsForHash().putAll(
                key,
                Map.of(
                "memberId", String.valueOf(metadata.memberId()),
                "role", metadata.role(),
                "device", metadata.device(),
                "ip", metadata.ip())
        );
        redisTemplate.expire(key, tokenType.ttl());
        redisTemplate.opsForValue().set(reverseKey, token, tokenType.ttl());
    }

    @Override
    public Optional<TokenSnapshot> find(String token, AuthTokenType tokenType) {
        String key = tokenType.redisKey(token);
        Map<Object, Object> values = redisTemplate.opsForHash().entries(key);
        if (values.isEmpty()) {
            return Optional.empty();
        }

        Long ttlSeconds = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (ttlSeconds == null || ttlSeconds <= 0) {
            return Optional.empty();
        }

        Object memberId = values.get("memberId");
        Object role = values.get("role");
        Object device = values.get("device");
        Object ip = values.get("ip");
        if (memberId == null || role == null || device == null || ip == null) {
            return Optional.empty();
        }

        return Optional.of(new TokenSnapshot(
                new TokenMetadata(
                        Long.valueOf(memberId.toString()),
                        role.toString(),
                        device.toString(),
                        ip.toString()
                ),
                ttlSeconds
        ));
    }

    @Override
    public void delete(String token, AuthTokenType tokenType) {
        Optional<TokenSnapshot> snapshot = find(token, tokenType);
        redisTemplate.delete(tokenType.redisKey(token));

        snapshot.ifPresent(value -> {
            String reverseKey = tokenType.reverseKey(value.metadata().memberId(), value.metadata().device());
            String currentToken = redisTemplate.opsForValue().get(reverseKey);
            if (token.equals(currentToken)) {
                redisTemplate.delete(reverseKey);
            }
        });
    }
}
```

- [ ] **Step 8: core 소스 작성 — 통합 ApiResponse**

blog 버전(`resultCode` 포함)을 채택한다. auth 호출부는 `success`/`fail` 만 쓰므로 그대로 컴파일되며, auth 응답 JSON에 `header.resultCode` 필드가 추가되는 것이 유일한 계약 변화다(순수 추가 — front 영향 없음).

`core/src/main/java/com/woobeee/core/api/ApiResponse.java`:

```java
package com.woobeee.core.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
public record ApiResponse<T>(
        Header header,
        T data
) {

    @Builder
    public record Header(
            boolean isSuccessful,
            String message,
            int resultCode
    ) {}

    /* ===== success ===== */

    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(
                new Header(true, message, HttpStatus.OK.value()),
                data
        );
    }

    public static <T> ApiResponse<T> success(String message) {
        return new ApiResponse<>(
                new Header(true, message, HttpStatus.OK.value()),
                null
        );
    }

    public static <T> ApiResponse<T> createSuccess(T data, String message) {
        return new ApiResponse<>(
                new Header(true, message, HttpStatus.CREATED.value()),
                data
        );
    }

    public static <T> ApiResponse<T> createSuccess(String message) {
        return new ApiResponse<>(
                new Header(true, message, HttpStatus.CREATED.value()),
                null
        );
    }

    public static <T> ApiResponse<T> deleteSuccess(String message) {
        return new ApiResponse<>(
                new Header(true, message, HttpStatus.NO_CONTENT.value()),
                null
        );
    }

    /* ===== fail ===== */

    public static ApiResponse<LocalDateTime> fail(HttpStatus errorCode, String message) {
        return new ApiResponse<>(
                new Header(false, message, errorCode.value()),
                LocalDateTime.now()
        );
    }
}
```

- [ ] **Step 9: 테스트 통과 확인**

```bash
cd /Users/administrator/Documents/projects/woobeee
./mvnw -pl core -am test
```

Expected: `AuthTokenTypeTest` 2건 + `ApiResponseTest` 4건 PASS, `BUILD SUCCESS`.

- [ ] **Step 10: core가 웹 스타터를 끌어오지 않았는지 확인**

```bash
cd /Users/administrator/Documents/projects/woobeee
./mvnw -pl core dependency:tree | grep -E "starter-webmvc|starter-webflux|tomcat-embed|reactor-netty" && echo "FAIL: web stack leaked into core" || echo "OK: no web stack in core"
```

Expected: `OK: no web stack in core`.

> `-q` 를 쓰면 `dependency:tree` 출력 자체가 억제되어 grep이 항상 빈 입력을 받는다 — 통과가 아니라
> 무의미한 검사가 된다. 반드시 `-q` 없이 실행한다. `tomcat`/`netty` 대신
> `tomcat-embed`/`reactor-netty` 로 좁힌 이유는 Lettuce가 순수 `netty` 코어를 정당하게
> 끌어오기 때문이다(웹 스택이 아니다).

- [ ] **Step 11: 커밋**

```bash
cd /Users/administrator/Documents/projects/woobeee
git add pom.xml core mvnw mvnw.cmd .mvn .gitignore .gitattributes
git commit -m "feat(core): add parent POM and web-stack-agnostic core module

- parent POM (packaging=pom) with core/app-mvc/app-webflux modules
- core: ApiResponse (resultCode-carrying superset), token contract shared by both apps
- AuthTokenTypeTest pins the Redis key contract that app-mvc and app-webflux share"
```

---

## Task 2: app-mvc — auth + blog 이관, product/cart 결합 제거

**Files:**
- Create: `woobeee/app-mvc/pom.xml`
- Create: `woobeee/app-mvc/src/main/java/com/woobeee/mvc/WoobeeeMvcApplication.java`
- Create: `woobeee/app-mvc/src/main/resources/application.yaml`
- Copy+rename: `art-market-place/src/main/java/com/woobeee/artmarketplace/{auth,blog,_common}/**` → `woobeee/app-mvc/src/main/java/com/woobeee/mvc/{auth,blog,_common}/**`
- Delete after copy: `mvc/auth/api/ApiResponse.java`, `mvc/blog/api/ApiResponse.java`, `mvc/auth/token/**`, `mvc/blog/config/InitConfig.java`
- Move: `mvc/blog/config/RedisConfig.java` → `mvc/_common/config/RedisConfig.java`
- Create (from product): `mvc/_common/storage/StorageProperties.java`, `mvc/_common/storage/StorageConfig.java`
- Modify: `mvc/auth/service/AuthService.java` (cart 이벤트 결합 제거)
- Modify: `mvc/auth/api/request/BuyerSignupRequest.java`, `LoginRequest.java`, `mvc/auth/service/dto/GoogleAuthorizationContext.java`, `mvc/auth/service/RedisGoogleAuthorizationStateStore.java` (guestCartToken 제거)
- Modify: `mvc/auth/entity/Address.java` (`UUID memberId` → `Long memberId`)
- Copy+rename tests: `auth/{controller,service}` 4개 + `mockito-extensions`
- Create: `woobeee/app-mvc/src/test/java/com/woobeee/mvc/WoobeeeMvcApplicationTests.java`

**Interfaces:**
- Consumes: Task 1의 `com.woobeee.core.api.ApiResponse`, `com.woobeee.core.token.{TokenStore,TokenGenerator,UuidTokenGenerator,RedisTokenStore}`, `com.woobeee.core.token.dto.{AuthTokenType,TokenMetadata,TokenSnapshot}`
- Produces:
  - `com.woobeee.mvc.WoobeeeMvcApplication` — Boot 진입점, `@SpringBootApplication` + `@ConfigurationPropertiesScan`
  - `com.woobeee.mvc._common.storage.StorageProperties` — `@ConfigurationProperties("storage.s3")`, getter: `getEndpoint/getRegion/getBucket/getAccessKey/getSecretKey/getPresignedUrlExpirationSeconds/isPathStyleAccessEnabled`
  - `com.woobeee.mvc._common.storage.StorageConfig` — `S3Configuration`, `S3Client`, `S3Presigner` 빈 제공
  - `com.woobeee.mvc._common.config.RedisConfig` — `StringRedisTemplate` 빈 제공(core `RedisTokenStore`가 주입받음)
  - `com.woobeee.mvc.auth.entity.{Buyer,Seller,Address,MemberType}`, `com.woobeee.mvc.auth.repository.{BuyerRepository,SellerRepository}`
  - HTTP 경로 불변: `/api/auth/**`, `/api/back/{posts,comments,likes,categories}`
  - Maven 좌표: `com.woobeee:app-mvc:0.0.1-SNAPSHOT`

- [ ] **Step 1: app-mvc POM 작성**

`woobeee/app-mvc/pom.xml`. 원본 POM에서 auth/blog가 실제로 쓰지 않는 것(`spring-session-data-redis`, `spring-security-crypto`)은 제외했다. QueryDSL은 `blog/repository/PostQueryRepositoryImpl`이 아직 사용하므로 유지한다(네이티브 SQL 전환은 후속 과제).

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.woobeee</groupId>
        <artifactId>woobeee</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <artifactId>app-mvc</artifactId>
    <name>app-mvc</name>
    <description>Blocking surface: auth + blog on Spring MVC / JPA</description>
    <packaging>jar</packaging>

    <dependencies>
        <dependency>
            <groupId>com.woobeee</groupId>
            <artifactId>core</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <!-- core 를 통해 전이로도 들어오지만, 이 모듈이 StringRedisTemplate 을 직접
             구성하므로(_common/config/RedisConfig, blog/support/RedisSupport) 명시 선언한다. -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <dependency>
            <groupId>com.querydsl</groupId>
            <artifactId>querydsl-jpa</artifactId>
            <classifier>jakarta</classifier>
        </dependency>

        <!-- Boot 4는 FlywayAutoConfiguration 을 spring-boot-autoconfigure 에서 떼어내
             별도 모듈 spring-boot-flyway 로 옮겼다. 이것이 없으면 flyway-core 가 classpath
             에 있어도 자동설정이 로드되지 않아 `spring.flyway.*` 가 조용히 무시되고
             마이그레이션이 아예 실행되지 않는다 (에러도 나지 않는다). 반드시 함께 선언한다. -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-flyway</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-dbcp2</artifactId>
        </dependency>

        <dependency>
            <groupId>com.google.api-client</groupId>
            <artifactId>google-api-client</artifactId>
        </dependency>
        <dependency>
            <groupId>software.amazon.awssdk</groupId>
            <artifactId>s3</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <release>${maven.compiler.release}</release>
                    <annotationProcessorPaths>
                        <path>
                            <groupId>com.querydsl</groupId>
                            <artifactId>querydsl-apt</artifactId>
                            <version>${querydsl.version}</version>
                            <classifier>jakarta</classifier>
                        </path>
                        <path>
                            <groupId>jakarta.persistence</groupId>
                            <artifactId>jakarta.persistence-api</artifactId>
                            <version>3.2.0</version>
                        </path>
                        <path>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                            <version>${lombok.version}</version>
                        </path>
                    </annotationProcessorPaths>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

그리고 **루트 `pom.xml` 의 `<modules>` 에 `app-mvc` 를 등록**한다(Task 1은 `core` 만 선언해 두었다).

```xml
    <modules>
        <module>core</module>
        <module>app-mvc</module>
    </modules>
```

`app-webflux` 는 아직 존재하지 않으므로 추가하지 않는다 — Task 4가 등록한다.

- [ ] **Step 2: auth / blog / _common 소스 복사 후 패키지 일괄 치환**

```bash
cd /Users/administrator/Documents/projects/woobeee
SRC=/Users/administrator/Documents/projects/art-market-place/src/main/java/com/woobeee/artmarketplace
DST=app-mvc/src/main/java/com/woobeee/mvc
mkdir -p "$DST"
cp -R "$SRC/auth" "$SRC/blog" "$SRC/_common" "$DST/"

# 폐기/이동 대상 제거
rm -rf "$DST/auth/token"
rm -f  "$DST/auth/api/ApiResponse.java"
rm -f  "$DST/blog/api/ApiResponse.java"
rm -f  "$DST/blog/config/InitConfig.java"      # 전체가 if(false) — 죽은 코드

# blog/config/RedisConfig -> _common/config/RedisConfig
mv "$DST/blog/config/RedisConfig.java" "$DST/_common/config/RedisConfig.java"
rmdir "$DST/blog/config"

# product/config -> _common/storage
mkdir -p "$DST/_common/storage"
cp /Users/administrator/Documents/projects/art-market-place/src/main/java/com/woobeee/artmarketplace/product/config/StorageProperties.java \
   /Users/administrator/Documents/projects/art-market-place/src/main/java/com/woobeee/artmarketplace/product/config/StorageConfig.java \
   "$DST/_common/storage/"

# 패키지/임포트 일괄 치환 (순서 중요: 좁은 규칙 먼저)
find "$DST" -name '*.java' -print0 | xargs -0 sed -i '' \
  -e 's|com\.woobeee\.artmarketplace\.auth\.token|com.woobeee.core.token|g' \
  -e 's|com\.woobeee\.artmarketplace\.auth\.api\.ApiResponse|com.woobeee.core.api.ApiResponse|g' \
  -e 's|com\.woobeee\.artmarketplace\.blog\.api\.ApiResponse|com.woobeee.core.api.ApiResponse|g' \
  -e 's|com\.woobeee\.artmarketplace\.blog\.config\.RedisConfig|com.woobeee.mvc._common.config.RedisConfig|g' \
  -e 's|com\.woobeee\.artmarketplace\.product\.config|com.woobeee.mvc._common.storage|g' \
  -e 's|com\.woobeee\.artmarketplace|com.woobeee.mvc|g'
```

- [ ] **Step 3: 치환 잔여물 확인**

```bash
cd /Users/administrator/Documents/projects/woobeee
grep -rn "artmarketplace" app-mvc/src/main/java && echo "FAIL: 잔여 참조 있음" || echo "OK: 잔여 참조 없음"
grep -rn "com.woobeee.mvc.product\|com.woobeee.mvc.cart" app-mvc/src/main/java && echo "FAIL: 폐기 도메인 참조" || echo "OK"
```

Expected: 두 줄 모두 `OK`. 두 번째가 FAIL이면 남은 참조는 `auth/service/AuthService.java`의 cart 이벤트 하나뿐이어야 하며 Step 5에서 제거된다.

- [ ] **Step 4: `_common/storage` 패키지 선언 수정**

`sed`는 `com.woobeee.artmarketplace.product.config` → `com.woobeee.mvc._common.storage`를 이미 바꿨으므로 두 파일의 `package` 줄은 맞다. 다음으로 확인만 한다.

```bash
head -1 app-mvc/src/main/java/com/woobeee/mvc/_common/storage/StorageProperties.java
head -1 app-mvc/src/main/java/com/woobeee/mvc/_common/storage/StorageConfig.java
```

Expected: 둘 다 `package com.woobeee.mvc._common.storage;`

- [ ] **Step 5: `AuthService`에서 cart 이벤트 결합 제거**

`app-mvc/src/main/java/com/woobeee/mvc/auth/service/AuthService.java` 에서 네 곳을 편집한다.

(a) import 삭제:

```java
import com.woobeee.mvc.cart.event.MemberAuthenticatedEvent;
```

(b) import 삭제:

```java
import org.springframework.context.ApplicationEventPublisher;
```

(c) 필드 삭제:

```java
    private final ApplicationEventPublisher eventPublisher;
```

(d) 메서드 전체 삭제:

```java
    private void publishGuestCartMerge(Long buyerId, String guestCartToken) {
        if (!StringUtils.hasText(guestCartToken)) {
            return;
        }
        eventPublisher.publishEvent(new MemberAuthenticatedEvent(buyerId, guestCartToken));
    }
```

(e) 호출부 2곳 삭제 — 하나는 단독 문장이므로 줄 삭제:

```java
        publishGuestCartMerge(buyer.getId(), context.guestCartToken());
```

(f) 다른 하나는 람다 본문 안에 있다. 아래 3줄을

```java
                                createSession(buyer.getId(), context.memberType().roleName(), context.device(), ip);
                        publishGuestCartMerge(buyer.getId(), context.guestCartToken());
                        return session;
```

다음으로 바꾼다:

```java
                                createSession(buyer.getId(), context.memberType().roleName(), context.device(), ip);
                        return session;
```

- [ ] **Step 6: `guestCartToken` 필드 제거 (cart 폐기로 무의미해진 파라미터)**

front는 이 필드를 전송하지 않으므로 안전하게 제거한다.

(a) `mvc/auth/api/request/BuyerSignupRequest.java` — 마지막 컴포넌트 `String guestCartToken` 과 그 앞 콤마를 제거.

(b) `mvc/auth/api/request/LoginRequest.java` — 동일하게 `String guestCartToken` 제거.

(c) `mvc/auth/service/dto/GoogleAuthorizationContext.java` — 마지막 컴포넌트 `String guestCartToken` 과 그 앞 콤마를 제거. 결과:

```java
public record GoogleAuthorizationContext(
        GoogleAuthorizationAction action,
        String codeVerifier,
        String device,
        MemberType memberType,
        String nickname,
        boolean termsAgreed,
        boolean privacyPolicyAgreed,
        String businessRegistrationCertificateUrl
) {
}
```

(d) `mvc/auth/service/AuthService.java` — `GoogleAuthorizationContext` 생성 2곳(원본 59행·87행 근처)에서 마지막 인자 `normalizeOptionalText(request.guestCartToken())` 과 그 앞 콤마를 제거.

(e) `mvc/auth/service/RedisGoogleAuthorizationStateStore.java` — 저장 시 `"guestCartToken", context.guestCartToken() == null ? "" : context.guestCartToken()` 항목과 그 앞 콤마를 제거하고, 복원 시 마지막 인자 `normalizeBlank(readString(values, "guestCartToken"))` 과 그 앞 콤마를 제거.

- [ ] **Step 7: `Address.memberId` 타입 정합화 (spec §9 리스크 해소)**

`address` 테이블은 코드 어디서도 조회되지 않는 미사용 엔티티이고, buyers/sellers PK는 `BIGINT`다. 신규 스키마에서 회원 참조 타입을 통일한다.

`mvc/auth/entity/Address.java` 에서:

```java
import java.util.UUID;
```

줄을 삭제하고,

```java
    private UUID memberId;
```

를 다음으로 바꾼다:

```java
    private Long memberId;
```

- [ ] **Step 8: Boot 진입점 작성**

`app-mvc/src/main/java/com/woobeee/mvc/WoobeeeMvcApplication.java`. `core`의 `@Repository`/`@Component`(RedisTokenStore, UuidTokenGenerator)를 스캔하려면 `com.woobeee` 를 스캔 베이스로 잡아야 한다.

```java
package com.woobeee.mvc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = {"com.woobeee.mvc", "com.woobeee.core"})
@ConfigurationPropertiesScan(basePackages = {"com.woobeee.mvc", "com.woobeee.core"})
public class WoobeeeMvcApplication {
    public static void main(String[] args) {
        SpringApplication.run(WoobeeeMvcApplication.class, args);
    }
}
```

- [ ] **Step 9: `application.yaml` 작성 (Flyway는 Task 3에서 켠다)**

`app-mvc/src/main/resources/application.yaml`:

```yaml
server:
  shutdown: graceful
  port: 8000
  ssl:
    enabled: false

oauth:
  google:
    client-id: 985212099499-uel8drlne8o4et7e3u1gbnjjvdi0k11l.apps.googleusercontent.com
    client-secret: ${GOOGLE_CLIENT_SECRET:change-me}
    redirect-uri: ${GOOGLE_REDIRECT_URI:http://localhost:3000/auth/google/callback}
    authorization-uri: https://accounts.google.com/o/oauth2/v2/auth
    token-uri: https://oauth2.googleapis.com/token
    scope: openid email profile
    authorization-state-ttl-seconds: 600
    connect-timeout-seconds: 5
    read-timeout-seconds: 10

storage:
  s3:
    endpoint: ${S3_ENDPOINT:http://localhost:9000}
    region: ${S3_REGION:ap-northeast-2}
    bucket: ${S3_BUCKET:woobeee}
    access-key: ${S3_ACCESS_KEY:admin}
    secret-key: ${S3_SECRET_KEY:admin!23}
    presigned-url-expiration-seconds: 600
    path-style-access-enabled: true

spring:
  application:
    name: woobeee-app-mvc
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:9379}
      password: ${REDIS_PASSWORD:root!23}
  jpa:
    open-in-view: false
    show-sql: true
    properties:
      hibernate:
        format_sql: true
        use_sql_comments: true
        highlight_sql: true
        auto_quote_keyword: true

  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:9432/market}
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:123456789}
    driver-class-name: org.postgresql.Driver
    type: org.apache.commons.dbcp2.BasicDataSource
    dbcp2:
      initial-size: 5
      max-total: 30
      max-idle: 15
      min-idle: 5
      max-wait-millis: 10000
      validation-query: SELECT 1
      test-on-borrow: true
      test-while-idle: true
      time-between-eviction-runs-millis: 5000
      min-evictable-idle-time-millis: 60000

logging:
  level:
    org.hibernate.orm.jdbc.bind: trace
    org.springframework.transaction.interceptor: trace
  file:
    name: logs/app-mvc.log
```

- [ ] **Step 10: 테스트 이관 — auth 테스트 4개 + mockito 설정**

```bash
cd /Users/administrator/Documents/projects/woobeee
SRCT=/Users/administrator/Documents/projects/art-market-place/src/test
DSTT=app-mvc/src/test/java/com/woobeee/mvc
mkdir -p "$DSTT" app-mvc/src/test/resources
cp -R "$SRCT/java/com/woobeee/artmarketplace/auth" "$DSTT/"
cp -R "$SRCT/resources/mockito-extensions" app-mvc/src/test/resources/

find "$DSTT" -name '*.java' -print0 | xargs -0 sed -i '' \
  -e 's|com\.woobeee\.artmarketplace\.auth\.token|com.woobeee.core.token|g' \
  -e 's|com\.woobeee\.artmarketplace\.auth\.api\.ApiResponse|com.woobeee.core.api.ApiResponse|g' \
  -e 's|com\.woobeee\.artmarketplace|com.woobeee.mvc|g'
```

- [ ] **Step 11: 이관 테스트에서 제거된 `guestCartToken` 인자 정리**

레코드를 위치 인자로 생성하는 4곳에서 마지막 `null` 을 지운다.

(a) `app-mvc/src/test/java/com/woobeee/mvc/auth/service/AuthServiceTest.java`

```java
        BuyerSignupRequest request = new BuyerSignupRequest("buyer-nick", true, true, "ios", null);
```
→
```java
        BuyerSignupRequest request = new BuyerSignupRequest("buyer-nick", true, true, "ios");
```

```java
        LoginRequest request = new LoginRequest(MemberType.BUYER, "android", null);
```
→
```java
        LoginRequest request = new LoginRequest(MemberType.BUYER, "android");
```

같은 파일의 `GoogleAuthorizationContext` 생성 2곳에서 마지막 인자 `null` 하나씩 제거 (인자 9개 → 8개):

```java
        GoogleAuthorizationContext context = new GoogleAuthorizationContext(
                GoogleAuthorizationAction.BUYER_SIGNUP,
                "code-verifier",
                "ios",
                null,
                "buyer-nick",
                true,
                true,
                null
        );
```

```java
        GoogleAuthorizationContext context = new GoogleAuthorizationContext(
                GoogleAuthorizationAction.LOGIN,
                "code-verifier",
                "web",
                MemberType.SELLER,
                null,
                false,
                false,
                null
        );
```

(b) `app-mvc/src/test/java/com/woobeee/mvc/auth/controller/AuthControllerTest.java` — 3곳:

```java
        BuyerSignupRequest request = new BuyerSignupRequest("buyer-nick", true, true, "ios", null);
```
→
```java
        BuyerSignupRequest request = new BuyerSignupRequest("buyer-nick", true, true, "ios");
```

```java
        LoginRequest request = new LoginRequest(MemberType.BUYER, "android", null);
```
→
```java
        LoginRequest request = new LoginRequest(MemberType.BUYER, "android");
```

```java
        BuyerSignupRequest request = new BuyerSignupRequest(" ", true, true, "ios", null);
```
→
```java
        BuyerSignupRequest request = new BuyerSignupRequest(" ", true, true, "ios");
```

- [ ] **Step 12: 컨텍스트 로딩 테스트 작성**

원본 `ArtMarketPlaceApplicationTests`에서 product/cart 빈을 제거하고 이름을 바꾼 버전. DataSource/Hibernate autoconfig를 제외하므로 PostgreSQL 없이 통과한다.

`app-mvc/src/test/java/com/woobeee/mvc/WoobeeeMvcApplicationTests.java`:

```java
package com.woobeee.mvc;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.woobeee.core.token.TokenStore;
import com.woobeee.mvc.auth.repository.BuyerRepository;
import com.woobeee.mvc.auth.repository.SellerRepository;
import com.woobeee.mvc.auth.service.AuthService;
import com.woobeee.mvc.auth.service.TokenService;
import com.woobeee.mvc.blog.repository.CategoryRepository;
import com.woobeee.mvc.blog.repository.CommentRepository;
import com.woobeee.mvc.blog.repository.LikeRepository;
import com.woobeee.mvc.blog.repository.PostRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ImportAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class
})
class WoobeeeMvcApplicationTests {
    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private TokenStore tokenStore;

    @MockitoBean
    private BuyerRepository buyerRepository;

    @MockitoBean
    private SellerRepository sellerRepository;

    @MockitoBean
    private CategoryRepository categoryRepository;

    @MockitoBean
    private PostRepository postRepository;

    @MockitoBean
    private CommentRepository commentRepository;

    @MockitoBean
    private LikeRepository likeRepository;

    @MockitoBean
    private JPAQueryFactory jpaQueryFactory;

    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 13: 컴파일 확인 (실패 시 남은 결합 지점을 여기서 잡는다)**

```bash
cd /Users/administrator/Documents/projects/woobeee
./mvnw -pl core,app-mvc -am test-compile
```

Expected: `BUILD SUCCESS`. 실패하면 메시지의 심볼을 위 매핑 표와 대조해 남은 참조를 고친다. `PostServiceImpl`이 `blog.service`에서 `StorageProperties`를 쓰므로 import가 `com.woobeee.mvc._common.storage.StorageProperties` 로 바뀌었는지 확인한다.

- [ ] **Step 14: 테스트 실행 (DB 불필요 — SchemaValidationTest는 Task 3에서 추가)**

```bash
cd /Users/administrator/Documents/projects/woobeee
./mvnw -pl core,app-mvc -am test
```

Expected: `BUILD SUCCESS`. `AuthServiceTest`, `TokenServiceTest`, `AuthControllerTest`, `TokenGenerateControllerTest`, `WoobeeeMvcApplicationTests` 전부 PASS. core 테스트 6건도 함께 PASS.

- [ ] **Step 15: 커밋**

```bash
cd /Users/administrator/Documents/projects/woobeee
git add app-mvc
git commit -m "feat(app-mvc): migrate auth and blog from art-market-place

- auth + blog + _common moved to com.woobeee.mvc.*, token/ApiResponse now from core
- drop product/cart: remove guest-cart-merge event coupling and guestCartToken param
- product/config storage beans relocated to mvc/_common/storage
- delete dead InitConfig (entirely if(false)); Address.memberId UUID -> Long
- 5 tests migrated and passing"
```

---

## Task 3: Flyway 단일 스키마 + JPA validate + 로컬 인프라

**Files:**
- Create: `woobeee/app-mvc/src/main/resources/db/migration/V1__auth_blog.sql`
- Modify: `woobeee/app-mvc/src/main/resources/application.yaml` (flyway + `ddl-auto=validate`)
- Create: `woobeee/app-mvc/src/test/java/com/woobeee/mvc/SchemaValidationTest.java`
- Create: `woobeee/.docker-compose/docker-compose.yml`
- Create: `woobeee/.docker-compose/config/.gitkeep`

**Interfaces:**
- Consumes: Task 2의 `com.woobeee.mvc.auth.entity.**`, `com.woobeee.mvc.blog.entity.**`, `com.woobeee.mvc._common.config.QuerydslConfig`
- Produces:
  - Flyway 베이스라인 `V1__auth_blog.sql` — 테이블 `buyers, sellers, address, categories, posts, comments, likes` + 인덱스. 다음 마이그레이션 번호는 `V2`(게임 테이블, 후속 spec).
  - `docker compose -f .docker-compose/docker-compose.yml up -d` 로 PostgreSQL `9432` / Redis `9379` / MinIO `9000` 기동.

- [ ] **Step 1: 로컬 인프라 정의 (Kafka 제거 — auth/blog/game 어디서도 쓰지 않음)**

`woobeee/.docker-compose/docker-compose.yml`:

```yaml
services:
  postgres-management:
    image: postgres:18
    container_name: woobeee-db
    restart: always
    ports:
      - 9432:5432
    environment:
      POSTGRES_USER: root
      POSTGRES_PASSWORD: 123456789
      POSTGRES_DB: market
      TZ: Asia/Seoul
    volumes:
      - .docker-volumes/postgres/market:/var/lib/postgresql

  redis:
    image: redis:latest
    container_name: woobeee-redis
    restart: always
    ports:
      - 9379:6379
    volumes:
      - .docker-volumes/redis/data:/data
    command: [ "redis-server", "--appendonly", "yes", "--requirepass", "root!23" ]

  minio:
    image: minio/minio:latest
    container_name: woobeee-minio
    ports:
      - "9000:9000"
      - "9001:9001"
    volumes:
      - .docker-volumes/minio:/data
    environment:
      MINIO_ROOT_USER: admin
      MINIO_ROOT_PASSWORD: admin!23
    command: server /data --console-address ":9001"
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "mc", "ready", "local"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 20s

  createbuckets:
    image: minio/mc:latest
    depends_on:
      minio:
        condition: service_healthy
    entrypoint: >
      /bin/sh -c "
      until mc alias set myminio http://minio:9000 admin admin!23; do
        echo 'waiting for minio...';
        sleep 3;
      done;
      mc mb myminio/woobeee || true;
      mc anonymous set public myminio/woobeee || true
      "
```

- [ ] **Step 2: Flyway 베이스라인 작성**

`app-mvc/src/main/resources/db/migration/V1__auth_blog.sql` — 원본 `docs/_ddl.sql`에서 product/cart 테이블·인덱스를 제외하고, `address.member_id`를 `BIGINT`로 정합화(Task 2 Step 7과 짝).

```sql
CREATE TABLE IF NOT EXISTS buyers (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    google_subject VARCHAR(100) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    nickname VARCHAR(60) NOT NULL,
    terms_agreed BOOLEAN NOT NULL,
    privacy_policy_agreed BOOLEAN NOT NULL,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE IF NOT EXISTS sellers (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    google_subject VARCHAR(100) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    nickname VARCHAR(60) NOT NULL,
    terms_agreed BOOLEAN NOT NULL,
    privacy_policy_agreed BOOLEAN NOT NULL,
    business_registration_certificate_url VARCHAR(1000),
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE IF NOT EXISTS address (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name VARCHAR(255),
    recipient_name VARCHAR(255),
    phone_number VARCHAR(255),
    zipcode VARCHAR(255),
    address1 VARCHAR(255),
    address2 VARCHAR(255),
    is_default BOOLEAN NOT NULL,
    created_at TIMESTAMP(6),
    member_id BIGINT
);

CREATE TABLE IF NOT EXISTS categories (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name_ko VARCHAR(255),
    name_en VARCHAR(255),
    created_at TIMESTAMP(6),
    updated_at TIMESTAMP(6),
    parent_id BIGINT
);

CREATE TABLE IF NOT EXISTS posts (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    title_ko VARCHAR(255),
    title_en VARCHAR(255),
    text_ko TEXT,
    text_en TEXT,
    views BIGINT,
    created_at TIMESTAMP(6),
    updated_at TIMESTAMP(6),
    category_id BIGINT,
    member_id BIGINT,
    member_role VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS comments (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    content VARCHAR(255),
    created_at TIMESTAMP(6),
    updated_at TIMESTAMP(6),
    post_id BIGINT,
    parent_id BIGINT,
    member_id BIGINT,
    member_role VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS likes (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    member_id BIGINT,
    member_role VARCHAR(255),
    post_id BIGINT,
    created_at TIMESTAMP(6),
    CONSTRAINT uk_likes_member_post UNIQUE (member_id, member_role, post_id)
);

CREATE INDEX IF NOT EXISTS idx_categories_parent_id ON categories (parent_id);
CREATE INDEX IF NOT EXISTS idx_posts_category_id ON posts (category_id);
CREATE INDEX IF NOT EXISTS idx_posts_member ON posts (member_id, member_role);
CREATE INDEX IF NOT EXISTS idx_comments_post_id ON comments (post_id);
CREATE INDEX IF NOT EXISTS idx_comments_parent_id ON comments (parent_id);
CREATE INDEX IF NOT EXISTS idx_likes_post_id ON likes (post_id);
```

- [ ] **Step 3: `application.yaml` 에 Flyway 활성화 + JPA validate**

`app-mvc/src/main/resources/application.yaml` 의 `spring:` 블록에서 `jpa:` 아래 `hibernate` 설정을 추가하고 flyway 블록을 넣는다. `spring.jpa` 블록을 다음으로 교체:

```yaml
  jpa:
    open-in-view: false
    show-sql: true
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        format_sql: true
        use_sql_comments: true
        highlight_sql: true
        auto_quote_keyword: true
        hbm2ddl:
          auto: validate
```

그리고 같은 `spring:` 블록 안에 flyway 설정을 추가:

```yaml
  flyway:
    enabled: true
    baseline-on-migrate: true
    locations: classpath:db/migration
```

- [ ] **Step 4: 인프라 기동**

Docker Desktop이 꺼져 있으면 먼저 켠다(`open -a Docker` 후 데몬 준비까지 대기).

```bash
cd /Users/administrator/Documents/projects/woobeee
docker info > /dev/null 2>&1 || open -a Docker
until docker info > /dev/null 2>&1; do printf '.'; sleep 3; done; echo " docker ready"
docker compose -f .docker-compose/docker-compose.yml up -d
docker compose -f .docker-compose/docker-compose.yml ps
```

Expected: `woobeee-db`, `woobeee-redis`, `woobeee-minio` 가 `running`.

- [ ] **Step 5: Flyway 마이그레이션 적용 확인 (앱 부팅)**

```bash
cd /Users/administrator/Documents/projects/woobeee
timeout 90 ./mvnw -pl app-mvc spring-boot:run 2>&1 | tee /tmp/app-mvc-boot.log | grep -E "Migrating schema|Successfully applied|Started WoobeeeMvcApplication|ERROR|Schema-validation" | head -20
```

Expected: `Successfully applied 1 migration` 과 `Started WoobeeeMvcApplication`. `Schema-validation:` 오류가 나오면 해당 컬럼/테이블 불일치를 `V1__auth_blog.sql` 또는 엔티티에서 맞춘다(엔티티가 기준).

```bash
docker exec woobeee-db psql -U root -d market -c "\dt"
```

Expected: `flyway_schema_history` + 7개 테이블.

- [ ] **Step 6: `SchemaValidationTest` 이관 (JPA 매핑 ↔ 실제 스키마 검증)**

`app-mvc/src/test/java/com/woobeee/mvc/SchemaValidationTest.java`:

```java
package com.woobeee.mvc;

import static org.assertj.core.api.Assertions.assertThat;

import com.woobeee.mvc._common.config.QuerydslConfig;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig
@EnableAutoConfiguration
@EntityScan(basePackages = "com.woobeee.mvc")
@Import(QuerydslConfig.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:9432/market",
        "spring.datasource.username=root",
        "spring.datasource.password=123456789",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.datasource.type=org.apache.commons.dbcp2.BasicDataSource",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.hbm2ddl.auto=validate",
        "spring.flyway.enabled=false"
})
class SchemaValidationTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void schemaMatchesJpaMappings() {
        assertThat(entityManagerFactory).isNotNull();
    }
}
```

- [ ] **Step 7: 스키마 검증 테스트 실행**

```bash
cd /Users/administrator/Documents/projects/woobeee
./mvnw -pl core,app-mvc -am test
```

Expected: `SchemaValidationTest` 포함 전부 PASS, `BUILD SUCCESS`. 실패 시 오류 메시지의 `missing column` / `wrong column type` 을 `V1__auth_blog.sql` 에 반영한다.

- [ ] **Step 8: 커밋**

```bash
cd /Users/administrator/Documents/projects/woobeee
git add app-mvc .docker-compose
git commit -m "feat(app-mvc): make Flyway the single schema source, JPA validate-only

- V1__auth_blog.sql baseline (buyers/sellers/address/categories/posts/comments/likes)
- ddl-auto=validate; SchemaValidationTest pins JPA mappings against real schema
- local infra: postgres 9432 / redis 9379 / minio 9000 (kafka dropped, unused)"
```

---

## Task 4: app-webflux — game 골격 + 공유 Redis 토큰 검증

**Files:**
- Create: `woobeee/app-webflux/pom.xml`
- Create: `woobeee/app-webflux/src/main/java/com/woobeee/game/WoobeeeGameApplication.java`
- Create: `woobeee/app-webflux/src/main/java/com/woobeee/game/security/GamePrincipal.java`
- Create: `woobeee/app-webflux/src/main/java/com/woobeee/game/security/ReactiveTokenVerifier.java`
- Create: `woobeee/app-webflux/src/main/java/com/woobeee/game/security/GameAuthWebFilter.java`
- Create: `woobeee/app-webflux/src/main/java/com/woobeee/game/api/GameController.java`
- Create: `woobeee/app-webflux/src/main/resources/application.yaml`
- Test: `woobeee/app-webflux/src/test/java/com/woobeee/game/api/GameControllerTest.java`

**Interfaces:**
- Consumes: Task 1의 `com.woobeee.core.api.ApiResponse`, `com.woobeee.core.token.dto.{AuthTokenType,TokenMetadata}`
- Produces:
  - `com.woobeee.game.security.GamePrincipal` — `record GamePrincipal(Long memberId, String role, String device)`
  - `com.woobeee.game.security.ReactiveTokenVerifier` — `Mono<TokenMetadata> verify(String accessToken)`; 토큰이 없거나 만료면 `Mono.empty()`
  - `com.woobeee.game.security.GameAuthWebFilter` — `WebFilter`; 검증 성공 시 exchange attribute `GameAuthWebFilter.PRINCIPAL_ATTRIBUTE`(`"woobeee.game.principal"`)에 `GamePrincipal` 저장
  - HTTP: `GET /api/game/health` → `ApiResponse<String>`; `GET /api/game/me` → `ApiResponse<GamePrincipal>`(미인증 시 401)
  - Maven 좌표: `com.woobeee:app-webflux:0.0.1-SNAPSHOT`

- [ ] **Step 1: app-webflux POM 작성**

R2DBC 드라이버는 런타임용, JDBC 드라이버는 넣지 않는다 — 마이그레이션은 app-mvc가 소유하므로(spec §5) 이 앱은 Flyway를 끈다.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.woobeee</groupId>
        <artifactId>woobeee</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <artifactId>app-webflux</artifactId>
    <name>app-webflux</name>
    <description>Non-blocking surface: game on Spring WebFlux / R2DBC</description>
    <packaging>jar</packaging>

    <dependencies>
        <dependency>
            <groupId>com.woobeee</groupId>
            <artifactId>core</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-r2dbc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>r2dbc-postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.projectreactor</groupId>
            <artifactId>reactor-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

> `core`가 `spring-boot-starter-data-redis` 를 전이로 가져오므로 Lettuce의 reactive API(`ReactiveStringRedisTemplate`)를 그대로 쓸 수 있다. 별도 redis 의존을 추가하지 않는다.

그리고 **루트 `pom.xml` 의 `<modules>` 에 `app-webflux` 를 등록**한다(Task 1이 `core`, Task 2가 `app-mvc` 를 등록했다). 이로써 세 모듈이 모두 선언된 최종 형태가 된다:

```xml
    <modules>
        <module>core</module>
        <module>app-mvc</module>
        <module>app-webflux</module>
    </modules>
```

- [ ] **Step 2: 실패 테스트 작성 — 게임 API 계약**

`app-webflux/src/test/java/com/woobeee/game/api/GameControllerTest.java`:

```java
package com.woobeee.game.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.woobeee.core.token.dto.TokenMetadata;
import com.woobeee.game.security.GameAuthWebFilter;
import com.woobeee.game.security.ReactiveTokenVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(GameController.class)
@Import(GameAuthWebFilter.class)
class GameControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ReactiveTokenVerifier reactiveTokenVerifier;

    @Test
    void healthIsPublic() {
        webTestClient.get().uri("/api/game/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.header.isSuccessful").isEqualTo(true)
                .jsonPath("$.data").isEqualTo("UP");
    }

    @Test
    void meResolvesPrincipalFromSharedRedisToken() {
        when(reactiveTokenVerifier.verify(eq("tok-1")))
                .thenReturn(Mono.just(new TokenMetadata(7L, "ROLE_BUYER", "ios", "127.0.0.1")));

        webTestClient.get().uri("/api/game/me")
                .header("Authorization", "Bearer tok-1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.memberId").isEqualTo(7)
                .jsonPath("$.data.role").isEqualTo("ROLE_BUYER")
                .jsonPath("$.data.device").isEqualTo("ios");
    }

    @Test
    void meRejectsMissingToken() {
        webTestClient.get().uri("/api/game/me")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void meRejectsUnknownToken() {
        when(reactiveTokenVerifier.verify(eq("bad-token"))).thenReturn(Mono.empty());

        webTestClient.get().uri("/api/game/me")
                .header("Authorization", "Bearer bad-token")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
```

- [ ] **Step 3: 테스트가 실패하는지 확인**

```bash
cd /Users/administrator/Documents/projects/woobeee
./mvnw -q -pl app-webflux -am test
```

Expected: 컴파일 실패 — `package com.woobeee.game.security does not exist`, `cannot find symbol: class GameController`.

- [ ] **Step 4: `GamePrincipal` 작성**

`app-webflux/src/main/java/com/woobeee/game/security/GamePrincipal.java`:

```java
package com.woobeee.game.security;

public record GamePrincipal(
        Long memberId,
        String role,
        String device
) {
}
```

- [ ] **Step 5: `ReactiveTokenVerifier` 작성 — core의 키 계약으로 공유 Redis 직접 검증**

앱 간 HTTP 호출 없이 Redis 해시를 직접 읽는다(spec §4-3).

`app-webflux/src/main/java/com/woobeee/game/security/ReactiveTokenVerifier.java`:

```java
package com.woobeee.game.security;

import com.woobeee.core.token.dto.AuthTokenType;
import com.woobeee.core.token.dto.TokenMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class ReactiveTokenVerifier {
    private final ReactiveStringRedisTemplate redisTemplate;

    public Mono<TokenMetadata> verify(String accessToken) {
        String key = AuthTokenType.ACCESS.redisKey(accessToken);

        return redisTemplate.<String, String>opsForHash()
                .entries(key)
                .collectMap(java.util.Map.Entry::getKey, java.util.Map.Entry::getValue)
                .flatMap(this::toMetadata);
    }

    private Mono<TokenMetadata> toMetadata(java.util.Map<String, String> values) {
        String memberId = values.get("memberId");
        String role = values.get("role");
        String device = values.get("device");
        String ip = values.get("ip");

        if (memberId == null || role == null || device == null || ip == null) {
            return Mono.empty();
        }

        return Mono.just(new TokenMetadata(Long.valueOf(memberId), role, device, ip));
    }
}
```

- [ ] **Step 6: `GameAuthWebFilter` 작성**

`app-webflux/src/main/java/com/woobeee/game/security/GameAuthWebFilter.java`:

```java
package com.woobeee.game.security;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class GameAuthWebFilter implements WebFilter {
    public static final String PRINCIPAL_ATTRIBUTE = "woobeee.game.principal";

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final ReactiveTokenVerifier reactiveTokenVerifier;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String accessToken = resolveAccessToken(exchange);
        if (!StringUtils.hasText(accessToken)) {
            return chain.filter(exchange);
        }

        // then() 은 검증이 비어서 완료돼도(= 토큰 무효) 체인을 한 번 실행한다.
        // switchIfEmpty 를 덧붙이면 Mono<Void> 는 값을 절대 emit 하지 않으므로
        // 항상 발동해 체인이 두 번 구독된다 — 그래서 쓰지 않는다.
        return reactiveTokenVerifier.verify(accessToken)
                .doOnNext(metadata -> exchange.getAttributes().put(
                        PRINCIPAL_ATTRIBUTE,
                        new GamePrincipal(metadata.memberId(), metadata.role(), metadata.device())
                ))
                .then(Mono.defer(() -> chain.filter(exchange)));
    }

    private String resolveAccessToken(ServerWebExchange exchange) {
        String authorization = exchange.getRequest().getHeaders().getFirst(AUTHORIZATION_HEADER);
        if (!StringUtils.hasText(authorization) || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }

        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        return StringUtils.hasText(token) ? token : null;
    }
}
```

- [ ] **Step 7: `GameController` 작성**

`app-webflux/src/main/java/com/woobeee/game/api/GameController.java`:

```java
package com.woobeee.game.api;

import com.woobeee.core.api.ApiResponse;
import com.woobeee.game.security.GameAuthWebFilter;
import com.woobeee.game.security.GamePrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/game")
public class GameController {

    @GetMapping("/health")
    public Mono<ApiResponse<String>> health() {
        return Mono.just(ApiResponse.success("UP", "Game surface is up"));
    }

    @GetMapping("/me")
    public Mono<ApiResponse<GamePrincipal>> me(ServerWebExchange exchange) {
        Object principal = exchange.getAttribute(GameAuthWebFilter.PRINCIPAL_ATTRIBUTE);
        if (!(principal instanceof GamePrincipal gamePrincipal)) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Access token is required"));
        }

        return Mono.just(ApiResponse.success(gamePrincipal, "Principal resolved"));
    }
}
```

- [ ] **Step 8: Boot 진입점 작성**

`app-webflux/src/main/java/com/woobeee/game/WoobeeeGameApplication.java`. core의 `RedisTokenStore`(블로킹 `StringRedisTemplate` 요구)를 스캔하면 안 되므로 `com.woobeee.core` 는 스캔 베이스에서 제외하고, 필요한 계약 클래스만 import 해 쓴다.

```java
package com.woobeee.game;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class WoobeeeGameApplication {
    public static void main(String[] args) {
        SpringApplication.run(WoobeeeGameApplication.class, args);
    }
}
```

- [ ] **Step 9: `application.yaml` 작성**

`app-webflux/src/main/resources/application.yaml`:

```yaml
server:
  shutdown: graceful
  port: 8001

spring:
  application:
    name: woobeee-app-webflux
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:9379}
      password: ${REDIS_PASSWORD:root!23}
  r2dbc:
    url: ${R2DBC_URL:r2dbc:postgresql://localhost:9432/market}
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:123456789}
    pool:
      initial-size: 5
      max-size: 30
  flyway:
    enabled: false

logging:
  file:
    name: logs/app-webflux.log
```

> 스키마 소유자는 app-mvc다. 이 앱은 Flyway를 끄고 R2DBC로 기존 테이블 위에서만 동작한다(spec §5).

- [ ] **Step 10: 테스트 통과 확인**

```bash
cd /Users/administrator/Documents/projects/woobeee
./mvnw -pl core,app-webflux -am test
```

Expected: `GameControllerTest` 4건 PASS, `BUILD SUCCESS`. `@WebFluxTest`는 R2DBC/Redis 자동설정을 로드하지 않으므로 인프라 없이 통과한다.

- [ ] **Step 11: 실제 기동 + 공유 토큰 검증 스모크 테스트**

app-mvc가 발급한 토큰을 app-webflux가 검증하는지가 이 아키텍처의 핵심 가정이다. Redis에 직접 토큰을 심어 확인한다.

```bash
cd /Users/administrator/Documents/projects/woobeee
docker compose -f .docker-compose/docker-compose.yml up -d
./mvnw -pl app-webflux spring-boot:run > /tmp/app-webflux.log 2>&1 &
until curl -sf http://localhost:8001/api/game/health > /dev/null; do printf '.'; sleep 2; done; echo " up"

curl -s http://localhost:8001/api/game/health

# core의 키 계약(auth:token:access:<token>)으로 토큰을 심는다 — app-mvc가 쓰는 것과 동일한 포맷
docker exec woobeee-redis redis-cli -a 'root!23' --no-auth-warning \
  HSET auth:token:access:smoke-tok memberId 7 role ROLE_BUYER device ios ip 127.0.0.1
docker exec woobeee-redis redis-cli -a 'root!23' --no-auth-warning \
  EXPIRE auth:token:access:smoke-tok 900

echo; echo "--- with token ---"
curl -s -H "Authorization: Bearer smoke-tok" http://localhost:8001/api/game/me
echo; echo "--- without token ---"
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8001/api/game/me

kill %1
docker exec woobeee-redis redis-cli -a 'root!23' --no-auth-warning DEL auth:token:access:smoke-tok
```

Expected: health가 `"data":"UP"`, 토큰 있으면 `"memberId":7,"role":"ROLE_BUYER","device":"ios"`, 토큰 없으면 `401`.

- [ ] **Step 12: 커밋**

```bash
cd /Users/administrator/Documents/projects/woobeee
git add app-webflux
git commit -m "feat(app-webflux): add game surface skeleton with shared Redis token verification

- WebFlux + R2DBC app on :8001, Flyway disabled (app-mvc owns the schema)
- ReactiveTokenVerifier reads the shared Redis token via core's AuthTokenType key contract
  (no app-to-app HTTP)
- GET /api/game/health (public), GET /api/game/me (authenticated)"
```

---

## Task 5: front 이관 + rewrites로 두 백엔드 단일 오리진화

**Files:**
- Copy: `art-market-place/front/**` → `woobeee/front/**` (`node_modules`, `.next`, `tsconfig.tsbuildinfo`, `pnpm-lock.yaml` 제외)
- Modify: `woobeee/front/next.config.mjs` (rewrites 추가)
- Modify: `woobeee/front/lib/api.ts` (기본 base URL을 동일 오리진으로)
- Modify: `woobeee/front/package.json` (`name` 갱신)
- Create: `woobeee/front/.env.local.example`

**Interfaces:**
- Consumes: Task 2의 app-mvc 경로(`/api/auth/**`, `/api/back/**`), Task 4의 app-webflux 경로(`/api/game/**`)
- Produces: `npm run build` 가 통과하는 Next.js 앱. 프록시 규칙 — `/api/auth/*` · `/api/back/*` → `http://localhost:8000`, `/api/game/*` → `http://localhost:8001`.

- [ ] **Step 1: front 복사 (의존성·빌드 산출물 제외)**

```bash
cd /Users/administrator/Documents/projects/woobeee
SRC=/Users/administrator/Documents/projects/art-market-place/front
rsync -a \
  --exclude 'node_modules/' \
  --exclude '.next/' \
  --exclude 'tsconfig.tsbuildinfo' \
  --exclude 'pnpm-lock.yaml' \
  "$SRC/" front/
ls front
```

Expected: `app components hooks lib public styles package.json package-lock.json next.config.mjs tailwind.config.ts tsconfig.json postcss.config.mjs components.json next-env.d.ts index.html`

- [ ] **Step 2: `package.json` 이름 갱신**

`front/package.json` 에서

```json
  "name": "react-blog",
```
를
```json
  "name": "woobeee-front",
```
로 바꾼다.

- [ ] **Step 3: rewrites 추가**

`front/next.config.mjs` 전체를 다음으로 교체한다. spec §2의 의도(프론트가 두 백엔드를 단일 오리진으로 노출 → CORS/게이트웨이 불필요)를 실제 백엔드 경로(`/api/auth`, `/api/back`, `/api/game`)에 맞춘 것이다.

```javascript
/** @type {import('next').NextConfig} */
const MVC_ORIGIN = process.env.MVC_ORIGIN || "http://localhost:8000"
const WEBFLUX_ORIGIN = process.env.WEBFLUX_ORIGIN || "http://localhost:8001"

const nextConfig = {
  eslint: {
    ignoreDuringBuilds: true,
  },
  typescript: {
    ignoreBuildErrors: true,
  },
  images: {
    unoptimized: true,
  },
  async rewrites() {
    return [
      // blog + auth surface -> app-mvc (Tomcat/JPA)
      { source: "/api/auth/:path*", destination: `${MVC_ORIGIN}/api/auth/:path*` },
      { source: "/api/back/:path*", destination: `${MVC_ORIGIN}/api/back/:path*` },
      // game surface -> app-webflux (Netty/R2DBC)
      { source: "/api/game/:path*", destination: `${WEBFLUX_ORIGIN}/api/game/:path*` },
    ]
  },
}

export default nextConfig
```

- [ ] **Step 4: 클라이언트 base URL을 동일 오리진 기본값으로 전환**

rewrites가 프록시하므로 절대 URL이 필요 없다. `front/lib/api.ts` 23행

```typescript
const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:8000"
```

를 다음으로 바꾼다:

```typescript
// 빈 문자열 = 동일 오리진. Next rewrites(next.config.mjs)가 /api/auth, /api/back -> app-mvc,
// /api/game -> app-webflux 로 프록시한다. 백엔드를 직접 호출해야 하면 NEXT_PUBLIC_API_BASE_URL 설정.
const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? ""
```

- [ ] **Step 5: 환경변수 예시 파일 작성**

`front/.env.local.example`:

```dotenv
# 두 백엔드 오리진 (Next rewrites가 서버 사이드에서 프록시)
MVC_ORIGIN=http://localhost:8000
WEBFLUX_ORIGIN=http://localhost:8001

# 브라우저가 백엔드를 직접 호출해야 할 때만 설정한다. 비어 있으면 동일 오리진 + rewrites 사용.
NEXT_PUBLIC_API_BASE_URL=
```

- [ ] **Step 6: 의존성 설치**

```bash
cd /Users/administrator/Documents/projects/woobeee/front
npm install
```

Expected: `node_modules` 생성, 오류 없음.

- [ ] **Step 7: 빌드 확인**

```bash
cd /Users/administrator/Documents/projects/woobeee/front
npm run build
```

Expected: `Compiled successfully` 및 라우트 목록 출력, exit code 0.

> `app/products`, `app/cart`, `app/chat` 은 폐기된 백엔드 도메인을 호출하므로 런타임에 404가 난다. 이번 범위에서는 페이지를 남겨두고(사용자 결정) 후속 정리한다.

- [ ] **Step 8: 커밋**

```bash
cd /Users/administrator/Documents/projects/woobeee
git add front
git commit -m "feat(front): migrate Next.js app and proxy both backends via rewrites

- /api/auth, /api/back -> app-mvc :8000; /api/game -> app-webflux :8001
- API_BASE_URL defaults to same-origin so rewrites remove the need for CORS
- products/cart/chat pages retained pending follow-up cleanup (backends dropped)"
```

---

## Task 6: 문서 이관·개정 + 전체 검증

**Files:**
- Copy: `art-market-place/docs/{ARCHITECTURE.md,FRONTEND.md,DESIGN.md,_global/,_common/,auth/,blog/,front/}` → `woobeee/docs/`
- 제외: `docs/product/`, `docs/cart/`, `docs/_ddl.sql`(Flyway가 대체), `docs/superpowers/specs/2026-06-03-cart-guest-merge-design.md`
- Create: `woobeee/CLAUDE.md`
- Create: `woobeee/README.md`
- Create: `woobeee/docs/game/PRD.md`
- Modify: `woobeee/docs/ARCHITECTURE.md` (멀티모듈 구조로 개정)

**Interfaces:**
- Consumes: Task 1–5의 최종 모듈 구조·명령·포트
- Produces: 다음 세션이 이 리포에서 바로 작업할 수 있는 진입 문서

- [ ] **Step 1: 문서 복사 (폐기 도메인 제외)**

```bash
cd /Users/administrator/Documents/projects/woobeee
SRCD=/Users/administrator/Documents/projects/art-market-place/docs
cp "$SRCD/ARCHITECTURE.md" "$SRCD/FRONTEND.md" "$SRCD/DESIGN.md" docs/
cp -R "$SRCD/_global" "$SRCD/_common" "$SRCD/auth" "$SRCD/blog" "$SRCD/front" docs/
mkdir -p docs/game
ls docs
```

Expected: `ARCHITECTURE.md DESIGN.md FRONTEND.md _common _global auth blog front game superpowers`. `product`/`cart`/`_ddl.sql` 은 **없어야 한다**.

- [ ] **Step 2: Kafka ADR에 폐기 사실 명시**

`docs/_global/adr/ADR-003-kafka.md` 파일 맨 위(제목 다음 줄)에 다음을 삽입한다.

```markdown
> **상태 갱신 (2026-07-31):** woobeee 재구성에서 Kafka는 **제거**되었다. Kafka는 product 도메인
> 이벤트 전용이었고 product/cart가 폐기되면서 사용처가 사라졌다. 로컬 compose에서도 제외했다.
> 게임 도메인에서 이벤트 스트리밍이 필요해지면 이 ADR을 다시 열어 재평가한다.
```

- [ ] **Step 3: 게임 도메인 PRD 자리표시 작성**

`docs/game/PRD.md`:

```markdown
# game 도메인 PRD

- 상태: **미작성** — 기능 설계는 후속 spec에서 다룬다.

## 현재 구현된 범위 (골격만)

| 항목 | 내용 |
| --- | --- |
| 스택 | Spring WebFlux + R2DBC (Netty), 포트 8001 |
| 인증 | 공유 Redis 토큰 검증만 수행. 발급은 app-mvc(auth)가 담당 |
| 엔드포인트 | `GET /api/game/health` (공개), `GET /api/game/me` (인증 필요) |
| 스키마 | 없음. 게임 테이블은 `V2__game.sql` 로 추가 예정 |

## 후속 spec에서 결정할 것

- 실시간 통신 방식: WebSocket / SSE / 폴링
- 게임 규칙과 도메인 모델
- 게임 테이블 스키마 (`app-mvc/src/main/resources/db/migration/V2__game.sql`)
- 인수 기준 (Acceptance Criteria) 표

## 인수 기준 (Acceptance Criteria)

미작성 — 후속 spec에서 추가한다. (`CLAUDE.md`의 테스트 결정 프로세스에 따라 AC가 테스트의 단일 출처다.)
```

- [ ] **Step 4: `docs/ARCHITECTURE.md` 상단에 멀티모듈 구조 섹션 삽입**

파일 첫 제목 줄 바로 다음에 다음 내용을 삽입한다(기존 도메인별 흐름 설명은 그대로 유지).

```markdown
> **2026-07-31 재구성:** 단일 모듈 모놀리스(`art-market-place`)에서 Maven 멀티모듈
> (`woobeee`)로 전환했다. product/cart는 폐기했고 game(WebFlux)을 추가했다. 아래 모듈 구조가
> 우선하며, 이후 절의 도메인 설명은 auth/blog에 대해서만 유효하다.

## 모듈 구조

```text
                    Next.js (front :3000)
        /api/auth/* /api/back/*      /api/game/*
                    │                     │
               [app-mvc :8000]     [app-webflux :8001]
               Tomcat / JPA         Netty / R2DBC
                    │                     │
                    └──────── core ───────┘
              (ApiResponse · 토큰 계약 · Redis 토큰스토어)
                    │                     │
            ┌───────┴─────────────────────┴───────┐
       PostgreSQL :9432 (공유)          Redis :9379 (공유 토큰)
            └──── Flyway가 스키마 단일 관리 (app-mvc 소유) ────┘
```

| 모듈 | 스택 | 책임 | 의존 |
| --- | --- | --- | --- |
| `core` | 순수 라이브러리 (웹 스택 무의존) | `ApiResponse`, 토큰 계약(`AuthTokenType`/`TokenMetadata`), `RedisTokenStore` | — |
| `app-mvc` | Boot + `starter-webmvc` + JPA | auth(토큰 발급·로그인·OAuth) + blog, Flyway 소유 | core |
| `app-webflux` | Boot + `starter-webflux` + R2DBC | game(골격), Redis 토큰 **검증만** | core |
| `front` | Next.js 14 | rewrites로 두 백엔드 프록시 | — |

- 논리적으로 하나의 앱(회원·DB·Redis 공유), 물리적으로 Boot 프로세스 2개(Netty와 Tomcat은 한
  JVM에 공존 불가).
- 토큰 공유: app-mvc(auth)가 발급 → Redis에 `auth:token:access:<token>` 해시로 저장 →
  app-webflux가 `ReactiveTokenVerifier`로 같은 키를 직접 읽어 검증. 앱 간 HTTP 호출 없음.
- 스키마: Flyway가 단일 소스(`app-mvc/src/main/resources/db/migration/`). JPA는 `validate`
  전용이고 app-webflux는 `flyway.enabled=false`.
```

- [ ] **Step 5: `CLAUDE.md` 작성 (멀티모듈 전제로 전면 개정)**

`woobeee/CLAUDE.md`:

```markdown
# CLAUDE.md

이 문서는 Claude Code가 `woobeee` 레포지토리에서 작업할 때 따르는 운영 기준이다.

## 프로젝트 개요

- **구조**: Maven 멀티모듈 (`core` / `app-mvc` / `app-webflux` / `front`), Java 25, Spring Boot 4.0.5
- **두 표면**: `blog`+`auth` = Spring MVC + JPA (`app-mvc` :8000) / `game` = Spring WebFlux + R2DBC (`app-webflux` :8001)
- **프론트엔드**: `front/` Next.js 14 + React 18 + TypeScript + Tailwind (shadcn/ui, Radix). rewrites로 두 백엔드를 단일 오리진화
- **데이터/인프라**: PostgreSQL :9432(공유), Redis :9379(공유 토큰), MinIO :9000, Google OAuth
- **전신**: `art-market-place` 단일 모듈 모놀리스. product/cart 도메인은 폐기했다.

### 모듈 경계

| 모듈 | 넣어도 되는 것 | 넣으면 안 되는 것 |
| --- | --- | --- |
| `core` | 두 앱이 공유하는 계약: `ApiResponse`, 토큰 계약, `RedisTokenStore` | `spring-boot-starter-webmvc` / `-webflux` 의존, 도메인 로직 |
| `app-mvc` | auth, blog, 블로킹 I/O, JPA 엔티티, Flyway 마이그레이션 | 게임 로직 |
| `app-webflux` | game, 논블로킹 I/O, R2DBC | 블로킹 호출(JPA/JDBC/`StringRedisTemplate`), Flyway 실행 |

`core`가 웹 스타터를 끌어오면 두 앱 중 하나가 깨진다. 다음으로 검증한다:

```bash
./mvnw -pl core dependency:tree | grep -E "starter-webmvc|starter-webflux|tomcat-embed|reactor-netty" && echo "FAIL: web stack leaked into core" || echo "OK"
```

## 문서 읽기 순서

1. `docs/superpowers/specs/2026-07-31-multimodule-game-blog-restructure-design.md` — 현 구조의 설계 근거
2. `docs/ARCHITECTURE.md` — 모듈 구조와 도메인별 흐름
3. `docs/_global/PRD.md`, `docs/_global/adr/` — 전체 목표, 인프라 ADR
4. 작업 도메인의 `docs/<domain>/PRD.md` 와 `docs/<domain>/adr/` (`auth`, `blog`, `game`, `front`)
5. `docs/FRONTEND.md`, `docs/DESIGN.md`
6. 관련 코드와 테스트

## 핵심 규칙

- **도메인 패키지 경계를 유지**한다: `com.woobeee.mvc.{_common,auth,blog}`, `com.woobeee.game`, `com.woobeee.core`. 도메인 간 직접 의존을 늘리지 않는다.
- **`core`는 웹 스택 무의존**을 유지한다. 공통 코드가 MVC나 WebFlux 타입을 필요로 하면 core가 아니라 해당 앱에 둔다.
- **쿼리 구현 규칙**:
  - 단순 조회(PK/단일 컬럼)는 Spring Data 파생 메서드.
  - 그 외 커스텀 조회(동적 조건·검색·집계·조인/서브쿼리·목록)는 **네이티브 SQL**(`@Query(nativeQuery = true)`).
  - 네이티브 쿼리는 **N+1을 해결한 형태**로 쓴다: 조인으로 한 번에, 또는 식별자를 모아 배치(IN) 조회. 루프 안 단건 조회 금지. 값은 바인딩 파라미터로만.
  - QueryDSL은 신규 사용 금지. `app-mvc`의 `blog/repository/PostQueryRepositoryImpl` 이 유일한 잔존 사용처이며 네이티브 SQL 전환 대상이다.
- **스키마는 Flyway가 단일 소스**다. `app-mvc/src/main/resources/db/migration/` 에 `V<n>__<name>.sql` 을 추가한다. JPA는 `validate` 전용이므로 엔티티만 바꾸면 부팅이 실패한다 — 마이그레이션을 함께 쓰고 `SchemaValidationTest` 를 통과시켜야 한다.
- **app-webflux에서 블로킹 호출 금지**. Redis는 `ReactiveStringRedisTemplate`, DB는 R2DBC. core의 `RedisTokenStore`(블로킹)는 app-mvc 전용이다.
- **토큰 계약을 바꿀 때는 양쪽을 함께 본다.** `core`의 `AuthTokenType` 키 규칙과 `TokenMetadata` 필드가 app-mvc(발급)와 app-webflux(검증)의 유일한 접점이다. `AuthTokenTypeTest` 가 이를 고정한다.
- **테스트는 PRD의 인수 기준에서 도출한다**:
  - 각 도메인 PRD의 `## 인수 기준 (Acceptance Criteria)` 표가 단일 출처다. 별도 테스트 케이스 문서는 두지 않는다.
  - 동작/API 계약을 바꾸면 **먼저 AC 표를 갱신**하고 그 AC를 커버하는 테스트를 추가/수정한다.
  - 테스트 메서드 이름·주석에 AC ID(예: `BLOG-AC-03`)를 참조해 PRD ↔ 테스트 추적을 유지한다.
  - AC가 "미작성"인 도메인(`blog`, `game`)은 테스트 백로그다.
- 동작이나 구조가 바뀌면 해당 `docs/<domain>/*` 문서를 함께 갱신한다.

## 빌드 · 실행 · 검증

### 로컬 인프라

```bash
docker compose -f .docker-compose/docker-compose.yml up -d   # postgres 9432 · redis 9379 · minio 9000
```

### 기본 검증 명령

```bash
./mvnw -pl core,app-mvc,app-webflux -am test   # SchemaValidationTest는 PostgreSQL이 떠 있어야 통과
cd front && npm run build
```

`core`의 웹 스택 무의존 확인:

```bash
./mvnw -pl core dependency:tree | grep -E "starter-webmvc|starter-webflux|tomcat-embed|reactor-netty" && echo "FAIL: web stack leaked into core" || echo "OK"
```

### 개발 서버

```bash
./mvnw -pl app-mvc spring-boot:run       # :8000  auth + blog
./mvnw -pl app-webflux spring-boot:run   # :8001  game
cd front && npm run dev                  # :3000  rewrites로 위 둘을 프록시
```

### 필요한 환경변수

미설정 시 `application.yaml` 기본값을 쓴다.

- app-mvc: `GOOGLE_CLIENT_SECRET`, `GOOGLE_REDIRECT_URI`, `S3_ENDPOINT`, `S3_REGION`, `S3_BUCKET`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`
- app-webflux: `R2DBC_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`
- front: `MVC_ORIGIN`, `WEBFLUX_ORIGIN`, `NEXT_PUBLIC_API_BASE_URL` (`front/.env.local.example` 참조)

## API 엔드포인트

| 앱 | 도메인 | 베이스 경로 | 비고 |
| --- | --- | --- | --- |
| app-mvc | auth | `/api/auth` | `signup/buyers`, `signup/sellers`, `login`, `callback-google`, `access-tokens`, `refresh-tokens` |
| app-mvc | blog | `/api/back/posts`, `/api/back/comments`, `/api/back/likes`, `/api/back/categories` | 게시글/댓글/좋아요/카테고리 |
| app-webflux | game | `/api/game` | `health`(공개), `me`(인증) — 나머지는 후속 spec |

## 안전 수칙

- 위험 명령(`rm -rf`, `git reset --hard`, `git push --force`, destructive SQL)은 사용자의 명시적 승인 없이 실행하지 않는다.
- 구현/검증 중 테스트·빌드가 실패하면 원인을 요약하고 **수정 지속 / 부분 롤백 / 전체 롤백** 중 어디로 갈지 사용자에게 확인한다. 승인 없이 실패한 변경을 임의로 되돌리지 않는다.
- 전신 리포 `/Users/administrator/Documents/projects/art-market-place` 는 **수정하지 않는다**.

## 알려진 후속 과제

| 항목 | 내용 |
| --- | --- |
| 게임 도메인 설계 | 실시간 통신 방식(WebSocket/SSE/폴링), 게임 규칙, `V2__game.sql` → 별도 spec |
| front 잔존 페이지 | `app/products`, `app/cart`, `app/chat` 은 폐기된 백엔드를 호출한다. 삭제 또는 게임 화면으로 대체 |
| QueryDSL 잔존 | `blog/repository/PostQueryRepositoryImpl` 을 네이티브 SQL로 전환 |
| blog AC 미작성 | `docs/blog/PRD.md` 의 인수 기준 표가 비어 있어 blog 테스트가 없다 |
| 하네스 재설계 | `art-market-place`의 `amp-backend-feature` 하네스는 art-marketplace 도메인 전제여서 이관하지 않았다. 필요 시 game/blog 기준으로 새로 구성 |
```

- [ ] **Step 6: `README.md` 작성**

`woobeee/README.md`:

```markdown
# woobeee

게임(WebFlux)과 블로그(MVC)를 함께 서비스하는 Maven 멀티모듈 애플리케이션.

## 모듈

| 모듈 | 스택 | 포트 | 책임 |
| --- | --- | --- | --- |
| `core` | 순수 Java 라이브러리 | — | 두 앱이 공유하는 `ApiResponse` · 토큰 계약 · Redis 토큰스토어 |
| `app-mvc` | Spring MVC + JPA (Tomcat) | 8000 | `auth`(토큰 발급·로그인·Google OAuth) + `blog` |
| `app-webflux` | Spring WebFlux + R2DBC (Netty) | 8001 | `game` (골격) |
| `front` | Next.js 14 | 3000 | rewrites로 두 백엔드를 단일 오리진화 |

논리적으로 하나의 앱(회원·PostgreSQL·Redis 공유), 물리적으로 Boot 프로세스 2개.

## 시작하기

```bash
# 1) 로컬 인프라 (PostgreSQL 9432 · Redis 9379 · MinIO 9000)
docker compose -f .docker-compose/docker-compose.yml up -d

# 2) 백엔드 — 최초 기동 시 app-mvc의 Flyway가 스키마를 만든다
./mvnw -pl app-mvc spring-boot:run        # :8000
./mvnw -pl app-webflux spring-boot:run    # :8001

# 3) 프론트
cd front && npm install && npm run dev    # :3000
```

## 검증

```bash
./mvnw -pl core,app-mvc,app-webflux -am test   # SchemaValidationTest는 PostgreSQL 필요
cd front && npm run build
```

## 스키마

Flyway가 단일 소스다: `app-mvc/src/main/resources/db/migration/`. JPA는 `validate` 전용이므로
엔티티 변경 시 마이그레이션을 함께 추가해야 한다. `app-webflux` 는 `flyway.enabled=false` 로
기존 테이블 위에서만 동작한다.

## 문서

`docs/` — 설계 근거는 `docs/superpowers/specs/`, 아키텍처는 `docs/ARCHITECTURE.md`,
작업 규칙은 `CLAUDE.md`.
```

- [ ] **Step 7: 전체 백엔드 검증**

```bash
cd /Users/administrator/Documents/projects/woobeee
docker compose -f .docker-compose/docker-compose.yml up -d
./mvnw -pl core,app-mvc,app-webflux -am test
```

Expected: `BUILD SUCCESS`, 3개 모듈 리액터 전부 성공. 테스트 총 15건(core 6 + app-mvc 5클래스 + app-webflux 4) PASS.

- [ ] **Step 8: core 경계 검증**

```bash
cd /Users/administrator/Documents/projects/woobeee
./mvnw -pl core dependency:tree | grep -E "starter-webmvc|starter-webflux|tomcat-embed|reactor-netty" && echo "FAIL: web stack leaked into core" || echo "OK: core has no web stack"
```

Expected: `OK: core has no web stack` (`-q` 를 붙이면 grep 입력이 비어 검사가 무의미해진다)

- [ ] **Step 9: 프론트 빌드 검증**

```bash
cd /Users/administrator/Documents/projects/woobeee/front
npm run build
```

Expected: `Compiled successfully`, exit 0.

- [ ] **Step 10: 폐기 도메인 잔여물 검증**

```bash
cd /Users/administrator/Documents/projects/woobeee
grep -rln "artmarketplace" --include='*.java' --include='*.xml' --include='*.yaml' . && echo "FAIL" || echo "OK: no artmarketplace refs"
ls docs | grep -E "^(product|cart)$" && echo "FAIL: 폐기 도메인 문서 잔존" || echo "OK: product/cart docs absent"
find . -path ./front/node_modules -prune -o -type d \( -name product -o -name cart \) -print | grep -v "^./front" && echo "CHECK" || echo "OK: no product/cart backend dirs"
```

Expected: 세 줄 모두 `OK`. (`front/app/products`, `front/app/cart` 는 의도적으로 남긴 것이므로 세 번째 검사에서 `./front` 이하는 제외한다.)

- [ ] **Step 11: 커밋**

```bash
cd /Users/administrator/Documents/projects/woobeee
git add CLAUDE.md README.md docs
git commit -m "docs: rewrite project docs for the woobeee multimodule structure

- CLAUDE.md: module boundaries, core web-stack-free invariant, Flyway-as-single-source,
  no-blocking-in-webflux rule, follow-up backlog
- ARCHITECTURE.md: multimodule topology and shared-token flow
- carry over _global/_common/auth/blog/front docs; drop product/cart and _ddl.sql
- mark Kafka ADR superseded; add docs/game/PRD.md placeholder"
```

- [ ] **Step 12: 최종 상태 보고**

```bash
cd /Users/administrator/Documents/projects/woobeee
git log --oneline
git status --short
```

Expected: 6개 신규 커밋, working tree 깨끗(추적 대상 기준).

---

## Self-Review

**1. Spec coverage**

| spec 절 | 요구사항 | 담당 Task |
| --- | --- | --- |
| §2 아키텍처 | 프로세스 2개, front가 단일 오리진화 | Task 2·4·5 |
| §3 모듈 구성 | parent POM + core/app-mvc/app-webflux/front | Task 1·2·4·5 |
| §3 핵심 제약 | core가 web 스타터 무의존 | Task 1 Step 10, Task 6 Step 8 |
| §4 인증 흐름 1–2 | app-mvc가 발급, 기존 필터로 검증 | Task 2 (auth + `AccessTokenLoginIdHeaderFilter` 이관) |
| §4 인증 흐름 3 | app-webflux가 `WebFilter`로 reactive Redis 직접 검증 | Task 4 Step 5–6, 스모크 Step 11 |
| §4 인증 흐름 4 | 토큰 계약을 core에 두어 공유 | Task 1 (`AuthTokenTypeTest`가 고정) |
| §5 스키마 | Flyway 단일 소스, JPA validate, webflux는 flyway off | Task 3, Task 4 Step 9 |
| §6 배포 | compose: postgres·redis + 두 앱 포트 + front | Task 3 Step 1 (인프라), 앱은 `spring-boot:run` |
| §7 검증 전략 | SchemaValidationTest, R2DBC/WebTestClient, front build, 전체 빌드 | Task 3 Step 6–7, Task 4 Step 10, Task 5 Step 7, Task 6 Step 7–9 |
| §8 마이그레이션 단계 1–7 | 순서대로 | Task 1→2→3→4→5→6 |
| §9 문서 전면 개정 | CLAUDE.md 개정 | Task 6 Step 5 |
| §9 하네스 무효화 | 이관하지 않고 후속 과제로 기록 | Task 6 Step 5 (후속 과제 표) |
| §9 Flyway↔R2DBC | 소유 앱 고정(app-mvc), webflux에 JDBC 미포함 | Task 4 Step 1·9 |
| §9 회원 ID 타입 | `Address.member_id` UUID → BIGINT | Task 2 Step 7, Task 3 Step 2 |
| §10 경로/패키지 | `/projects/woobeee`, `com.woobeee.{core,mvc,game}` | Global Constraints |

**의도적 spec 이탈 (근거 명시):**
- spec §2의 `/api/blog/*` → 실제 매핑은 `/api/back/*` + `/api/auth/*`. 컨트롤러 경로를 바꾸면 front 호출부가 전부 깨지므로 rewrites를 실제 경로에 맞췄다 (Global Constraints, Task 5 Step 3).
- spec §3의 루트 `db/migration/` → `app-mvc/src/main/resources/db/migration/`. Flyway 기본 classpath 경로라 추가 설정이 불필요하다 (File Structure 각주).
- spec §6의 compose에 두 앱 서비스 추가 → 인프라만 compose로 띄우고 앱은 `spring-boot:run`. 앱 Docker 이미지 빌드는 이 재구성 범위 밖이며, 후속 배포 작업에서 다룬다.
- spec §3 core 목록의 "공통예외" → auth와 blog의 예외 advice는 서로 다른 계층 구조(`AuthRestControllerAdvice` vs `ErrorCode` 기반 `AuthControllerAdvice`)를 갖고 있어 통합하면 응답 계약이 바뀐다. 도메인별로 유지하고 `ApiResponse` 만 통합했다.

**2. Placeholder scan** — TBD/TODO 없음. 모든 코드 스텝에 실제 코드 블록이 있고, 모든 검증 스텝에 실행 명령과 기대 출력이 있다.

**3. Type consistency** — `ApiResponse.Header(boolean, String, int)`는 Task 1 정의와 Task 4 사용이 일치. `AuthTokenType.redisKey`가 만드는 `auth:token:access:<token>` 은 Task 1 테스트, Task 4 `ReactiveTokenVerifier`, Task 4 Step 11 스모크에서 동일. `TokenMetadata(Long, String, String, String)` 필드 순서는 Task 1 정의와 Task 4 테스트/변환이 일치. `GamePrincipal(Long, String, String)` 은 Task 4 정의·필터·컨트롤러·테스트에서 일치. `GameAuthWebFilter.PRINCIPAL_ATTRIBUTE = "woobeee.game.principal"` 은 필터와 컨트롤러 양쪽에서 상수로 참조.
