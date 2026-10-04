package cn.xiezitai.repository;

import cn.xiezitai.entity.FileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface FileRepository extends JpaRepository<FileEntity, Long> {
    Optional<FileEntity> findByStoredName(String storedName);
    List<FileEntity> findAllByOrderByCreatedAtDesc();
    List<FileEntity> findByScanStatus(String scanStatus);
}
