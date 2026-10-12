# syntax=docker/dockerfile:1
#
# app-mvc (auth + blog, Spring MVC + JPA) 이미지.
#
# 빌드 컨텍스트는 반드시 "레포 루트"여야 한다 — app-mvc 가 core 모듈에 의존해서
# 둘을 같이 빌드해야 하기 때문이다. 그래서 -f 로 이 파일을 가리키고 마지막에 . 을 준다:
#
#   docker build -f app-mvc/Dockerfile -t woobeee/app-mvc:latest .
#
# 멀티플랫폼(amd64+arm64) 이미지는 scripts/docker-build.sh 로 만든다.
#
# 실행 (DB/Redis/S3 설정은 이미지에 넣지 않고 환경변수로 주입한다 — 비밀이 이미지에 남지 않게):
#
#   docker run --rm -p 8000:8000 -e DB_URL=... -e REDIS_HOST=... woobeee/app-mvc:latest
#
# 필요한 환경변수 목록은 CLAUDE.md 의 "필요한 환경변수" 참고.

# 자바 버전을 한 곳에서 관리한다. 빌드(JDK)와 실행(JRE)이 같은 버전이어야 한다.
ARG JAVA_VERSION=25

# =====================================================================
# 1단계 build: 소스에서 jar 를 만들고, 실행용 레이어로 쪼개 둔다.
#   이 단계의 JDK/Maven/소스는 최종 이미지에 들어가지 않는다(멀티스테이지).
# =====================================================================
# --platform=$BUILDPLATFORM: 이 단계는 "빌드를 돌리는 기계"의 아키텍처로 실행한다. 만들어지는 jar 는
# 아키텍처와 무관하므로(네이티브 라이브러리 jar 없음) 멀티플랫폼 빌드에서 QEMU 에뮬레이션 없이
# 한 번만 빌드하고, 아래 runtime 단계만 타깃(amd64/arm64)별로 만들어진다.
FROM --platform=$BUILDPLATFORM eclipse-temurin:${JAVA_VERSION}-jdk AS build
WORKDIR /workspace

# [캐시 전략] 소스보다 "pom.xml 같은 잘 안 바뀌는 파일"을 먼저 복사한다.
# 소스만 바뀌면 이 아래 의존성 다운로드 레이어는 캐시를 그대로 쓴다.
# 두 모듈 pom 을 다 복사하는 이유: 루트 pom 이 두 모듈을 선언해서 하나라도 없으면 해석에 실패한다.
COPY mvnw ./
COPY .mvn .mvn
COPY pom.xml ./
COPY core/pom.xml core/
COPY app-mvc/pom.xml app-mvc/
# app-weblfux는 deprecated

# --mount=type=cache: ~/.m2 를 빌드 사이에 보존한다(이미지 레이어에는 남지 않는다).
# -pl app-mvc -am: app-mvc 와 그것이 의존하는 core 만 대상으로 한다.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -pl app-mvc -am dependency:go-offline

# 이제 소스를 복사한다.
COPY core core
COPY app-mvc app-mvc

# 테스트는 이미지 빌드에서 돌리지 않는다(SchemaValidationTest 가 Postgres 를 요구한다).
# 테스트는 CI/로컬에서 따로 돌린다.
# 'extract --layers' 는 Spring Boot 가 jar 를 변경 빈도별 폴더(dependencies, application …)로
# 풀어 주는 기능이다. 이걸 폴더별로 COPY 하면 Docker 레이어도 그대로 나뉜다.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -pl app-mvc -am package -DskipTests \
    && java -Djarmode=tools -jar app-mvc/target/app-mvc.jar \
        extract --layers --launcher --destination /extracted

# =====================================================================
# 2단계 runtime: 실행에 필요한 것만 담은 최종 이미지.
# =====================================================================
FROM eclipse-temurin:${JAVA_VERSION}-jre AS runtime
WORKDIR /app

# [보안] root 로 실행하지 않는다. 고정 uid/gid 를 써서 볼륨 권한 맞추기도 쉽다.
# 로그 디렉터리(logback 이 /app/logs 에 파일을 쓴다)를 미리 만들어 소유권을 준다.
# 없으면 비root 사용자가 만들지 못해 로그 설정이 실패한다.
RUN groupadd --system --gid 1001 app \
    && useradd --system --uid 1001 --gid app --no-create-home app \
    && mkdir logs && chown app:app logs

# [레이어 순서] 안 바뀌는 것 → 자주 바뀌는 것 순서로 COPY 한다.
# 코드만 고치면 마지막 application 레이어만 다시 만들어지고 푸시/풀도 그만큼만 일어난다.
COPY --from=build --chown=app:app /extracted/dependencies/ ./
COPY --from=build --chown=app:app /extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /extracted/application/ ./

USER app

# EXPOSE 는 문서 역할만 한다. 실제 공개는 docker run -p 로 한다.
EXPOSE 8000

# 컨테이너 메모리 한도의 75% 까지 힙으로 쓴다(기본값은 25% 라 메모리를 많이 남긴다).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

# exec 형식(JSON 배열)이라 java 가 PID 1 이 되어 docker stop 의 SIGTERM 을 직접 받는다
# → Spring 이 정상 종료(graceful shutdown)한다.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
