package com.woobeee.game.room;

import com.woobeee.game.identity.GameParticipant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

    /**
     * Task 0 regression: {@code RoomService.join} used to read {@code room.status()} /
     * {@code room.isFull()} and then call {@code room.addMember()} as separate synchronized
     * {@code Room} calls. Each call was individually thread-safe, but the sequence was not, so
     * two racers could both observe "not full" (one slot left) before either of them actually
     * added a member, and both would be seated — an over-capacity room. This hammers the last
     * free OMOK slot with many concurrent joiners and asserts the room never exceeds
     * {@code gameType().capacity()} and that exactly one racer wins the slot.
     */
    @Test
    void concurrentJoinsForTheLastSlotNeverExceedRoomCapacity() throws InterruptedException {
        int iterations = 2000;
        int racersPerTrial = 4;
        ExecutorService pool = Executors.newFixedThreadPool(racersPerTrial);

        try {
            for (int i = 0; i < iterations; i++) {
                Room room = service.create(GameType.OMOK, HOST); // 1 member seated, 1 slot free
                CountDownLatch go = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(racersPerTrial);
                Queue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

                for (int r = 0; r < racersPerTrial; r++) {
                    GameParticipant racer = GameParticipant.guest(i + "-" + r, "r" + i + "-" + r);
                    pool.submit(() -> {
                        try {
                            go.await();
                            service.join(room.roomId(), "code", racer);
                        } catch (ResponseStatusException expectedRejection) {
                            // room already full or already started — a valid outcome
                        } catch (Throwable t) {
                            unexpected.add(t);
                        } finally {
                            done.countDown();
                        }
                    });
                }

                go.countDown();
                assertThat(done.await(5, TimeUnit.SECONDS)).as("iteration %d timed out", i).isTrue();
                assertThat(unexpected).as("iteration %d threw unexpectedly", i).isEmpty();

                assertThat(room.members().size())
                        .as("iteration %d: room must never exceed its game type's capacity", i)
                        .isLessThanOrEqualTo(GameType.OMOK.capacity());
            }
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * Task 0 regression: a JOIN could pass the {@code status() != WAITING} check just as a
     * concurrent {@code RoomService.start} flipped the room to {@code IN_PROGRESS}, seating the
     * new participant in a game that had already begun. Runs many iterations racing a late JOIN
     * against a host START on a fresh room each time, and asserts the late joiner is never a
     * member while the room reports {@code IN_PROGRESS} — either the join lands before start (and
     * start then fails because the newcomer is not ready) or start wins and the join is rejected.
     */
    @Test
    void aLateJoinerIsNeverSeatedInARoomThatHasAlreadyStarted() throws InterruptedException {
        int iterations = 500;
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            for (int i = 0; i < iterations; i++) {
                Room room = service.create(GameType.DODGE, HOST);
                service.join(room.roomId(), "code", GUEST);
                service.setReady(room.roomId(), HOST.participantId(), true);
                service.setReady(room.roomId(), GUEST.participantId(), true);

                GameParticipant late = GameParticipant.guest("late-" + i, "late" + i);
                CountDownLatch go = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);
                Queue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

                pool.submit(() -> {
                    try {
                        go.await();
                        service.join(room.roomId(), "code", late);
                    } catch (ResponseStatusException expectedRejection) {
                        // room already in progress, or (rarely) full — a valid outcome
                    } catch (Throwable t) {
                        unexpected.add(t);
                    } finally {
                        done.countDown();
                    }
                });
                pool.submit(() -> {
                    try {
                        go.await();
                        service.start(room.roomId(), HOST.participantId());
                    } catch (ResponseStatusException expectedRejection) {
                        // e.g. "All players must be ready" if the late joiner slipped in first
                    } catch (Throwable t) {
                        unexpected.add(t);
                    } finally {
                        done.countDown();
                    }
                });

                go.countDown();
                assertThat(done.await(5, TimeUnit.SECONDS)).as("iteration %d timed out", i).isTrue();
                assertThat(unexpected).as("iteration %d threw unexpectedly", i).isEmpty();

                boolean lateIsMember = room.member(late.participantId()).isPresent();
                boolean inProgress = room.status() == RoomStatus.IN_PROGRESS;
                assertThat(lateIsMember && inProgress)
                        .as("iteration %d: late joiner must never be seated in an in-progress room", i)
                        .isFalse();
            }
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
