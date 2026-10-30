package com.woobeee.mvc.pokemon;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * POKEMON-AC-09 — 진행 상태를 바꿀 수 있는 사람은 명단으로 정해진다.
 * 이 명단이 비거나 잘못 바인딩되면 아무나 남의 신청서 상태를 옮기게 되므로, 설정이
 * 실제로 Set&lt;Long&gt; 으로 들어오는지까지 본다.
 */
class PokemonPropertiesTest {

    /** POKEMON-AC-09 — 여럿을 넣으면 쉼표로 끊긴 문자열로 들어온다. */
    @Test
    void aCommaSeparatedStringBindsToTheManagerIdSet() {
        PokemonProperties properties = bind(Map.of("pokemon.manager-member-ids", "1,3"));

        assertThat(properties.managerMemberIds()).containsExactlyInAnyOrder(1L, 3L);
    }

    /** POKEMON-AC-09 */
    @Test
    void onlyTheListedMembersAreManagers() {
        PokemonProperties properties = new PokemonProperties(Set.of(1L));

        assertThat(properties.isManager(1L)).isTrue();
        assertThat(properties.isManager(2L)).isFalse();
        assertThat(properties.isManager(3L)).isFalse();
        assertThat(properties.isManager(99L)).isFalse();
    }

    /** POKEMON-AC-09 — 비회원은 회원 id 가 없다. null 을 운영자로 보면 안 된다. */
    @Test
    void aGuestIsNeverAManager() {
        assertThat(new PokemonProperties(Set.of(1L)).isManager(null)).isFalse();
    }

    /**
     * 설정을 빠뜨렸을 때 명단이 비면 <b>아무도</b> 운영자가 아니게 되어 상태를 옮길 수 없다.
     * 그래서 기본값을 둔다 — 조용히 잠기는 것보다 낫다.
     *
     * <p>기본값은 <b>한 명</b>이다. 전역 운영자가 늘어나면 그만큼 남의 차수를 손댈 수 있는
     * 사람이 늘어난다 — 자기 차수는 주최자 자격으로 이미 주무를 수 있으므로 여기에 더하지 않는다.
     */
    @Test
    void anAbsentListFallsBackToTheSingleDefaultManager() {
        PokemonProperties properties = bind(Map.of("pokemon.manager-member-ids", ""));

        assertThat(properties.managerMemberIds()).containsExactly(1L);
    }


    private static PokemonProperties bind(Map<String, Object> values) {
        ConfigurationPropertySource source = new MapConfigurationPropertySource(values);
        return new Binder(source)
                .bind("pokemon", PokemonProperties.class)
                .orElseThrow(() -> new AssertionError("pokemon properties did not bind"));
    }
}
