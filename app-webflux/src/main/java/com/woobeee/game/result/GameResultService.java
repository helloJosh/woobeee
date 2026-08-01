package com.woobeee.game.result;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;

/**
 * 종료된 게임을 남긴다.
 *
 * <p>순서가 계약이다: 결과 행 → 참가자 행 → 기보 업로드 → key 부착. 업로드가 실패하면
 * {@code replay_object_key} 가 null 로 남을 뿐 전적은 그대로다.
 */
@Service
public class GameResultService {
    private final GameResultRepository repository;
    private final ReplayUploader replayUploader;
    private final Clock clock;

    public GameResultService(GameResultRepository repository, ReplayUploader replayUploader, Clock clock) {
        this.repository = repository;
        this.replayUploader = replayUploader;
        this.clock = clock;
    }

    public Mono<Long> record(FinishedGame game, String replayNdjson) {
        return repository.insertResult(game, clock.instant())
                .flatMap(gameResultId -> repository.insertParticipants(gameResultId, game.participants())
                        .then(replayUploader.upload(game.gameType(), gameResultId, replayNdjson))
                        .flatMap(key -> repository.attachReplayKey(gameResultId, key))
                        .thenReturn(gameResultId));
    }
}
