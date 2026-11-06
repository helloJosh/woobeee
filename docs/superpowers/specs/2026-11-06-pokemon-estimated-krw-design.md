# pokemon: 환차손익 제거와 예상 금액 전환 설계

- 날짜: 2026-11-06
- 도메인: `pokemon` (app-mvc + front)
- 상태: 초안 (사용자 검토 대기)

## 1. 목적

공동구매의 **환차손익을 모두 없앤다.** 신청서의 원화 금액은 확정값이 아니라 **예상 금액**이다.
환율은 **하루 한 번** 가져온다. 사용자 결정:

- 원화는 DB에 저장하지 않고, 루피 합계 × 오늘 환율로 매번 계산한다(항상 예상값).
- 갱신 시각은 매일 09:10 KST.
- "오늘 환율 기준 예상" 문구를 신청 폼과 신청서 목록 양쪽에 표시한다.

실제 청구액은 카드사 환율과 해외결제 수수료 때문에 어차피 달라지므로(기존 PRD 도 같은 입장),
"확정 이체액"이라는 개념을 버리는 것이 목표다.

## 2. 범위

### 하는 것
- 차수·신청서에서 환율 스냅샷과 확정 환율, 확정 원화 컬럼 제거 (Flyway `V27`)
- 환율 방식(`FIXED` / `PER_ORDER`) 선택 제거
- 환율 캐시 TTL 24시간 + 매일 09:10 KST 스케줄 갱신, 기존 stale 폴백 유지
- 응답에 `estimatedKrw`, `rate`, `rateFetchedAt` 추가
- 운영자 집계(AC-18)의 원화 합계를 오늘 환율 기준 "예상"으로 전환
- 프론트의 환차손익과 환율 입력 UI 제거, 예상 문구 추가
- PRD AC 표, 테스트, `docs/api/README.md`, CLAUDE.md 갱신

### 하지 않는 것
- 통화 추가·변경 (통화는 차수 생성 때 고르고 그대로)
- 환율 제공자 교체 (`open.er-api.com` 유지)
- 기부금 모델 변경 (`donation_krw` 는 사용자 입력이라 유지)

## 3. 설계

### 3.1 환율 갱신 (`ExchangeRateService`)
- Redis `pokemon:rate:<CUR>` TTL 1시간 → **24시간**. `:last`(TTL 없음)는 그대로.
- `@Scheduled(cron = "0 10 9 * * *", zone = "Asia/Seoul")` 가 지원 통화(`POKEMON_CURRENCIES`)를
  모두 갱신한다. 스케줄러는 `@EnableScheduling` 이 이미 있는지 확인하고 없으면 켠다.
- 조회 순서는 기존과 같다: 신선값 → 없으면 외부 조회 → 실패하면 `:last` 를 `stale: true` 로
  → 그것도 없으면 503 `pokemon_rateUnavailable`. 환율을 모른 채 추정값을 만들지 않는다(AC-06).
- 스케줄 갱신 실패는 로그만 남기고 삼킨다. 다음 조회가 지연 조회로 보충한다.
- `POKEMON_RATE_CACHE_TTL` 기본값을 `PT24H` 로 바꾼다.

### 3.2 데이터 (Flyway `V27__pokemon_drop_fx.sql`)
| 테이블 | 제거 컬럼 |
| --- | --- |
| `pokemon_rounds` | `rate_mode`, `quoted_rate`, `quoted_at`, `settled_rate`, `settled_at` |
| `pokemon_orders` | `quoted_rate`, `quoted_at`, `items_krw`, `transfer_krw` |

- 유지: `pokemon_orders.donation_krw`, `total_inr`, `extra_inr`, 항목 단가 스냅샷(루피).
- 엔티티(`PokemonRounds`, `PokemonOrders`)와 `PokemonRateMode` 를 함께 정리한다.
  JPA 는 `validate` 전용이므로 엔티티와 마이그레이션이 같이 가야 `SchemaValidationTest` 가 통과한다.
- **운영 DB 적용은 CLAUDE.md 안전 수칙 대상이다.** 적용 전에 SQL 문장과 영향받는 행 수를
  보여주고 명시적 승인을 받는다. 앱을 띄우는 것(compose `app-mvc`)이 곧 적용이므로 띄우기
  전에 알린다. 기존 신청서의 확정 원화는 사라지고 예상값으로 대체된다.
- `V25`, `V26` 및 기존 마이그레이션 파일은 수정하지 않는다.

### 3.3 서버 계산과 응답
- 단일 계산식: `estimatedKrw = round(totalInr × rate) + donationKrw` (`BigDecimal.HALF_UP`).
  `PokemonServiceImpl.toKrw` 를 그대로 쓴다.
- `PokemonOrderResponse`: `quotedRate`, `quotedAt`, `itemsKrw`, `transferKrw` 제거.
  `estimatedItemsKrw`(기부금 제외), `estimatedKrw`(기부금 포함) 추가.
- `PokemonRoundResponse` / `PokemonRoundBoardResponse`: `rateMode`, `quotedRate`, `quotedAt`,
  `settledRate`, `settledAt` 제거. 보드 응답에 `rate`(오늘 환율)와 `rateFetchedAt`, `stale` 를 싣는다
  (기존 `ExchangeRateResponse` 재사용).
- `PostPokemonRoundRequest` / `PutPokemonRoundRequest`: `rateMode`, `quotedRate` 제거.
  통화는 생성 시에만 받는다. `PokemonRounds.changeStatus` 는 `currentRate` 인자와
  `settledRate` 확정 분기를 없앤다.
- 신청 제출·수정(AC-23)은 환율을 박지 않는다. 입력은 항목·기부금·자유 루피·메모뿐이다.
- 운영자 집계(AC-18)는 DB 합계가 아니라 **루피 합계를 DB 에서 집계 → 서버가 오늘 환율을 한 번
  곱한다.** 원화 컬럼에 의존하던 네이티브 쿼리는 루피 기준으로 다시 쓴다(N+1 없음, 바인딩 파라미터).
  개별 신청서 환산 합과 집계 환산이 1원 어긋날 수 있으므로, 집계 표시는 "예상"이라 명시하고
  오차를 허용한다.

### 3.4 프론트
- `lib/pokemon.ts` 에서 환차손익 함수와 환율 확정 관련 로직 제거, 예상 금액 함수는
  서버 값(`estimatedKrw`)을 우선 쓰고 폼 미리보기만 `Math.round(inr × rate)` 로 같은 결과를 낸다.
- `order-form.tsx`: 환율 방식·환율 입력 UI 제거. 금액 옆에 "○월 ○일 환율 기준 예상" 표시.
  `stale` 이면 "마지막으로 확인된 환율" 문구로 바꾼다.
- `round-board.tsx`, `order-rows.tsx`, `orders/[orderId]/page.tsx`: 신청서 목록·상세에 같은 문구와
  예상 금액. 이체 확정 표현("이체할 금액")은 "예상 금액"으로 바꾼다.
- `manage/page.tsx`, `new/page.tsx`: 환율 방식·환율 입력 제거(통화 선택은 새 차수에만).
- `lib/api.ts` 타입에서 제거된 필드를 정리하고 `npm test`(tsc 포함)로 누락을 잡는다.

### 3.5 문서 (PRD AC 변경)
| AC | 변경 |
| --- | --- |
| AC-04 | "이체액" → **예상 금액** = `round(루피합계 × 오늘 환율) + 기부금`. 서버는 HALF_UP, 프론트 미리보기는 같은 결과 |
| AC-05 | 삭제 (신청 환율 고정 개념이 없어짐). 대신 "신청 뒤 환율이 움직이면 예상 금액도 따라 움직인다"로 교체 |
| AC-06 | 신선값 TTL 24시간, **매일 09:10 KST 갱신**, 폴백 규칙 유지 |
| AC-10 | 삭제 (환율 확정 없음) |
| AC-11 | 삭제 (환차손익 없음) |
| AC-16·17 | "환차손익" 언급 정리 |
| AC-18 | 원화 합계는 오늘 환율 기준 "예상" |
| AC-23 | "수정 시점 환율로 재계산·갱신" 문장 삭제. `settled_rate` 언급 삭제 |
| AC-27 | `FIXED`/`PER_ORDER` 삭제. 통화는 바꿀 수 없다만 남김 |
| AC-33 | 환율 수정 언급 삭제 |
| 신규 AC-39 | 예상 금액 표시: 모든 원화는 "예상"이며 기준 환율 날짜를 함께 보인다 (stale 포함) |

테스트 매핑 표와 "서버 테스트 없음" 서술도 함께 갱신한다. CLAUDE.md 의 pokemon 설명(통화·환율·계좌·상품표),
`POKEMON_RATE_*` 환경변수 설명, `docs/api/README.md` 의 요청·응답 필드도 고친다.

## 4. 테스트

- 서버: `ExchangeRateService` 의 TTL 24시간·폴백·스케줄 갱신 실패 삼킴, 예상 금액 계산(HALF_UP,
  기부금 포함·제외), 운영자 집계 환산, `changeStatus` 가 환율 인자 없이 동작함. AC ID 를 메서드명/주석에 단다.
- 마이그레이션: `SchemaValidationTest`(market_test, V27 포함) 통과.
- 프론트: `lib/pokemon.test.ts` 에서 환차 테스트를 지우고 예상 금액·문구 기준 테스트 추가.
  `npm test` 가 tsc 부터 돌므로 제거된 필드 참조가 남으면 실패한다.
- 기대 개수: 기존 서버 225 / 프론트 312 에서 제거·추가 후 CLAUDE.md 의 개수를 실제 값으로 갱신.

## 5. 위험과 순서

1. **되돌릴 수 없는 DROP COLUMN.** 운영 반영 직전에 승인받는다. 필요하면 적용 전
   `pokemon_orders`/`pokemon_rounds` 의 현재 값을 CSV 로 덤프해 둘 수 있다(제안만, 사용자 결정).
2. **배포 순서.** 새 앱은 컬럼이 없다고 가정하고 옛 앱은 있다고 가정한다. app-mvc 와 front 를 같은
   시점에 재빌드·재기동한다(compose 한 번에).
3. **구현 순서:** (1) AC 표 갱신 → (2) 서버 엔티티·마이그레이션·서비스·응답 → (3) 서버 테스트 →
   (4) 프론트 → (5) 문서 → (6) market_test 로 전체 테스트 → (7) 운영 적용 승인 요청.
4. 테스트·빌드가 실패하면 원인을 요약하고 계속 / 부분 롤백 / 전체 롤백 중 사용자에게 묻는다.

## 6. 열린 질문

없음. 갱신 시각 09:10 KST, 문구 위치(폼·목록), 항상 예상값 방식은 사용자가 확정했다.
