package com.woobeee.game.identity;

/**
 * 회원과 게스트를 하나로 다루는 참가자 식별자.
 *
 * <p>participantId 에 접두사를 두는 이유는 회원 11번과 게스트가 같은 문자열을 갖는 사고를
 * 타입이 아니라 값에서 막기 위해서다. 결과 테이블에도 이 문자열이 그대로 들어간다.
 */
public record GameParticipant(
        String participantId,
        String displayName,
        ParticipantKind kind,
        Long memberId
) {
    public static GameParticipant member(long memberId, String displayName) {
        return new GameParticipant("m:" + memberId, displayName, ParticipantKind.MEMBER, memberId);
    }

    public static GameParticipant guest(String guestId, String displayName) {
        return new GameParticipant("g:" + guestId, displayName, ParticipantKind.GUEST, null);
    }
}
