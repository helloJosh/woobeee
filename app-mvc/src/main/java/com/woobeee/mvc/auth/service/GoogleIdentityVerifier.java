package com.woobeee.mvc.auth.service;

import com.woobeee.mvc.auth.service.dto.GoogleIdentity;

public interface GoogleIdentityVerifier {
    GoogleIdentity verify(String idToken);
}
