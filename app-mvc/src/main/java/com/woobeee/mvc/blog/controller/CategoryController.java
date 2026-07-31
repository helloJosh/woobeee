package com.woobeee.mvc.blog.controller;

import com.woobeee.core.api.ApiResponse;
import com.woobeee.mvc.blog.api.request.PostCategoryRequest;
import com.woobeee.mvc.blog.api.response.GetCategoryResponse;
import com.woobeee.mvc.blog.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/back/categories")
@Tag(name = "Category Controller", description = "카테고리 컨트롤러")
@RequiredArgsConstructor
public class CategoryController {
    private final CategoryService categoryService;

    @PostMapping("/{parentId}")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "카테고리 저장 API", description = "부모 카테고리 하위에 새 카테고리를 저장합니다.")
    public ApiResponse<Void> saveCategory (
            @PathVariable(value = "parentId") Long parentId,
            @Valid @RequestBody PostCategoryRequest request
    ) {
        categoryService.saveCategory(request, parentId);
        return ApiResponse.createSuccess("Category created");
    }

    @DeleteMapping("/{categoryId}")
    @Operation(summary = "카테고리 삭제 API", description = "카테고리와 하위 카테고리를 삭제합니다.")
    public ApiResponse<Void> deleteCategory (
            @PathVariable(value = "categoryId") Long categoryId
    ) {
        categoryService.deleteCategory(categoryId);
        return ApiResponse.success("Category deleted");
    }

    @GetMapping
    @Operation(summary = "카테고리 전체 조회 API", description = "카테고리 트리 전체를 조회합니다.")
    public ApiResponse<List<GetCategoryResponse>> getCategoryList(
            @RequestHeader(name = "Accept-Language", defaultValue = "ko-KR") String locale
    ) {
        List<GetCategoryResponse> response = categoryService.getCategoryList(locale);
        return ApiResponse.success(response, "Categories retrieved");
    }
}
