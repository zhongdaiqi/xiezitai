package cn.xiezitai.repository;

import cn.xiezitai.entity.Comment;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    /** open-in-view=false，访问 comment.article 前必须把关联一起取出来 */
    @EntityGraph(attributePaths = "article")
    @Query("select c from Comment c where c.id = :id")
    Optional<Comment> findByIdWithArticle(@Param("id") Long id);

    @EntityGraph(attributePaths = "article")
    List<Comment> findByArticleIdAndStatusOrderByCreatedAtDesc(Long articleId, String status);

    /** 树形组装用：一级评论按时间正序（回复按时间正序挂在根下面） */
    @EntityGraph(attributePaths = "article")
    List<Comment> findByArticleIdAndStatusOrderByCreatedAtAsc(Long articleId, String status);

    @EntityGraph(attributePaths = "article")
    List<Comment> findAllByOrderByCreatedAtDesc();

    /** 级联删除子回复 */
    List<Comment> findByParentId(Long parentId);

    /** 注销账号：按作者名找该用户的全部评论（评论实体只存名字快照，不关联 User） */
    List<Comment> findByAuthorName(String authorName);
}
