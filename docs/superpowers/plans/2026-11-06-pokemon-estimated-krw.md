# pokemon 예상 금액 전환 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 공동구매의 환차손익을 없애고, 신청서 원화를 "하루 한 번 갱신되는 오늘 환율 × 루피 합계"로 매번 계산한 예상 금액으로만 보여준다.

**Architecture:** 서버가 응답마다 오늘 환율(`ExchangeRateService.current`)로 `estimatedKrw` 를 계산해 내려준다. 원화·환율 스냅샷 컬럼은 Flyway `V27` 로 지운다. 한 번에 깨지지 않도록 ①환율 갱신 → ②예상 필드를 **추가** → ③저장 컬럼과 환율 방식 **제거** → ④프론트 → ⑤문서·검증 순으로 간다. 각 태스크 끝에서 빌드와 테스트가 초록이어야 한다.

**Tech Stack:** Java 25, Spring Boot 4.0.5, JPA(validate)+Flyway, Redis, JUnit5/Mockito, Next.js 14, vitest.

**Spec:** `docs/superpowers/specs/2026-11-06-pokemon-estimated-krw-design.md`

## Global Constraints

- 갱신 시각: 매일 `09:10` KST — `@Scheduled(cron = "0 10 9 * * *", zone = "Asia/Seoul")`.
- 환율 캐시 신선값 TTL 24시간(`POKEMON_RATE_CACHE_TTL` 기본 `24h`). `:last` 키(TTL 없음)와 `stale` 폴백, 모르면 503 `pokemon_rateUnavailable` 은 유지.
- 예상 금액 = `round(totalInr × 오늘 환율) + donationKrw`, `BigDecimal.HALF_UP`(서버) / `Math.round`(프론트 미리보기). **기부금에는 환율을 곱하지 않는다.**
- "오늘 환율 기준 예상" 문구를 신청 폼과 신청서 목록 **양쪽**에 표시한다. `stale` 이면 "마지막으로 확인된 환율" 문구.
- `V25`·`V26` 등 적용된 마이그레이션은 수정 금지. 새 파일 `V27__pokemon_drop_fx.sql`.
- **운영 DB `market`(`localhost:9432`) 에 `V27` 을 적용하기 전에는 SQL 문장과 영향 행 수를 보여주고 명시적 승인을 받는다.** compose `app-mvc` 를 띄우면 곧 적용이다. 테스트는 `market_test` 만 쓴다.
- 쿼리는 파생 메서드 또는 네이티브 SQL(N+1 금지, 바인딩 파라미터). 테스트 메서드 이름·주석에 AC ID 를 단다.
- `core` 는 웹 스타터 무의존 유지. 도메인 경계: `com.woobeee.mvc.pokemon` 안에서만 변경.
- 테스트·빌드 실패 시 원인을 요약하고 계속 / 부분 롤백 / 전체 롤백 중 사용자에게 묻는다. 임의 롤백 금지.
- 커밋 메시지 끝에 `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` 를 붙인다.

## Review Focus

- 환율 제공자가 죽은 채 09:10 갱신이 돌 때 예외가 번지지 않고 다음 지연 조회가 보충한다 → Task 1.
- 제공자가 죽고 `:last` 만 있을 때 예상 금액이 그 값으로 계산되고 `stale: true` 가 실린다 → Task 2.
- 신청 뒤 환율이 움직이면 같은 신청서의 예상 금액이 따라 움직인다(구 AC-05 의 반대) → Task 2.
- 기부금이 환율에 곱해지지 않는다(JPY 처럼 환율이 1 미만이어도) → Task 2, Task 4.
- 운영자 집계 합계가 신청서별 예상 금액의 합과 정확히 같다(1원 오차 없음) → Task 4.

---

## File Structure

| 파일 | 책임 | 변경 |
| --- | --- | --- |
| `app-mvc/.../pokemon/rate/ExchangeRateService.java` | 환율 조회·캐시 | `refreshAll()` 추가 |
| `app-mvc/.../pokemon/rate/ExchangeRateRefresher.java` | 09:10 스케줄 | 신규 |
| `app-mvc/.../pokemon/rate/ExchangeRateProperties.java`, `application.yaml` | TTL 기본값 | 1h → 24h |
| `.../pokemon/api/response/PokemonOrderResponse.java` | 신청서 응답 | `estimatedItemsKrw`, `estimatedKrw` 추가 → 이후 저장 필드 제거 |
| `.../pokemon/api/response/PokemonMyOrderResponse.java`, `repository/PokemonMyOrderRow.java`, `repository/PokemonOrderRepository.java` | 마이페이지 줄 | `transferKrw` → `estimatedKrw`(루피·기부금 조회 후 서버 환산) |
| `.../pokemon/api/response/PokemonRoundResponse.java`, `PokemonRoundBoardResponse.java` | 차수·보드 응답 | 환율 필드 제거, `estimatedTransferKrwTotal`, `rate` |
| `.../pokemon/entity/PokemonRounds.java`, `PokemonOrders.java`, `PokemonRateMode.java`(삭제) | 엔티티 | 환율 컬럼 제거 |
| `.../pokemon/service/PokemonServiceImpl.java` | 서비스 | rateMode/quoted/settled 로직 제거, 환산 적용 |
| `.../pokemon/api/request/PostPokemonRoundRequest.java`, `PutPokemonRoundRequest.java` | 요청 | `rateMode`, `quotedRate` 제거 |
| `app-mvc/src/main/resources/db/migration/V27__pokemon_drop_fx.sql` | 스키마 | 신규 |
| `front/lib/pokemon.ts`, `lib/api.ts`, `lib/pokemon.test.ts` | 판단 로직·타입 | 환차·환율 방식 제거, 예상 금액 |
| `front/components/pokemon/{order-form,order-rows,round-board}.tsx`, `app/pokemon/**`, `app/mypage/page.tsx` | 화면 | 예상 문구, 환율 UI 제거 |
| `docs/pokemon/PRD.md`, `docs/api/README.md`, `CLAUDE.md` | 문서 | 갱신 |

---

### Task 1: 환율 하루 한 번 갱신 (AC-06)

**Files:**
- Modify: `app-mvc/src/main/java/com/woobeee/mvc/pokemon/rate/ExchangeRateService.java`
- Modify: `app-mvc/src/main/java/com/woobeee/mvc/pokemon/rate/ExchangeRateProperties.java` (기본 `Duration.ofHours(24)`, javadoc)
- Modify: `app-mvc/src/main/resources/application.yaml` (`cache-ttl: ${POKEMON_RATE_CACHE_TTL:24h}`)
- Create: `app-mvc/src/main/java/com/woobeee/mvc/pokemon/rate/ExchangeRateRefresher.java`
- Test: `app-mvc/src/test/java/com/woobeee/mvc/pokemon/rate/ExchangeRateServiceTest.java` (신규, Mockito 로 `StringRedisTemplate`·`ExchangeRateClient` 대역)

**Interfaces:**
- Produces: `void ExchangeRateService.refreshAll()` — 지원 통화마다 신선값을 무시하고 `client.fetchToKrw` 로 다시 받아 `cache()`. 한 통화가 실패해도 나머지를 계속하고 예외를 던지지 않는다.
- Produces: `ExchangeRateRefresher.refresh()` — `@Scheduled(cron = "0 10 9 * * *", zone = "Asia/Seoul")` 로 `refreshAll()` 호출. `SchedulingConfig` 가 이미 `@EnableScheduling` 을 켠다.

- [ ] **Step 1: 실패하는 테스트 작성** — `ExchangeRateServiceTest`:
  - `POKEMON_AC_06_refreshAll_overwritesFreshValue`: Redis 에 신선값이 있어도 `refreshAll()` 이 `client.fetchToKrw("INR")` 를 호출하고 새 값을 `set`(TTL = `properties.cacheTtl()`) 한다.
  - `POKEMON_AC_06_refreshAll_swallowsProviderFailure`: `client.fetchToKrw` 가 `Optional.empty()` 또는 예외일 때 `assertThatCode(service::refreshAll).doesNotThrowAnyException()` 이고, 다음 통화는 여전히 처리된다.
  - `POKEMON_AC_06_current_fallsBackToLastAsStale`: 신선값 없음 + 제공자 empty + `:last` 있음 → `current("INR").stale()` 가 `true`.
  - `POKEMON_AC_06_current_throwsWhenNothingKnown`: 셋 다 없음 → `PokemonException`(`RATE_UNAVAILABLE`).
  - `ExchangeRateProperties` 기본 TTL: `new ExchangeRateProperties(null, null, null).cacheTtl()` 이 `Duration.ofHours(24)`.
- [ ] **Step 2: 실패 확인** — `./mvnw -pl app-mvc -am test -Dtest=ExchangeRateServiceTest` → `refreshAll` 미정의 컴파일 오류로 FAIL.
- [ ] **Step 3: 구현** — 위 Interfaces 시그니처대로. `refreshAll` 은 통화별 try/catch 로 `log.warn`. 기본 TTL 과 yaml 을 24시간으로.
- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS. 전체 `./mvnw -pl core,app-mvc -am test` 도 초록.
- [ ] **Step 5: 커밋** — `git add` 위 파일 → `feat(pokemon): 환율을 매일 09:10 KST 에 갱신하고 캐시를 24시간으로`.

---

### Task 2: 응답에 예상 금액 추가 (AC-04, AC-05, AC-39)

저장 컬럼은 **건드리지 않고** 필드만 더한다. 이 태스크 이후에도 프론트는 옛 필드를 읽으므로 호환된다.

**Files:**
- Modify: `.../pokemon/api/response/PokemonOrderResponse.java`, `PokemonMyOrderResponse.java`, `PokemonRoundResponse.java`, `PokemonRoundBoardResponse.java`
- Modify: `.../pokemon/repository/PokemonMyOrderRow.java`, `PokemonOrderRepository.java` (`findMyOrders` 가 `o.total_inr AS "totalInr"`, `o.donation_krw AS "donationKrw"` 도 반환)
- Modify: `.../pokemon/service/PokemonServiceImpl.java`
- Test: `app-mvc/src/test/java/com/woobeee/mvc/pokemon/service/PokemonServiceImplTest.java`

**Interfaces:**
- Consumes: `ExchangeRateService.current(String currency) -> ExchangeRate(toKrw, fetchedAt, stale)`.
- Produces: `PokemonOrderResponse` 에 `long estimatedItemsKrw`(기부금 제외), `long estimatedKrw`(기부금 포함); `PokemonOrderResponse.of(order, items, comments, viewerId, BigDecimal rate)` (rate 인자 추가).
- Produces: `PokemonMyOrderResponse.estimatedKrw`(= `round(totalInr × rate) + donationKrw`, 통화별 환율은 서비스가 `currency` 로 한 번씩만 조회해 캐시한 맵으로 적용 — N+1 환율 조회 금지).
- Produces: `PokemonRoundResponse.estimatedTransferKrwTotal`(취소 제외 신청서의 `estimatedKrw` 합). 보드는 기존 `currentRate`(`ExchangeRateResponse`: `toKrw`, `fetchedAt`, `stale`)를 그대로 쓴다.
- 계산식은 서비스의 기존 `toKrw(BigDecimal amount, BigDecimal rate)` 하나만 쓴다.

- [ ] **Step 1: 실패하는 테스트 작성** — `PokemonServiceImplTest` 에 추가(환율은 `exchangeRateService.current` 스텁):
  - `POKEMON_AC_04_estimate_isRoundedInrTimesRatePlusDonation`: `totalInr=298`, 환율 `14.13`, 기부금 `5000` → `estimatedItemsKrw == 4211`, `estimatedKrw == 9211`.
  - `POKEMON_AC_04_estimate_doesNotMultiplyDonationByRate`: 환율 `9.5`(JPY 형), `totalInr=0`, 기부금 `1000` → `estimatedKrw == 1000`.
  - `POKEMON_AC_05_estimate_followsRateAfterOrder`: 같은 신청서를 환율 `14.13` 로 한 번, `15.00` 으로 한 번 조회 → `estimatedItemsKrw` 가 `4211` 에서 `4470` 으로 바뀐다.
  - `POKEMON_AC_39_stale_rateIsExposedOnBoard`: `current` 가 `stale=true` 를 반환 → 보드의 `currentRate.stale() == true`.
  - `getMyOrders` 환산: 통화 `INR` 두 건이어도 `exchangeRateService.current("INR")` 호출은 1회(`verify(..., times(1))`).
- [ ] **Step 2: 실패 확인** — `./mvnw -pl app-mvc -am test -Dtest=PokemonServiceImplTest` → 새 필드 미정의로 FAIL.
- [ ] **Step 3: 구현** — 서비스의 `toResponse`/보드·`describe`/`getMyOrders` 가 환율을 한 번 읽어 위 필드를 채운다. `describe` 가 쓰는 `transferTotal(orders)` 는 `estimatedKrw` 합으로 새 필드에 매핑(옛 `transferKrwTotal` 은 이 태스크에서는 유지).
- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS, 전체 `./mvnw -pl core,app-mvc -am test` 초록.
- [ ] **Step 5: 커밋** — `feat(pokemon): 신청서·차수·마이페이지 응답에 예상 원화(estimatedKrw)를 더한다`.

---

### Task 3: 환율 스냅샷·확정·환율 방식 제거 (AC-05, AC-10, AC-11, AC-23, AC-27, AC-33)

**Files:**
- Create: `app-mvc/src/main/resources/db/migration/V27__pokemon_drop_fx.sql`
- Delete: `.../pokemon/entity/PokemonRateMode.java`
- Modify: `.../entity/PokemonRounds.java`, `PokemonOrders.java`
- Modify: `.../api/request/PostPokemonRoundRequest.java`, `PutPokemonRoundRequest.java`
- Modify: `.../api/response/PokemonOrderResponse.java`, `PokemonRoundResponse.java`, `PokemonMyOrderResponse.java`
- Modify: `.../service/PokemonServiceImpl.java`
- Test: `PokemonServiceImplTest`, `PokemonOrderRepositoryTest`, `SchemaValidationTest`

**Interfaces:**
- Consumes: Task 2 의 `estimated*` 필드.
- Produces: `V27` — `pokemon_rounds` 에서 `rate_mode`, `quoted_rate`, `quoted_at`, `settled_rate`, `settled_at`; `pokemon_orders` 에서 `quoted_rate`, `quoted_at`, `items_krw`, `transfer_krw` 를 `DROP COLUMN`. `donation_krw`·`total_inr`·`extra_inr` 는 남긴다. (컬럼에 걸린 CHECK 제약이 있으면 `V15`·`V21` 에서 확인해 같은 파일에서 먼저 푼다.)
- Produces: `PokemonRounds.open(hostMemberId, sequence, title, currency, bankAccount, deadline, memo)` · `update(title, bankAccount, deadline, memo)` · `changeStatus(PokemonRoundStatus next)` (환율 인자 제거). `PokemonOrders.create(...)`/`reprice(...)` 에서 `quotedRate`, `quotedAt`, `itemsKrw` 인자 제거, `transferKrw` 계산 제거.
- Produces: 요청 `PostPokemonRoundRequest`·`PutPokemonRoundRequest` 에서 `rateMode`, `quotedRate` 제거. 응답에서 `rateMode`, `quotedRate`, `quotedAt`, `settledRate`, `settledAt`, `itemsKrw`, `transferKrw`, `transferKrwTotal` 제거 (`estimated*` 로 대체됨).
- 서비스: `createRound`/`updateRound`/`changeRoundStatus`/`createOrder`/`updateOrder` 에서 환율 스냅샷·`rateMode(...)`·`round.rateFor` 사용을 모두 걷어낸다. `changeRoundStatus` 는 환율을 읽지 않는다.

- [ ] **Step 1: 실패하는 테스트로 고정** — 
  - `PokemonServiceImplTest`: 기존 환율 방식·`settledRate` 관련 테스트를 삭제하고, `POKEMON_AC_08_changeRoundStatus_doesNotTouchExchangeRate` 추가(`verifyNoInteractions(exchangeRateService)` 로 `changeRoundStatus` 가 환율을 읽지 않음을 확인).
  - `PokemonOrderRepositoryTest`: `order(...)`/`round(...)` 헬퍼를 새 시그니처로 고친다(`PokemonRateMode`, 환율 인자 제거). `findMyOrders` 가 `totalInr`·`donationKrw` 를 돌려주는지 단언.
  - `SchemaValidationTest`: 그대로 둔다 — `V27` + 엔티티 정합이 통과 조건.
- [ ] **Step 2: 실패 확인** — `./mvnw -pl app-mvc -am clean test` → 컴파일 오류로 FAIL. (`clean` 필수: 낡은 `target/classes` 의 마이그레이션이 남으면 중복 버전 오류.)
- [ ] **Step 3: 구현** — 위 Interfaces 대로 엔티티·요청·응답·서비스·`V27` 을 함께 고친다. `V27` 은 `market_test` 에서만 돌린다(테스트가 자동 적용).
- [ ] **Step 4: 통과 확인** — `./mvnw -pl core,app-mvc -am clean test` → PASS(개수는 Task 5 에서 CLAUDE.md 에 반영). `grep -rnE "quotedRate|settledRate|rateMode|PokemonRateMode|itemsKrw|transferKrw" app-mvc/src` 가 `V15`·`V21`·`V27` 외에는 비어 있어야 한다.
- [ ] **Step 5: 커밋** — `refactor(pokemon): 환율 스냅샷·확정·환율 방식을 걷어낸다 (V27)`. **운영 DB 적용은 하지 않는다** — Task 5.

---

### Task 4: 프론트 — 환차손익 제거와 예상 표시 (AC-04, AC-16, AC-18, AC-39)

**Files:**
- Modify: `front/lib/pokemon.ts`, `front/lib/api.ts`, `front/lib/pokemon.test.ts`
- Modify: `front/components/pokemon/order-form.tsx`, `order-rows.tsx`, `round-board.tsx`, `product-picker.tsx`
- Modify: `front/app/pokemon/new/page.tsx`, `app/pokemon/[handle]/[sequence]/{new,manage}/page.tsx`, `app/pokemon/orders/[orderId]/page.tsx`, `app/pokemon/orders/[orderId]/edit/page.tsx`, `app/mypage/page.tsx`

**Interfaces:**
- Consumes: Task 3 의 응답 모양 — `PokemonOrder.estimatedItemsKrw`, `estimatedKrw`; `PokemonRound.estimatedTransferKrwTotal`; `PokemonMyOrder.estimatedKrw`; 보드 `currentRate: { toKrw, fetchedAt, stale }`.
- Produces(`lib/pokemon.ts`): 제거 — `PokemonRateMode`, `rateFor`, `effectiveRate`, `isRoundSettled`, `fxDelta`, `formatSignedKrw`, `RATE_MODE_LABELS`, `validateRoundDraft` 의 `rateMode`/`quotedRate` 검증, `PokemonRoundDraft.rateMode`/`quotedRate`. `quote(selection, products, rate, donationKrw, extra)` 는 유지하되 반환을 `{ totalAmount, totalCoins, estimatedItemsKrw, donationKrw, estimatedKrw }` 로. `summarizeRound(orders)` 는 환율 인자 없이 `order.estimatedItemsKrw`/`estimatedKrw` 합으로 계산하고 `fxKrw` 를 없앤다. 새 함수 `rateNote(rate: { fetchedAt: string; stale: boolean }): string` — 정상 `"○월 ○일 환율 기준 예상"`, stale 이면 `"마지막으로 확인된 환율 기준 예상 (○월 ○일)"`.
- 화면: 신청 폼(`order-form`)·차수 보드(`round-board`)·신청서 상세·내 신청 목록의 원화는 모두 "예상"이며 `rateNote` 를 곁들인다. 이체 확정 표현("이체할 금액")은 "예상 금액"으로. 환율 방식 `Select`·환율 `Input`(차수 생성·관리 화면)은 삭제하고 통화 선택은 새 차수에서만 남긴다.

- [ ] **Step 1: 테스트를 먼저 고친다** — `lib/pokemon.test.ts`:
  - 삭제: `fxDelta`, `effectiveRate`, `isRoundSettled`, `rateFor`, `formatSignedKrw`, 환율 방식 검증 테스트와 `s.fxKrw` 단언.
  - 추가 `AC-04 quote: estimatedKrw 는 round(루피×환율)+기부금`: `quote({1:1}, PRODUCTS, 14.13, 5000)` 의 `estimatedKrw` 가 `estimatedItemsKrw + 5000`, 기부금은 환율을 곱하지 않음.
  - 추가 `AC-18 summarizeRound: 합계는 신청서별 예상 금액의 합`: `ORDERED`·`DEPOSIT_CONFIRMED`·`DELIVERED`·`CANCELLED` 네 건 중 취소를 뺀 `estimatedKrw` 합과 `totalTransferKrw` 가 정확히 같다(1원 오차 없음), `totalTransferKrw - totalItemsKrw` 가 기부금 합.
  - 추가 `AC-39 rateNote`: 정상은 `"11월 6일 환율 기준 예상"` 형태, stale 은 `"마지막으로 확인된 환율 기준 예상 (11월 6일)"`.
  - 픽스처(`round()`, `order()`, `myOrder()`)를 새 모양으로.
- [ ] **Step 2: 실패 확인** — `cd front && env -u NODE_OPTIONS npm test` → tsc 오류와 새 테스트 FAIL.
- [ ] **Step 3: 구현** — 위 Interfaces 대로 `lib/` 를 먼저, 그다음 컴포넌트·페이지의 참조를 모두 정리한다. `lib/api.ts` 요청 타입에서 `rateMode`/`quotedRate` 제거.
- [ ] **Step 4: 통과 확인** — `env -u NODE_OPTIONS npm test` PASS(tsc 포함) 후 `env -u NODE_OPTIONS npm run build` 성공. `grep -rnE "quotedRate|settledRate|rateMode|fxDelta|formatSignedKrw|transferKrw" front/lib front/components front/app` 이 비어 있어야 한다.
- [ ] **Step 5: 커밋** — `feat(front/pokemon): 환차손익을 없애고 예상 금액과 환율 기준일을 보여준다`.

---

### Task 5: 문서·검증·운영 적용 (게이트)

**Files:**
- Modify: `docs/pokemon/PRD.md`, `docs/api/README.md`, `CLAUDE.md`, `.docker-compose/.env.example`(해당 시), `docs/superpowers/specs/2026-11-06-pokemon-estimated-krw-design.md`(3.3 집계 문구)

- [ ] **Step 1: PRD AC 표를 스펙 3.5 대로 갱신** — AC-04·06·16·17·18·23·27·33 수정, AC-05·10·11 삭제(AC-05 는 "신청 뒤 환율이 움직이면 예상 금액도 따라 움직인다" 로 교체), 신규 AC-39(모든 원화는 "예상"이며 기준 환율 날짜를 함께 보인다, stale 포함). 테스트 매핑 표와 "서버 테스트 없음" 서술을 이 작업의 실제 테스트로 고친다. 스펙 3.3 의 "운영자 집계는 서버가 DB 에서 루피 합계를 집계" 문구는 구현과 다르므로 "프론트 `summarizeRound` 가 신청서별 예상 금액을 합산" 으로 바로잡는다.
- [ ] **Step 2: `docs/api/README.md`·`CLAUDE.md` 갱신** — pokemon 요청·응답 필드, `POKEMON_RATE_CACHE_TTL` 기본 24h, pokemon 설명의 "통화·환율·계좌·상품표" 에서 환율 방식 언급 삭제, 테스트 개수(실제 실행 결과)와 `V27` 언급.
- [ ] **Step 3: 전체 검증** — `./mvnw -pl core,app-mvc -am clean test`, `cd front && env -u NODE_OPTIONS npm test && env -u NODE_OPTIONS npm run build`, `core` 웹 스택 grep 게이트. 모두 초록이어야 한다.
- [ ] **Step 4: 커밋** — `docs: 환차손익 제거와 예상 금액 전환을 PRD·API·CLAUDE.md 에 반영`.
- [ ] **Step 5: ⛔ 운영 적용 게이트** — 사용자에게 다음을 보여주고 **승인을 받은 뒤에만** 진행한다: `V27` 전문, `SELECT count(*) FROM pokemon_orders;`·`pokemon_rounds;` 로 센 영향 행 수, 되돌릴 수 없다는 점, (원하면) 적용 전 CSV 덤프. 승인되면 app-mvc 와 front 를 **같은 시점에** 재빌드·재기동(`docker compose -f .docker-compose/docker-compose.yml up -d --build app-mvc front`)하고 `www.woobeee.com/pokemon/hs` 에서 예상 문구·환차 부재를 확인한다. 푸시는 사용자가 요청할 때만.

---

## Self-Review

- **스펙 커버리지:** 3.1→Task 1, 3.2→Task 3(V27)·Task 5 게이트, 3.3→Task 2·3, 3.4→Task 4, 3.5·테스트→Task 1~5, 5(위험·순서)→Task 5 Step 5. 빠진 요구 없음.
- **스펙과 다른 점(의도적):** 운영자 집계를 서버 네이티브 쿼리가 아니라 프론트가 합산한다(현 구조가 이미 프론트 `summarizeRound`). Task 5 Step 1 에서 스펙을 고친다.
- **타입 일관성:** `estimatedItemsKrw`/`estimatedKrw`/`estimatedTransferKrwTotal`/`rateNote` 가 Task 2 정의 → Task 3·4 사용에서 같은 이름.
