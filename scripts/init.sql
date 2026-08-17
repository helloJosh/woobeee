-- 블로그 카테고리 시드.
--
-- 스키마는 Flyway 가 단일 소스다(app-mvc/src/main/resources/db/migration). 이 파일은 DDL 을
-- 만들지 않고 데이터만 넣으므로, **app-mvc 가 한 번 떠서 V1 이 적용된 뒤** 실행해야 한다.
-- docker-entrypoint-initdb.d 에 걸면 categories 테이블이 아직 없어서 실패한다.
--
--   psql "postgresql://root:123456789@localhost:9432/market" -f scripts/init.sql
--
-- 여러 번 돌려도 같은 상태가 되도록 id 를 명시하고 ON CONFLICT 로 덮어쓴다.

BEGIN;

-- 루트 카테고리 (parent_id IS NULL)
INSERT INTO categories (id, name_ko, name_en, created_at, updated_at, parent_id) VALUES
    (1, 'BACKEND',  'BACKEND',  TIMESTAMP '2025-09-01 23:04:23.024768', TIMESTAMP '2025-09-01 23:04:23.024820', NULL),
    (3, 'FRONTEND', 'FRONTEND', TIMESTAMP '2025-09-01 23:04:23.058000', TIMESTAMP '2025-09-01 23:04:23.058598', NULL)
ON CONFLICT (id) DO UPDATE SET
    name_ko    = EXCLUDED.name_ko,
    name_en    = EXCLUDED.name_en,
    created_at = EXCLUDED.created_at,
    updated_at = EXCLUDED.updated_at,
    parent_id  = EXCLUDED.parent_id;

-- 하위 카테고리
INSERT INTO categories (id, name_ko, name_en, created_at, updated_at, parent_id) VALUES
    (2, 'Spring',   'Spring',   TIMESTAMP '2025-09-01 23:04:23.053480', TIMESTAMP '2026-08-17 00:00:00.000000', 1),
    (5, 'Database', 'Database', TIMESTAMP '2025-09-03 23:04:23.058000', TIMESTAMP '2025-09-03 23:04:23.058000', 1),
    (6, 'Kafka',    'Kafka',    TIMESTAMP '2026-02-08 17:25:13.000000', TIMESTAMP '2026-02-08 17:25:35.000000', 1),
    (4, 'NextJS',   'NextJS',   TIMESTAMP '2025-09-01 23:04:23.064046', TIMESTAMP '2025-09-01 23:04:23.064058', 3)
ON CONFLICT (id) DO UPDATE SET
    name_ko    = EXCLUDED.name_ko,
    name_en    = EXCLUDED.name_en,
    created_at = EXCLUDED.created_at,
    updated_at = EXCLUDED.updated_at,
    parent_id  = EXCLUDED.parent_id;

-- Redis 만 id 를 박지 않는다. 위 넷은 posts.category_id 가 값을 들고 있어 id 를 고정해야
-- 하지만, Redis 를 참조하는 글은 없다. V6__backend_categories.sql 과 같은 방식으로 이름+부모
-- 중복만 막는다 -- 그래야 이 스크립트를 V6 적용 뒤에 다시 돌려도 Redis 가 두 개가 되지 않는다.
INSERT INTO categories (name_ko, name_en, created_at, updated_at, parent_id)
SELECT 'Redis', 'Redis',
       TIMESTAMP '2026-08-17 00:00:00', TIMESTAMP '2026-08-17 00:00:00', 1
WHERE NOT EXISTS (
    SELECT 1 FROM categories WHERE name_ko = 'Redis' AND parent_id = 1
);

-- 블로그 글. 구 프로젝트(woobeee-blog)의 postgres DB `public.post` 5행을 그대로 옮긴 것이다.
--
-- 컬럼 하나가 넘어오지 못했다: 구 `post.user_id` 는 uuid(=`user_info.id`)인데 새 스키마의
-- `posts.member_id` 는 BIGINT(members FK)다. 다섯 글 모두 같은 작성자
-- (635b783d-e21e-4727-bff0-fb28cf1a48ff)인데, 대응하는 members 행이 아직 없으므로 NULL 로 둔다.
-- members 를 옮긴 뒤 UPDATE 로 채워야 한다.
--
-- category_id 는 2·5·6 만 쓰이고 위 카테고리 시드에 전부 들어 있다.
INSERT INTO posts (id, title_ko, title_en, text_ko, text_en, views, created_at, updated_at, category_id, member_id) VALUES
    (5, 'MySQL와 PostgreSQL에서 복합키 성능 이슈, 실험 검증', 'Testing single, composite key issue in MySql and PostgreSQL', '## 1. 왜 하필 블로그의 첫 게시글이 복합키와 단일키
처음 백엔드 개발자로 입사했을 때, 데이터베이스 ERD가 복합키로 덕지덕지 붙어있던 기억이 너무 강렬했습니다.  심지어 로그 테이블에는 **FK와 복합키가 5개**나 붙어 있었습니다 😱  

실무에서는 당장 구조를 바꾸기 어렵기 때문에, **어떻게 운영해왔고 성능을 개선했는지**를 공유하고자  
첫 번째 게시글 주제로 선택했습니다.


## 왜 단일키를 쓸까? 복합키는 아예 안되는건가요?
결론부터 말하자면, **PostgreSQL에서는 FK 설정 여부에 따라 성능차이가 거의 없고**,  
**MySQL에서는 보조 인덱스를 걸었을 때 성능차이가 확연히 납니다.**

즉, PostgreSQL에서는 복합키를 고려할 만합니다. 예를 들어,
- 비즈니스적으로 특정 값을 **복합키로 강조**하고 싶을 때  
- 컬럼에 **비즈니스 도메인 키만 남기고 싶을 때**

아래 테스트는 1,000만 개의 데이터를 가진 두 테이블(복합키 / 단일키)에 대해  
시간 컬럼에 인덱스를 걸고, 요청당 **1만 개 조회 + 10개 저장**을 반복하며 측정한 결과입니다.


## 2. PostgreSQL (힙구조의 예시)

PostgreSQL은 테이블이 **Heap 구조**로 이루어져 있으며,  인덱스는 `TID 포인터`로 행 위치를 찾아갑니다.  즉, 복합키든 단일키든 포인터 크기는 동일하기 때문에 큰 차이는 없습니다.  
하지만 FK가 걸려 있다면 **검색 범위가 넓어져 성능이 떨어질 수밖에 없습니다.**

> 아래 테스트를 보면 FK가 있을 때는 복합키 성능이 낮고,  
> FK가 없을 때는 단일키와 거의 비슷한 결과를 보입니다.

- 테스트데이터로 복합키의 response time (FK, index가 걸려있다 가정) 

![](${image1.png})
![](${image2.png})

- 테스트데이터로 복합키의 response time (FK는 없고,  index가 걸려있다 가정) 

![](${image3.png})
![](${image4.png})

- 테스트데이터로 단일키만 걸려있을때의 response time (FK, index가 걸려있다 가정)

![](${image5.png})
![](${image6.png})

- 테스트데이터로 단일키만 걸려있을때의 response time (FK는 없고, index가 걸려있다 가정)

![](${image7.png})
![](${image8.png})

보시면 알겠지만 복합키일때 FK가 없고 있고의 차이가 좀 크다는 것을 알수 있습니다. 오히려 FK가 없을때는 단일키나 복합키나 조회속도가 비슷하다는 것을 테스트를 통해 알 수 있습니다.

> 참고문서
> https://www.postgresql.org/docs/current/sql-createindex.html
> https://www.postgresql.org/docs/current/sql-cluster.html


## 3. MYSQL (클러스터형의 예시)

MySQL은 **클러스터형 인덱스 구조**로, 보조 인덱스를 걸면  `(인덱스 키) + (row locator(PK))` 형태로 저장됩니다.   이때 PK가 복합키면 **PK 전체 컬럼이 함께 붙는 오버헤드**가 발생합니다.
> 즉, 복합키의 PK가 클수록 인덱스 크기도 비례해 커집니다.

아래 테스트 결과를 보면, **MySQL에서는 단일키의 성능이 월등히 좋습니다.**

- 테스트데이터로 복합키의 response time (FK, index가 걸려있다 가정) 

![](${image9.png})
![](${image10.png})

- 테스트데이터로 복합키의 response time (FK는 없고,  index가 걸려있다 가정) 

![](${image11.png})
![](${image12.png})

- 테스트데이터로 단일키만 걸려있을때의 response time (FK, index가 걸려있다 가정)

![](${image13.png})
![](${image14.png})

- 테스트데이터로 단일키만 걸려있을때의 response time (FK는 없고, index가 걸려있다 가정)

![](${image15.png})
![](${image16.png})

> 참고문서
> https://dev.mysql.com/doc/refman/8.4/en/innodb-index-types.html


## 4. MSSQL
MSSQL은 **클러스터형, 힙 구조 테이블을 모두 지원**하므로,  테이블 구조에 따라 PostgreSQL 또는 MySQL 방식 중 하나와 유사한 동작을 할 것으로 예상됩니다.  


## 5. 마치며
입사 후 PostgreSQL을 사용하면서,  기존 복합키 구조를 완전히 제거하지 않고 **FK만 선택적으로 삭제**하여  리스크 없이 성능을 개선했습니다.

결과적으로 **350ms → 평균 80~100ms**,  
현재는 **첫 요청 270ms / 평균 50~70ms**로 개선되었습니다.

##### 🔹 FK가 걸린 상태의 로그 조회 (예전 API)

![](${image17.png})

##### 🔹 FK를 제거한 상태의 로그 조회 (현재 API)

![](${image18.png})

제가 공부하고 경험한 내용이 도움이 되길 바랍니다

> FK 매핑과 테스트코드는 아래 url에 있으니 필요하신 분은 쓰시면 될 것같습니다. 
> woobeee-blog 안에 sample 모듈에 singlecompositekey 패키지 안에 있습니다.
> 🔗 [Github Link](https://github.com/helloJosh/woobeee-blog/tree/main/sample/src/main/java/com/woobeee/sample/singlecompositekey)
', '', 0, '2025-10-15 22:24:03.961413', '2025-10-15 22:24:03.961426', 5, NULL),
    (9, '청크 기반 대용량 SQL 데이터 최적화', 'Chunk-Oriented Processing for Large-Scale SQL Data', '## 1. 시작하며
입사 초기에 다른 DB에서 SQL로 데이터를 추출해 자사 DB에 저장하는 API가 있었습니다. 문제는 **컬럼 200개, 데이터 3천만 건 이상**이 되면 프로그램이 **메모리 초과로 다운**되는 것이었습니다.  게다가 FastAPI 기반으로 워커가 3개였기 때문에, 기본 RAM 사용량만 **7.5GB**에 달했습니다.

이 문제를 해결하기 위해 고민 끝에 **Spring Batch의 Chunk-Oriented Processing** 기능을 사용했습니다.  

그 결과:
- 서버 평상시 RAM 소모량: **7.5GB → 2GB**
- 처리 가능 데이터: **1억 2천만 건 (약 2시간 내 완료)**

이 글에서는 해당 과정에서의 **Spring Batch 선택 이유, 청크 기반의 원리, 실제 테스트 결과**를 공유합니다.  
> 📦 **예시 코드 및 참고 자료**
> - [🔗 GitHub Sample Code](https://github.com/helloJosh/woobeee-blog/tree/main/sample/src/main/java/com/woobeee/sample/sqlbatch)
> - [📘 Spring Batch Doc: Chunk-Oriented Processing](https://docs.spring.io/spring-batch/reference/step/chunk-oriented-processing.html)


## 2. 청크 기반이 아닐 때의 문제점
JVM의 메모리 구조를 보면, `new` 키워드로 객체를 생성할 때 **Heap 메모리에 공간이 부족하면 OutOfMemoryError**가 발생합니다.
> 🔗 [OpenJDK: allocation.cpp 소스 코드](https://github.com/openjdk/jdk/blob/master/src/hotspot/share/memory/allocation.cpp)

즉, 대용량 데이터를 한 번에 `ResultSet`으로 가져오면 **모든 데이터를 한꺼번에 메모리에 적재**하게 되고, 결국 프로그램이 메모리 초과로 종료됩니다.

이 문제의 가장 빠른 해결책은 데이터를 **청크 단위 (예: 1만~5만 행)** 로 나누어 처리하는 것입니다.  
즉, 일정 크기만큼 데이터를 읽고, 처리하고, DB에 반영한 뒤 메모리를 비우는 방식으로 효율을 극대화합니다.

## 3. 왜 Spring Batch를 선택했는가
당시 고민했던 선택지는 **Apache Spark**와 **Spring Batch**였습니다.

#### 3-1. Spark를 고려한 이유
- 데이터 모델 학습용으로 쓰이는 API라 Spark의 빅데이터 적합성은 매우 매력적이었음.
- 병렬처리 및 분산처리 성능이 뛰어남.
#### 3-2. 그럼에도 불구하고 Spring Batch를 선택한 이유
1. **Spring 친숙도**  
   → 이미 Spring 기반 프로젝트였기 때문에, 문서를 보며 빠르게 구현 가능.
2. **학습 비용 절감**  
   → Spark, Scala, HDFS 등을 새로 배우기엔 리소스가 부족함.
3. **DB 트랜잭션 처리 가능**  
   → Spark는 트랜잭션 처리를 직접 커스텀해야 했음.
4. **I/O 유연성**  
   → 다양한 Reader/Writer/Processor를 기본 제공하며, 커스텀 구현이 용이함.

> 참고: [ETL in Java — Spring Batch vs Apache Spark (StackOverflow)](https://stackoverflow.com/questions/53689531/etl-in-java-spring-batch-vs-apache-spark-benchmarking)


## 4. Spring Batch 기본 구조
Spring Batch는 대용량 데이터를 **Step 단위로 청크 처리**할 수 있도록 설계된 프레임워크입니다.
![](${image1.png})

#### 주요 구성요소
| 구성요소 | 설명 |
|-----------|------|
| **Job** | 전체 작업 단위. 같은 `JobParameter`는 동일 JobInstance로 취급되며, 재실행 불가. |
| **Step** | Job을 구성하는 실행 단위. `ItemReader`, `ItemProcessor`, `ItemWriter`, `Tasklet`으로 구성. |
| **ItemReader** | 데이터 소스에서 데이터를 1건씩 읽어오는 클래스. `open`, `read`, `close` 메서드를 통해 제어. |
| **ItemProcessor** | 읽어온 데이터를 가공하거나 변환. 비즈니스 로직을 적용. |
| **ItemWriter** | 처리된 데이터를 DB나 파일 등 타겟에 기록. |
| **Tasklet** | Reader/Writer로 처리할 수 없는 단일 작업용 커스텀 로직. |
| **JobLauncher** | Job 실행 담당. |
| **JobRepository** | Job/Step 실행 이력 관리. |
>  Tip:  
> - ItemReader, Processor, Wirter는 커스터마이징이 가능하며, File 관련 batch 처리는 따로 글을 쓸계획입니다.
> - `@Scheduled` 기반 스케줄러는 단순 반복에는 적합하지만,  복잡한 배치 스케줄링은 Quartz나 Cron 기반 스케줄러와 연동하는 것이 더 안정적입니다.


## 청크 기반 프로세싱 테스트 결과
아래는 동일한 SQL Job을 청크 크기별로 실행했을 때의 dm-back-batch의 그라파나의 실제 결과입니다.  
(데이터 컬럼 200개, 전체 1억 건 기준 / 시스템: 4코어 16GB RAM)

| 청크 크기 | 메모리 사용량 | 처리 시간 | 비고 |
|------------|----------------|-------------|------|
| 500개 | 350MB → 377MB | 너무 느려 측정 불가 | |
| 10,000개 | 1.64GB | 약 38분 | ✅ 가장 효율적 |
| 50,000개 | 2.5GB | 약 58분 | 메모리 증가로 오히려 비효율적 |

![](${image2.png})
![](${image3.png})

> 💡 결론:  
> “**청크 크기는 작아도 느리고, 너무 커도 메모리 과다**” — 즉, **서버/DB 스펙별 최적값**이 존재함.


## 마치며
이 경험을 통해 대용량 SQL 데이터 문제를 단순하고 효과적으로 해결할 수 있었습니다.

- 메모리 절감: **7.5GB → 2GB**
- 처리 효율 향상: **1억 2천만 건 / 2시간 처리**
- 유지보수성 개선: 트랜잭션 단위로 안정적인 배치 실행 가능

> 샘플 코드는 밑에 붙여넣었습니다.
> [🔗 GitHub Sample Code](https://github.com/helloJosh/woobeee-blog/tree/main/sample/src/main/java/com/woobeee/sample/sqlbatch)', '', 0, '2025-10-15 22:29:22.058142', '2025-10-15 22:29:22.05815', 2, NULL),
    (10, '오브젝트 스토리지를 사용한 대용량 파일 처리 최적화', 'Large-Scale File Processing Optimization Using Object Storage', '## 1. 서론
이전에는 **FastAPI** 환경에서 대용량 파일이나 SQL 데이터를 다루다가 메모리 초과로 프로그램이 자주 종료되는 문제가 있었습니다.
이 문제를 해결하기 위해 **Spring 기반의 3계층 아키텍처**로 전환했습니다.

* **socket-api**: 장시간 연결이 필요한 실시간 소켓 통신 처리
* **web-api**: 일반적인 HTTP 요청 처리
* **batch 모듈**: 대용량 데이터 및 파일 배치 처리

또한 분산 환경 구성을 위해 **Redis**, **Kafka**, **Object Storage**, **Gateway**, **JWT 토큰 인증**을 도입했습니다.
그중에서도 특히 **Object Storage 기반의 청크(chunk) 단위 파일 처리**는 저희 환경에 맞게 커스터마이징이 필요했는데, 이번 글에서는 그 구현 방식과 고려사항을 정리했습니다.


## 2. 청크 기반이 아닐때의 문제점
JVM의 메모리 할당 구조를 보면 new 키워드로 객체를 생성할 때 **Heap 메모리에 공간이 부족하면 OutOfMemoryError**가 발생합니다.
> 🔗 [jdk/src/hotspot/share/memory/allocation.cpp at master · openjdk/jdk](https://github.com/openjdk/jdk/blob/master/src/hotspot/share/memory/allocation.cpp)

이 문제의 가장 단순한 해결책은 데이터를 **청크 단위(예: 1만~5만개씩)** 로 나누어 처리하는 것입니다.
이렇게 하면 메모리를 효율적으로 사용하고 안정적으로 배치 작업을 수행할 수 있습니다. SQL 같은 경우는 **Spring Batch** 에서 기본으로 제공해주는 **Item Reader** 클래스가 있지만, **File System** 혹은 **Object Storage** 은 직접 구현해야합니다.


## 3. 파일 청크 기반 처리 시 고려사항
SQL과 달리 **Object Storage의 파일**은 “줄(line)” 단위 접근이 불가능합니다.
즉, 데이터를 청크 단위로 처리하려면 **바이트 단위**로 접근해야 합니다.

가장 단순한 방식은 “N번째 줄부터 재개”하는 방법이지만,
이 방식은 매번 파일의 **처음부터 다시 읽어야 하므로 비효율적**입니다.

이를 해결하기 위해, 작업을 시작하기 전에 **파일 전체를 한 번 스캔하여 개행문자의 바이트 오프셋(start, end)** 을 미리 계산합니다. 이 정보를 바탕으로 Object Storage에서 해당 범위만 읽어오면 **정확한 줄 단위 데이터를 유지하면서 효율적인 청크 처리**가 가능합니다.


## 4. 바이트로 끊어서 넣기
```
concurrentFlatFileJob
 └── flatFileMasterStep (Partition Step)
       ├── MinioRangePartitioner → { key, startOffset, endOffset } 설정
       └── flatFileWorkerStep (병렬/단일 실행 선택)
              ├── concurrentFlatFileItemReader(@StepScope)
              └── concurrentFlatFileWriter
```
Partitioner 단계에서 파일 전체 바이트를 읽고, 각 줄의 바이트 길이를 기반으로 ExecutionContext에 구간 정보를 저장합니다. 그 후 WorkerStep에서 해당 구간만 읽어 병렬로 처리합니다.
쓰레드는 최대 8개까지 병렬 실행이 가능하지만, 저희 환경에서는 **DB 부하를 최소화하기 위해 단일 실행 모드**로 설정했습니다.


## 5. N번째 줄 부터 재개 vs 바이트로 끊어서 넣기
csv 5MB 정도의 크기인 파일로 상대적으로 비교했을때의 시간차이에서 많은 차이는 나지 않지만 바이트로 끊어서 넣기가 빠른 것을 알수있습니다.

- N번째 줄부터 재개

| start_time                 | end_time                   |
|----------------------------|----------------------------|
| 2025-10-08 23:36:49.700677 | 2025-10-08 23:36:49.799743 |
| 2025-10-08 23:22:36.437151 | 2025-10-08 23:22:36.516905 |

- 바이트로 끊어서 넣기

| start_time                 | end_time                   |
|----------------------------|----------------------------|
| 2025-10-10 00:22:36.459000 | 2025-10-10 00:22:36.476494 |
| 2025-10-09 22:18:44.346081 | 2025-10-09 22:18:44.405655 |


## 6. 마치며

이번 프로젝트를 통해 “**Object Storage + Batch + 청크 기반 구조**”를 결합하면 대용량 데이터도 안정적으로 처리할 수 있다는 것을 확인했습니다. 비슷한 문제를 겪는 분들에게 이 글이 도움이 되길 바랍니다.

> woobeee-blog의 sample 모듈 안에 코드가 있습니다. 필요하면 아래 url을 통해 확인해주세요
> [🔗 GitHub Sample Code](https://github.com/helloJosh/woobeee-blog/tree/main/sample/src/main/java/com/woobeee/sample/filebatch)', '', 0, '2025-10-15 22:33:41.83097', '2025-10-15 22:33:41.830977', 2, NULL),
    (11, '쿠폰 발급 API 최적화', 'Optimizing Coupon API', '## 1. 서론
NHN Academy 팀 프로젝트에서 저는 쿠폰 발급 기능을 담당했는데, 이 기능은 다른 API와 달리 **짧은 시간에 요청이 몰릴 가능성**이 높았습니다. 이를 검증하기 위해 **Artillery 부하 테스트**를 진행하였더니 요청 수가 증가할수록 응답 시간이 급격히 늘어나며 아래 사진과 같이 **최대 400ms 이상 지연**되는 현상이 발생했습니다. 
![](${image1.png})
개인적으로 이 문제의 원인을 깊이 파악하고 해결 방안을 실험해 보았습니다. 이 글에서는 그 과정과 결과를 공유하고자 합니다.

## 2. RabbitMQ vs Kafka
병목 현상을 완화하기 위해, API 요청을 바로 DB로 쓰는 구조 대신 **Message Queue(MQ)** 를 사용하기로 했습니다.
고민한 후보는 **RabbitMQ**와 **Kafka**였으며, 각각의 특징은 다음과 같습니다.

#### RabbitMQ
* 메시지를 **Consumer에게 직접 Push**하는 구조 → 실시간성이 높음
* 상대적으로 설정이 단순하고 빠른 응답에 유리

#### Kafka
* 메시지를 **파일(로그) 형태로 저장**하고, **Consumer가 직접 Pull**
* offset 기반으로 이전 메시지 재처리가 용이
* **Zero-Copy** 기능으로 커널 간 복사 최소화 → 대용량 처리 효율적
* 높은 처리량에 유리하지만 **실시간성은 다소 낮음**

이번 문제의 핵심은 **“요청이 몰려도 순차적으로, 실시간으로 쿠폰을 발급”** 하는 것이었기에, **RabbitMQ**를 선택했습니다.


## 3. 시스템 설계 
**🧱 전체 아키텍처 흐름**
```
[Client] → [Web API] → [RabbitMQ Queue] → [Batch Worker] → [Redis 저장]
        		                                               ↓
		                                              [10분마다 Bulk Insert]
```
#### 처리 흐름 상세
1. 사용자가 쿠폰 발급 요청 → **RabbitMQ**에 메시지 등록
2. **Batch Worker**가 메시지 수신 후 **Redis**에 저장
   * req:{슬롯}:list : 요청 리스트
   * req:{슬롯}:prod : 요청 스테이징 리스트
   * req:{슬롯}:count : 요청 수 카운팅
3. **10분마다 실행되는 Batch Job**이 동작
   * Redis의 list를 proc으로 스왑 (Lua 스크립트 활용)
   * 스왑된 메시지를 **Bulk Insert**로 DB에 한꺼번에 저장
   * Redis 카운트 초기화

#### Lua 스크립트 활용 이유
10분 단위로 메시지를 처리하는 동안에도 **새로운 쿠폰 요청이 동시에 들어올 수 있기 때문에**, **원자성(Atomicity)** 을 보장해야 했습니다.
이에 **Lua 스크립트**를 활용하여 Redis 내에서 list → proc 스왑을 **단일 트랜잭션으로 처리**하도록 구현했습니다.
이 방식 덕분에 **나중에 들어온 메시지는 자동으로 다음 10분 배치에서 처리**되며, **데이터 정합성을 유지하면서도 락(lock) 비용을 최소화**할 수 있었습니다.


## 4. 마무리
이 구조를 적용한 뒤, 부하 테스트 결과 평균 응답 시간이 **약 150ms 수준으로 60% 단축**되었고 많은 트래픽에서도 안정적으로 메시지를 처리할 수 있었습니다.
![](${image2.png})

> **예시 코드** 프로젝트 했던 코드 밑에 있습니다.
> - 🔗[Coupon Batch Tasklet Code URL](https://github.com/helloJosh/bookstore-3runner/blob/main/batch/src/main/java/com/nhnacademy/batch/coupon/tasklet/Tasklets.java)
> - 🔗[Message Queue Consumer URL](https://github.com/helloJosh/bookstore-3runner/blob/main/batch/src/main/java/com/nhnacademy/batch/messagequeue/CouponRequestConsumer.java)', '', 0, '2025-10-22 22:46:15.338437', '2025-10-22 22:46:15.338451', 2, NULL),
    (12, 'Spring + Kafka 기반 Outbox 패턴 실전 구현', 'Spring Kafka Outbox Pattern', '## 1. 왜 Outbox 패턴
회사에서 Spring Kafka 기반으로 이벤트를 처리하던 중, Socket에서 발생한 IOException이 내부적으로 SQLException으로 전환되는 문제가 발생했다.

그 결과 **DB 상태와 메시지 처리 결과 간의 정합성이 깨지는 장애**가 발생했다.
* **Producer 측 트랜잭션은 롤백**
* 하지만 **Consumer는 이미 Kafka 이벤트를 수신하여 처리**
* 이후 재시도 과정에서 동일 이벤트가 **중복 발행**

이 문제를 해결하기 위해 **이벤트 발행을 DB 트랜잭션과 원자적으로 묶을 수 있는 Outbox 패턴**을 도입했고, 현재 블로그의 인증(Auth) 도메인에 적용하고 있어 그 구현 내용을 정리해본다.

## 2. 전체 아키텍처 개요
![](${image1.png})
* Auth Service
  * 비즈니스 트랜잭션 내에서 Outbox 테이블에 이벤트 저장
  * 서비스 로직과 이벤트 발행을 단일 트랜잭션으로 묶음
* Outbox Producer
  * Outbox 테이블을 배치 단위로 조회하여 Kafka로 이벤트 발행
* Producer SQL
  * FOR UPDATE SKIP LOCKED를 사용해 멀티 인스턴스 환경에서도 스레드 안전성 확보
* Outbox Recovery Scheduler
  * Producer 장애로 인해 SENDING 상태에 갇힌 메시지를 복구

## 3. Outbox 테이블 설계
```java
@Entity
public class Outbox {
    @Id
    private UUID id;

    private EventType type;
    private EventStatus status;

    private String topic;
    private String key;

    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    private int attempts;
    private String lastError;

    private LocalDateTime createdAt;
    private LocalDateTime lockedAt;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime sentAt;
} 
```
* status
  * NEW → SENDING → SENT / FAIL
* nextAttemptAt
  * 지수 백오프 기반 재시도 시점 관리
* lockedAt
  * 장애 발생 시 stuck 상태 판단 기준

## 4. 트랜잭션 내부 이벤트 저장
```java
@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
public void handleEvent(MessageEvent event) {
    log.info("send with redis: {}", event.message());

    String topic = profile + event.topic();
    String key = event.key();

    try {
        String payload = objectMapper.writeValueAsString(event.message());

        Outbox outboxMessage = Outbox.builder()
            .id(event.eventId())
            .type(EventType.TRIGGER)
            .status(EventStatus.NEW)
            .topic(topic)
            .key(key)
            .payload(payload)
            .attempts(0)
            .lastError(null)
            .createdAt(LocalDateTime.now())
            .nextAttemptAt(LocalDateTime.now())
            .sentAt(null).build();

        outBoxMessageCustomRepository.insertNew(
                event.eventId(), EventType.TRIGGER, EventStatus.NEW, topic, key, payload, LocalDateTime.now());

        log.info("Outbox stored. eventId={}, topic={}, key={}",
                outboxMessage.getId(), topic, key);
    } catch (JsonProcessingException e) {
        throw new RuntimeException(e);
    }
}
```
- 왜 BEFORE_COMMIT인가
  * 비즈니스 트랜잭션과 Outbox 저장을 동일 트랜잭션으로 묶기
  * 커밋 실패 시 → 이벤트도 같이 롤백
  * Kafka 직접 발행 ❌

## 5. Outbox Producer - 배치 발행
```java
@Scheduled(fixedDelayString = "1000") // 1초마다
public void publish() {List<OutBoxMessageCustomRepositoryImpl.OutboxRow> batch =
            outboxRepository.claimBatchForSend(LocalDateTime.now(), BATCH_SIZE);

    if (batch.isEmpty()) return;

    for (var row : batch) {
        try {
            kafkaTemplate.send(row.topic(), row.key(), row.payload()).get();
            outboxRepository.markSent(row.id(), LocalDateTime.now());

            log.info("Outbox sent. id={}, key={}, topic={}", row.id(), row.key(), row.topic());
        } catch (Exception ex) {
            int attempts = row.attempts();
            long delaySeconds = Math.min(300, (long) Math.pow(2, Math.min(attempts, 6)) * 5);
            LocalDateTime nextAttemptAt = LocalDateTime.now().plusSeconds(delaySeconds);

            outboxRepository.markFailed(row.id(), ex.getMessage(), nextAttemptAt);

            log.error("Outbox send failed. id={}, attempts={}, nextAttemptAt={}, err={}",
                    row.id(), attempts, nextAttemptAt, ex.getMessage(), ex);
        }
    }
}
```
 
##### claimBatchForSend의 SQL
```sql
WITH cte AS (
  SELECT id
  FROM outbox
  WHERE status IN (''NEW'',''FAIL'')
    AND next_attempt_at <= ?
  ORDER BY created_at
  LIMIT ?
  FOR UPDATE SKIP LOCKED
)
UPDATE outbox o
SET status = ''SENDING'',
    attempts = o.attempts + 1,
    locked_at = now(),
    last_error = NULL
FROM cte
WHERE o.id = cte.id
RETURNING
    o.id,
    o.type,
    o.status,
    o.topic,
    o.key,
    o.payload,
    o.attempts,
    o.last_error,
    o.created_at,
    o.locked_at,
    o.next_attempt_at,
    o.sent_at
```
* 조회 + 상태 변경을 원자적으로
* FOR UPDATE SKIP LOCKED
  * 다른 인스턴스가 처리 중인 row는 스킵
  * 수평 확장 가능
* 동기 send (get()) → at-least-once 보장

## 6. 실패 처리 재시도 전략
```java
@Scheduled(fixedDelayString = "6000")
public void recoverStuckSending() {
    LocalDateTime now = LocalDateTime.now();

    int recovered = recoveryRepository.recoverStuckSending(now, STUCK_THRESHOLD);

    if (recovered > 0) {
        log.warn("Recovered {} stuck SENDING outbox messages", recovered);
    }
}
```
- `STUCK_THRESHOLD` 만큼 SENT나 FAIL로 걸려있는 상태를 NEW로 복구

## 7. Outbox패턴의 장점
- DB, Kafka 정합성 보장
- 쓰레드 안전함
- 장애 복구. 가능성
- 운영 추적이 좋다

## 8. 한계와 개선 포인트
- **Idempotent하지 않음**
  - 다음 글에서 Idempotentency 구현 방법을 다룰 예정
* **대량 트래픽 환경에서 Scheduler 방식의 한계**
  * Batch size 제한
  * 스케줄 주기마다 발행되는 메시지 수가 처리 가능한 메시지 수를 초과하는 경우 backlog 발생
  * 폴링 기반 구조로 이벤트가 없을 때도 DB 부하 발생


## 9. 마무리 
실무에서 실제로 발생한 정합성 문제를 해결하기 위해 Outbox 패턴을 도입했고,
현재 트래픽 규모에서는 충분히 안정적으로 동작하고 있다.

다만, **트래픽이 증가할 경우 발생할 수 있는 한계**도 명확히 보이기 때문에
다음 글에서는 다음 주제를 다뤄볼까 고민중이다.
* Idempotent Consumer 구현
* Scheduler 기반 Outbox의 한계
* CDC(Debezium) 기반 Outbox로의 확장', '', 0, '2026-02-08 17:24:17.426884', '2026-02-08 17:24:17.426936', 6, NULL)
ON CONFLICT (id) DO UPDATE SET
    title_ko    = EXCLUDED.title_ko,
    title_en    = EXCLUDED.title_en,
    text_ko     = EXCLUDED.text_ko,
    text_en     = EXCLUDED.text_en,
    views       = EXCLUDED.views,
    created_at  = EXCLUDED.created_at,
    updated_at  = EXCLUDED.updated_at,
    category_id = EXCLUDED.category_id;

-- id 를 직접 박았으므로 IDENTITY 시퀀스를 최댓값 뒤로 밀어 둔다.
-- 이걸 빼면 다음 INSERT 가 id=1 을 시도해 중복 키로 죽는다.
SELECT setval(pg_get_serial_sequence('categories', 'id'), (SELECT MAX(id) FROM categories));
SELECT setval(pg_get_serial_sequence('posts', 'id'),      (SELECT MAX(id) FROM posts));

COMMIT;
