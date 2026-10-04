package cn.xiezitai.repository;

import cn.xiezitai.entity.PageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PageRepository extends JpaRepository<PageEntity, Long> {
    Optional<PageEntity> findBySlug(String slug);
}
