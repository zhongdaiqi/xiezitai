package cn.xiezitai.repository;

import cn.xiezitai.entity.UserBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface UserBlockRepository extends JpaRepository<UserBlock, Long> {

    List<UserBlock> findByBlockerIdOrderByCreatedAtDesc(Long blockerId);

    boolean existsByBlockerIdAndBlockedUsername(Long blockerId, String blockedUsername);

    void deleteByBlockerIdAndBlockedUsername(Long blockerId, String blockedUsername);

    long countByBlockedUsername(String blockedUsername);

    /**
     * 信息流过滤用：只取被屏蔽用户名集合。
     *
     * <p>调用方必须保证 blockerId 非空 —— 未登录时查不到任何屏蔽关系，
     * 也就意味着匿名访客看到的是未过滤的完整公开内容（符合预期：屏蔽只对登录用户生效）。
     */
    @Query("select b.blockedUsername from UserBlock b where b.blockerId = :blockerId")
    Set<String> findBlockedUsernames(@Param("blockerId") Long blockerId);
}
