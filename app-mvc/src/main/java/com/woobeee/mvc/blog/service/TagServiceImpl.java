package com.woobeee.mvc.blog.service;

import com.woobeee.mvc.blog.api.response.GetTagResponse;
import com.woobeee.mvc.blog.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class TagServiceImpl implements TagService {
    private final TagRepository tagRepository;

    @Override
    public List<GetTagResponse> getPopular(int limit) {
        List<GetTagResponse> out = new ArrayList<>();
        for (TagRepository.TagCount row : tagRepository.findPopular(Math.max(1, limit))) {
            out.add(new GetTagResponse(row.getId(), row.getName(), row.getCnt()));
        }
        return out;
    }
}
