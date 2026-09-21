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
                // 微信支付回调（无 JWT 登录态，可信性由验签保证）与模拟微信支付侧接口（仅本地模拟）
                .antMatchers("/api/pay/wechat/notify", "/api/mock/wechat/**").permitAll()
                .antMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // 用户与系统管理：仅管理员
                .antMatchers("/api/user/**", "/api/system/**").hasRole("ADMIN")
                // 奶品、营养信息的写操作仅管理员（小程序家长需要 GET 读取奶品与营养信息）
                .antMatchers(HttpMethod.POST, "/api/product/**", "/api/nutrition/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/product/**", "/api/nutrition/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.DELETE, "/api/product/**", "/api/nutrition/**").hasRole("ADMIN")
                // 班级与学生管理、统计看板：Web 管理端角色
                .antMatchers("/api/clazz/**", "/api/stats/**").hasAnyRole("ADMIN", "TEACHER")
                // 配送任务与签收/拒收：签收/拒收仅管理端角色（家长仅读取配送记录，配送站对签收只读）
                .antMatchers(HttpMethod.POST, "/api/delivery/task/generate").hasAnyRole("ADMIN", "TEACHER")
                .antMatchers(HttpMethod.PUT, "/api/delivery/task/cancel/**").hasAnyRole("ADMIN", "TEACHER")
                // 配送前缺货批量取消（影响当日全部待配送任务与配额回补）：仅管理员
                .antMatchers(HttpMethod.PUT, "/api/delivery/task/stockout-cancel").hasRole("ADMIN")
                // 配送日平移 / 配送日历重排 / 学期末摊平（批量改期与改量，影响面大）：仅管理员
                .antMatchers(HttpMethod.POST, "/api/delivery/task/shift").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/delivery/task/calendar-rebalance").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/delivery/task/rebalance").hasRole("ADMIN")
                // 配送例外（停送日/补课日）维护：仅管理员
                .antMatchers("/api/delivery/exception/**").hasRole("ADMIN")
                // "今日已送出"批量开始配送（退款闸门动作）：管理员与配送站；单条开始配送同权限（班主任不可开始配送，只读+签收）
                .antMatchers(HttpMethod.PUT, "/api/delivery/task/batch-start").hasAnyRole("ADMIN", "DELIVERY")
                .antMatchers(HttpMethod.PUT, "/api/delivery/task/start/**").hasAnyRole("ADMIN", "DELIVERY")
                // 家长端首页查看绑定学生的剩余待配送盒数（数据范围由 Service 层限定为学生本人）
                .antMatchers(HttpMethod.GET, "/api/delivery/task/pending-quantity").hasRole("PARENT")
                // 家长端首页聚合（剩余盒数 + 下次配送日 + 近期拒收）：同样仅家长
                .antMatchers(HttpMethod.GET, "/api/delivery/task/parent-home").hasRole("PARENT")
                // 家长端「当日豁免」：仅家长；数据范围在 Service 层强制为本人绑定学生，
                // 且只允许豁免"今天 + 尚未送出（待配送）"的任务
                .antMatchers(HttpMethod.GET, "/api/delivery/task/parent-exemption").hasRole("PARENT")
                .antMatchers(HttpMethod.POST, "/api/delivery/task/parent-exempt-today").hasRole("PARENT")
                // 奶站「当日未送达申报」：配送站与管理员可申报（只打标记与待办，不改任何任务/订单状态）
                .antMatchers(HttpMethod.POST, "/api/delivery/task/undelivered-report").hasAnyRole("ADMIN", "DELIVERY")
                // 申报跟进（关闭待办）：仅管理员；跟进只写说明，实际处置走既有签收/拒收/取消出口
                .antMatchers(HttpMethod.PUT, "/api/delivery/task/undelivered-report/**").hasRole("ADMIN")
                .antMatchers("/api/delivery/task/**").hasAnyRole("ADMIN", "TEACHER", "DELIVERY")
                .antMatchers(HttpMethod.POST, "/api/delivery/record/**").hasAnyRole("ADMIN", "TEACHER")
                // 完成订单仅管理端角色（订单已无独立"开始配送"入口，配送中由配送任务联动触发）
                .antMatchers(HttpMethod.PUT, "/api/order/complete/**").hasAnyRole("ADMIN", "TEACHER")
                // 订单数据仅学校侧角色与家长可见（配送站按任务配送，不接触订单数据）
                .antMatchers("/api/order/**").hasAnyRole("ADMIN", "TEACHER", "PARENT")
                // 退款域：家长可申请/预览/查看本人退款单；审核、执行与全部列表仅管理员
                // （班主任与配送站不接触退款，见设计方案 R9）
                .antMatchers("/api/refund/list").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/refund/order/**").hasAnyRole("ADMIN", "PARENT")
                .antMatchers(HttpMethod.PUT, "/api/refund/**").hasRole("ADMIN")
                .antMatchers("/api/refund/**").hasAnyRole("ADMIN", "PARENT")
                // 毕业清算（涉资金）：独立命名空间，仅管理员
                // ——刻意不复用 /api/clazz/**（ADMIN+TEACHER），避免班级管理角色顺带获得清算权限
                .antMatchers("/api/student/**").hasRole("ADMIN")
                // 其余接口需认证：奶品/营养 GET 等三端共用，数据范围由 Service 层数据权限控制
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
