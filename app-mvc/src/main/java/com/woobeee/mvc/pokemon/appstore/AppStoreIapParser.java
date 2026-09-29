package com.woobeee.mvc.pokemon.appstore;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * App Store 제품 페이지의 In-App Purchases 목록을 읽는다. HTTP 를 타지 않는 순수 함수라
 * 저장해 둔 페이지 조각으로 검증할 수 있다 ({@code src/test/resources/pokemon/app-store-iap.html}).
 *
 * <p>Apple 은 목록을
 * {@code <div class="text-pair ..."><span>이름</span> <span>₹ 가격</span></div>} 로 그린다.
 * <b>클래스 이름에 의존하지 않는다</b> — {@code svelte-1gyt6l2} 같은 빌드 해시가 붙어 있어
 * Apple 이 재배포할 때마다 바뀐다. 이름/가격 span 쌍의 모양만 본다.
 */
public final class AppStoreIapParser {

    /**
     * `<span>이름</span> <span>₹ 1,234.00</span>`.
     *
     * <p><b>UNICODE_CHARACTER_CLASS 가 반드시 필요하다.</b> Apple 은 통화 기호와 숫자를
     * non-breaking space(U+00A0)로 붙여 놓는데, 그 플래그가 없으면 자바의 {@code \s} 는
     * ASCII 공백만 보고 U+00A0 를 놓쳐 <b>한 줄도 못 읽는다</b>. (파이썬의 {@code \s} 는
     * 기본으로 유니코드 공백을 잡아서, 파이썬으로 먼저 확인하면 멀쩡해 보인다.)
     */
    private static final Pattern ITEM = Pattern.compile(
            "<span>([^<]{1,200}?)</span>\\s*<span>\\s*\\u20B9\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*</span>",
            Pattern.UNICODE_CHARACTER_CLASS);

    /** "1,200 PokéCoins" 처럼 이름 맨 앞에 붙은 수. 뒤에 코인을 뜻하는 말이 와야 코인으로 센다. */
    private static final Pattern COINS = Pattern.compile("^([0-9][0-9,]*)\\s*Pok[e\\u00E9]Coins?\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    private AppStoreIapParser() {}

    /**
     * 페이지에서 읽은 상품 목록. 같은 이름이 두 번 나오면 첫 번째만 남긴다 —
     * Apple 이 같은 목록을 레이아웃별로 여러 번 그리기 때문이다.
     *
     * <p>정렬은 스토어 순서를 따르지 않는다(무작위에 가깝다). 코인 상품을 코인 수 오름차순으로
     * 먼저 두고, 코인이 아닌 상품을 가격 오름차순으로 뒤에 둔다.
     */
    public static List<ScrapedProduct> parse(String html) {
        if (html == null || html.isBlank()) {
            return List.of();
        }

        Map<String, ScrapedProduct> byName = new LinkedHashMap<>();
        Matcher matcher = ITEM.matcher(html);
        while (matcher.find()) {
            // 이름에도 U+00A0 가 섞여 온다. 이름이 동기화 식별자이므로 여기서 한 번
            // 정규화해 두지 않으면 눈에 보이지 않는 차이로 같은 상품이 둘이 된다.
            String name = matcher.group(1).replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
            if (name.isEmpty()) {
                continue;
            }
            BigDecimal price = new BigDecimal(matcher.group(2).replace(",", ""));
            if (price.signum() <= 0) {
                continue;
            }
            byName.putIfAbsent(name, new ScrapedProduct(name, price, coinsIn(name)));
        }

        List<ScrapedProduct> products = new ArrayList<>(byName.values());
        products.sort(Comparator
                // 코인 상품이 먼저
                .comparing((ScrapedProduct p) -> p.coins() == 0)
                .thenComparingInt(ScrapedProduct::coins)
                .thenComparing(ScrapedProduct::priceInr)
                .thenComparing(ScrapedProduct::name));
        return List.copyOf(products);
    }

    static int coinsIn(String name) {
        Matcher matcher = COINS.matcher(name.trim());
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1).replace(",", ""));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
