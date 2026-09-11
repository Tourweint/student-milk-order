package com.milk.order.module.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.milk.order.common.enums.RoleType;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.auth.dto.LoginRequest;
import com.milk.order.module.auth.dto.RegisterRequest;
import com.milk.order.module.auth.dto.WxBindRequest;
import com.milk.order.module.auth.dto.WxLoginRequest;
import com.milk.order.module.auth.service.AuthService;
import com.milk.order.module.auth.vo.LoginResponse;
import com.milk.order.module.auth.vo.WxLoginVO;
import com.milk.order.module.auth.vo.WxSessionVO;
import com.milk.order.module.clazz.entity.Student;
import com.milk.order.module.clazz.mapper.StudentMapper;
import com.milk.order.module.user.entity.SysRole;
import com.milk.order.module.user.entity.SysUser;
import com.milk.order.module.user.entity.SysUserRole;
import com.milk.order.module.user.service.SysRoleService;
import com.milk.order.module.user.service.SysUserRoleService;
import com.milk.order.module.user.service.SysUserService;
import com.milk.order.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * 认证服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final SysUserService sysUserService;
    private final SysRoleService sysRoleService;
    private final SysUserRoleService sysUserRoleService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final StudentMapper studentMapper;

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${wechat.appid:}")
    private String wxAppId;

    @Value("${wechat.secret:}")
    private String wxSecret;

    @Value("${wechat.mock-enabled:true}")
    private boolean wxMockEnabled;

    @Override
    public LoginResponse login(LoginRequest request) {
        // 1. 查询用户
        SysUser user = sysUserService.getByUsername(request.getUsername());
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }

        // 2. 检查账号状态
        if (user.getStatus() != null && user.getStatus() != 1) {
            throw new BusinessException("账号已被禁用，请联系管理员");
        }

        // 3. 查询角色
        List<String> roles = sysUserRoleService.getRoleCodesByUserId(user.getId());

        // 4. 生成 Token
        String token = jwtTokenProvider.generateToken(user.getId(), user.getUsername(), roles);

        // 5. 组装返回
        LoginResponse response = new LoginResponse();
        response.setToken(token);
        response.setUserId(user.getId());
        response.setUsername(user.getUsername());
        response.setRealName(user.getRealName());
        response.setRoles(roles);

        log.info("用户 [{}] 登录成功", user.getUsername());
        return response;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void register(RegisterRequest request) {
        // 1. 校验用户名唯一
        if (sysUserService.isUsernameExists(request.getUsername(), null)) {
            throw new BusinessException("用户名已存在");
        }

        // 2. 校验角色
        String roleCode = request.getRoleCode() != null ? request.getRoleCode() : RoleType.PARENT.getCode();
        SysRole role = getRoleByCode(roleCode);
        if (role == null) {
            throw new BusinessException("无效的角色类型");
        }

        // 3. 创建用户
        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRealName(request.getRealName());
        user.setPhone(request.getPhone());
        user.setStatus(1);
        if (RoleType.PARENT.getCode().equals(roleCode)) {
            user.setStudentId(request.getStudentId());
        } else if (RoleType.TEACHER.getCode().equals(roleCode)) {
            user.setClassId(request.getClassId());
        }
        sysUserService.save(user);

        // 4. 分配角色
        SysUserRole userRole = new SysUserRole();
        userRole.setUserId(user.getId());
        userRole.setRoleId(role.getId());
        sysUserRoleService.save(userRole);

        log.info("用户 [{}] 注册成功，角色 [{}]", user.getUsername(), roleCode);
    }

    @Override
    public LoginResponse getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new BusinessException(401, "未登录或登录已过期");
        }

        String username = authentication.getName();
        SysUser user = sysUserService.getByUsername(username);
        if (user == null) {
            throw new BusinessException(401, "用户不存在");
        }

        List<String> roles = sysUserRoleService.getRoleCodesByUserId(user.getId());

        LoginResponse response = new LoginResponse();
        response.setUserId(user.getId());
        response.setUsername(user.getUsername());
        response.setRealName(user.getRealName());
        response.setRoles(roles);
        // 家长角色：回填绑定的学生信息（下单页/我的页展示用）
        if (roles.contains(RoleType.PARENT.getCode()) && user.getStudentId() != null) {
            response.setStudentId(user.getStudentId());
            Student student = studentMapper.selectById(user.getStudentId());
            if (student != null) {
                response.setStudentName(student.getStudentName());
            }
        }
        return response;
    }

    private SysRole getRoleByCode(String roleCode) {
        return sysRoleService.getOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, roleCode)
                .eq(SysRole::getStatus, 1));
    }

    // ==================== 微信授权登录 ====================

    @Override
    public WxLoginVO wxLogin(WxLoginRequest request) {
        WxSessionVO session = code2session(request.getCode());
        String openid = session.getOpenid();
        SysUser user = sysUserService.getByOpenid(openid);

        WxLoginVO vo = new WxLoginVO();
        vo.setOpenid(openid);
        if (user == null) {
            // 未绑定账号：返回 bound=false，引导前端走 wx-bind
            vo.setBound(false);
            return vo;
        }
        if (user.getStatus() != null && user.getStatus() != 1) {
            throw new BusinessException("账号已被禁用，请联系管理员");
        }
        fillLogin(vo, user);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WxLoginVO wxBind(WxBindRequest request) {
        WxSessionVO session = code2session(request.getCode());
        String openid = session.getOpenid();

        // 已绑定账号：直接登录（幂等）
        SysUser boundUser = sysUserService.getByOpenid(openid);
        if (boundUser != null) {
            if (boundUser.getStatus() != null && boundUser.getStatus() != 1) {
                throw new BusinessException("账号已被禁用，请联系管理员");
            }
            WxLoginVO vo = new WxLoginVO();
            vo.setOpenid(openid);
            fillLogin(vo, boundUser);
            return vo;
        }

        SysUser user;
        if (StringUtils.hasText(request.getUsername())) {
            // 绑定已有账号：校验用户名密码，保留账号原有学生归属
            user = sysUserService.getByUsername(request.getUsername());
            if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
                throw new BusinessException("用户名或密码错误");
            }
            if (user.getStatus() != null && user.getStatus() != 1) {
                throw new BusinessException("账号已被禁用，请联系管理员");
            }
            if (user.getOpenid() != null && !user.getOpenid().equals(openid)) {
                throw new BusinessException("该账号已绑定其他微信");
            }
            user.setOpenid(openid);
            sysUserService.updateById(user);
        } else {
            // 自动创建家长账号
            if (!StringUtils.hasText(request.getRealName())) {
                throw new BusinessException("请填写家长姓名");
            }
            checkStudentExists(request.getStudentId());
            String username = generateWxUsername(openid);
            user = new SysUser();
            user.setUsername(username);
            user.setPassword(passwordEncoder.encode(randomPassword()));
            user.setRealName(request.getRealName());
            user.setPhone(request.getPhone());
            user.setStudentId(request.getStudentId());
            user.setOpenid(openid);
            user.setStatus(1);
            sysUserService.save(user);

            // 分配家长角色
            SysRole role = getRoleByCode(RoleType.PARENT.getCode());
            if (role == null) {
                throw new BusinessException("家长角色未配置");
            }
            SysUserRole userRole = new SysUserRole();
            userRole.setUserId(user.getId());
            userRole.setRoleId(role.getId());
            sysUserRoleService.save(userRole);
            log.info("微信自动创建家长账号 [{}]，绑定学生 [{}]", user.getUsername(), request.getStudentId());
        }

        WxLoginVO vo = new WxLoginVO();
        vo.setOpenid(openid);
        fillLogin(vo, user);
        return vo;
    }

    /**
     * code 换取 openid：开发模式（未配置真实 appid/secret 或开启 mock）按 code 生成模拟 openid
     */
    private WxSessionVO code2session(String code) {
        if (wxMockEnabled || !StringUtils.hasText(wxAppId) || !StringUtils.hasText(wxSecret)) {
            WxSessionVO mock = new WxSessionVO();
            mock.setOpenid("mock_openid_" + code);
            mock.setSessionKey("mock_session_key");
            return mock;
        }
        String url = "https://api.weixin.qq.com/sns/jscode2session"
                + "?appid={appid}&secret={secret}&js_code={js_code}&grant_type=authorization_code";
        WxSessionVO session;
        try {
            session = restTemplate.getForObject(url, WxSessionVO.class, wxAppId, wxSecret, code);
        } catch (Exception e) {
            log.error("调用微信 code2session 失败", e);
            throw new BusinessException("微信登录失败，请稍后重试");
        }
        if (session == null || session.getErrcode() == null || session.getErrcode() != 0) {
            log.warn("微信 code2session 返回异常: {}", session == null ? "null" : session.getErrmsg());
            throw new BusinessException("微信登录凭证无效或已过期");
        }
        return session;
    }

    private void fillLogin(WxLoginVO vo, SysUser user) {
        List<String> roles = sysUserRoleService.getRoleCodesByUserId(user.getId());
        String token = jwtTokenProvider.generateToken(user.getId(), user.getUsername(), roles);
        vo.setBound(true);
        vo.setToken(token);
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setRoles(roles);
    }

    /**
     * 生成微信账号用户名：wx_ + openid 后 8 位，冲突时追加序号
     */
    private String generateWxUsername(String openid) {
        String base = "wx_" + openid.substring(Math.max(0, openid.length() - 8));
        String username = base;
        int suffix = 1;
        while (sysUserService.isUsernameExists(username, null)) {
            username = base + "_" + (suffix++);
        }
        return username;
    }

    private String randomPassword() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private void checkStudentExists(Long studentId) {
        if (studentId == null) {
            throw new BusinessException("请选择关联的学生");
        }
        Student student = studentMapper.selectById(studentId);
        if (student == null) {
            throw new BusinessException("关联的学生不存在");
        }
    }
}
