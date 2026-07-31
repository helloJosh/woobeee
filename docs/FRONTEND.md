# FRONTEND

프론트엔드는 `front/` 하위 Next.js 14 애플리케이션이다.

## 기술 스택

- Next.js 14 App Router
- React 18
- TypeScript
- Tailwind CSS
- Radix UI 기반 컴포넌트
- lucide-react 아이콘

## 디렉터리 구조

```text
front/
|-- app/                 라우트와 전역 레이아웃
|-- components/          화면 컴포넌트
|-- components/ui/       공통 UI primitive
|-- lib/                 API, 타입, 에러 처리, 유틸
|-- public/              정적 리소스
`-- package.json         프론트 빌드/실행 스크립트
```

## 주요 라우트

- `/`: 메인 페이지
- `/blog`: 기술블로그 블로그 목록
- `/blog/[postId]`: 블로그 상세
- `/login`: 로그인 authorization 시작
- `/logout`: 로그아웃
- `/products/[productId]`: 상품 상세 (좌 이미지 / 우 정보, 상세 표·설명·이미지, 같은 작가 작품)
- `/products/new`: 판매자 상품 등록
- `/cart`: 구매자 장바구니
- `/signup`: 회원가입 유형 선택
- `/signup/buyer`: 구매자 회원가입 authorization 시작
- `/signup/seller`: 판매자 회원가입 authorization 시작
- `/auth/google/callback`: Google authorization callback 처리

## API 호출 규칙

- API 호출 공통 로직은 `front/lib/api.ts`에 둔다.
- 응답 타입은 `front/lib/types.ts`에 정의한다.
- access token과 refresh token은 현재 `localStorage`에 저장한다.
- 토큰 응답의 `memberId`, `role`도 `localStorage`에 저장해 판매자 전용 UI와 상품 등록 요청에 사용한다.
- 홈 화면은 `next-themes` 기반 다크모드/라이트모드 전환을 제공한다.
- 회원가입 화면의 뒤로가기는 고정 라우트가 아니라 브라우저 이전 화면으로 이동한다.
- 요청에는 언어 정보(`ko-KR`, `en-US`)를 포함한다.
- 인증 만료 시 `POST /api/auth/refresh-tokens`로 refresh를 시도하고 실패하면 토큰을 제거한다.
- Google 인증은 Authorization Code + PKCE 흐름을 따른다. 프론트는 로그인/회원가입 시작 API에서 `authorizationUrl`을 받아 이동하고, callback 페이지에서 `code`와 `state`를 `POST /api/auth/callback-google`로 교환한다.
- 로그인/회원가입 성공 후 기본 이동 위치는 마켓플레이스 홈(`/`)이다.
- 상품 등록은 `POST /api/products/images`로 이미지 Presigned URL을 발급받아 스토리지에 `PUT` 업로드한 뒤, 반환된 temp image key로 `POST /api/products`를 호출한다.
- 상품 등록 페이지의 상품명, 상품설명과 텍스트 입력 draft는 `localStorage`에 1시간 TTL로 저장하고, 등록 성공 시 삭제한다. 파일 입력은 브라우저 보안 제약 때문에 복원하지 않는다.
- 홈 상품 검색은 통합 검색어(`q`), 작가(`artist`), 태그(`tag`)를 함께 지원한다.
- 상품 목록 이미지는 object key를 직접 조합하지 않고 API 응답의 presigned image URL을 사용한다.

## 컴포넌트 규칙

- 페이지는 `app/`에 두고, 재사용 가능한 화면 단위는 `components/`에 둔다.
- 버튼, 입력, 다이얼로그, 드롭다운 등은 `components/ui/`의 기존 컴포넌트를 우선 사용한다.
- 새 아이콘은 가능하면 `lucide-react`를 사용한다.
- UI 텍스트는 사용자가 수행할 행동을 기준으로 짧게 작성한다.

## 스타일 규칙

- Tailwind utility를 우선 사용한다.
- 공통 스타일이 반복되면 작은 컴포넌트로 분리한다.
- 과도한 카드 중첩과 장식성 레이아웃을 피한다.
- 블로그/상품 화면은 콘텐츠 탐색과 가독성을 우선한다.

## 빌드와 검증

```bash
cd front
npm run build
```

현재 `npm run lint`는 ESLint 초기 설정 프롬프트가 뜰 수 있으므로 자동 검증에는 포함하지 않는다. ESLint 설정을 도입한 뒤 자동 검증에 추가한다.

## 알려진 주의사항

- `next/font`가 Google Fonts를 조회하므로 네트워크가 막힌 환경에서는 `npm run build`가 실패할 수 있다.
- `API_BASE_URL`은 `NEXT_PUBLIC_API_BASE_URL`이 없으면 `http://localhost:8000`을 사용한다.
