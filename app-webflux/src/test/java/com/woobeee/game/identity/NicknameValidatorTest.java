package com.woobeee.game.identity;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NicknameValidatorTest {

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(NicknameValidator.normalize("  손님  ")).isEqualTo("손님");
    }

    @Test
    void acceptsTwentyCharacters() {
        String twenty = "a".repeat(20);
        assertThat(NicknameValidator.normalize(twenty)).isEqualTo(twenty);
    }

    @Test
    void rejectsTwentyOneCharacters() {
        assertThatThrownBy(() -> NicknameValidator.normalize("a".repeat(21)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsBlankAndNull() {
        assertThatThrownBy(() -> NicknameValidator.normalize("   "))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> NicknameValidator.normalize(null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsControlCharacters() {
        assertThatThrownBy(() -> NicknameValidator.normalize("bad" + Character.toString((char) 7) + "name"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
