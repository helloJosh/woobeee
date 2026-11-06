package com.woobeee.mvc.ai.service;

import com.woobeee.mvc.ai.api.response.AiRequestResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public interface AiRequestService {

    /** 글·이미지를 받아 요청과 outbox 를 한 트랜잭션으로 저장한다. 사용자당 진행 중 요청은 하나뿐이다. */
    AiRequestResponse create(String loginId, String text, List<MultipartFile> images);

    /** 내 가장 최근 요청. 없으면 null. 새로고침해도 진행 중인 요청을 다시 찾을 수 있게 한다. */
    AiRequestResponse latest(String loginId);

    /** 요청자 본인만 본다 — 남의 요청은 존재를 드러내지 않고 404 다. */
    AiRequestResponse get(String loginId, UUID id);

    /** {@code DEAD} 요청을 다시 돌린다. 본인 또는 전역 운영자만. */
    AiRequestResponse retry(String loginId, UUID id);

    /**
     * 제안 하나를 승인해 실제로 등록한다. {@code PENDING → APPLIED} CAS 에 성공한 호출만 등록하므로 두 번 눌러도 한 번만
     * 반영된다. 기존 서비스가 거절하면 예외가 나가고 트랜잭션이 CAS 까지 되돌려 제안은 {@code PENDING} 으로 남는다.
     */
    AiRequestResponse apply(String loginId, UUID id, Long actionId);

    /** 제안 하나를 거절한다. 아무것도 등록하지 않는다. */
    AiRequestResponse reject(String loginId, UUID id, Long actionId);
}
