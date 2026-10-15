package cn.xiezitai.security;

import cn.xiezitai.entity.User;
import cn.xiezitai.repository.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** 由 SecurityConfig 显式装配，不加 @Component，避免被 Servlet 容器二次注册 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserRepository users;

    public JwtAuthFilter(JwtUtil jwtUtil, UserRepository users) {
        this.jwtUtil = jwtUtil;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            Claims claims = jwtUtil.parse(header.substring(7));
            if (claims != null) {
                // 签名有效只说明「这枚令牌是我们签的」，不代表账号现在还能用：
                // 注册被驳回、被管理员停用、账号已删除，都要立刻失效 ——
                // 否则驳回/停用前签发的令牌还能一路用到过期（默认 72 小时，记住登录 30 天）。
                User user = users.findByUsername(claims.getSubject()).orElse(null);
                // isBanned()：因违规被封禁的账号（App Store 1.2 的 ejecting the user）。
                // 加在这里而不是只拦登录，是为了让**已签发的令牌立刻失效** ——
                // 否则被封的人手里那张 72 小时有效期的令牌还能继续发帖到自然过期。
                if (user != null && user.isEnabled() && user.isApproved() && !user.isBanned()) {
                    String role = user.getRole() != null ? user.getRole() : claims.get("role", String.class);
                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            user.getUsername(), null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + (role == null ? "USER" : role))));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
        }
        chain.doFilter(request, response);
    }
}
