package com.woobeee.mvc.blog.support;

import com.woobeee.mvc.blog.entity.Posts;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class RedisSupport {
    private static final String KEY_POST_VIEWS = "post:views:";
    private static final String ZSET_POST_VIEWS = "post:views:ranking";
    private static final String KEY_VIEW_LOCK   = "post:viewed:";      // post:viewed:{postId}:{ip}
    private static final Duration DEDUP_TTL     = Duration.ofHours(24);


    private final StringRedisTemplate redisTemplate;

    public long getCurrentPostView(long postId) {
        String countKey = KEY_POST_VIEWS + postId;
        String value = redisTemplate.opsForValue().get(countKey);
        return (value == null) ? 0L : Long.parseLong(value);
    }

    public long incrementPostViewAndRanking(long postId, HttpServletRequest request) {
        String ip = getClientIp(request);
        String lockKey  = KEY_VIEW_LOCK + postId + ":" + ip;
        String countKey = KEY_POST_VIEWS + postId;

        // 같은 IP가 24시간 내에 본 적이 없으면 true
        Boolean firstSeen = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", DEDUP_TTL);

        if (Boolean.TRUE.equals(firstSeen)) {
            Long after = redisTemplate.opsForValue().increment(countKey);
            redisTemplate.opsForZSet().incrementScore(ZSET_POST_VIEWS, String.valueOf(postId), 1.0);
            return after == null ? 0L : after;
        } else {
            // 이미 본 IP면 카운트는 그대로 반환
            String cur = redisTemplate.opsForValue().get(countKey);
            return (cur == null) ? 0L : Long.parseLong(cur);
        }
    }

    public String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) ip = request.getHeader("CF-Connecting-IP");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) ip = request.getHeader("X-Real-IP");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) ip = request.getHeader("Proxy-Client-IP");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) ip = request.getHeader("WL-Proxy-Client-IP");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) ip = request.getHeader("HTTP_CLIENT_IP");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) ip = request.getRemoteAddr();
        if (ip != null && ip.contains(",")) ip = ip.split(",")[0].trim();
        return ip;
    }

    public long getRedisOnlyViews(long postId) {
        String v = redisTemplate.opsForValue().get(KEY_POST_VIEWS + postId);
        return (v == null) ? 0L : Long.parseLong(v);
    }

    public List<Long> getTopViewedPostIds(int limit) {
        Set<String> ids = redisTemplate.opsForZSet()
                .reverseRange(ZSET_POST_VIEWS, 0, Math.max(0, limit - 1));
        if (ids == null || ids.isEmpty()) return List.of();
        return ids.stream().map(Long::valueOf).toList();
    }

    public long getTotalViews(Posts post) {
        long redisIncr = getRedisOnlyViews(post.getId());
        long dbBase = post.getViews() == null ? 0L : post.getViews();
        return dbBase + redisIncr;
    }
}
