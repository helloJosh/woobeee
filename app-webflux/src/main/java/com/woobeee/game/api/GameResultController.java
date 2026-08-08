package com.woobeee.game.api;

import com.woobeee.game.api.error.GameErrorCode;
import com.woobeee.core.api.ApiResponse;
import com.woobeee.game.api.response.GameResultSummaryResponse;
import com.woobeee.game.api.response.ReplayUrlResponse;
import com.woobeee.game.result.GameResultQueryRepository;
import com.woobeee.game.result.ReplayUploader;
import com.woobeee.game.security.GamePrincipal;
import com.woobeee.game.security.GamePrincipals;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/game")
public class GameResultController {
    private static final int MAX_LIMIT = 50;

    private final GameResultQueryRepository queryRepository;
    private final ReplayUploader replayUploader;

    public GameResultController(GameResultQueryRepository queryRepository, ReplayUploader replayUploader) {
        this.queryRepository = queryRepository;
        this.replayUploader = replayUploader;
    }

    @GetMapping("/me/results")
    public Mono<ApiResponse<List<GameResultSummaryResponse>>> myResults(
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            ServerWebExchange exchange
    ) {
        GamePrincipal principal = GamePrincipals.require(exchange);
        int cappedLimit = Math.clamp(limit, 1, MAX_LIMIT);
        int safeOffset = Math.max(offset, 0);

        return queryRepository.findByMemberId(principal.memberId(), cappedLimit, safeOffset)
                .collectList()
                .map(results -> ApiResponse.success(results, "Game results"));
    }

    @GetMapping("/results/{gameResultId}/replay")
    public Mono<ApiResponse<ReplayUrlResponse>> replay(
            @PathVariable long gameResultId,
            ServerWebExchange exchange
    ) {
        GamePrincipal principal = GamePrincipals.require(exchange);

        return queryRepository.findReplayAccess(gameResultId, principal.memberId())
                .switchIfEmpty(Mono.error(
                        GameErrorCode.NOT_A_PARTICIPANT.asException()))
                .flatMap(access -> {
                    if (!StringUtils.hasText(access.objectKey())) {
                        return Mono.error(GameErrorCode.REPLAY_UNAVAILABLE.asException());
                    }
                    return Mono.just(ApiResponse.success(
                            new ReplayUrlResponse(replayUploader.presignedDownloadUrl(access.objectKey())),
                            "Replay url"
                    ));
                });
    }
}
