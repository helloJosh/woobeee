package com.woobeee.mvc.auth.service;

import com.woobeee.mvc.auth.config.GoogleOauthProperties;
import com.woobeee.mvc.auth.service.dto.GoogleTokenExchangeResponse;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class GoogleOauthRestClient implements GoogleOauthClient {
    private final GoogleOauthProperties googleOauthProperties;
    private final RestClient restClient;

    public GoogleOauthRestClient(GoogleOauthProperties googleOauthProperties) {
        this.googleOauthProperties = googleOauthProperties;
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory(googleOauthProperties))
                .build();
    }

    @Override
    public GoogleTokenExchangeResponse exchangeAuthorizationCode(String code, String codeVerifier) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("client_id", googleOauthProperties.getClientId());
        form.add("client_secret", googleOauthProperties.getClientSecret());
        form.add("redirect_uri", googleOauthProperties.getRedirectUri());
        form.add("code_verifier", codeVerifier);

        try {
            GoogleTokenExchangeResponse response = restClient.post()
                    .uri(googleOauthProperties.getTokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(GoogleTokenExchangeResponse.class);

            if (response == null || response.idToken() == null || response.idToken().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Google token response is invalid");
            }

            return response;
        } catch (RestClientException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Failed to exchange Google authorization code",
                    exception
            );
        }
    }

    private SimpleClientHttpRequestFactory requestFactory(GoogleOauthProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()));
        requestFactory.setReadTimeout(Duration.ofSeconds(properties.getReadTimeoutSeconds()));
        return requestFactory;
    }
}
