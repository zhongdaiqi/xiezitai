package cn.xiezitai.repository;

import cn.xiezitai.entity.Article;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface ArticleRepository extends JpaRepository<Article, Long> {
    Optional<Article> findBySlug(String slug);
    /** 前台 slug 多候选匹配用：忽略大小写（WP 中文 slug 常以 %xx 小写形态入库，URL 解码后需换形态再查） */
    Optional<Article> findBySlugIgnoreCase(String slug);
    Page<Article> findByStatusOrderByPublishedAtDesc(String status, Pageable pageable);
    Page<Article> findAllByOrderByUpdatedAtDesc(Pageable pageable);
    boolean existsBySlug(String slug);

    /**
     * 后台文章列表：关键词（标题 / 摘要 / 正文 / 标签）+ 状态双条件过滤。
     *
     * <p>两个条件都写成「空串 = 不过滤」而不是「null = 不过滤」—— SQL 里 {@code null = ''}
     * 求值为 unknown、整条 where 直接不成立，会把列表查空。调用方负责把 null 归一成空串。
     *
     * <p>排序由 {@link Pageable} 传入（updatedAt desc, id desc），不写在 JPQL 里：
     * 只按 updatedAt 排的话，同一秒内保存的多篇顺序不稳定，翻页时会出现重复或漏掉。
     */
    @Query("""
            select a from Article a
            where (:kw = ''
                   or lower(a.title) like lower(concat('%', :kw, '%'))
                   or lower(coalesce(a.summary, '')) like lower(concat('%', :kw, '%'))
                   or lower(coalesce(a.content, '')) like lower(concat('%', :kw, '%'))
                   or lower(coalesce(a.tags, '')) like lower(concat('%', :kw, '%')))
              and (:st = '' or a.status = :st)
            """)
    Page<Article> searchAdmin(@Param("kw") String kw, @Param("st") String st, Pageable pageable);
}
