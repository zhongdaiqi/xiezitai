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

    /** 「我的文章」（App 内作者看自己的全部状态：待审/驳回/已发布） */
    Page<Article> findByAuthorOrderByUpdatedAtDesc(String author, Pageable pageable);

    /** 注销账号：作者名即 username（author 是字符串快照，不关联 User） */
    long countByAuthor(String author);
    java.util.List<Article> findByAuthor(String author);

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

    /**
     * 首页文章流（排除被当前用户屏蔽的作者）—— App Store 准则 1.2 的
     * "remove it from the user's feed instantly" 落点。
     *
     * <p>{@code blocked} 必须是**非空**集合：JPQL 的 {@code not in (:blocked)} 在集合为空时
     * 会被某些数据库（MySQL 5.7 / MariaDB）编译成恒假条件，把整个列表查空。
     * 调用方（ArticleController）已保证空集时走不带过滤的原生方法。
     *
     * <p>{@code author is null} 也要放行：导入的文章（WordPress/博客园）可能没有作者快照，
     * 不能因为屏蔽了某人就把无主文章一起过滤掉。
     */
    @Query("""
            select a from Article a
            where a.status = :st
              and (a.author is null or a.author not in :blocked)
            """)
    Page<Article> findPublishedExcludingAuthors(@Param("st") String st,
                                                @Param("blocked") java.util.Set<String> blocked,
                                                Pageable pageable);

    /**
     * 前台首页搜索：只在**已发布**文章里按关键词（标题 / 摘要 / 正文 / 标签）过滤。
     *
     * <p>与 {@link #searchAdmin} 同一个「空串 = 不过滤」口径；状态是硬编码的
     * {@code PUBLISHED}，草稿即便命中关键词也不会漏到前台。
     *
     * <p>排序不写在 JPQL 里，由调用方通过 {@link Pageable} 传
     * （publishedAt desc, id desc）—— 与首页默认列表同一口径，
     * 同一秒发布的文章顺序才稳定，翻页不会重复或漏。
     */
    @Query("""
            select a from Article a
            where a.status = 'PUBLISHED'
              and (:kw = ''
                   or lower(a.title) like lower(concat('%', :kw, '%'))
                   or lower(coalesce(a.summary, '')) like lower(concat('%', :kw, '%'))
                   or lower(coalesce(a.content, '')) like lower(concat('%', :kw, '%'))
                   or lower(coalesce(a.tags, '')) like lower(concat('%', :kw, '%')))
            """)
    Page<Article> searchPublished(@Param("kw") String kw, Pageable pageable);

    /** 搜索结果同样排除被屏蔽作者（blocked 必须非空，理由见 findPublishedExcludingAuthors） */
    @Query("""
            select a from Article a
            where a.status = 'PUBLISHED'
              and (a.author is null or a.author not in :blocked)
              and (:kw = ''
                   or lower(a.title) like lower(concat('%', :kw, '%'))
                   or lower(coalesce(a.summary, '')) like lower(concat('%', :kw, '%'))
                   or lower(coalesce(a.content, '')) like lower(concat('%', :kw, '%'))
                   or lower(coalesce(a.tags, '')) like lower(concat('%', :kw, '%')))
            """)
    Page<Article> searchPublishedExcluding(@Param("kw") String kw,
                                           @Param("blocked") java.util.Set<String> blocked,
                                           Pageable pageable);
}
