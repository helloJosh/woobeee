package com.woobeee.mvc.ai.api.request;

import java.util.List;

public record ProposeProductsRequest(int attempt, Long roundId, List<ProductDraft> items) {
}
