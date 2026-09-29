package com.woobeee.mvc.pokemon.service;

import com.woobeee.mvc.auth.entity.Member;
import com.woobeee.mvc.auth.repository.MemberRepository;
import com.woobeee.mvc.pokemon.PokemonProperties;
import com.woobeee.mvc.pokemon.api.request.*;
import com.woobeee.mvc.pokemon.api.response.*;
import com.woobeee.mvc.pokemon.entity.*;
import com.woobeee.mvc.pokemon.exception.PokemonErrorCode;
import com.woobeee.mvc.pokemon.rate.ExchangeRate;
import com.woobeee.mvc.pokemon.rate.ExchangeRateService;
import com.woobeee.mvc.pokemon.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PokemonServiceImpl implements PokemonService {
    /** DB 의 CHECK 와 같은 규칙. URL 에 그대로 들어가므로 좁게 잡는다. */
    private static final Pattern HANDLE = Pattern.compile("^[a-z0-9][a-z0-9-]{1,29}$");

    private final PokemonRoundRepository roundRepository;
    private final PokemonHostRepository hostRepository;
    private final PokemonOrderRepository orderRepository;
    private final PokemonOrderItemRepository itemRepository;
    private final PokemonOrderCommentRepository commentRepository;
    private final PokemonProductRepository productRepository;
    private final MemberRepository memberRepository;
    private final PokemonMemberResolver memberResolver;
    private final ExchangeRateService exchangeRateService;
    private final PokemonProperties properties;

    /* ===== 첫 화면 · 주최자 ===== */

    @Override
    @Transactional(readOnly = true)
    public PokemonHomeResponse getHome(String loginId) {
        Long viewerId = memberResolver.optionalMemberId(loginId);
        List<PokemonRounds> rounds = roundRepository.findAllByOrderByCreatedAtDesc();

        return new PokemonHomeResponse(
                toRoundResponses(rounds, viewerId),
                hostRepository.findById(viewerId == null ? -1L : viewerId)
                        .map(PokemonHosts::getHandle).orElse(null),
                viewerId != null,
                exchangeRateService.supportedCurrencies());
    }

    @Override
    @Transactional(readOnly = true)
    public PokemonHostResponse getHost(String loginId, String handle) {
        PokemonHosts host = hostRepository.findByHandle(normalizedHandle(handle))
                .orElseThrow(PokemonErrorCode.ROUND_NOT_FOUND::asException);
        Long viewerId = memberResolver.optionalMemberId(loginId);

        return new PokemonHostResponse(
                host.getHandle(),
                memberName(host.getMemberId()),
                host.getMemberId().equals(viewerId),
                toRoundResponses(
                        roundRepository.findAllByHostMemberIdOrderBySequenceDesc(host.getMemberId()),
                        viewerId));
    }

    @Override
    @Transactional
    public PokemonHostResponse claimHandle(String loginId, PokemonHandleRequest request) {
        Member member = memberResolver.optionalMember(loginId)
                .orElseThrow(PokemonErrorCode.LOGIN_REQUIRED::asException);

        String handle = normalizedHandle(request.handle());
        if (!HANDLE.matcher(handle).matches()) {
            throw PokemonErrorCode.INVALID_HANDLE.asException();
        }
        // 이미 정했으면 그대로 돌려준다 — 바꾸면 이전 주소가 죽으므로 갈아치우지 않는다.
        Optional<PokemonHosts> mine = hostRepository.findById(member.getId());
        if (mine.isPresent()) {
            return getHost(loginId, mine.get().getHandle());
        }
        if (hostRepository.existsByHandle(handle)) {
            throw PokemonErrorCode.HANDLE_TAKEN.asException();
        }

        hostRepository.save(PokemonHosts.claim(member.getId(), handle));
        return getHost(loginId, handle);
    }

    /* ===== 차수 ===== */

    @Override
    @Transactional
    public PokemonRoundResponse openRound(String loginId, PostPokemonRoundRequest request) {
        Member member = memberResolver.optionalMember(loginId)
                .orElseThrow(PokemonErrorCode.LOGIN_REQUIRED::asException);
        PokemonHosts host = hostRepository.findById(member.getId())
                .orElseThrow(PokemonErrorCode.HOST_REQUIRED::asException);

        String currency = request.currency().toUpperCase(Locale.ROOT);
        ExchangeRate live = exchangeRateService.current(currency);
        PokemonRateMode rateMode = rateMode(request.rateMode());
        // 환율을 적어 내지 않으면 지금 환율을 그대로 쓴다.
        BigDecimal quotedRate = request.quotedRate() == null ? live.toKrw() : request.quotedRate();

        int nextSequence = roundRepository.findFirstByHostMemberIdOrderBySequenceDesc(member.getId())
                .map(PokemonRounds::getSequence).orElse(0) + 1;

        PokemonRounds saved = roundRepository.save(PokemonRounds.open(
                member.getId(), nextSequence, trimmedOrNull(request.title()), currency,
                rateMode, quotedRate, live.fetchedAt(), request.bankAccount().trim(),
                request.deadline(), trimmedOrNull(request.memo())));

        return PokemonRoundResponse.of(saved, host.getHandle(), member.getNickname(), true, 0, 0);
    }

    @Override
    @Transactional(readOnly = true)
    public PokemonRoundBoardResponse getRoundBoard(String loginId, String handle, int sequence) {
        PokemonHosts host = hostRepository.findByHandle(normalizedHandle(handle))
                .orElseThrow(PokemonErrorCode.ROUND_NOT_FOUND::asException);
        PokemonRounds round = roundRepository
                .findByHostMemberIdAndSequence(host.getMemberId(), sequence)
                .orElseThrow(PokemonErrorCode.ROUND_NOT_FOUND::asException);

        Long viewerId = memberResolver.optionalMemberId(loginId);
        List<PokemonOrders> orders = orderRepository.findAllByRoundIdOrderByCreatedAtDesc(round.getId());
        List<Long> orderIds = orders.stream().map(PokemonOrders::getId).toList();
        Map<Long, List<PokemonOrderItemResponse>> itemsByOrder = loadItems(orderIds);
        Map<Long, List<PokemonCommentResponse>> commentsByOrder = loadComments(orderIds, viewerId);

        return new PokemonRoundBoardResponse(
                PokemonRoundResponse.of(round, host.getHandle(), memberName(host.getMemberId()),
                        canManage(round, viewerId), activeCount(orders), transferTotal(orders)),
                ExchangeRateResponse.from(exchangeRateService.current(round.getCurrency())),
                productRepository
                        .findAllByHostMemberIdAndCurrencyAndActiveTrueOrderBySortOrderAsc(
                                host.getMemberId(), round.getCurrency())
                        .stream().map(PokemonProductResponse::from).toList(),
                orders.stream()
                        .map(order -> PokemonOrderResponse.of(
                                order,
                                itemsByOrder.getOrDefault(order.getId(), List.of()),
                                commentsByOrder.getOrDefault(order.getId(), List.of()),
                                viewerId))
                        .toList());
    }

    @Override
    @Transactional
    public PokemonRoundResponse updateRound(String loginId, Long roundId, PutPokemonRoundRequest request) {
        PokemonRounds round = requireManagedRound(loginId, roundId);

        BigDecimal quotedRate = request.quotedRate() == null ? round.getQuotedRate() : request.quotedRate();
        LocalDateTime quotedAt = quotedRate.compareTo(round.getQuotedRate()) == 0
                ? round.getQuotedAt() : LocalDateTime.now();

        round.update(trimmedOrNull(request.title()), request.bankAccount().trim(), request.deadline(),
                trimmedOrNull(request.memo()), rateMode(request.rateMode()), quotedRate, quotedAt);
        return describe(round, memberResolver.optionalMemberId(loginId));
    }

    @Override
    @Transactional
    public PokemonRoundResponse changeRoundStatus(String loginId, Long roundId,
                                                  PatchPokemonRoundStatusRequest request) {
        PokemonRounds round = requireManagedRound(loginId, roundId);
        // 결제 완료로 넘길 때 그 순간의 환율이 필요하다 — 다른 전이에서는 쓰이지 않는다.
        ExchangeRate live = exchangeRateService.current(round.getCurrency());
        round.changeStatus(request.status(), live.toKrw(), LocalDateTime.now());
        return describe(round, memberResolver.optionalMemberId(loginId));
    }

    @Override
    @Transactional
    public void deleteRound(String loginId, Long roundId) {
        PokemonRounds round = requireManagedRound(loginId, roundId);
        // 신청서가 하나라도 있으면 지우지 않는다 — 남의 신청을 주최자가 통째로 날릴 수는 없다.
        if (!orderRepository.findAllByRoundIdOrderByCreatedAtDesc(roundId).isEmpty()) {
            throw PokemonErrorCode.ROUND_HAS_ORDERS.asException();
        }
        roundRepository.delete(round);
    }

    /* ===== 신청서 ===== */

    @Override
    @Transactional(readOnly = true)
    public PokemonOrderDetailResponse getOrder(String loginId, Long orderId) {
        PokemonOrders order = orderRepository.findById(orderId)
                .orElseThrow(PokemonErrorCode.ORDER_NOT_FOUND::asException);
        PokemonRounds round = requireRound(order.getRoundId());
        Long viewerId = memberResolver.optionalMemberId(loginId);

        return new PokemonOrderDetailResponse(
                ExchangeRateResponse.from(exchangeRateService.current(round.getCurrency())),
                describe(round, viewerId),
                toResponse(order, viewerId),
                canManage(round, viewerId));
    }

    @Override
    @Transactional
    public PokemonOrderResponse createOrder(String loginId, Long roundId, PostPokemonOrderRequest request) {
        PokemonRounds round = requireRound(roundId);
        if (!round.getStatus().acceptsOrders()) {
            throw PokemonErrorCode.ROUND_CLOSED.asException();
        }

        BigDecimal extraAmount = request.extraInr() == null ? BigDecimal.ZERO : request.extraInr();
        List<PokemonOrderItemRequest> items = validatedItems(request.items(), extraAmount);
        Member member = memberResolver.optionalMember(loginId).orElse(null);
        String applicantName = resolveName(member, request.applicantName());

        Map<Long, PokemonProducts> products = loadProducts(items, round);
        BigDecimal totalAmount = items.stream()
                .map(item -> products.get(item.productId()).getPrice()
                        .multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(extraAmount, BigDecimal::add);

        // 차수가 정한 방식대로 환율을 고른다 — FIXED 면 차수 환율, PER_ORDER 면 지금 환율.
        ExchangeRate live = exchangeRateService.current(round.getCurrency());
        BigDecimal rate = round.rateFor(live.toKrw());
        long itemsKrw = toKrw(totalAmount, rate);

        PokemonOrders saved = orderRepository.save(PokemonOrders.create(
                round.getId(),
                member == null ? null : member.getId(),
                applicantName,
                trimmedOrNull(request.depositorName()),
                totalAmount,
                extraAmount,
                rate,
                live.fetchedAt(),
                itemsKrw,
                request.donationKrw(),
                trimmedOrNull(request.memo())));

        List<PokemonOrderItems> savedItems = itemRepository.saveAll(items.stream()
                .map(item -> PokemonOrderItems.create(saved.getId(), products.get(item.productId()),
                        item.quantity()))
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
        PokemonRounds round = requireRound(order.getRoundId());
        assertCanModify(loginId, order, round);

        BigDecimal extraAmount = request.extraInr() == null ? BigDecimal.ZERO : request.extraInr();
        List<PokemonOrderItemRequest> items = validatedItems(request.items(), extraAmount);
        Map<Long, PokemonProducts> products = loadProducts(items, round);

        BigDecimal totalAmount = items.stream()
                .map(item -> products.get(item.productId()).getPrice()
                        .multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(extraAmount, BigDecimal::add);

        ExchangeRate live = exchangeRateService.current(round.getCurrency());
        BigDecimal rate = round.rateFor(live.toKrw());

        String applicantName = order.getMemberId() == null
                ? resolveName(null, request.applicantName())
                : order.getApplicantName();

        order.reprice(applicantName, trimmedOrNull(request.depositorName()), totalAmount, extraAmount,
                rate, live.fetchedAt(), toKrw(totalAmount, rate), request.donationKrw(),
                trimmedOrNull(request.memo()));

        // 항목은 전체 교체다. 수량만 바뀐 줄을 가려내는 것보다 지우고 다시 넣는 편이 단순하다.
        itemRepository.deleteAllByOrderId(orderId);
        itemRepository.flush();
        itemRepository.saveAll(items.stream()
                .map(item -> PokemonOrderItems.create(orderId, products.get(item.productId()),
                        item.quantity()))
                .toList());

        return toResponse(order, memberResolver.optionalMemberId(loginId));
    }

    @Override
    @Transactional
    public PokemonOrderResponse changeStatus(String loginId, Long orderId,
                                             PatchPokemonOrderStatusRequest request) {
        PokemonOrders order = orderRepository.findById(orderId)
                .orElseThrow(PokemonErrorCode.ORDER_NOT_FOUND::asException);
        requireHostOfRound(loginId, requireRound(order.getRoundId()));

        order.changeStatus(request.status());
        return toResponse(order, memberResolver.optionalMemberId(loginId));
    }

    @Override
    @Transactional
    public void deleteOrder(String loginId, Long orderId) {
        PokemonOrders order = orderRepository.findById(orderId)
                .orElseThrow(PokemonErrorCode.ORDER_NOT_FOUND::asException);
        assertCanModify(loginId, order, requireRound(order.getRoundId()));

        // DB 에 ON DELETE CASCADE 가 걸려 있지만 JPA 는 그것을 모른다 — 자식을 먼저 지운다.
        commentRepository.deleteAllByOrderId(orderId);
        itemRepository.deleteAllByOrderId(orderId);
        orderRepository.delete(order);
    }

    /* ===== 댓글 ===== */

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
                orderId, member == null ? null : member.getId(),
                resolveName(member, request.authorName()), content));

        return PokemonCommentResponse.of(saved, saved.getMemberId());
    }

    @Override
    @Transactional
    public void deleteComment(String loginId, Long commentId) {
        PokemonOrderComments comment = commentRepository.findById(commentId)
                .orElseThrow(PokemonErrorCode.COMMENT_NOT_FOUND::asException);

        Long viewerId = memberResolver.optionalMemberId(loginId);
        boolean host = orderRepository.findById(comment.getOrderId())
                .map(order -> canManage(requireRound(order.getRoundId()), viewerId))
                .orElse(false);
        if (!host && !comment.isWrittenBy(viewerId)) {
            throw PokemonErrorCode.NOT_YOURS.asException();
        }
        commentRepository.delete(comment);
    }

    /* ===== 상품 관리 ===== */

    @Override
    @Transactional(readOnly = true)
    public List<PokemonManagedProductResponse> getManagedProducts(String loginId) {
        Long hostId = requireHostMemberId(loginId);
        Set<Long> used = Set.copyOf(itemRepository.findUsedProductIds());

        return productRepository.findAllByHostMemberIdOrderBySortOrderAsc(hostId).stream()
                .map(product -> PokemonManagedProductResponse.of(product, used.contains(product.getId())))
                .toList();
    }

    @Override
    @Transactional
    public PokemonManagedProductResponse createProduct(String loginId, PokemonProductRequest request) {
        Long hostId = requireHostMemberId(loginId);
        String name = requireName(request.name());
        String currency = requireCurrency(request.currency());

        if (productRepository.existsByHostMemberIdAndNameAndCurrency(hostId, name, currency)) {
            throw PokemonErrorCode.DUPLICATE_PRODUCT_NAME.asException();
        }

        int nextOrder = productRepository.findAllByHostMemberIdOrderBySortOrderAsc(hostId).stream()
                .mapToInt(PokemonProducts::getSortOrder).max().orElse(0) + 1;

        PokemonProducts saved = productRepository.save(PokemonProducts.create(
                hostId, name, currency, request.price(), request.coins(), nextOrder, LocalDateTime.now()));
        return PokemonManagedProductResponse.of(saved, false);
    }

    @Override
    @Transactional
    public PokemonManagedProductResponse updateProduct(String loginId, Long productId,
                                                       PokemonProductRequest request) {
        PokemonProducts product = requireOwnProduct(loginId, productId);
        String name = requireName(request.name());
        String currency = requireCurrency(request.currency());

        boolean renamed = !name.equals(product.getName()) || !currency.equals(product.getCurrency());
        if (renamed && productRepository.existsByHostMemberIdAndNameAndCurrency(
                product.getHostMemberId(), name, currency)) {
            throw PokemonErrorCode.DUPLICATE_PRODUCT_NAME.asException();
        }

        // 가격을 고쳐도 과거 신청서는 움직이지 않는다 — 항목이 당시 단가를 스냅샷으로 들고 있다.
        product.update(name, currency, request.price(), request.coins(), request.active(),
                LocalDateTime.now());
        return PokemonManagedProductResponse.of(
                product, itemRepository.findUsedProductIds().contains(productId));
    }

    @Override
    @Transactional
    public void deleteProduct(String loginId, Long productId) {
        PokemonProducts product = requireOwnProduct(loginId, productId);
        // 신청서가 가리키는 상품은 지우지 않는다. 내려두면 신청 화면에서만 사라진다.
        if (itemRepository.findUsedProductIds().contains(productId)) {
            throw PokemonErrorCode.PRODUCT_IN_USE.asException();
        }
        productRepository.delete(product);
    }

    /* ===== 공통 ===== */

    /** 주최자이거나 전역 운영자. 차수를 주무를 수 있는 사람이다. */
    private boolean canManage(PokemonRounds round, Long viewerId) {
        return round.isHostedBy(viewerId) || properties.isManager(viewerId);
    }

    private PokemonRounds requireRound(Long roundId) {
        return roundRepository.findById(roundId)
                .orElseThrow(PokemonErrorCode.ROUND_NOT_FOUND::asException);
    }

    private PokemonRounds requireManagedRound(String loginId, Long roundId) {
        PokemonRounds round = requireRound(roundId);
        requireHostOfRound(loginId, round);
        return round;
    }

    private void requireHostOfRound(String loginId, PokemonRounds round) {
        if (!canManage(round, memberResolver.optionalMemberId(loginId))) {
            throw PokemonErrorCode.NOT_THE_HOST.asException();
        }
    }

    /**
     * 신청서 수정·삭제 규칙. 주최자는 언제든, 회원이 낸 것은 그 회원만, 비회원이 낸 것은
     * 누구나(본인 확인 수단이 없는데 막아 두면 잘못 낸 신청서를 아무도 거두지 못한다).
     * 단 입금이 확인된 뒤로는 주최자만이다.
     */
    private void assertCanModify(String loginId, PokemonOrders order, PokemonRounds round) {
        Long viewerId = memberResolver.optionalMemberId(loginId);
        if (canManage(round, viewerId)) {
            return;
        }
        if (order.hasOwner() && !order.isOwnedBy(viewerId)) {
            throw PokemonErrorCode.NOT_YOURS.asException();
        }
        if (order.getStatus() != PokemonOrderStatus.ORDERED) {
            throw PokemonErrorCode.ALREADY_SETTLED.asException();
        }
    }

    private Long requireHostMemberId(String loginId) {
        Member member = memberResolver.optionalMember(loginId)
                .orElseThrow(PokemonErrorCode.LOGIN_REQUIRED::asException);
        return hostRepository.findById(member.getId())
                .map(PokemonHosts::getMemberId)
                .orElseThrow(PokemonErrorCode.HOST_REQUIRED::asException);
    }

    private PokemonProducts requireOwnProduct(String loginId, Long productId) {
        PokemonProducts product = productRepository.findById(productId)
                .orElseThrow(PokemonErrorCode.PRODUCT_NOT_FOUND::asException);
        Long viewerId = memberResolver.optionalMemberId(loginId);
        // 전역 운영자는 남의 상품표도 손볼 수 있다 — 잘못 등록된 것을 대신 고쳐 줘야 한다.
        if (!product.getHostMemberId().equals(viewerId) && !properties.isManager(viewerId)) {
            throw PokemonErrorCode.NOT_THE_HOST.asException();
        }
        return product;
    }

    private PokemonRoundResponse describe(PokemonRounds round, Long viewerId) {
        List<PokemonOrders> orders = orderRepository.findAllByRoundIdOrderByCreatedAtDesc(round.getId());
        return PokemonRoundResponse.of(round, handleOf(round.getHostMemberId()),
                memberName(round.getHostMemberId()), canManage(round, viewerId),
                activeCount(orders), transferTotal(orders));
    }

    /** 차수 목록에 주최자·건수·금액을 붙인다 — 차수마다 조회하면 N+1 이라 한 번에 모아 온다. */
    private List<PokemonRoundResponse> toRoundResponses(List<PokemonRounds> rounds, Long viewerId) {
        if (rounds.isEmpty()) {
            return List.of();
        }
        List<Long> hostIds = rounds.stream().map(PokemonRounds::getHostMemberId).distinct().toList();
        Map<Long, String> handles = hostRepository.findAllByMemberIdIn(hostIds).stream()
                .collect(Collectors.toMap(PokemonHosts::getMemberId, PokemonHosts::getHandle));
        Map<Long, String> names = memberRepository.findAllById(hostIds).stream()
                .collect(Collectors.toMap(Member::getId, Member::getNickname));
        Map<Long, List<PokemonOrders>> ordersByRound =
                orderRepository.findAllByRoundIdIn(rounds.stream().map(PokemonRounds::getId).toList())
                        .stream().collect(Collectors.groupingBy(PokemonOrders::getRoundId));

        return rounds.stream()
                .map(round -> {
                    List<PokemonOrders> orders = ordersByRound.getOrDefault(round.getId(), List.of());
                    return PokemonRoundResponse.of(round, handles.get(round.getHostMemberId()),
                            names.get(round.getHostMemberId()), canManage(round, viewerId),
                            activeCount(orders), transferTotal(orders));
                })
                .toList();
    }

    private static int activeCount(List<PokemonOrders> orders) {
        return (int) orders.stream().filter(o -> o.getStatus().countsTowardTotals()).count();
    }

    private static long transferTotal(List<PokemonOrders> orders) {
        return orders.stream().filter(o -> o.getStatus().countsTowardTotals())
                .mapToLong(PokemonOrders::getTransferKrw).sum();
    }

    private String handleOf(Long memberId) {
        return hostRepository.findById(memberId).map(PokemonHosts::getHandle).orElse(null);
    }

    private String memberName(Long memberId) {
        return memberRepository.findById(memberId).map(Member::getNickname).orElse("알 수 없음");
    }

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

    private Map<Long, List<PokemonOrderItemResponse>> loadItems(List<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return itemRepository.findAllByOrderIdIn(orderIds).stream()
                .collect(Collectors.groupingBy(
                        PokemonOrderItems::getOrderId,
                        Collectors.mapping(PokemonOrderItemResponse::from, Collectors.toList())));
    }

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

    private List<PokemonOrderItemRequest> validatedItems(List<PokemonOrderItemRequest> items,
                                                         BigDecimal extraAmount) {
        if (items == null || items.isEmpty()) {
            if (extraAmount.signum() > 0) {
                return List.of();
            }
            throw PokemonErrorCode.EMPTY_ORDER.asException();
        }
        Set<Long> seen = items.stream().map(PokemonOrderItemRequest::productId)
                .collect(Collectors.toSet());
        if (seen.size() != items.size()) {
            throw PokemonErrorCode.DUPLICATE_PRODUCT.asException();
        }
        return items;
    }

    /** 그 차수 주최자의, 그 통화의, 살아 있는 상품만 담을 수 있다. */
    private Map<Long, PokemonProducts> loadProducts(List<PokemonOrderItemRequest> items,
                                                    PokemonRounds round) {
        if (items.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = items.stream().map(PokemonOrderItemRequest::productId).toList();
        Map<Long, PokemonProducts> found = productRepository.findAllByIdIn(ids).stream()
                .filter(PokemonProducts::isActive)
                .filter(product -> product.getHostMemberId().equals(round.getHostMemberId()))
                .filter(product -> product.getCurrency().equals(round.getCurrency()))
                .collect(Collectors.toMap(PokemonProducts::getId, product -> product));
        if (found.size() != ids.size()) {
            throw PokemonErrorCode.PRODUCT_NOT_FOUND.asException();
        }
        return found;
    }

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

    private static long toKrw(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private static PokemonRateMode rateMode(String raw) {
        try {
            return PokemonRateMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw PokemonErrorCode.BAD_REQUEST.asException();
        }
    }

    private String requireCurrency(String raw) {
        String currency = raw.trim().toUpperCase(Locale.ROOT);
        if (!exchangeRateService.supportedCurrencies().contains(currency)) {
            throw PokemonErrorCode.UNSUPPORTED_CURRENCY.asException();
        }
        return currency;
    }

    private static String requireName(String raw) {
        String name = trimmedOrNull(raw);
        if (name == null) {
            throw PokemonErrorCode.BAD_REQUEST.asException();
        }
        return name;
    }

    private static String normalizedHandle(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private static String trimmedOrNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
