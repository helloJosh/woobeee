// 장애물피하기 크로스 언어 골든의 재현 스크립트.
//
// front/lib/dodge-engine.test.ts 의 `parity with the server DodgeGame` 이 기대하는 문자열을
// **서버 코드에서** 뽑는다. 그 테스트의 traceOf(...) 와 한 글자도 다르지 않은 형식으로 찍으므로,
// 아래 명령의 출력과 테스트의 GOLDEN 배열이 그대로 대응한다.
//
//   ./mvnw -pl core,app-webflux -am compile -DskipTests    # target/classes 를 최신으로
//   jshell --class-path app-webflux/target/classes -q scripts/dodge-parity-trace.jsh
//
// 서버의 DodgeGame 을 고쳤다면 이 스크립트를 다시 돌려 GOLDEN 을 갱신해야 한다 — 갱신하지
// 않으면 프론트 테스트는 낡은 기대값에 대고 계속 초록이다. 그것이 이 골든의 유일한 약점이라
// 재현을 한 줄로 만들어 둔다.

import com.woobeee.game.dodge.*;
import java.util.*;

void trace(int seed, int n) {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < n; i++) ids.add("p" + i);
    DodgeGame game = new DodgeGame(ids, seed);
    DodgeFrame first = game.currentFrame();
    StringBuilder starts = new StringBuilder();
    for (String id : ids) {
        Cell c = first.positions().get(id);
        starts.append(id).append("=").append(c.x()).append(",").append(c.y()).append(" ");
    }
    List<String> obstacleTrace = new ArrayList<>();
    while (!game.finished() && game.tick() < 100000) {
        DodgeFrame f = game.advanceOneTick(Map.of());
        if (f.tick() <= 3) {
            StringBuilder sb = new StringBuilder("t" + f.tick() + ":");
            for (Cell c : f.obstacles()) sb.append("(").append(c.x()).append(",").append(c.y()).append(")");
            obstacleTrace.add(sb.toString());
        }
    }
    Map<String, Integer> ranks = new TreeMap<>(game.finalRanks());
    System.out.println("seed=" + seed + " n=" + n
            + " | ticks=" + game.tick()
            + " | starts=" + starts.toString().trim()
            + " | ranks=" + ranks
            + " | obs=" + String.join("|", obstacleTrace));
}

for (int seed : new int[]{42, 12345, 987654321}) {
    for (int n : new int[]{1, 2, 5, 8}) {
        trace(seed, n);
    }
}
/exit
