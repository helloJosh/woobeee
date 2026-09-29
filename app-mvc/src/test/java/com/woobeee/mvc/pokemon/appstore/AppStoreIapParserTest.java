package com.woobeee.mvc.pokemon.appstore;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * POKEMON-AC-20 — App Store 의 In-App Purchases 목록 읽기.
 * 픽스처는 2026-09-29 의 실제 페이지 조각이다.
 */
class AppStoreIapParserTest {

    private static String fixture() throws IOException {
        try (InputStream in = AppStoreIapParserTest.class
                .getResourceAsStream("/pokemon/app-store-iap.html")) {
            assertThat(in).as("픽스처가 있어야 한다").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** POKEMON-AC-20 */
    @Test
    void itReadsEveryInAppPurchaseWithItsRupeePrice() throws IOException {
        List<ScrapedProduct> products = AppStoreIapParser.parse(fixture());

        assertThat(products).hasSize(10);
        assertThat(products).extracting(ScrapedProduct::name)
                .contains("100 PokéCoins", "550 PokéCoins", "14,500 PokéCoins",
                        "Event Ticket", "Event Pass Deluxe");
    }

    /** POKEMON-AC-20 — 쉼표가 든 가격도 숫자로 읽어야 한다. */
    @Test
    void aThousandsSeparatorInThePriceIsNotLost() throws IOException {
        ScrapedProduct biggest = AppStoreIapParser.parse(fixture()).stream()
                .filter(p -> p.name().equals("14,500 PokéCoins"))
                .findFirst().orElseThrow();

        assertThat(biggest.priceInr()).isEqualByComparingTo(new BigDecimal("2899"));
        assertThat(biggest.coins()).isEqualTo(14500);
    }

    /** POKEMON-AC-20 — 코인이 아닌 상품은 코인 0 이다. 가격은 그대로 읽는다. */
    @Test
    void anItemThatIsNotCoinsHasNoCoinCount() throws IOException {
        ScrapedProduct ticket = AppStoreIapParser.parse(fixture()).stream()
                .filter(p -> p.name().equals("Event Ticket"))
                .findFirst().orElseThrow();

        assertThat(ticket.coins()).isZero();
        assertThat(ticket.priceInr()).isEqualByComparingTo(new BigDecimal("59"));
    }

    /** POKEMON-AC-20 — 코인 상품이 코인 수 오름차순으로 먼저, 나머지가 뒤로. */
    @Test
    void coinPacksComeFirstInAscendingOrder() throws IOException {
        List<ScrapedProduct> products = AppStoreIapParser.parse(fixture());

        assertThat(products.subList(0, 6)).extracting(ScrapedProduct::coins)
                .containsExactly(100, 550, 1200, 2500, 5200, 14500);
        assertThat(products.subList(6, 10)).allSatisfy(p -> assertThat(p.coins()).isZero());
    }

    /**
     * POKEMON-AC-20 — Apple 의 CSS 클래스는 빌드 해시라 재배포마다 바뀐다.
     * 클래스 이름이 통째로 달라져도 계속 읽혀야 한다.
     */
    @Test
    void aChangedCssClassHashDoesNotBreakTheParse() throws IOException {
        String repainted = fixture().replace("svelte-1gyt6l2", "svelte-deadbeef");

        assertThat(AppStoreIapParser.parse(repainted))
                .hasSameSizeAs(AppStoreIapParser.parse(fixture()));
    }

    /** POKEMON-AC-20 — 같은 목록이 레이아웃별로 여러 번 그려져도 한 번만 센다. */
    @Test
    void aListRepeatedForAnotherLayoutIsNotCountedTwice() throws IOException {
        String doubled = fixture() + fixture();

        assertThat(AppStoreIapParser.parse(doubled)).hasSize(10);
    }

    /** POKEMON-AC-21 — 읽을 것이 없으면 빈 목록. 동기화가 이것을 보고 건너뛴다. */
    @Test
    void anUnreadablePageYieldsNothingRatherThanGarbage() {
        assertThat(AppStoreIapParser.parse("")).isEmpty();
        assertThat(AppStoreIapParser.parse(null)).isEmpty();
        assertThat(AppStoreIapParser.parse("<html><body>점검 중입니다</body></html>")).isEmpty();
    }

    /**
     * POKEMON-AC-20 회귀 — Apple 은 통화 기호와 숫자를 non-breaking space(U+00A0)로 붙인다.
     * 자바의 {@code \s} 는 UNICODE_CHARACTER_CLASS 없이는 U+00A0 를 못 잡아 <b>한 줄도</b>
     * 안 읽힌다. 실제로 처음 구현이 이것 때문에 0개를 냈다.
     */
    @Test
    void aNonBreakingSpaceBetweenTheSymbolAndTheNumberIsStillWhitespace() {
        String nbsp = "<span>100 PokéCoins</span> <span>\u20B9\u00A029</span>";

        assertThat(AppStoreIapParser.parse(nbsp)).singleElement()
                .satisfies(p -> {
                    assertThat(p.coins()).isEqualTo(100);
                    assertThat(p.priceInr()).isEqualByComparingTo(new BigDecimal("29"));
                });
    }

    /** POKEMON-AC-20 — 이름에 섞인 U+00A0 도 보통 공백으로 정리한다. 이름이 동기화 식별자다. */
    @Test
    void aNonBreakingSpaceInsideTheNameIsNormalised() {
        String nbsp = "<span>1,200\u00A0PokéCoins</span> <span>\u20B9 289</span>";

        assertThat(AppStoreIapParser.parse(nbsp)).singleElement()
                .satisfies(p -> assertThat(p.name()).isEqualTo("1,200 PokéCoins"));
    }

    /** POKEMON-AC-20 — 이름 앞의 수만 코인으로 센다. */
    @Test
    void onlyALeadingCountBeforePokeCoinsIsACoinAmount() {
        assertThat(AppStoreIapParser.coinsIn("1,200 PokéCoins")).isEqualTo(1200);
        assertThat(AppStoreIapParser.coinsIn("100 PokeCoins")).isEqualTo(100);
        assertThat(AppStoreIapParser.coinsIn("Event Pass 3 Deluxe")).isZero();
        assertThat(AppStoreIapParser.coinsIn("Bundle with 500 gems")).isZero();
    }
}
