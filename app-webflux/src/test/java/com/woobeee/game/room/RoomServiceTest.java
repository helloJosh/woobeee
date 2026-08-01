package com.woobeee.game.room;

import com.woobeee.game.identity.GameParticipant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoomServiceTest {

    private static final GameParticipant HOST = GameParticipant.member(11L, "host");
    private static final GameParticipant GUEST = GameParticipant.guest("a", "guest");

    private RoomRegistry registry;
    private RoomService service;

    @BeforeEach
    void setUp() {
        AtomicInteger counter = new AtomicInteger();
        GameIdGenerator ids = new GameIdGenerator() {
            @Override
            public String nextRoomId() {
                return "room-" + counter.incrementAndGet();
            }

            @Override
            public String nextInviteCode() {
                return "code";
            }

            @Override
            public String nextGuestId() {
                return "guest";
            }

            @Override
            public int nextSeed() {
                return 42;
            }
        };
        registry = new RoomRegistry(ids, Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC));
        service = new RoomService(registry);
    }

    private HttpStatus statusOf(Throwable throwable) {
        return (HttpStatus) ((ResponseStatusException) throwable).getStatusCode();
    }

    /** GAME-AC-02 */
    @Test
    void wrongInviteCodeIsForbidden() {
        Room room = service.create(GameType.OMOK, HOST);

        assertThatThrownBy(() -> service.requireRoom(room.roomId(), "wrong"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void unknownRoomIsNotFound() {
        assertThatThrownBy(() -> service.requireRoom("nope", "code"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.NOT_FOUND));
    }

    /** GAME-AC-04 */
    @Test
    void joiningAFullRoomIsRejected() {
        Room room = service.create(GameType.OMOK, HOST);
        service.join(room.roomId(), "code", GUEST);

        assertThatThrownBy(() -> service.join(room.roomId(), "code", GameParticipant.guest("b", "third")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.CONFLICT));
    }

    /** GAME-AC-05 */
    @Test
    void newParticipantCannotJoinAGameInProgress() {
        Room room = service.create(GameType.DODGE, HOST);
        service.join(room.roomId(), "code", GUEST);
        service.setReady(room.roomId(), HOST.participantId(), true);
        service.setReady(room.roomId(), GUEST.participantId(), true);
        service.start(room.roomId(), HOST.participantId());

        assertThatThrownBy(() -> service.join(room.roomId(), "code", GameParticipant.guest("b", "late")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.CONFLICT));
    }

    /** GAME-AC-05 */
    @Test
    void existingParticipantMayReconnectDuringAGameInProgress() {
        Room room = service.create(GameType.DODGE, HOST);
        service.join(room.roomId(), "code", GUEST);
        service.setReady(room.roomId(), HOST.participantId(), true);
        service.setReady(room.roomId(), GUEST.participantId(), true);
        service.start(room.roomId(), HOST.participantId());
        service.markDisconnected(room.roomId(), GUEST.participantId());

        Room rejoined = service.join(room.roomId(), "code", GUEST);

        assertThat(rejoined.member(GUEST.participantId()).orElseThrow().connection())
                .isEqualTo(ConnectionState.CONNECTED);
        assertThat(rejoined.members()).hasSize(2);
    }

    /** GAME-AC-08 */
    @Test
    void disconnectKeepsTheSeatUntilTheGraceExpires() {
        Room room = service.create(GameType.OMOK, HOST);
        service.join(room.roomId(), "code", GUEST);

        service.markDisconnected(room.roomId(), GUEST.participantId());

        assertThat(room.members()).hasSize(2);
        assertThat(room.member(GUEST.participantId()).orElseThrow().connection())
                .isEqualTo(ConnectionState.DISCONNECTED);

        service.confirmLeave(room.roomId(), GUEST.participantId());

        assertThat(room.member(GUEST.participantId())).isEmpty();
    }

    /** GAME-AC-08 */
    @Test
    void confirmLeaveIsIgnoredWhenTheParticipantReconnectedFirst() {
        Room room = service.create(GameType.OMOK, HOST);
        service.join(room.roomId(), "code", GUEST);
        service.markDisconnected(room.roomId(), GUEST.participantId());
        service.join(room.roomId(), "code", GUEST);

        service.confirmLeave(room.roomId(), GUEST.participantId());

        assertThat(room.member(GUEST.participantId())).isPresent();
    }

    /** GAME-AC-06 */
    @Test
    void hostLeavingPromotesTheNextMember() {
        Room room = service.create(GameType.DODGE, HOST);
        service.join(room.roomId(), "code", GUEST);

        service.leaveNow(room.roomId(), HOST.participantId());

        assertThat(room.hostParticipantId()).isEqualTo(GUEST.participantId());
    }

    /** GAME-AC-06 */
    @Test
    void lastMemberLeavingDestroysTheRoom() {
        Room room = service.create(GameType.OMOK, HOST);

        service.leaveNow(room.roomId(), HOST.participantId());

        assertThat(registry.find(room.roomId())).isEmpty();
    }

    @Test
    void startRequiresTheHost() {
        Room room = service.create(GameType.OMOK, HOST);
        service.join(room.roomId(), "code", GUEST);
        service.setReady(room.roomId(), HOST.participantId(), true);
        service.setReady(room.roomId(), GUEST.participantId(), true);

        assertThatThrownBy(() -> service.start(room.roomId(), GUEST.participantId()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void omokNeedsTwoReadyMembersToStart() {
        Room room = service.create(GameType.OMOK, HOST);
        service.setReady(room.roomId(), HOST.participantId(), true);

        assertThatThrownBy(() -> service.start(room.roomId(), HOST.participantId()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void startFlipsStatusToInProgress() {
        Room room = service.create(GameType.OMOK, HOST);
        service.join(room.roomId(), "code", GUEST);
        service.setReady(room.roomId(), HOST.participantId(), true);
        service.setReady(room.roomId(), GUEST.participantId(), true);

        service.start(room.roomId(), HOST.participantId());

        assertThat(room.status()).isEqualTo(RoomStatus.IN_PROGRESS);
    }

    /** GAME-AC-09 */
    @Test
    void explicitLeaveRemovesTheMemberWithoutGrace() {
        Room room = service.create(GameType.DODGE, HOST);
        service.join(room.roomId(), "code", GUEST);

        service.leaveNow(room.roomId(), GUEST.participantId());

        assertThat(room.member(GUEST.participantId())).isEmpty();
    }
}
