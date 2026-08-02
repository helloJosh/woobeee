package com.woobeee.game.dodge;

import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 기보를 ndjson으로 직렬화한다. 첫 줄은 헤더(격자·틱 간격·난수 규칙), 그 뒤로는 입력이나 이탈이
 * 있던 틱만 한 줄씩 — 헤더의 필드 이름은 브라우저 재생기와의 계약이므로 임의로 바꾸면 안 된다.
 *
 * <p>이탈은 같은 틱 줄 위에 별도 필드 {@code departures} 로 싣는다(자체 줄 타입을 새로 만들지
 * 않는다) — 한 틱에 이동과 이탈이 동시에 있을 수 있고(예: 누군가 이동하는 바로 그 틱에 다른
 * 참가자가 방을 나간다), 줄 타입을 나누면 클라이언트가 같은 틱 번호를 가진 두 줄을 다시 합쳐야
 * 하는 번거로움만 늘어난다. {@code moves} 나 {@code departures} 는 비어 있으면 아예 필드 자체를
 * 쓰지 않는다 — 헤더 이후 각 줄은 그 틱에 실제로 있었던 것만 담는다.
 *
 * <p><b>헤더의 {@code v} 는 2다.</b> {@code departures} 필드가 추가되면서 파일의 의미가
 * 바뀌었다 — v1 그대로 읽는 리더는 {@code inputsByTick} 만 보고 이탈을 놓쳐, 원본과 다른 승자·
 * 다른 길이를 "정상"으로 재생해 버린다(F1과 같은 실패가 클라이언트에서 조용히 재현된다). 그래서
 * 이 필드가 생긴 시점에 버전을 반드시 올린다 — 읽는 쪽이 몰라도 되는 변경이 아니다.
 */
@Component
public class DodgeReplayWriter {
    private final ObjectMapper objectMapper;

    public DodgeReplayWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toNdjson(DodgeReplay replay, Map<String, String> displayNames) {
        StringBuilder builder = new StringBuilder();

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("v", 2);
        header.put("gameType", "DODGE");
        header.put("cols", DodgeRules.COLUMNS);
        header.put("rows", DodgeRules.ROWS);
        header.put("tickMs", DodgeRules.TICK_MILLIS);
        header.put("seed", replay.seed());
        header.put("prng", "xorshift32");
        header.put("baseSpawn", DodgeRules.BASE_SPAWN);
        header.put("spawnStep", DodgeRules.SPAWN_STEP);
        header.put("spawnStepTicks", DodgeRules.SPAWN_STEP_TICKS);
        header.put("maxSpawn", DodgeRules.MAX_SPAWN);
        header.put("players", playersOf(replay, displayNames));
        builder.append(write(header)).append('\n');

        List<Integer> ticks = new ArrayList<>();
        ticks.addAll(replay.inputsByTick().keySet());
        ticks.addAll(replay.departuresByTick().keySet());
        List<Integer> orderedTicks = ticks.stream().distinct().sorted().toList();

        for (Integer tick : orderedTicks) {
            Map<String, Direction> moves = replay.inputsByTick().get(tick);
            List<String> departures = replay.departuresByTick().get(tick);
            boolean hasMoves = moves != null && !moves.isEmpty();
            boolean hasDepartures = departures != null && !departures.isEmpty();
            if (!hasMoves && !hasDepartures) {
                continue;
            }

            Map<String, Object> line = new LinkedHashMap<>();
            line.put("tick", tick);
            if (hasMoves) {
                Map<String, String> named = new LinkedHashMap<>();
                moves.forEach((participantId, direction) -> named.put(participantId, direction.name()));
                line.put("moves", named);
            }
            if (hasDepartures) {
                line.put("departures", List.copyOf(departures));
            }
            builder.append(write(line)).append('\n');
        }

        return builder.toString();
    }

    private List<Map<String, Object>> playersOf(DodgeReplay replay, Map<String, String> displayNames) {
        List<Map<String, Object>> players = new ArrayList<>();
        for (String participantId : replay.participantIds()) {
            Map<String, Object> player = new LinkedHashMap<>();
            player.put("participantId", participantId);
            player.put("displayName", displayNames.getOrDefault(participantId, participantId));
            players.add(player);
        }
        return players;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialise dodge replay", exception);
        }
    }
}
