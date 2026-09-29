package com.woobeee.mvc.pokemon.appstore;

import com.woobeee.mvc.pokemon.entity.PokemonProducts;
import com.woobeee.mvc.pokemon.repository.PokemonProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** POKEMON-AC-21 — 매일 동기화가 상품표에 무엇을 하는가. */
class PokemonProductSyncServiceTest {

    private FakeProductRepository products;
    private String page;

    @BeforeEach
    void setUp() {
        products = new FakeProductRepository();
        page = item("100 PokéCoins", "29") + item("550 PokéCoins", "149");
    }

    private PokemonProductSyncService serviceReturning(String html) {
        return new PokemonProductSyncService(() -> Optional.ofNullable(html), products);
    }

    private static String item(String name, String price) {
        return "<span>" + name + "</span> <span>₹ " + price + "</span>";
    }

    /** POKEMON-AC-21 — 처음 보는 상품은 들어온다. */
    @Test
    void newProductsAreAdded() {
        assertThat(serviceReturning(page).sync()).isEqualTo(2);

        assertThat(products.rows).extracting(PokemonProducts::getName)
                .containsExactly("100 PokéCoins", "550 PokéCoins");
        assertThat(products.rows).allMatch(PokemonProducts::isActive);
    }

    /** POKEMON-AC-21 — 이미 있던 상품은 값만 갱신한다. 행이 늘지 않는다. */
    @Test
    void anExistingProductIsUpdatedInPlace() {
        serviceReturning(page).sync();
        serviceReturning(item("100 PokéCoins", "35") + item("550 PokéCoins", "149")).sync();

        assertThat(products.rows).hasSize(2);
        assertThat(products.rows.getFirst().getPriceInr()).isEqualByComparingTo(new BigDecimal("35"));
    }

    /** POKEMON-AC-21 — 목록에서 사라진 상품은 지우지 않고 내린다. 과거 신청서가 가리킨다. */
    @Test
    void aProductThatLeftTheStoreIsRetiredNotDeleted() {
        serviceReturning(page).sync();
        serviceReturning(item("100 PokéCoins", "29")).sync();

        assertThat(products.rows).hasSize(2);
        assertThat(products.rows.stream().filter(PokemonProducts::isActive)).hasSize(1);
        assertThat(products.rows.get(1).getName()).isEqualTo("550 PokéCoins");
        assertThat(products.rows.get(1).isActive()).isFalse();
    }

    /** POKEMON-AC-21 — 내려갔던 상품이 돌아오면 다시 올라온다. */
    @Test
    void aRetiredProductComesBackWhenTheStoreListsItAgain() {
        serviceReturning(page).sync();
        serviceReturning(item("100 PokéCoins", "29")).sync();
        serviceReturning(page).sync();

        assertThat(products.rows).hasSize(2);
        assertThat(products.rows).allMatch(PokemonProducts::isActive);
    }

    /**
     * POKEMON-AC-21 — 이것이 이 서비스의 핵심이다. 페이지를 못 가져오거나 마크업이 바뀌어
     * 한 줄도 못 읽으면 <b>아무것도 건드리지 않는다</b>. 파싱이 한 번 깨졌다고 상품표가
     * 비면 그날 아무도 신청을 못 한다 — 어제 목록이 낫다.
     */
    @Test
    void aFailedFetchLeavesYesterdaysProductsAlone() {
        serviceReturning(page).sync();

        assertThat(serviceReturning(null).sync()).isZero();
        assertThat(serviceReturning("<html>점검 중</html>").sync()).isZero();

        assertThat(products.rows).hasSize(2);
        assertThat(products.rows).allMatch(PokemonProducts::isActive);
    }

    /** POKEMON-AC-21 — 정렬 순서는 매번 다시 매긴다. 스토어 순서는 무작위에 가깝다. */
    @Test
    void sortOrderIsReassignedEveryRun() {
        serviceReturning(item("550 PokéCoins", "149") + item("100 PokéCoins", "29")).sync();

        assertThat(products.rows).extracting(PokemonProducts::getName)
                .containsExactly("100 PokéCoins", "550 PokéCoins");
        assertThat(products.rows).extracting(PokemonProducts::getSortOrder)
                .containsExactly(1, 2);
    }

    /** 저장 순서와 id 부여만 흉내 내는 최소 구현. 이 서비스가 쓰는 것은 findAll/save 뿐이다. */
    private static final class FakeProductRepository implements PokemonProductRepository {
        private final List<PokemonProducts> rows = new ArrayList<>();
        private final AtomicLong sequence = new AtomicLong();

        @Override
        public List<PokemonProducts> findAll() {
            return List.copyOf(rows);
        }

        @Override
        public <S extends PokemonProducts> S save(S product) {
            setId(product, sequence.incrementAndGet());
            rows.add(product);
            return product;
        }

        private static void setId(PokemonProducts product, long id) {
            try {
                var field = PokemonProducts.class.getDeclaredField("id");
                field.setAccessible(true);
                field.set(product, id);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException(ex);
            }
        }

        @Override
        public List<PokemonProducts> findAllByActiveTrueOrderBySortOrderAsc() {
            return rows.stream().filter(PokemonProducts::isActive).toList();
        }

        @Override
        public List<PokemonProducts> findAllByIdIn(Collection<Long> ids) {
            return rows.stream().filter(p -> ids.contains(p.getId())).toList();
        }

        // ---- 이 테스트가 쓰지 않는 JpaRepository 계약 ----
        @Override public void flush() {}
        @Override public <S extends PokemonProducts> S saveAndFlush(S entity) { return save(entity); }
        @Override public <S extends PokemonProducts> List<S> saveAllAndFlush(Iterable<S> entities) { throw unsupported(); }
        @Override public void deleteAllInBatch(Iterable<PokemonProducts> entities) { throw unsupported(); }
        @Override public void deleteAllByIdInBatch(Iterable<Long> ids) { throw unsupported(); }
        @Override public void deleteAllInBatch() { throw unsupported(); }
        @Override public PokemonProducts getOne(Long id) { throw unsupported(); }
        @Override public PokemonProducts getById(Long id) { throw unsupported(); }
        @Override public PokemonProducts getReferenceById(Long id) { throw unsupported(); }
        @Override public <S extends PokemonProducts> List<S> findAll(org.springframework.data.domain.Example<S> example) { throw unsupported(); }
        @Override public <S extends PokemonProducts> List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { throw unsupported(); }
        @Override public <S extends PokemonProducts> List<S> saveAll(Iterable<S> entities) { throw unsupported(); }
        @Override public Optional<PokemonProducts> findById(Long id) { return rows.stream().filter(p -> id.equals(p.getId())).findFirst(); }
        @Override public boolean existsById(Long id) { return findById(id).isPresent(); }
        @Override public List<PokemonProducts> findAllById(Iterable<Long> ids) { throw unsupported(); }
        @Override public long count() { return rows.size(); }
        @Override public void deleteById(Long id) { throw unsupported(); }
        @Override public void delete(PokemonProducts entity) { throw unsupported(); }
        @Override public void deleteAllById(Iterable<? extends Long> ids) { throw unsupported(); }
        @Override public void deleteAll(Iterable<? extends PokemonProducts> entities) { throw unsupported(); }
        @Override public void deleteAll() { throw unsupported(); }
        @Override public List<PokemonProducts> findAll(org.springframework.data.domain.Sort sort) { throw unsupported(); }
        @Override public org.springframework.data.domain.Page<PokemonProducts> findAll(org.springframework.data.domain.Pageable pageable) { throw unsupported(); }
        @Override public <S extends PokemonProducts> Optional<S> findOne(org.springframework.data.domain.Example<S> example) { throw unsupported(); }
        @Override public <S extends PokemonProducts> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { throw unsupported(); }
        @Override public <S extends PokemonProducts> long count(org.springframework.data.domain.Example<S> example) { throw unsupported(); }
        @Override public <S extends PokemonProducts> boolean exists(org.springframework.data.domain.Example<S> example) { throw unsupported(); }
        @Override public <S extends PokemonProducts, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { throw unsupported(); }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("이 테스트가 쓰지 않는 경로다");
        }
    }
}
