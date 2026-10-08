package cn.xiezitai.repository;

import cn.xiezitai.entity.DistRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DistRecordRepository extends JpaRepository<DistRecord, Long> {

    List<DistRecord> findByArticleIdOrderByIdAsc(Long articleId);

    List<DistRecord> findByArticleIdIn(Collection<Long> articleIds);

    /** 文章 × 目标 的唯一定位（分发前先查它在不在，决定「更新」还是「新建」） */
    Optional<DistRecord> findByArticleIdAndChannelAndTargetId(Long articleId, String channel, Long targetId);

    long countByArticleId(Long articleId);

    /**
     * 删文章时顺手清掉它的分发记录（留着只会成为悬空行）。
     *
     * <p>派生删除必须先查后删，**必须显式开事务** —— Spring Data 只会给 CRUD 方法自动加事务，
     * 接口上自己声明的 {@code deleteByXxx} 不会，少这一行运行时会抛 TransactionRequiredException。
     */
    @Transactional
    void deleteByArticleId(Long articleId);

    /** 删站点/账号时清掉指向它的记录 */
    @Transactional
    void deleteByChannelAndTargetId(String channel, Long targetId);
}
