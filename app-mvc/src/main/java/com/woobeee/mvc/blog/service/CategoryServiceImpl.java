package com.woobeee.mvc.blog.service;

import com.woobeee.mvc.blog.api.request.PostCategoryRequest;
import com.woobeee.mvc.blog.api.response.GetCategoryResponse;
import com.woobeee.mvc.blog.entity.Categories;
import com.woobeee.mvc.blog.repository.CategoryRepository;
import com.woobeee.mvc.blog.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringUtils.defaultIfBlank;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class CategoryServiceImpl implements CategoryService {
    private final CategoryRepository categoryRepository;
    private final PostRepository postRepository;

    /**
     * 클래스의 {@code @Transactional(readOnly = true)} 를 반드시 덮어야 한다. 덮지 않으면
     * Spring 이 커넥션에 {@code SET TRANSACTION READ ONLY} 를 걸고 PostgreSQL 이
     * {@code cannot execute INSERT in a read-only transaction} 으로 거부한다 — 화면에서는
     * "카테고리가 안 만들어진다" 로만 보인다. BLOG-AC-25.
     */
    @Override
    @Transactional
    public void saveCategory(PostCategoryRequest request, Long parentId) {
        categoryRepository.save(
                new Categories (
                        request.nameKo(),
                        request.nameEn(),
                        parentId)
        );
    }

    /** 삭제도 쓰기다 — {@link #saveCategory} 와 같은 이유로 읽기 전용을 덮는다. BLOG-AC-25. */
    @Override
    @Transactional
    public void deleteCategory(Long categoryId) {
        List<Long> ids = new ArrayList<>();
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(categoryId);

        while (!stack.isEmpty()) {
            Long id = stack.pop();
            ids.add(id);
            for (Categories child : categoryRepository.findAllByParentId(id)) {
                stack.push(child.getId());
            }
        }

        postRepository.deleteAllByCategoryIdIn(ids);
        categoryRepository.deleteAllByIdInBatch(ids);
    }

    @Override
    public List<GetCategoryResponse> getCategoryList(String locale) {
        List<Categories> categories = categoryRepository.findAll();
        if (categories.isEmpty()) return List.of();

        boolean isKo = locale != null && locale.toLowerCase().startsWith("ko");

        Function<Categories, String> nameSelector = c -> isKo
                ? defaultIfBlank(c.getNameKo(), c.getNameEn())
                : defaultIfBlank(c.getNameEn(), c.getNameKo());

        // id 목록
        List<Long> ids = categories.stream().map(Categories::getId).toList();

        // 각 카테고리에 "직접" 달린 글 수
        Map<Long, Integer> directCountMap = postRepository
                .countGroupByCategoryId(ids).stream()
                .collect(Collectors.toMap(
                        PostRepository.CategoryCount::getCategoryId,
                        c -> (int) c.getCnt()));

        // 빠른 조회용 맵
        Map<Long, Categories> byId = categories.stream()
                .collect(Collectors.toMap(Categories::getId, Function.identity()));

        // parentId -> childrenIds 인접 리스트
        Map<Long, List<Long>> childrenByParent = new HashMap<>();
        List<Long> roots = new ArrayList<>();

        for (Categories c : categories) {
            Long pid = c.getParentId();
            if (pid == null) {
                roots.add(c.getId());
            } else {
                childrenByParent
                        .computeIfAbsent(pid, k -> new ArrayList<>())
                        .add(c.getId());
            }
        }

        // 재귀 빌드 (불변 객체 생성)
        List<GetCategoryResponse> result = roots.stream()
                .map(rootId ->
                        buildCategoryTree(rootId, byId, childrenByParent, directCountMap, nameSelector))
                .toList();

        return result;
    }

    private GetCategoryResponse buildCategoryTree(
            Long id,
            Map<Long, Categories> byId,
            Map<Long, List<Long>> childrenByParent,
            Map<Long, Integer> directCountMap,
            Function<Categories, String> nameSelector
    ) {
        Categories me = byId.get(id);
        if (me == null) {
            // 데이터 불일치(부모는 있는데 자식이 없는 등) 방어
            return new GetCategoryResponse(id, null, 0, List.of());
        }

        List<Long> childIds = childrenByParent.getOrDefault(id, List.of());

        List<GetCategoryResponse> children = childIds.stream()
                .map(childId ->
                        buildCategoryTree(childId, byId, childrenByParent, directCountMap, nameSelector))
                .toList();

        int direct = directCountMap.getOrDefault(id, 0);
        int childrenSum = children.stream().mapToInt(GetCategoryResponse::count).sum();
        int total = direct + childrenSum;

        // children는 이미 unmodifiable list(toList()) 성격이라 외부 수정이 어려움(불변 설계에 유리)
        return new GetCategoryResponse(
                me.getId(),
                nameSelector.apply(me),
                total,
                children
        );
    }

//    @Override
//    public List<GetCategoryResponse> getCategoryList(String locale) {
//        List<Categories> categories = categoryRepository.findAll();
//        if (categories.isEmpty()) return List.of();
//
//        List<Long> ids = categories.stream().map(Categories::getId).toList();
//
//        // 각 카테고리에 "직접" 달린 글 수
//        Map<Long, Integer> directCountMap = postRepository.countGroupByCategoryId(ids).stream()
//                .collect(Collectors.toMap(
//                        PostRepository.CategoryCount::getCategoryId,
//                        c -> (int) c.getCnt()
//                ));
//
//        boolean isKo = locale != null && locale.toLowerCase().startsWith("ko");
//        Function<Categories, String> nameSelector = c -> isKo
//                ? defaultIfBlank(c.getNameKo(), c.getNameEn())
//                : defaultIfBlank(c.getNameEn(), c.getNameKo());
//
//        // 1) DTO 맵 구성 (초기 postCount는 직접 글 수)
//        Map<Long, GetCategoryResponse> dtoMap = new HashMap<>(ids.size() * 2);
//        for (Categories c : categories) {
//            dtoMap.put(
//                    c.getId(),
//                    new GetCategoryResponse(
//                            c.getId(),
//                            nameSelector.apply(c),
//                            directCountMap.getOrDefault(c.getId(), 0),
//                            new ArrayList<>()
//                    )
//            );
//        }
//
//        // 2) 트리 구성
//        List<GetCategoryResponse> roots = new ArrayList<>();
//        for (Categories c : categories) {
//            GetCategoryResponse me = dtoMap.get(c.getId());
//            Long parentId = c.getParentId();
//            if (parentId == null) {
//                roots.add(me);
//            } else {
//                GetCategoryResponse parent = dtoMap.get(parentId);
//                if (parent != null) parent.getChildren().add(me);
//            }
//        }
//
//        // 3) 자식들의 개수를 부모에 누적 (후위 순회)
//        for (GetCategoryResponse root : roots) {
//            aggregateCounts(root);
//        }
//
//        return roots;
//    }
//
//
//    /** 자식들의 postCount를 부모에 합산하여 최종 postCount로 갱신 */
//    private int aggregateCounts(GetCategoryResponse node) {
//        int sum = node.getCount(); // 내 글 수(직접)
//        for (GetCategoryResponse child : node.getChildren()) {
//            sum += aggregateCounts(child); // 자식 서브트리 합
//        }
//        node.setCount(sum);
//        return sum;
//    }
}
