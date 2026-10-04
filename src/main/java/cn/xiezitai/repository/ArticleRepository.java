package cn.xiezitai.repository;

import cn.xiezitai.entity.Article;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ArticleRepository extends JpaRepository<Article, Long> {
    Optional<Article> findBySlug(String slug);
    Page<Article> findByStatusOrderByPublishedAtDesc(String status, Pageable pageable);
    Page<Article> findAllByOrderByUpdatedAtDesc(Pageable pageable);
    boolean existsBySlug(String slug);
}
