package com.woobeee.mvc.pokemon;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Set;

/**
 * @param managerMemberIds 어느 차수든 주무를 수 있는 전역 운영자. 차수는 원래 그 주최자가
 *                         관리하지만, 잘못 열린 것을 대신 고쳐 줄 사람이 필요하다.
 *                         블로그의 ROLE_ADMIN 과 별개이고, 역할이 아니라 명단으로 좁힌다 —
 *                         설정으로 빼 두어 사람이 바뀌어도 배포 없이 고친다.
 */
@ConfigurationProperties(prefix = "pokemon")
public record PokemonProperties(Set<Long> managerMemberIds) {

    public PokemonProperties {
        if (managerMemberIds == null || managerMemberIds.isEmpty()) {
            managerMemberIds = Set.copyOf(List.of(1L, 3L));
        }
    }

    public boolean isManager(Long memberId) {
        return memberId != null && managerMemberIds.contains(memberId);
    }
}
