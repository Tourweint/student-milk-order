package com.milk.order.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.milk.order.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
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

/**
 * Spring Security 配置
 */
@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf().disable()
                .cors().configurationSource(corsConfigurationSource())
                .and()
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                .and()
                .authorizeRequests()
                // 公开接口
                .antMatchers("/api/auth/login", "/api/auth/register",
                        "/api/auth/wx-login", "/api/auth/wx-bind",
                        "/api/auth/bind-student/search").permitAll()
                .antMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // 用户与系统管理：仅管理员
                .antMatchers("/api/user/**", "/api/system/**").hasRole("ADMIN")
                // 奶品、营养信息的写操作仅管理员（小程序家长需要 GET 读取奶品与营养信息）
                .antMatchers(HttpMethod.POST, "/api/product/**", "/api/nutrition/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/product/**", "/api/nutrition/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.DELETE, "/api/product/**", "/api/nutrition/**").hasRole("ADMIN")
                // 班级与学生管理、统计看板：Web 管理端角色
                .antMatchers("/api/clazz/**", "/api/stats/**").hasAnyRole("ADMIN", "TEACHER")
                // 配送任务与签收/拒收：仅管理端角色（家长仅读取配送记录）
                .antMatchers("/api/delivery/task/**").hasAnyRole("ADMIN", "TEACHER")
                .antMatchers(HttpMethod.POST, "/api/delivery/record/**").hasAnyRole("ADMIN", "TEACHER")
                // 其余接口需认证：订单、续订等三端共用，数据范围由 Service 层数据权限控制
                .anyRequest().authenticated()
                .and()
                .exceptionHandling()
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    response.setStatus(401);
                    response.getWriter().write(new ObjectMapper()
                            .writeValueAsString(ApiResponse.error(401, "未登录或登录已过期")));
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    response.setStatus(403);
                    response.getWriter().write(new ObjectMapper()
                            .writeValueAsString(ApiResponse.error(403, "权限不足")));
                });

        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
