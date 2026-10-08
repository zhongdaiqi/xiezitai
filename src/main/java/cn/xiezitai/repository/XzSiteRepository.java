package cn.xiezitai.repository;

import cn.xiezitai.entity.XzSite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface XzSiteRepository extends JpaRepository<XzSite, Long> {

    Optional<XzSite> findByApiUrlAndUsername(String apiUrl, String username);

    boolean existsByApiUrlAndUsername(String apiUrl, String username);
}
