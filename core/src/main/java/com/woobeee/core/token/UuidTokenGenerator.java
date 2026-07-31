package com.woobeee.core.token;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class UuidTokenGenerator implements TokenGenerator {
    @Override
    public String nextToken() {
        return UUID.randomUUID().toString();
    }
}
