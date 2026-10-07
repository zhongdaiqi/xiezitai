package cn.xiezitai.security;

import cn.xiezitai.repository.RequestLogRepository;
import cn.xiezitai.repository.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final RequestLogFilter requestLogFilter;

    public SecurityConfig(JwtUtil jwtUtil, RequestLogRepository requestLogRepository, UserRepository userRepository) {
        this.jwtAuthFilter = new JwtAuthFilter(jwtUtil, userRepository);
        this.requestLogFilter = new RequestLogFilter(requestLogRepository);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsSource()))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // 公开：站点页面与公开读接口
                .requestMatchers("/", "/article/**", "/page/**", "/media/**", "/admin.html", "/index.html",
                        "/css/**", "/js/**", "/vendor/**", "/favicon.ico", "/robots.txt", "/sitemap.xml").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/articles/**", "/api/pages/**").permitAll()
                .requestMatchers("/api/auth/login", "/api/auth/register").permitAll()
                // 评论仅限登录用户（禁止匿名）；读接口仍是公开的
                .requestMatchers(HttpMethod.POST, "/api/articles/*/comments").authenticated()
                .requestMatchers("/api/v1/**").permitAll()   // 开放 API / MCP：内部用 token 鉴权
                // 管理端
                .requestMatchers("/api/admin/**", "/api/auth/me", "/api/auth/totp/**", "/api/auth/password").authenticated()
                .anyRequest().permitAll())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(requestLogFilter, JwtAuthFilter.class)
            .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) ->
                    res.sendError(401, "未登录或登录已过期")));
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
