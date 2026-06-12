package com.nifty.analysis.repository;

import com.nifty.analysis.entity.LearningArticle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LearningArticleRepository extends JpaRepository<LearningArticle, Long> {

    Optional<LearningArticle> findBySlug(String slug);

    List<LearningArticle> findByCategory(String category);
}
