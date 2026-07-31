package com.woobeee.mvc.auth.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "oauth.google")
public class GoogleOauthProperties {
    @NotBlank
    private String clientId;

    @NotBlank
    private String clientSecret;

    @NotBlank
    private String redirectUri;

    private String authorizationUri = "https://accounts.google.com/o/oauth2/v2/auth";

    private String tokenUri = "https://oauth2.googleapis.com/token";

    private String scope = "openid email profile";

    private long authorizationStateTtlSeconds = 600;

    private long connectTimeoutSeconds = 5;

    private long readTimeoutSeconds = 10;
}
