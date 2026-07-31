# ADR-002. API 응답은 도메인별 ApiResponse 래퍼 + RestControllerAdvice로 일관 처리한다

- 상태: 적용
- 범위: _common (패턴), 구현은 각 도메인

## 맥락

응답 형식과 예외 처리 방식이 도메인마다 제각각이면 프론트엔드 처리가 번거롭다. 성공/실패를 공통 형태로 감싸고, 예외→HTTP 응답 변환을 한 곳에 모으는 규칙이 필요하다.

## 결정

공통 **형태(shape)** 를 정하고, 각 도메인이 그 형태를 따르는 `ApiResponse`와 `RestControllerAdvice`를 둔다.

- 응답 래퍼 `ApiResponse<T>`(record):
  - `header { isSuccessful, message }` + `data`.
  - `@JsonInclude(NON_NULL)`로 null 필드는 직렬화에서 제외.
  - 팩토리: `success(...)`, `createSuccess(...)`, `deleteSuccess(...)`, `fail(status, message)`. 실패 응답의 `data`는 발생 시각(`LocalDateTime.now()`)을 담는다.
- 예외 처리 `@RestControllerAdvice(basePackages = "...<domain>")`:
  - 도메인 패키지 범위로 한정해 도메인별로 advice를 둔다(예: `CartRestControllerAdvice`, `ProductRestControllerAdvice`, auth/blog의 advice).
  - `MethodArgumentNotValidException` → `400` + 첫 필드 에러 메시지.
  - `ResponseStatusException` → 해당 status + reason 메시지.
- 서비스 계층은 검증 실패를 `ResponseStatusException(status, reason)`으로 던지고, advice가 이를 `ApiResponse.fail`로 변환한다.

## 근거

- 프론트엔드는 `header.isSuccessful`/`message`라는 단일 규약으로 성공/실패를 처리할 수 있다.
- advice를 도메인 패키지로 한정해, 한 도메인의 예외 처리 변경이 다른 도메인에 영향을 주지 않는다.

## 트레이드오프 / 한계

- `ApiResponse`가 도메인마다 **별도 클래스로 중복** 존재한다(같은 형태의 복제). 형태를 바꾸면 도메인 수만큼 손봐야 한다.
- 도메인 경계 격리(복제)와 DRY(단일 공통 클래스) 사이의 선택이며, 현재는 경계 격리를 택했다. 형태 변경 빈도가 높아지면 `_common`으로 단일화하는 것을 재검토한다.
- 실패 응답 `data`에 타임스탬프를 담는 관례는 도메인 간 동일하게 유지한다.
