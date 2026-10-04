package cn.xiezitai.repository;

import cn.xiezitai.entity.RequestLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RequestLogRepository extends JpaRepository<RequestLog, Long> {
}
