package com.woobeee.game.identity;

import io.r2dbc.spi.Readable;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * members 는 app-mvc 가 소유하는 테이블이다. game 은 읽기만 한다.
 */
@Component
public class MemberReader {
    private static final String SELECT_NICKNAME =
            "SELECT nickname FROM members WHERE id = :id AND active = true";

    private final DatabaseClient databaseClient;

    public MemberReader(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public Mono<String> findNickname(long memberId) {
        return databaseClient.sql(SELECT_NICKNAME)
                .bind("id", memberId)
                .map((Readable row) -> row.get("nickname", String.class))
                .one();
    }
}
