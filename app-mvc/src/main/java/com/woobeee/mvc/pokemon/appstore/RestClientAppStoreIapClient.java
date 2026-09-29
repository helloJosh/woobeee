package com.woobeee.mvc.pokemon.appstore;

import com.woobeee.mvc.pokemon.PokemonProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 하루 한 번만 부른다. apps.apple.com 의 robots.txt 는 {@code /in/app/...} 를 막지 않고
 * 오히려 사이트맵에 올려 두었다.
 *
 * <p>두 가지를 맞춰 줘야 페이지가 제대로 온다:
 * <ul>
 *   <li><b>리다이렉트를 따라간다.</b> {@code .../app/pokemon-go/...} 는 301 로
 *       {@code .../app/pok%C3%A9mon-go/...}(é 를 인코딩한 정식 주소)로 보낸다. RestClient 의
 *       기본 요청 팩토리는 리다이렉트를 따라가지 않아 <b>빈 본문</b>을 받고, 파서는 0개를 낸다.
 *       기본 URL 을 정식 주소로 두었지만, Apple 이 또 옮겨도 조용히 죽지 않게 따라가게 한다.
 *   <li><b>User-Agent 를 브라우저로 보낸다.</b> 기본 UA 로는 페이지가 다르게 내려온다.
 *   <li><b>본문을 UTF-8 로 직접 해석한다.</b> Apple 은 {@code content-type: text/html} 만 주고
 *       charset 을 붙이지 않는데, 그러면 Spring 의 StringHttpMessageConverter 가
 *       ISO-8859-1 로 떨어져 {@code ₹} 와 {@code é} 가 깨진다 — 페이지는 멀쩡히 받아 놓고
 *       파서가 0개를 낸다. {@code String.class} 로 받으면 안 되는 이유다.
 * </ul>
 */
@Component
@Slf4j
public class RestClientAppStoreIapClient implements AppStoreIapClient {
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final RestClient restClient = RestClient.builder()
            .requestFactory(new JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()))
            .build();
    private final PokemonProperties properties;

    public RestClientAppStoreIapClient(PokemonProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<String> fetchProductPage() {
        try {
            byte[] body = restClient.get()
                    .uri(properties.appStoreUrl())
                    .header(HttpHeaders.USER_AGENT, BROWSER_USER_AGENT)
                    .header(HttpHeaders.ACCEPT_LANGUAGE, "en-IN,en;q=0.9")
                    .retrieve()
                    .body(byte[].class);
            return Optional.ofNullable(body).map(bytes -> new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception ex) {
            log.warn("could not fetch the App Store product page: {}", ex.toString());
            return Optional.empty();
        }
    }
}
