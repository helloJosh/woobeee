package com.woobeee.game.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GameParticipantTest {

    @Test
    void memberIdIsPrefixedSoItCannotCollideWithAGuestId() {
        GameParticipant participant = GameParticipant.member(11L, "nick");

        assertThat(participant.participantId()).isEqualTo("m:11");
        assertThat(participant.kind()).isEqualTo(ParticipantKind.MEMBER);
        assertThat(participant.memberId()).isEqualTo(11L);
        assertThat(participant.displayName()).isEqualTo("nick");
    }

    @Test
    void guestIdIsPrefixedAndCarriesNoMemberId() {
        GameParticipant participant = GameParticipant.guest("ab12", "손님");

        assertThat(participant.participantId()).isEqualTo("g:ab12");
        assertThat(participant.kind()).isEqualTo(ParticipantKind.GUEST);
        assertThat(participant.memberId()).isNull();
    }
}
