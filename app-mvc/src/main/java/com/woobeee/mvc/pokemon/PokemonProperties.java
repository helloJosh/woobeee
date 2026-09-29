package com.woobeee.mvc.pokemon;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Set;

/**
 * @param bankAccount      신청자가 이체할 계좌. 고정 1개다 — 화면에 그대로 찍히는 문자열이라
 *                         "은행 계좌번호 예금주" 순서로 넣는다. 기본값은 application.yaml 에
 *                         있고, 공동구매 페이지가 공개이므로 이 값도 공개로 노출된다.
 * @param managerMemberIds 신청서의 진행 상태를 바꿀 수 있는 회원 id. 블로그의 ROLE_ADMIN 과
 *                         별개다 — 이 공동구매를 실제로 굴리는 사람만 손대야 하므로 역할이
 *                         아니라 명단으로 좁힌다. 설정으로 빼 두어 사람이 바뀌어도 배포 없이 고친다.
 */
@ConfigurationProperties(prefix = "pokemon")
public record PokemonProperties(String bankAccount, Set<Long> managerMemberIds) {

    public PokemonProperties {
        if (bankAccount == null || bankAccount.isBlank()) {
            bankAccount = "계좌 정보가 아직 설정되지 않았습니다";
        }
        if (managerMemberIds == null || managerMemberIds.isEmpty()) {
            managerMemberIds = Set.copyOf(List.of(1L, 3L));
        }
    }

    public boolean isManager(Long memberId) {
        return memberId != null && managerMemberIds.contains(memberId);
    }
}
