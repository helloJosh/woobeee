package com.woobeee.game.identity;

import com.woobeee.game.api.error.GameErrorCode;
import com.woobeee.game.room.GameIdGenerator;
import com.woobeee.game.room.Room;
import com.woobeee.game.room.RoomMember;
import com.woobeee.game.room.RoomService;
import com.woobeee.game.room.RoomStatus;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * 게스트 신원은 game 도메인이 발급한다. auth 와 core 토큰 계약은 건드리지 않는다.
 *
 * <p>Redis 레이아웃: key {@code game:guest:{token}}, hash 필드 participantId/displayName/roomId,
 * TTL 6시간. auth의 AuthTokenType/TokenMetadata 와는 별개의 키스페이스다.
 */
@Service
public class GuestIdentityService {
    public static final Duration GUEST_TOKEN_TTL = Duration.ofHours(6);

    private static final String KEY_PREFIX = "game:guest:";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ReactiveStringRedisTemplate redisTemplate;
    private final RoomService roomService;
    private final GameIdGenerator idGenerator;

    public GuestIdentityService(
            ReactiveStringRedisTemplate redisTemplate,
            RoomService roomService,
            GameIdGenerator idGenerator
    ) {
        this.redisTemplate = redisTemplate;
        this.roomService = roomService;
        this.idGenerator = idGenerator;
    }

    /**
     * 순서가 중요하다: 초대 코드를 먼저 검증하고(잘못된 코드는 닉네임 중복 여부를 알려주지 않는다),
     * 그다음 닉네임을 정규화하고, 방 내 닉네임 중복을 검사하고, 마지막으로 그 방에 실제로 들어갈
     * 수 있는지(상태·정원)를 본다.
     *
     * <p><b>왜 상태·정원 검사가 여기 있는가</b> — 이게 없으면 정원이 찼거나 이미 시작된 방에도
     * 토큰이 발급되고, 프론트는 그 토큰을 들고 플레이 화면으로 넘어간 뒤 WebSocket JOIN 에서야
     * 거절당한다. 거절 사유를 보여줄 자리가 없는 화면에서.
     *
     * <p><b>왜 닉네임 검사가 상태·정원 검사보다 먼저인가</b> — {@code issue} 는 호출될 때마다 새
     * {@code participantId} 를 만들므로, 이 지점에서 "이미 이 방에 있는 사람"을 알아볼 수단은
     * 닉네임뿐이다. 그 검사를 먼저 두면 이미 방에 있는 이름에게는 정원·상태 오류가 절대 가지
     * 않는다. 순서를 뒤집으면 자기가 이미 들어가 있는 방에서 "방이 꽉 찼다"는 말을 듣게 된다.
     * (정상적인 재접속은 애초에 이 경로를 타지 않는다 — 저장해 둔 게스트 토큰으로 JOIN 을 다시
     * 하고, {@link Room#admit} 이 {@code RECONNECTED} 로 상태·정원 검사를 건너뛴다.)
     */
    public Mono<GuestToken> issue(String roomId, String inviteCode, String rawNickname) {
        Room room = roomService.requireRoom(roomId, inviteCode);
        String nickname = NicknameValidator.normalize(rawNickname);

        boolean taken = room.members().stream()
                .map(RoomMember::participant)
                .anyMatch(participant -> participant.displayName().equals(nickname));
        if (taken) {
            throw GameErrorCode.NICKNAME_TAKEN.asException();
        }

        switch (room.previewAdmission(RoomStatus.WAITING)) {
            case GAME_ALREADY_STARTED -> throw GameErrorCode.GAME_ALREADY_STARTED.asException();
            case ROOM_FULL -> throw GameErrorCode.ROOM_FULL.asException();
            case ADMITTED, RECONNECTED -> {
                // 들어갈 자리가 있다. 계속 진행한다.
            }
        }

        GameParticipant participant = GameParticipant.guest(idGenerator.nextGuestId(), nickname);
        String token = newToken();
        String key = KEY_PREFIX + token;

        return redisTemplate.<String, String>opsForHash()
                .putAll(key, Map.of(
                        "participantId", participant.participantId(),
                        "displayName", participant.displayName(),
                        "roomId", roomId
                ))
                .then(redisTemplate.expire(key, GUEST_TOKEN_TTL))
                .thenReturn(new GuestToken(token, participant.participantId(), participant.displayName()));
    }

    public Mono<GameParticipant> verify(String token) {
        return entries(token).map(values -> new GameParticipant(
                values.get("participantId"),
                values.get("displayName"),
                ParticipantKind.GUEST,
                null
        ));
    }

    public Mono<String> roomIdOf(String token) {
        return entries(token).map(values -> values.get("roomId"));
    }

    private Mono<Map<String, String>> entries(String token) {
        return redisTemplate.<String, String>opsForHash()
                .entries(KEY_PREFIX + token)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .filter(values -> values.get("participantId") != null
                        && values.get("displayName") != null
                        && values.get("roomId") != null);
    }

    private String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }
}
