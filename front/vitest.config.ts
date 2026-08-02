import { fileURLToPath } from "node:url"
import { defineConfig } from "vitest/config"

/**
 * 프론트의 첫 테스트 러너. 여기 있는 설정은 두 가지뿐이다.
 *
 * 1. `@/*` 별칭 — tsconfig 의 paths 와 같은 규칙이다. 없으면 `lib/game-join.ts` 처럼
 *    `@/lib/api` 를 import 하는 모듈을 테스트에서 열 수 없다.
 * 2. include 를 `lib/**` 로 좁힌다 — 이 레포에서 테스트를 두는 곳은 React 없는 lib 모듈뿐이고,
 *    `.next/` 안의 생성물까지 훑을 이유가 없다.
 *
 * 환경은 기본값 node 다. jsdom 을 붙이지 않은 것은 의도적이다 — 테스트 대상이 전부
 * React-free 모듈이고, `window.sessionStorage` 가 필요한 두 곳은 테스트가 직접 최소한의
 * 스텁을 세운다. DOM 전체를 끌어오는 것보다 무엇을 가정하는지가 눈에 보인다.
 */
export default defineConfig({
    resolve: {
        alias: {
            "@": fileURLToPath(new URL(".", import.meta.url)),
        },
    },
    test: {
        include: ["lib/**/*.test.ts"],
    },
})
