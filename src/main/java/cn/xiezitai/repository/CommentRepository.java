package cn.xiezitai.repository;

import cn.xiezitai.entity.Comment;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {
    @EntityGraph(attributePaths = "article")
    List<Comment> findByArticleIdAndStatusOrderByCreatedAtDesc(Long articleId, String status);

    @EntityGraph(attributePaths = "article")
    List<Comment> findAllByOrderByCreatedAtDesc();
}
