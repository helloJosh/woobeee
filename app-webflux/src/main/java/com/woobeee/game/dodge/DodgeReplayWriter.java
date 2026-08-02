package com.woobeee.game.dodge;

import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 기보를 ndjson으로 직렬화한다. 첫 줄은 헤더(격자·틱 간격·난수 규칙), 그 뒤로는 입력이 있던
 * 틱만 한 줄씩 — 헤더의 필드 이름은 브라우저 재생기와의 계약이므로 임의로 바꾸면 안 된다.
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
        header.put("v", 1);
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

        List<Integer> ticks = new ArrayList<>(replay.inputsByTick().keySet());
        ticks.sort(Integer::compareTo);

        for (Integer tick : ticks) {
            Map<String, Direction> moves = replay.inputsByTick().get(tick);
            if (moves == null || moves.isEmpty()) {
                continue;
            }

            Map<String, String> named = new LinkedHashMap<>();
            moves.forEach((participantId, direction) -> named.put(participantId, direction.name()));

            Map<String, Object> line = new LinkedHashMap<>();
            line.put("tick", tick);
            line.put("moves", named);
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
