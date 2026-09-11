package com.milk.order.module.system.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.milk.order.module.system.entity.OperationLog;
import com.milk.order.module.system.service.OperationLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 操作日志 AOP 切面
 *
 * 自动拦截所有 Controller 的写操作（POST/PUT/DELETE），记录操作人、请求信息、耗时、状态。
 * GET 请求不记录（避免日志量过大）。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OperationLogAspect {

    private final OperationLogService operationLogService;
    private final ObjectMapper objectMapper;

    /** 只记录写操作 */
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "DELETE");

    @Around("within(@org.springframework.web.bind.annotation.RestController *)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return joinPoint.proceed();
        }
        HttpServletRequest request = attributes.getRequest();
        String httpMethod = request.getMethod();

        // GET 请求不记录
        if (!WRITE_METHODS.contains(httpMethod)) {
            return joinPoint.proceed();
        }

        long start = System.currentTimeMillis();
        OperationLog opLog = new OperationLog();
        try {
            // 基本信息
            opLog.setRequestUrl(request.getRequestURI());
            opLog.setRequestMethod(httpMethod);
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            opLog.setMethod(signature.getDeclaringTypeName() + "." + signature.getName());
            opLog.setOperation(buildOperation(request.getRequestURI(), httpMethod));
            opLog.setIp(getClientIp(request));

            // 请求参数（过滤掉密码等敏感字段）
            Object[] args = joinPoint.getArgs();
            if (args != null && args.length > 0) {
                String params = Arrays.stream(args)
                        .filter(arg -> !(arg instanceof javax.servlet.ServletRequest)
                                && !(arg instanceof javax.servlet.ServletResponse))
                        .map(arg -> {
                            try {
                                return objectMapper.writeValueAsString(arg);
                            } catch (Exception e) {
                                return arg.toString();
                            }
                        })
                        .collect(Collectors.joining(","));
                // 简单脱敏：密码字段替换为 ***
                params = params.replaceAll("\"password\"\\s*:\\s*\"[^\"]*\"", "\"password\":\"***\"");
                opLog.setRequestParams(params.length() > 2000 ? params.substring(0, 2000) : params);
            }

            // 操作用户
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
                opLog.setUsername(auth.getName());
            } else {
                opLog.setUsername("anonymous");
            }

            Object result = joinPoint.proceed();
            opLog.setStatus(1);
            return result;
        } catch (Throwable e) {
            opLog.setStatus(0);
            String errorMsg = e.getMessage();
            opLog.setErrorMsg(errorMsg != null && errorMsg.length() > 2000 ? errorMsg.substring(0, 2000) : errorMsg);
            throw e;
        } finally {
            opLog.setCostTime(System.currentTimeMillis() - start);
            try {
                operationLogService.save(opLog);
            } catch (Exception e) {
                log.error("操作日志保存失败", e);
            }
        }
    }

    /**
     * 根据 URL 和 HTTP 方法生成操作描述
     */
    private String buildOperation(String url, String httpMethod) {
        String action = switch (httpMethod) {
            case "POST" -> "新增";
            case "PUT" -> "修改";
            case "DELETE" -> "删除";
            default -> httpMethod;
        };
        // 从 URL 提取模块名
        String module = url.replace("/api/", "").split("/")[0];
        if (url.contains("/pay/")) action = "支付";
        else if (url.contains("/sign")) action = "签收";
        else if (url.contains("/trigger/")) action = "手动续订";
        else if (url.contains("/login")) action = "登录";
        else if (url.contains("/generate")) action = "生成";
        return action + " - " + module;
    }

    /**
     * 获取客户端真实 IP
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 多级代理取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
