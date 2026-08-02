import { fileURLToPath } from "node:url"
import { defineConfig } from "vitest/config"

/**
 * 프론트의 첫 테스트 러너. 여기 있는 설정은 두 가지뿐이다.
 *
 * 1. `@/*` 별칭 — tsconfig 의 paths 와 같은 규칙이다. 없으면 `lib/game-join.ts` 처럼
 *    `@/lib/api` 를 import 하는 모듈을 테스트에서 열 수 없다.
 * 2. 훑지 않을 곳만 지정한다. include 를 `lib/**` 로 좁혀 두면 나중에 누군가
 *    `components/` 나 `hooks/` 에 스펙을 두었을 때 러너가 **말없이 건너뛴다** — 초록인데
 *    아무것도 돌지 않는 상태가 가장 나쁘다. 기본 include 를 그대로 쓰고 생성물만 뺀다.
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
        exclude: ["**/node_modules/**", "**/.next/**"],
    },
})
