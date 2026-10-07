package cn.xiezitai.repository;

import cn.xiezitai.entity.FileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface FileRepository extends JpaRepository<FileEntity, Long> {
    Optional<FileEntity> findByStoredName(String storedName);
    List<FileEntity> findAllByOrderByCreatedAtDesc();
    List<FileEntity> findByScanStatus(String scanStatus);
    /** 内容去重：同一字节流只落一份盘，后存的引用先存的（WP 导入重复媒体复用） */
    Optional<FileEntity> findFirstBySha256OrderByIdAsc(String sha256);
}
