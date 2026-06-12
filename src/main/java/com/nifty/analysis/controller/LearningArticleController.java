package com.nifty.analysis.controller;

import com.nifty.analysis.entity.LearningArticle;
import com.nifty.analysis.repository.LearningArticleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/learning")
@RequiredArgsConstructor
@Slf4j
public class LearningArticleController {

    private final LearningArticleRepository learningArticleRepository;

    @GetMapping("/articles")
    public ResponseEntity<List<LearningArticle>> getAllArticles() {
        log.info("REST request to list all educational articles");
        return ResponseEntity.ok(learningArticleRepository.findAll());
    }

    @GetMapping("/articles/{slug}")
    public ResponseEntity<LearningArticle> getArticleBySlug(@PathVariable("slug") String slug) {
        log.info("REST request to fetch learning article: {}", slug);
        return learningArticleRepository.findBySlug(slug)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
