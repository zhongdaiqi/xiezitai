package cn.xiezitai.repository;

import cn.xiezitai.entity.BloggerSite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface BloggerSiteRepository extends JpaRepository<BloggerSite, Long> {

    /** 关联时按 Blogger 博客 id 去重（同一个博客重复授权只刷新令牌，不新增行） */
    Optional<BloggerSite> findByBlogId(String blogId);

    List<BloggerSite> findByGoogleEmail(String googleEmail);

    /**
     * 「解除整个 Google 账号」：删掉该账号下所有博客关联。
     *
     * <p>派生删除方法必须显式 {@code @Transactional} —— Spring Data 只给 CRUD 方法自动开事务，
     * 自己声明的 {@code deleteByXxx} 不会，漏了会在运行时抛 TransactionRequiredException。
     */
    @Transactional
    void deleteByGoogleEmail(String googleEmail);
}
