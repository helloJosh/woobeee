package com.woobeee.mvc.pokemon.appstore;

import com.woobeee.mvc.pokemon.entity.PokemonProducts;
import com.woobeee.mvc.pokemon.repository.PokemonProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * App Store 의 In-App Purchases 목록을 하루 한 번 상품표에 반영한다.
 *
 * <p>규칙 셋:
 * <ul>
 *   <li>페이지를 못 가져오거나 한 줄도 못 읽으면 <b>아무것도 건드리지 않는다</b>. 파싱이
 *       한 번 깨졌다고 상품표가 통째로 비면 그날 신청을 아무도 못 한다 — 어제 목록이 낫다.
 *   <li>사라진 상품은 지우지 않고 내린다(active=false). 과거 신청서가 가리키고 있다.
 *   <li>이름이 식별자다. 스토어가 상품 id 를 내주지 않는다.
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PokemonProductSyncService {
    private final AppStoreIapClient client;
    private final PokemonProductRepository productRepository;

    /**
     * 매일 06:00 (Asia/Seoul). 신청이 몰리는 시간대를 피해 하루 시작 전에 맞춰 둔다.
     *
     * <p><b>여기에 {@code @Transactional} 이 있어야 한다.</b> {@link #sync()} 를 같은 빈 안에서
     * 부르는 자기 호출이라 그쪽 어노테이션은 프록시를 타지 않는다 — 트랜잭션 없이 돌면
     * {@code findAll} 이 준 엔티티가 준영속이라 가격 갱신과 내림이 <b>조용히 사라진다</b>.
     * 새 상품은 {@code save} 가 자체 트랜잭션을 열어 들어가므로, 겉으로는 "10개 반영" 이라고
     * 찍히는데 바뀐 값만 안 남는다.
     */
    @Scheduled(cron = "${pokemon.app-store.sync-cron:0 0 6 * * *}", zone = "Asia/Seoul")
    @Transactional
    public void syncDaily() {
        sync();
    }

    /** 반영된 상품 수. 건너뛰었으면 0. */
    @Transactional
    public int sync() {
        List<ScrapedProduct> scraped = client.fetchProductPage()
                .map(AppStoreIapParser::parse)
                .orElseGet(List::of);

        if (scraped.isEmpty()) {
            // 스토어가 잠깐 막혔거나 마크업이 바뀐 것이다. 지난 목록을 그대로 둔다.
            log.warn("App Store 에서 읽은 상품이 없다 — 상품표를 그대로 둔다");
            return 0;
        }

        LocalDateTime now = LocalDateTime.now();
        Map<String, PokemonProducts> existing = productRepository.findAll().stream()
                .collect(Collectors.toMap(PokemonProducts::getName, Function.identity(), (a, b) -> a));

        Set<String> seen = new HashSet<>();
        int sortOrder = 1;
        for (ScrapedProduct product : scraped) {
            seen.add(product.name());
            PokemonProducts row = existing.get(product.name());
            if (row == null) {
                productRepository.save(
                        PokemonProducts.seen(product.name(), product.priceInr(), product.coins(), sortOrder, now));
            } else {
                row.refresh(product.priceInr(), product.coins(), sortOrder, now);
            }
            sortOrder++;
        }

        int retired = 0;
        for (PokemonProducts row : existing.values()) {
            if (!seen.contains(row.getName()) && row.isActive()) {
                row.retire();
                retired++;
            }
        }

        log.info("App Store 상품 동기화: {}개 반영, {}개 내림", scraped.size(), retired);
        return scraped.size();
    }
}
