package com.woobeee.mvc.ai.service;

import com.woobeee.mvc.ai.api.response.AiRequestResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public interface AiRequestService {

    /** 글·이미지를 받아 요청과 outbox 를 한 트랜잭션으로 저장한다. 사용자당 진행 중 요청은 하나뿐이다. */
    AiRequestResponse create(String loginId, String text, List<MultipartFile> images);

    /** 요청자 본인만 본다 — 남의 요청은 존재를 드러내지 않고 404 다. */
    AiRequestResponse get(String loginId, UUID id);

    /** {@code DEAD} 요청을 다시 돌린다. 본인 또는 전역 운영자만. */
    AiRequestResponse retry(String loginId, UUID id);
}
