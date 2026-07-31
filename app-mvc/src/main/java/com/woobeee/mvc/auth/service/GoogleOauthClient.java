package com.woobeee.mvc.auth.service;

import com.woobeee.mvc.auth.service.dto.GoogleTokenExchangeResponse;

public interface GoogleOauthClient {
    GoogleTokenExchangeResponse exchangeAuthorizationCode(String code, String codeVerifier);
}
