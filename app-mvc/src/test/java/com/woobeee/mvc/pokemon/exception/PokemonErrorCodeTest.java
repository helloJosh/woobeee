package com.woobeee.mvc.pokemon.exception;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PokemonErrorCodeTest {

    /** surefire 는 모듈 디렉터리에서 돈다. */
    private static final Path FRONT_MESSAGES =
            Path.of("..", "front", "lib", "errors", "error-messages.ts");

    /** POKEMON-AC-14 */
    @Test
    void everyCodeIsDistinct() {
        List<String> codes = Arrays.stream(PokemonErrorCode.values())
                .map(PokemonErrorCode::code).toList();

        assertThat(codes).doesNotHaveDuplicates();
    }

    /** POKEMON-AC-14 — 코드는 접두사로 도메인을 드러낸다. */
    @Test
    void everyCodeIsNamespacedToThePokemonDomain() {
        assertThat(Arrays.stream(PokemonErrorCode.values()).map(PokemonErrorCode::code))
                .allSatisfy(code -> assertThat(code).startsWith("pokemon_"));
    }

    /** POKEMON-AC-14 — enum ↔ TS 지도 양방향 대조. 한 방향만 보면 죽은 키를 못 잡는다. */
    @Test
    void theFrontMapAndTheEnumAgreeInBothDirections() throws IOException {
        Assumptions.assumeTrue(Files.exists(FRONT_MESSAGES), "front/ is not checked out");
        String source = Files.readString(FRONT_MESSAGES, StandardCharsets.UTF_8);

        int koStart = source.indexOf("ko: {");
        int enStart = source.indexOf("en: {");
        assertThat(koStart).isNotNegative();
        assertThat(enStart).isGreaterThan(koStart);

        Set<String> declared = Arrays.stream(PokemonErrorCode.values())
                .map(PokemonErrorCode::code)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(pokemonKeysIn(source.substring(koStart, enStart)))
                .as("ko: keys must match PokemonErrorCode exactly")
                .containsExactlyInAnyOrderElementsOf(declared);
        assertThat(pokemonKeysIn(source.substring(enStart)))
                .as("en: keys must match PokemonErrorCode exactly")
                .containsExactlyInAnyOrderElementsOf(declared);
    }

    private static Set<String> pokemonKeysIn(String block) {
        Matcher matcher = Pattern.compile("\"\\s*(pokemon_[A-Za-z0-9_]+)\\s*\"\\s*:").matcher(block);
        Set<String> keys = new LinkedHashSet<>();
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }
}
