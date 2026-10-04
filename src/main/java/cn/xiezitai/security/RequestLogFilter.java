package cn.xiezitai.security;

import cn.xiezitai.entity.RequestLog;
import cn.xiezitai.repository.RequestLogRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 记录所有请求日志（入库，供安全分析与 AI 风险识别）。由 SecurityConfig 显式装配 */
public class RequestLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

    private final RequestLogRepository repo;

    public RequestLogFilter(RequestLogRepository repo) {
        this.repo = repo;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } finally {
            try {
                RequestLog rl = new RequestLog();
                rl.setMethod(request.getMethod());
                rl.setUri(truncate(request.getRequestURI(), 200));
                rl.setIp(clientIp(request));
                rl.setUserAgent(truncate(request.getHeader("User-Agent"), 300));
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
                    rl.setUsername(String.valueOf(auth.getPrincipal()));
                }
                rl.setStatus(response.getStatus());
                rl.setDurationMs(System.currentTimeMillis() - start);
                repo.save(rl);
            } catch (Exception e) {
                log.warn("请求日志记录失败: {}", e.getMessage());
            }
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private String clientIp(HttpServletRequest request) {
        String[] headers = {"X-Forwarded-For", "X-Real-IP"};
        for (String h : headers) {
            String v = request.getHeader(h);
            if (v != null && !v.isBlank()) return v.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
