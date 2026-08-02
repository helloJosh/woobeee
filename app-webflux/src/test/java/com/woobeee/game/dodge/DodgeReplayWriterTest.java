package com.woobeee.game.dodge;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DodgeReplayWriter} 가 쓰는 <b>파일</b>을 고정한다.
 *
 * <p>{@code DodgeReplayTest} 는 작성기의 출력을 곧바로 Jackson 으로 다시 읽어 필드를 확인한다 —
 * 형식의 조각들은 그것으로 충분히 잡힌다. 여기서 하는 일은 다르다: 진짜 한 판을 끝까지 두어
 * 만든 기보를 파일로 커밋해 두고, 작성기가 <b>같은 바이트</b>를 계속 만들어 내는지를 본다.
 * 그 파일은 {@code front/lib/dodge-replay-roundtrip.test.ts} 가 그대로 읽어 타입스크립트
 * 엔진으로 재생하는 바로 그 파일이다 — 그래서 이 두 테스트가 붙어 있어야 "작성기가 쓴 것을
 * 리더가 읽는다" 는 경로 전체가 검사된다. 둘 중 하나만 있으면, 그 사이의 어긋남이 양쪽 스위트를
 * 초록으로 둔 채 조용히 다른 게임을 그린다.
 *
 * <p>형식을 정말로 바꿔야 한다면 여기서 먼저 깨진다. 그때 {@link ReplayFixture} 로 두 파일을
 * 다시 만들고 프론트 테스트를 함께 돌려야 한다 — 그 절차가 곧 "리더도 같이 봤다" 는 확인이다.
 */
class DodgeReplayWriterTest {

    private static String resource(String name) {
        try (InputStream stream =
                     DodgeReplayWriterTest.class.getResourceAsStream("/replay/" + name)) {
            assertThat(stream).as("missing committed fixture /replay/" + name).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read /replay/" + name, exception);
        }
    }

    @Test
    void theCommittedFixtureIsExactlyWhatTheWriterProduces() {
        String ndjson = new DodgeReplayWriter(new ObjectMapper())
                .toNdjson(ReplayFixture.build(), ReplayFixture.DISPLAY_NAMES);

        assertThat(ndjson).isEqualTo(resource(ReplayFixture.NDJSON_NAME));
    }

    /**
     * 픽스처가 <b>재미있는</b> 파일로 남아 있는지. 다시 만들 때 실수로 이탈도 한글 이름도 없는
     * 밋밋한 기보로 바꿔 버리면, 위의 바이트 비교는 여전히 통과하면서 왕복 테스트가 검사하는
     * 범위만 조용히 줄어든다.
     */
    @Test
    void theFixtureStillExercisesDeparturesAndNonAsciiNames() {
        String ndjson = resource(ReplayFixture.NDJSON_NAME);
        String[] lines = ndjson.strip().split("\n");

        assertThat(lines[0]).contains("\"v\":2").contains("\"손님\"");
        assertThat(lines).hasSizeGreaterThan(5);
        assertThat(ndjson).contains("\"departures\":[\"g:c\"]");
        assertThat(ndjson).contains("\"moves\":");
    }

    /**
     * 커밋된 자취가 이 엔진이 실제로 만들어 내는 자취다. 프론트 테스트가 <b>같은 파일</b>에
     * 대해 같은 주장을 하므로, 어느 한쪽 엔진이 움직이면 그쪽만 깨진다.
     */
    @Test
    void rerunningTheCommittedFixtureProducesTheCommittedTrace() {
        assertThat(ReplayFixture.traceOf(ReplayFixture.build()))
                .isEqualTo(resource(ReplayFixture.TRACE_NAME));
    }

    /**
     * 자취의 마지막 줄이 {@link DodgeReplayRunner} 가 내는 결과와 같은 게임을 말하는지. 자취는
     * 러너를 부르지 않고 같은 순서를 손으로 밟으므로, 이 확인이 없으면 자취가 프로덕션 재생
     * 경로와 다른 게임을 그려도 아무도 모른다.
     */
    @Test
    void theTraceDescribesTheSameGameTheProductionRunnerReplays() {
        DodgeReplay replay = ReplayFixture.build();
        DodgeGame replayed = new DodgeReplayRunner().rerun(replay);

        String lastLine = resource(ReplayFixture.TRACE_NAME).strip().lines()
                .reduce((first, second) -> second)
                .orElseThrow();

        assertThat(lastLine).startsWith("final ticks=" + replayed.tick() + " ");
        replayed.finalRanks().forEach((participantId, rank) ->
                assertThat(lastLine).contains(participantId + "=" + rank));
    }
}
