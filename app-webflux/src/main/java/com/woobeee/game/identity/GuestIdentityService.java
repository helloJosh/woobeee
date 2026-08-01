package com.woobeee.game.identity;

import com.woobeee.game.room.GameIdGenerator;
import com.woobeee.game.room.Room;
import com.woobeee.game.room.RoomMember;
import com.woobeee.game.room.RoomService;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
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
     * 그다음 닉네임을 정규화하고, 마지막으로 방 내 닉네임 중복을 검사한다.
     */
    public Mono<GuestToken> issue(String roomId, String inviteCode, String rawNickname) {
        Room room = roomService.requireRoom(roomId, inviteCode);
        String nickname = NicknameValidator.normalize(rawNickname);

        boolean taken = room.members().stream()
                .map(RoomMember::participant)
                .anyMatch(participant -> participant.displayName().equals(nickname));
        if (taken) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Nickname is already used in this room");
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
