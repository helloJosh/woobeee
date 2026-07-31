package com.woobeee.mvc.blog.repository;

import com.woobeee.mvc.blog.entity.Categories;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CategoryRepository extends JpaRepository<Categories, Long> {
    List<Categories> findAllByParentId(Long parentId);
}
