package com.woobeee.mvc.ai.service;

import com.woobeee.mvc.ai.api.response.AiActionResponse;
import com.woobeee.mvc.ai.api.response.AiRequestResponse;
import com.woobeee.mvc.ai.config.AiProperties;
import com.woobeee.mvc.ai.entity.AiOutbox;
import com.woobeee.mvc.ai.entity.AiRequestStatus;
import com.woobeee.mvc.ai.entity.AiRequests;
import com.woobeee.mvc.ai.exception.AiErrorCode;
import com.woobeee.mvc.auth.entity.Member;
import com.woobeee.mvc.auth.repository.MemberRepository;
import com.woobeee.mvc.ai.repository.AiActionRepository;
import com.woobeee.mvc.ai.repository.AiOutboxRepository;
import com.woobeee.mvc.ai.repository.AiRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AiRequestServiceImpl implements AiRequestService {
    /** 진행 중인 요청 — 사용자당 하나뿐이다(AI-AC-02). V28 의 부분 유니크 인덱스와 같은 집합이다. */
    private static final List<AiRequestStatus> IN_FLIGHT =
            Arrays.stream(AiRequestStatus.values()).filter(AiRequestStatus::isInFlight).toList();

    private final MemberRepository memberRepository;
    private final AiRequestRepository requestRepository;
    private final AiOutboxRepository outboxRepository;
    private final AiActionRepository actionRepository;
    private final AiImageStore imageStore;
    private final AiProperties properties;

    @Override
    @Transactional
    public AiRequestResponse create(String loginId, String text, List<MultipartFile> images) {
        Member member = requireMember(loginId);
        String cleanText = StringUtils.hasText(text) ? text.strip() : null;
        List<MultipartFile> files = images == null ? List.of()
                : images.stream().filter(file -> file != null && !file.isEmpty()).toList();
        validate(cleanText, files);

        // 업로드·저장 전에 막는다. 동시에 두 번 제출하면 여기를 통과해도 DB 인덱스가 하나만 허용한다.
        if (requestRepository.existsByMemberIdAndStatusIn(member.getId(), IN_FLIGHT)) {
            throw AiErrorCode.REQUEST_IN_PROGRESS.asException();
        }

        UUID id = UUID.randomUUID();
        List<String> keys = new ArrayList<>();
        try {
            for (int i = 0; i < files.size(); i++) {
                String key = "ai/" + id + "/" + i;
                imageStore.put(key, bytesOf(files.get(i)), files.get(i).getContentType());
                keys.add(key);
            }
            AiRequests request = requestRepository.saveAndFlush(AiRequests.receive(id, member.getId(), cleanText, keys));
            outboxRepository.save(AiOutbox.pending(id, LocalDateTime.now()));
            return toResponse(request, List.of());
        } catch (DataIntegrityViolationException ex) {
            keys.forEach(imageStore::delete);
            throw AiErrorCode.REQUEST_IN_PROGRESS.asException();
        } catch (RuntimeException ex) {
            keys.forEach(imageStore::delete);
            throw ex;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public AiRequestResponse get(String loginId, UUID id) {
        Member member = requireMember(loginId);
        AiRequests request = requestRepository.findById(id)
                .filter(found -> found.getMemberId().equals(member.getId()))
                .orElseThrow(AiErrorCode.NOT_FOUND::asException);
        return toResponse(request, actionRepository.findByRequestIdOrderById(id).stream()
                .map(AiActionResponse::of).toList());
    }

    @Override
    @Transactional
    public AiRequestResponse retry(String loginId, UUID id) {
        Member member = requireMember(loginId);
        AiRequests request = requestRepository.findById(id)
                .filter(found -> found.getMemberId().equals(member.getId()) || properties.isManager(member.getId()))
                .orElseThrow(AiErrorCode.NOT_FOUND::asException);
        if (request.getStatus() != AiRequestStatus.DEAD) {
            throw AiErrorCode.NOT_RETRYABLE.asException();
        }
        if (requestRepository.existsByMemberIdAndStatusIn(request.getMemberId(), IN_FLIGHT)) {
            throw AiErrorCode.REQUEST_IN_PROGRESS.asException();
        }

        int requeued;
        try {
            requeued = requestRepository.requeueDead(id, LocalDateTime.now());
        } catch (DataIntegrityViolationException ex) {
            throw AiErrorCode.REQUEST_IN_PROGRESS.asException();
        }
        if (requeued == 0) {
            throw AiErrorCode.NOT_RETRYABLE.asException();
        }
        outboxRepository.save(AiOutbox.pending(id, LocalDateTime.now()));

        AiRequests fresh = requestRepository.findById(id).orElse(request);
        return toResponse(fresh, actionRepository.findByRequestIdOrderById(id).stream()
                .map(AiActionResponse::of).toList());
    }

    private Member requireMember(String loginId) {
        if (!StringUtils.hasText(loginId)) {
            throw AiErrorCode.LOGIN_REQUIRED.asException();
        }
        return memberRepository.findByEmail(loginId).orElseThrow(AiErrorCode.LOGIN_REQUIRED::asException);
    }

    /** 쓰기 전에 전부 검사한다 — 어느 하나라도 어긋나면 업로드도 저장도 일어나지 않는다. */
    private void validate(String text, List<MultipartFile> files) {
        if (text == null && files.isEmpty()) {
            throw AiErrorCode.EMPTY_REQUEST.asException();
        }
        if (files.size() > properties.maxImages()) {
            throw AiErrorCode.TOO_MANY_IMAGES.asException();
        }
        for (MultipartFile file : files) {
            String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
            if (!AiProperties.ALLOWED_IMAGE_TYPES.contains(type)) {
                throw AiErrorCode.UNSUPPORTED_IMAGE.asException();
            }
            if (file.getSize() > properties.maxImageBytes()) {
                throw AiErrorCode.IMAGE_TOO_LARGE.asException();
            }
        }
    }

    private static byte[] bytesOf(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw AiErrorCode.BAD_REQUEST.asException();
        }
    }

    private AiRequestResponse toResponse(AiRequests request, List<AiActionResponse> actions) {
        return AiRequestResponse.of(request, request.getImageKeys().stream().map(imageStore::url).toList(), actions);
    }
}
