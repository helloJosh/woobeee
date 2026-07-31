package com.woobeee.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ApiResponseTest {

    @Test
    void successCarriesOkResultCode() {
        ApiResponse<String> response = ApiResponse.success("payload", "fetched");

        assertThat(response.header().isSuccessful()).isTrue();
        assertThat(response.header().message()).isEqualTo("fetched");
        assertThat(response.header().resultCode()).isEqualTo(200);
        assertThat(response.data()).isEqualTo("payload");
    }

    @Test
    void createSuccessCarriesCreatedResultCode() {
        ApiResponse<String> response = ApiResponse.createSuccess("payload", "created");

        assertThat(response.header().resultCode()).isEqualTo(201);
    }

    @Test
    void deleteSuccessCarriesNoContentResultCodeAndNullData() {
        ApiResponse<Object> response = ApiResponse.deleteSuccess("deleted");

        assertThat(response.header().resultCode()).isEqualTo(204);
        assertThat(response.data()).isNull();
    }

    @Test
    void failCarriesErrorCodeAndTimestamp() {
        var response = ApiResponse.fail(HttpStatus.NOT_FOUND, "missing");

        assertThat(response.header().isSuccessful()).isFalse();
        assertThat(response.header().resultCode()).isEqualTo(404);
        assertThat(response.data()).isNotNull();
    }
}
