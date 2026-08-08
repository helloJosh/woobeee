package com.woobeee.game.identity;

import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemberReaderTest {

    @Test
    void returnsNicknameForAKnownMember() {
        DatabaseClient client = mock(DatabaseClient.class, RETURNS_DEEP_STUBS);
        when(client.sql(anyString()).bind(eq("id"), eq(11L)).map(any(java.util.function.Function.class)).one())
                .thenReturn(Mono.just("nick"));

        MemberReader reader = new MemberReader(client);

        StepVerifier.create(reader.findNickname(11L))
                .expectNext("nick")
                .verifyComplete();
    }

    @Test
    void completesEmptyForAnUnknownMember() {
        DatabaseClient client = mock(DatabaseClient.class, RETURNS_DEEP_STUBS);
        when(client.sql(anyString()).bind(eq("id"), eq(99L)).map(any(java.util.function.Function.class)).one())
                .thenReturn(Mono.empty());

        MemberReader reader = new MemberReader(client);

        StepVerifier.create(reader.findNickname(99L))
                .verifyComplete();
    }

    @Test
    void selectsOnlyTheNicknameColumnFromMembers() {
        DatabaseClient client = mock(DatabaseClient.class, RETURNS_DEEP_STUBS);
        when(client.sql(anyString()).bind(anyString(), any()).map(any(java.util.function.Function.class)).one())
                .thenReturn(Mono.just("nick"));

        new MemberReader(client).findNickname(11L).block();

        verify(client).sql("SELECT nickname FROM members WHERE id = :id AND active = true");
    }
}
