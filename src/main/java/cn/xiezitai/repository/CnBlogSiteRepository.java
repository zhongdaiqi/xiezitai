package cn.xiezitai.repository;

import cn.xiezitai.entity.CnBlogSite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CnBlogSiteRepository extends JpaRepository<CnBlogSite, Long> {

    Optional<CnBlogSite> findByUrl(String url);

    boolean existsByUrl(String url);
}
