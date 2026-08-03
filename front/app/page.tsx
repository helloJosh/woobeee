import GameHub from "@/components/game/game-hub"

// 게임 허브가 곧 메인 화면이다. 랜딩과 허브를 따로 두던 구조를 접었다 —
// 허브 UI 자체는 components/game/game-hub.tsx 에 있고, /game 은 여기로 리다이렉트한다.
export default function HomePage() {
    return <GameHub />
}
