package com.woobeee.mvc.pokemon.service;

import com.woobeee.mvc.auth.entity.Member;
import com.woobeee.mvc.pokemon.PokemonProperties;
import com.woobeee.mvc.pokemon.api.request.PatchPokemonOrderStatusRequest;
import com.woobeee.mvc.pokemon.api.request.PokemonOrderItemRequest;
import com.woobeee.mvc.pokemon.api.request.PostPokemonCommentRequest;
import com.woobeee.mvc.pokemon.api.request.PostPokemonOrderRequest;
import com.woobeee.mvc.pokemon.api.request.PutPokemonOrderRequest;
import com.woobeee.mvc.pokemon.api.response.*;
import com.woobeee.mvc.pokemon.entity.PokemonOrderComments;
import com.woobeee.mvc.pokemon.entity.PokemonOrderItems;
import com.woobeee.mvc.pokemon.entity.PokemonOrderStatus;
import com.woobeee.mvc.pokemon.entity.PokemonOrders;
import com.woobeee.mvc.pokemon.entity.PokemonProducts;
import com.woobeee.mvc.pokemon.exception.PokemonErrorCode;
import com.woobeee.mvc.pokemon.rate.ExchangeRate;
import com.woobeee.mvc.pokemon.rate.ExchangeRateService;
import com.woobeee.mvc.pokemon.repository.PokemonOrderCommentRepository;
import com.woobeee.mvc.pokemon.repository.PokemonOrderItemRepository;
import com.woobeee.mvc.pokemon.repository.PokemonOrderRepository;
import com.woobeee.mvc.pokemon.repository.PokemonProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PokemonServiceImpl implements PokemonService {
    private final PokemonOrderRepository orderRepository;
    private final PokemonOrderItemRepository itemRepository;
    private final PokemonOrderCommentRepository commentRepository;
    private final PokemonProductRepository productRepository;
    private final PokemonMemberResolver memberResolver;
    private final ExchangeRateService exchangeRateService;
    private final PokemonProperties properties;

    @Override
    @Transactional(readOnly = true)
    public PokemonBoardResponse getBoard(String loginId) {
        List<PokemonOrders> orders = orderRepository.findAllByOrderByCreatedAtDesc();
        List<Long> orderIds = orders.stream().map(PokemonOrders::getId).toList();
        Long viewerMemberId = memberResolver.optionalMemberId(loginId);
        Map<Long, List<PokemonOrderItemResponse>> itemsByOrder = loadItems(orderIds);
        Map<Long, List<PokemonCommentResponse>> commentsByOrder = loadComments(orderIds, viewerMemberId);

        return new PokemonBoardResponse(
                ExchangeRateResponse.from(exchangeRateService.current()),
                productRepository.findAllByActiveTrueOrderBySortOrderAsc().stream()
                        .map(PokemonProductResponse::from).toList(),
                orders.stream()
                        .map(order -> PokemonOrderResponse.of(
                                order,
                                itemsByOrder.getOrDefault(order.getId(), List.of()),
                                commentsByOrder.getOrDefault(order.getId(), List.of()),
                                viewerMemberId))
                        .toList(),
                properties.bankAccount(),
                properties.isManager(viewerMemberId));
    }

    @Override
    @Transactional(readOnly = true)
    public PokemonOrderDetailResponse getOrder(String loginId, Long orderId) {
        PokemonOrders order = orderRepository.findById(orderId)
                .orElseThrow(PokemonErrorCode.ORDER_NOT_FOUND::asException);
        Long viewerMemberId = memberResolver.optionalMemberId(loginId);

        return new PokemonOrderDetailResponse(
                ExchangeRateResponse.from(exchangeRateService.current()),
                toResponse(order, viewerMemberId),
                properties.bankAccount(),
                properties.isManager(viewerMemberId));
    }

    @Override
    @Transactional(readOnly = true)
    public ExchangeRateResponse getRate() {
        return ExchangeRateResponse.from(exchangeRateService.current());
    }

    @Override
    @Transactional
    public PokemonOrderResponse createOrder(String loginId, PostPokemonOrderRequest request) {
        BigDecimal extraInr = request.extraInr() == null ? BigDecimal.ZERO : request.extraInr();
        List<PokemonOrderItemRequest> items = validatedItems(request.items(), extraInr);
        Member member = memberResolver.optionalMember(loginId).orElse(null);
        String applicantName = resolveName(member, request.applicantName());

        // 가격은 요청이 아니라 상품표에서 읽는다 — 클라이언트가 보낸 금액을 믿으면 안 된다.
        Map<Long, PokemonProducts> products = loadProducts(items);
        BigDecimal totalInr = items.stream()
                .map(item -> products.get(item.productId()).getPriceInr()
                        .multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(extraInr, BigDecimal::add);

        ExchangeRate rate = exchangeRateService.current();
        long itemsKrw = totalInr.multiply(rate.inrToKrw()).setScale(0, RoundingMode.HALF_UP).longValue();

        PokemonOrders saved = orderRepository.save(PokemonOrders.create(
                member == null ? null : member.getId(),
                applicantName,
                trimmedOrNull(request.depositorName()),
                totalInr,
                extraInr,
                rate.inrToKrw(),
                rate.fetchedAt(),
                itemsKrw,
                request.donationKrw(),
                trimmedOrNull(request.memo())));

        List<PokemonOrderItems> savedItems = itemRepository.saveAll(items.stream()
                .map(item -> PokemonOrderItems.create(
                        saved.getId(), products.get(item.productId()), item.quantity()))
                .toList());

        return PokemonOrderResponse.of(
                saved,
                savedItems.stream().map(PokemonOrderItemResponse::from).toList(),
                List.of(),
                member == null ? null : member.getId());
    }

    @Override
    @Transactional
    public PokemonOrderResponse updateOrder(String loginId, Long orderId, PutPokemonOrderRequest request) {
        PokemonOrders order = orderRepository.findById(orderId)
                .orElseThrow(PokemonErrorCode.ORDER_NOT_FOUND::asException);
        assertCanModify(loginId, order);

        BigDecimal extraInr = request.extraInr() == null ? BigDecimal.ZERO : request.extraInr();
        List<PokemonOrderItemRequest> items = validatedItems(request.items(), extraInr);
        Map<Long, PokemonProducts> products = loadProducts(items);

        BigDecimal totalInr = items.stream()
                .map(item -> products.get(item.productId()).getPriceInr()
                        .multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(extraInr, BigDecimal::add);

        // 주문 내용이 바뀌었으니 금액의 근거도 새로 잡는다.
        ExchangeRate rate = exchangeRateService.current();
        long itemsKrw = totalInr.multiply(rate.inrToKrw()).setScale(0, RoundingMode.HALF_UP).longValue();

        // 회원 신청서의 이름은 계속 닉네임이 이긴다. 비회원 신청서만 적어 넣은 이름을 고칠 수 있다.
        String applicantName = order.getMemberId() == null
                ? resolveName(null, request.applicantName())
                : order.getApplicantName();

        order.reprice(applicantName, trimmedOrNull(request.depositorName()), totalInr, extraInr,
                rate.inrToKrw(), rate.fetchedAt(), itemsKrw, request.donationKrw(),
                trimmedOrNull(request.memo()));

        // 항목은 전체 교체다. 수량만 바뀐 줄을 가려내는 것보다 지우고 다시 넣는 편이 단순하고,
        // 신청서 하나의 항목은 많아야 열 줄 남짓이다.
        itemRepository.deleteAllByOrderId(orderId);
        itemRepository.flush();
        List<PokemonOrderItems> savedItems = itemRepository.saveAll(items.stream()
                .map(item -> PokemonOrderItems.create(orderId, products.get(item.productId()), item.quantity()))
                .toList());

        return PokemonOrderResponse.of(
                order,
                savedItems.stream().map(PokemonOrderItemResponse::from).toList(),
                commentRepository.findAllByOrderIdInOrderByCreatedAtAsc(List.of(orderId)).stream()
                        .map(comment -> PokemonCommentResponse.of(comment, memberResolver.optionalMemberId(loginId)))
                        .toList(),
                memberResolver.optionalMemberId(loginId));
    }

    @Override
    @Transactional
    public PokemonOrderResponse changeStatus(String loginId, Long orderId,
                                             PatchPokemonOrderStatusRequest request) {
        memberResolver.requireManager(loginId);

        PokemonOrders order = orderRepository.findById(orderId)
                .orElseThrow(PokemonErrorCode.ORDER_NOT_FOUND::asException);
        // 준비중으로 넘길 때 그 순간의 환율이 필요하다 — 다른 전이에서는 쓰이지 않지만
        // 캐시된 값이라 매번 외부를 치지 않는다.
        ExchangeRate rate = exchangeRateService.current();
        order.changeStatus(request.status(), rate.inrToKrw(), LocalDateTime.now());

        return toResponse(order, memberResolver.optionalMemberId(loginId));
    }

    @Override
    @Transactional
    public void deleteOrder(String loginId, Long orderId) {
        PokemonOrders order = orderRepository.findById(orderId)
                .orElseThrow(PokemonErrorCode.ORDER_NOT_FOUND::asException);

        assertCanModify(loginId, order);

        // DB 에 ON DELETE CASCADE 가 걸려 있지만 JPA 는 그것을 모른다 — 자식을 먼저 지워
        // 영속성 컨텍스트와 DB 가 어긋나지 않게 한다.
        commentRepository.deleteAllByOrderId(orderId);
        itemRepository.deleteAllByOrderId(orderId);
        orderRepository.delete(order);
    }

    @Override
    @Transactional
    public PokemonCommentResponse createComment(String loginId, Long orderId,
                                                PostPokemonCommentRequest request) {
        if (!orderRepository.existsById(orderId)) {
            throw PokemonErrorCode.ORDER_NOT_FOUND.asException();
        }

        String content = trimmedOrNull(request.content());
        if (content == null) {
            throw PokemonErrorCode.EMPTY_COMMENT.asException();
        }

        Member member = memberResolver.optionalMember(loginId).orElse(null);
        PokemonOrderComments saved = commentRepository.save(PokemonOrderComments.create(
                orderId,
                member == null ? null : member.getId(),
                resolveName(member, request.authorName()),
                content));

        return PokemonCommentResponse.of(saved, saved.getMemberId());
    }

    @Override
    @Transactional
    public void deleteComment(String loginId, Long commentId) {
        PokemonOrderComments comment = commentRepository.findById(commentId)
                .orElseThrow(PokemonErrorCode.COMMENT_NOT_FOUND::asException);

        if (!memberResolver.isManager(loginId)) {
            Long memberId = memberResolver.optionalMemberId(loginId);
            if (memberId == null || !comment.isWrittenBy(memberId)) {
                throw PokemonErrorCode.NOT_YOURS.asException();
            }
        }

        commentRepository.delete(comment);
    }

    /**
     * 수정·삭제의 공통 권한 규칙.
     *
     * <ul>
     *   <li>운영자는 언제든
     *   <li>회원이 낸 신청서는 그 회원만
     *   <li>비회원이 낸 신청서는 주인이 없으므로 누구나 — 본인 확인 수단이 없는데 막아 두면
     *       잘못 낸 신청서를 아무도 거두지 못한다
     *   <li>준비가 시작된 뒤로는 운영자만 — 이미 물건을 사러 갔기 때문이다
     * </ul>
     */
    private void assertCanModify(String loginId, PokemonOrders order) {
        if (memberResolver.isManager(loginId)) {
            return;
        }
        if (order.hasOwner() && !order.isOwnedBy(memberResolver.optionalMemberId(loginId))) {
            throw PokemonErrorCode.NOT_YOURS.asException();
        }
        if (order.getStatus() != PokemonOrderStatus.ORDERED) {
            throw PokemonErrorCode.ALREADY_SETTLED.asException();
        }
    }

    /** 단건 조립 — 항목과 댓글을 각각 한 번씩 읽는다. */
    private PokemonOrderResponse toResponse(PokemonOrders order, Long viewerMemberId) {
        List<Long> ids = List.of(order.getId());
        return PokemonOrderResponse.of(
                order,
                itemRepository.findAllByOrderIdIn(ids).stream()
                        .map(PokemonOrderItemResponse::from).toList(),
                commentRepository.findAllByOrderIdInOrderByCreatedAtAsc(ids).stream()
                        .map(comment -> PokemonCommentResponse.of(comment, viewerMemberId)).toList(),
                viewerMemberId);
    }

    /** 목록 화면의 항목은 orderId IN (...) 한 번으로 모아 온다. 주문마다 조회하면 N+1 이다. */
    private Map<Long, List<PokemonOrderItemResponse>> loadItems(List<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return itemRepository.findAllByOrderIdIn(orderIds).stream()
                .collect(Collectors.groupingBy(
                        PokemonOrderItems::getOrderId,
                        Collectors.mapping(PokemonOrderItemResponse::from, Collectors.toList())));
    }

    /** 댓글도 같은 이유로 IN 한 번이다. */
    private Map<Long, List<PokemonCommentResponse>> loadComments(List<Long> orderIds, Long viewerMemberId) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return commentRepository.findAllByOrderIdInOrderByCreatedAtAsc(orderIds).stream()
                .collect(Collectors.groupingBy(
                        PokemonOrderComments::getOrderId,
                        Collectors.mapping(
                                comment -> PokemonCommentResponse.of(comment, viewerMemberId),
                                Collectors.toList())));
    }

    /**
     * 상품을 하나도 고르지 않았어도 자유 입력 루피가 있으면 유효한 신청이다 —
     * 상품표에 없는 것을 신청하는 경로가 그것이다. 둘 다 비면 살 것이 없다.
     */
    private List<PokemonOrderItemRequest> validatedItems(List<PokemonOrderItemRequest> items,
                                                         BigDecimal extraInr) {
        if (items == null || items.isEmpty()) {
            if (extraInr.signum() > 0) {
                return List.of();
            }
            throw PokemonErrorCode.EMPTY_ORDER.asException();
        }
        Set<Long> seen = items.stream()
                .map(PokemonOrderItemRequest::productId)
                .collect(Collectors.toSet());
        if (seen.size() != items.size()) {
            throw PokemonErrorCode.DUPLICATE_PRODUCT.asException();
        }
        return items;
    }

    /**
     * 신청에 담긴 상품을 한 번의 IN 조회로 읽는다. 내려간(active=false) 상품이나 없는 id 는
     * 거절한다 — 스토어에서 사라진 것을 팔 수는 없다.
     */
    private Map<Long, PokemonProducts> loadProducts(List<PokemonOrderItemRequest> items) {
        if (items.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = items.stream().map(PokemonOrderItemRequest::productId).toList();
        Map<Long, PokemonProducts> found = productRepository.findAllByIdIn(ids).stream()
                .filter(PokemonProducts::isActive)
                .collect(Collectors.toMap(PokemonProducts::getId, product -> product));
        if (found.size() != ids.size()) {
            throw PokemonErrorCode.PRODUCT_NOT_FOUND.asException();
        }
        return found;
    }

    /** 로그인이면 닉네임이 이긴다 — 남의 이름을 적어 넣을 수 없게 하려는 것이다. */
    private String resolveName(Member member, String requested) {
        if (member != null) {
            return member.getNickname();
        }
        String trimmed = trimmedOrNull(requested);
        if (trimmed == null) {
            throw PokemonErrorCode.NAME_REQUIRED.asException();
        }
        return trimmed;
    }

    private static String trimmedOrNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
