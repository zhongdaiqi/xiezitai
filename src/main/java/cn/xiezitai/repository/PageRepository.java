package cn.xiezitai.repository;

import cn.xiezitai.entity.PageEntity;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PageRepository extends JpaRepository<PageEntity, Long> {
    Optional<PageEntity> findBySlug(String slug);
    /** 前台 slug 多候选匹配用（同 ArticleRepository.findBySlugIgnoreCase） */
    Optional<PageEntity> findBySlugIgnoreCase(String slug);

    /** slug 是唯一索引，页面自己的唯一性判断不能借用「只查文章表」的那版（见 ArticleService.uniqueSlug） */
    boolean existsBySlug(String slug);

    /** 按 sortOrder 升序 + id 升序（sortOrder 相同时按创建先后排，确保排序稳定） */
    List<PageEntity> findAllByOrderBySortOrderAscIdAsc();

    /** 已发布页面按 sortOrder 升序 + id 升序，用于前台导航 */
    List<PageEntity> findByPublishedTrueOrderBySortOrderAscIdAsc();
}
