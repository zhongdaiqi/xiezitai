package cn.xiezitai.repository;

import cn.xiezitai.entity.AbuseReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AbuseReportRepository extends JpaRepository<AbuseReport, Long> {

    List<AbuseReport> findByStatusOrderByCreatedAtDesc(String status);

    List<AbuseReport> findAllByOrderByCreatedAtDesc();

    long countByStatus(String status);

    /** 同一举报人对同一对象的重复举报去重（避免刷单把后台灌满） */
    long countByReporterUsernameAndTypeAndTargetIdAndStatus(String reporter, String type, Long targetId, String status);
}
