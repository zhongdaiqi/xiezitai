package cn.xiezitai.repository;

import cn.xiezitai.entity.WpSite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WpSiteRepository extends JpaRepository<WpSite, Long> {

    Optional<WpSite> findByUrl(String url);

    boolean existsByUrl(String url);
}
