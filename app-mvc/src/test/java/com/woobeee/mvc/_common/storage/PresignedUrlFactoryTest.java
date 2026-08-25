package com.woobeee.mvc._common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * presigned URL 의 <b>결정성</b>을 고정한다. 이것이 이 클래스가 존재하는 이유다 — AWS SDK 는
 * 서명 시각을 항상 "지금"으로 넣어 URL 이 초 단위로 달라지고, CDN 은 쿼리스트링까지 캐시 키에
 * 넣으므로 방문자마다 다른 오브젝트가 되어 원점 트래픽이 방문자 수에 비례한다.
 *
 * <p>서명 자체가 유효한지(MinIO 가 받아주는지)는 여기서 검증할 수 없다 — 실 스토리지가 필요하다.
 * 여기서 고정하는 것은 <b>같은 시간대면 같은 URL</b>, <b>시간이 넘어가면 다른 URL</b>, 그리고
 * 만료 절벽이 없다는 것이다.
 */
class PresignedUrlFactoryTest {

    private static final String KEY = "13/ctid_structure.png";

    private static StorageProperties properties(String publicEndpoint, long ttlSeconds) {
        StorageProperties properties = new StorageProperties();
        properties.setEndpoint("http://localhost:9000");
        properties.setPublicEndpoint(publicEndpoint);
        properties.setRegion("ap-northeast-2");
        properties.setBucket("woobeee");
        properties.setAccessKey("admin");
        properties.setSecretKey("admin!23");
        properties.setPresignedUrlExpirationSeconds(ttlSeconds);
        return properties;
    }

    private static PresignedUrlFactory at(String isoInstant, String publicEndpoint, long ttlSeconds) {
        return new PresignedUrlFactory(
                properties(publicEndpoint, ttlSeconds),
                Clock.fixed(Instant.parse(isoInstant), ZoneOffset.UTC));
    }

    private static PresignedUrlFactory at(String isoInstant) {
        return at(isoInstant, "https://image.woobeee.com", 86400);
    }

    private static Map<String, String> queryOf(String url) {
        Map<String, String> params = new HashMap<>();
        Arrays.stream(url.substring(url.indexOf('?') + 1).split("&")).forEach(pair -> {
            int eq = pair.indexOf('=');
            params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        });
        return params;
    }

    /**
     * 같은 시간대의 두 방문자는 <b>글자 하나까지</b> 같은 URL 을 받는다. 이게 깨지면 CDN 캐시가
     * 방문자마다 갈라져 원점 트래픽이 방문자 수에 비례한다.
     */
    @Test
    void twoVisitorsInTheSameHourGetTheIdenticalUrl() {
        String early = at("2026-08-26T10:00:01Z").getUrl(KEY);
        String late = at("2026-08-26T10:59:59Z").getUrl(KEY);

        assertThat(early).isEqualTo(late);
    }

    /** 시간이 넘어가면 달라진다 — 안 그러면 만료가 갱신되지 않는다. */
    @Test
    void theUrlChangesWhenTheHourRollsOver() {
        String beforeRollover = at("2026-08-26T10:59:59Z").getUrl(KEY);
        String afterRollover = at("2026-08-26T11:00:00Z").getUrl(KEY);

        assertThat(beforeRollover).isNotEqualTo(afterRollover);
    }

    /** 서명 시각은 정시로 내려간다. 이것이 결정성의 기계장치다. */
    @Test
    void theSigningTimestampIsTruncatedToTheHour() {
        Map<String, String> query = queryOf(at("2026-08-26T10:37:42Z").getUrl(KEY));

        assertThat(query.get("X-Amz-Date")).isEqualTo("20260826T100000Z");
        assertThat(query.get("X-Amz-Credential")).startsWith("admin/20260826/ap-northeast-2/s3/");
    }

    /**
     * 만료 절벽이 없다는 것을 고정한다. 버킷과 TTL 이 같으면 시간 끝에 들어온 방문자가 곧
     * 만료되는 URL 을 받아 이미지가 깨진다 — TTL(24h) 에서 버킷(1h) 을 뺀 23시간이 최악값이다.
     */
    @Test
    void aVisitorArrivingAtTheEndOfTheHourStillHasAlmostAFullDayLeft() {
        Instant arrival = Instant.parse("2026-08-26T10:59:59Z");
        Map<String, String> query = queryOf(at(arrival.toString()).getUrl(KEY));

        Instant signedAt = Instant.parse("2026-08-26T10:00:00Z");
        Instant expiresAt = signedAt.plusSeconds(Long.parseLong(query.get("X-Amz-Expires")));

        assertThat(Duration.between(arrival, expiresAt)).isGreaterThanOrEqualTo(Duration.ofHours(23));
    }

    /** TTL 이 버킷보다 짧거나 같으면 절벽이 생긴다 — 조용히 깨지는 대신 터진다. */
    @Test
    void aTtlThatDoesNotExceedTheBucketIsRejected() {
        assertThatThrownBy(() -> at("2026-08-26T10:00:00Z", "https://image.woobeee.com", 3600).getUrl(KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("3600");
    }

    /**
     * host 는 브라우저용 {@code public-endpoint} 에서 온다. 서버용 endpoint 를 쓰면 서명은
     * 유효한데 브라우저가 열 수 없는 URL 이 되고, 서명이 host 를 포함하므로 나중에 문자열로
     * 고칠 수도 없다.
     */
    @Test
    void theHostComesFromThePublicEndpointNotTheServerOne() {
        String url = at("2026-08-26T10:00:00Z").getUrl(KEY);

        assertThat(url).startsWith("https://image.woobeee.com/woobeee/13/ctid_structure.png?");
        assertThat(url).doesNotContain("localhost:9000");
    }

    /** public-endpoint 가 비면 서버 endpoint 로 폴백한다 — 로컬은 설정 없이 돌아야 한다. */
    @Test
    void anEmptyPublicEndpointFallsBackToTheServerEndpoint() {
        String url = at("2026-08-26T10:00:00Z", "", 86400).getUrl(KEY);

        assertThat(url).startsWith("http://localhost:9000/woobeee/13/ctid_structure.png?");
    }

    /**
     * 기본 포트가 아니면 host 에 포함해야 한다 — 브라우저가 보내는 Host 헤더와 정확히 같지
     * 않으면 MinIO 가 SignatureDoesNotMatch 를 낸다. 두 URL 의 서명이 달라야 그 차이가
     * 서명에 반영됐다는 뜻이다.
     */
    @Test
    void aNonDefaultPortChangesTheSignature() {
        String withPort = at("2026-08-26T10:00:00Z", "http://localhost:9000", 86400).getUrl(KEY);
        String withoutPort = at("2026-08-26T10:00:00Z", "http://localhost", 86400).getUrl(KEY);

        assertThat(queryOf(withPort).get("X-Amz-Signature"))
                .isNotEqualTo(queryOf(withoutPort).get("X-Amz-Signature"));
    }

    /**
     * 파일명은 RFC3986 로 인코딩하고 {@code /} 는 경로 구분자로 남긴다. {@code URLEncoder} 는
     * 공백을 {@code +} 로 만드는데 경로에서는 리터럴 {@code +} 로 읽혀 키가 어긋난다.
     */
    @Test
    void nonAsciiAndSpacesArePercentEncodedWhileSlashesStay() {
        String url = at("2026-08-26T10:00:00Z").getUrl("13/한글 그림.png");

        assertThat(url).contains("/woobeee/13/%ED%95%9C%EA%B8%80%20%EA%B7%B8%EB%A6%BC.png?");
        assertThat(url.substring(0, url.indexOf('?'))).doesNotContain("+");
    }

    /** 키가 다르면 서명도 달라야 한다 — 한 서명이 다른 오브젝트를 열면 안 된다. */
    @Test
    void adifferentKeyProducesADifferentSignature() {
        String one = at("2026-08-26T10:00:00Z").getUrl("13/a.png");
        String two = at("2026-08-26T10:00:00Z").getUrl("13/b.png");

        assertThat(queryOf(one).get("X-Amz-Signature")).isNotEqualTo(queryOf(two).get("X-Amz-Signature"));
    }
}
