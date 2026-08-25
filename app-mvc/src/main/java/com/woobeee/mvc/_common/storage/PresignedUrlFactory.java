package com.woobeee.mvc._common.storage;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 브라우저가 여는 presigned GET URL 을 만든다. <b>서명 시각을 시간 단위로 내려</b> 같은 시간대의
 * 모든 방문자가 글자 하나까지 같은 URL 을 받게 하는 것이 이 클래스의 존재 이유다.
 *
 * <h2>왜 AWS SDK 를 쓰지 않는가</h2>
 * {@code S3Presigner} 는 서명 시각을 항상 "지금"으로 넣고 그 값이 {@code X-Amz-Date} 로 URL 에
 * 들어간다. 클록을 주입할 수단이 없다 — {@code S3Presigner.Builder} 와
 * {@code GetObjectPresignRequest.Builder} 어디에도 없다. 그래서 SDK 로 만든 URL 은 초 단위로
 * 달라지고, CDN 은 쿼리스트링까지 캐시 키에 넣으므로 <b>방문자마다 다른 오브젝트가 된다</b>.
 * 실측으로 확인했다: 같은 서명 URL 을 반복하면 Cloudflare 가 HIT 을 주지만, 서명을 새로 만들면
 * 매번 MISS 다. 이미지 300KB 짜리 글에 100명이 오면 원점 트래픽이 300KB 대 30MB 로 갈린다.
 *
 * <h2>왜 만료 절벽이 없는가</h2>
 * 버킷(내림 단위)과 TTL 은 서로 다른 일을 한다 — 버킷은 캐시 공유 범위를, TTL 은 안전 여유를
 * 정한다. 둘을 같게 두면 시간 끝에 들어온 방문자가 곧 만료되는 URL 을 받아 이미지가 깨진다.
 * 여기서는 버킷 1시간, TTL 은 설정값(기본 24시간)이므로 최악의 남은 유효기간이
 * {@code TTL - 1시간} = 23시간이다.
 *
 * <p>서명은 host 를 포함하므로 만든 뒤에 문자열로 못 고친다. host 는 서버가 MinIO 에 붙는
 * {@code endpoint} 가 아니라 브라우저가 닿는 {@code public-endpoint} 에서 나온다.
 */
@Component
public class PresignedUrlFactory {
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String SERVICE = "s3";
    private static final String TERMINATOR = "aws4_request";
    private static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";

    /**
     * 서명 시각을 내리는 단위. 이 값이 캐시 공유 범위다 — 1시간이면 같은 시간대의 방문자가
     * 모두 같은 URL 을 받는다. 늘리면 캐시가 더 잘 붙지만 TTL 에서 빼는 여유도 그만큼 커진다.
     */
    private static final Duration BUCKET = Duration.ofHours(1);

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final StorageProperties properties;
    private final Clock clock;

    public PresignedUrlFactory(StorageProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 오브젝트 키에 대한 presigned GET URL.
     *
     * @param key 버킷 안의 키. {@code /} 는 경로 구분자로 유지되고 나머지는 퍼센트 인코딩된다.
     */
    public String getUrl(String key) {
        long ttlSeconds = properties.getPresignedUrlExpirationSeconds();
        if (ttlSeconds <= BUCKET.toSeconds()) {
            // 이러면 시간 끝에 들어온 방문자가 곧 만료되는 URL 을 받는다. 조용히 깨지는 대신
            // 기동 때 터뜨리는 편이 낫다.
            throw new IllegalStateException(
                    "storage.s3.presigned-url-expiration-seconds must exceed the %d초 버킷 (현재 %d초)"
                            .formatted(BUCKET.toSeconds(), ttlSeconds));
        }

        URI base = URI.create(trimTrailingSlash(browserFacingEndpoint()));
        String host = hostHeaderOf(base);
        Instant signedAt = clock.instant().truncatedTo(ChronoUnit.HOURS);
        String amzDate = AMZ_DATE.format(signedAt);
        String dateStamp = DATE_STAMP.format(signedAt);
        String scope = "%s/%s/%s/%s".formatted(dateStamp, properties.getRegion(), SERVICE, TERMINATOR);

        String canonicalUri = "/" + uriEncode(properties.getBucket(), false)
                + "/" + uriEncode(key, false);

        Map<String, String> query = new LinkedHashMap<>();
        query.put("X-Amz-Algorithm", ALGORITHM);
        query.put("X-Amz-Credential", properties.getAccessKey() + "/" + scope);
        query.put("X-Amz-Date", amzDate);
        query.put("X-Amz-Expires", String.valueOf(ttlSeconds));
        query.put("X-Amz-SignedHeaders", "host");
        String canonicalQuery = canonicalQueryOf(query);

        String canonicalRequest = String.join("\n",
                "GET",
                canonicalUri,
                canonicalQuery,
                "host:" + host + "\n",
                "host",
                UNSIGNED_PAYLOAD);

        String stringToSign = String.join("\n",
                ALGORITHM,
                amzDate,
                scope,
                hex(sha256(canonicalRequest)));

        byte[] signingKey = signingKey(dateStamp);
        String signature = hex(hmac(signingKey, stringToSign));

        return base + canonicalUri + "?" + canonicalQuery + "&X-Amz-Signature=" + signature;
    }

    private String browserFacingEndpoint() {
        return StringUtils.hasText(properties.getPublicEndpoint())
                ? properties.getPublicEndpoint()
                : properties.getEndpoint();
    }

    /**
     * 서명에 들어가는 {@code host} 헤더. 브라우저가 보내는 것과 <b>정확히</b> 같아야 한다 —
     * 다르면 MinIO 가 SignatureDoesNotMatch 를 낸다. 그래서 기본 포트(80/443)는 붙이지 않는다.
     */
    private String hostHeaderOf(URI base) {
        int port = base.getPort();
        boolean defaultPort = port == -1
                || ("http".equals(base.getScheme()) && port == 80)
                || ("https".equals(base.getScheme()) && port == 443);
        return defaultPort ? base.getHost() : base.getHost() + ":" + port;
    }

    private byte[] signingKey(String dateStamp) {
        byte[] key = hmac(("AWS4" + properties.getSecretKey()).getBytes(StandardCharsets.UTF_8), dateStamp);
        key = hmac(key, properties.getRegion());
        key = hmac(key, SERVICE);
        return hmac(key, TERMINATOR);
    }

    /** 정렬한 뒤 키·값을 각각 인코딩한다. AWS 는 이 순서와 인코딩을 그대로 다시 계산해 맞춘다. */
    private static String canonicalQueryOf(Map<String, String> query) {
        return query.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> uriEncode(entry.getKey(), true) + "=" + uriEncode(entry.getValue(), true))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }

    /**
     * RFC3986 인코딩. {@code URLEncoder} 를 쓸 수 없다 — 그쪽은 폼 인코딩이라 공백을 {@code +}
     * 로 만들고, 경로에서 {@code +} 는 리터럴로 읽혀 서명이 실제 키와 어긋난다.
     */
    static String uriEncode(String input, boolean encodeSlash) {
        StringBuilder encoded = new StringBuilder();
        for (byte b : input.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~';
            if (unreserved) {
                encoded.append(c);
            } else if (c == '/' && !encodeSlash) {
                encoded.append('/');
            } else {
                encoded.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return encoded.toString();
    }

    private static String trimTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("storage.s3 endpoint 가 비어 있다");
        }
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is not available", exception);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to compute HmacSHA256", exception);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b & 0xFF));
        }
        return hex.toString();
    }
}
