package com.woobeee.game.room;

import com.woobeee.game.identity.GameParticipant;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class RoomTest {

    private Room newRoom() {
        return new Room("room-1", "invite-1", GameType.OMOK, Instant.parse("2026-08-01T00:00:00Z"),
                GameParticipant.member(11L, "host"));
    }

    @Test
    void creatorBecomesHostAndFirstMember() {
        Room room = newRoom();

        assertThat(room.hostParticipantId()).isEqualTo("m:11");
        assertThat(room.members()).hasSize(1);
        assertThat(room.status()).isEqualTo(RoomStatus.WAITING);
        assertThat(room.members().getFirst().connection()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(room.members().getFirst().ready()).isFalse();
    }

    @Test
    void membersKeepJoinOrder() {
        Room room = newRoom();
        room.addMember(GameParticipant.guest("a", "second"));
        room.addMember(GameParticipant.guest("b", "third"));

        assertThat(room.members().stream().map(m -> m.participant().displayName()))
                .containsExactly("host", "second", "third");
    }

    @Test
    void promotingHostPicksTheNextMemberInJoinOrder() {
        Room room = newRoom();
        room.addMember(GameParticipant.guest("a", "second"));
        room.addMember(GameParticipant.guest("b", "third"));

        room.removeMember("m:11");
        room.promoteNextHost();

        assertThat(room.hostParticipantId()).isEqualTo("g:a");
    }

    @Test
    void omokHoldsTwoAndDodgeHoldsEight() {
        assertThat(GameType.OMOK.capacity()).isEqualTo(2);
        assertThat(GameType.DODGE.capacity()).isEqualTo(8);
    }

    @Test
    void connectionAndReadyAreTracked() {
        Room room = newRoom();

        room.setReady("m:11", true);
        room.setConnection("m:11", ConnectionState.DISCONNECTED);

        RoomMember member = room.member("m:11").orElseThrow();
        assertThat(member.ready()).isTrue();
        assertThat(member.connection()).isEqualTo(ConnectionState.DISCONNECTED);
    }

    /**
     * C1 regression: {@code Room.members} was a plain {@code LinkedHashMap} mutated from
     * different Netty event loops (one per session), the disconnect-grace timer on
     * {@code Schedulers.parallel}, and READY/LEAVE paths, with no synchronization at all.
     * Concurrent addMember/removeMember/members() calls could corrupt the map
     * (ConcurrentModificationException, lost entries). This hammers the room from a handful of
     * writer threads plus concurrent reader threads and asserts nothing escapes and the final
     * membership is exactly "added minus removed".
     */
    @Test
    void survivesConcurrentAddRemoveAndMemberReadsWithoutCorruption() throws InterruptedException {
        Room room = newRoom();
        int workers = 8;
        int perWorker = 300; // 2400 addMember + 1200 removeMember calls from writers alone
        int readers = 4;

        ExecutorService writerPool = Executors.newFixedThreadPool(workers);
        ExecutorService readerPool = Executors.newFixedThreadPool(readers);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch writersDone = new CountDownLatch(workers);
        AtomicBoolean stopReaders = new AtomicBoolean(false);
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        Set<String> expectedSurvivors = ConcurrentHashMap.newKeySet();
        expectedSurvivors.add(room.hostParticipantId());

        for (int w = 0; w < workers; w++) {
            int workerIndex = w;
            writerPool.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < perWorker; i++) {
                        String guestId = workerIndex + "-" + i;
                        GameParticipant participant = GameParticipant.guest(guestId, "p" + guestId);
                        try {
                            room.addMember(participant);
                            if (i % 2 == 0) {
                                room.removeMember(participant.participantId());
                            } else {
                                expectedSurvivors.add(participant.participantId());
                            }
                        } catch (Throwable thrown) {
                            failures.add(thrown);
                        }
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    writersDone.countDown();
                }
            });
        }

        for (int r = 0; r < readers; r++) {
            readerPool.submit(() -> {
                while (!stopReaders.get()) {
                    try {
                        room.members();
                        room.member("m:11");
                        room.isFull();
                        room.promoteNextHost();
                    } catch (Throwable thrown) {
                        failures.add(thrown);
                    }
                }
            });
        }

        startLatch.countDown();
        boolean writersFinished = writersDone.await(30, TimeUnit.SECONDS);
        stopReaders.set(true);
        writerPool.shutdown();
        readerPool.shutdown();
        assertThat(writerPool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(readerPool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(writersFinished).isTrue();
        assertThat(failures).isEmpty();
        assertThat(room.members().stream().map(m -> m.participant().participantId()))
                .containsExactlyInAnyOrderElementsOf(expectedSurvivors);
    }
}
