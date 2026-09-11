package com.milk.order.module.user.vo;

import com.milk.order.module.user.entity.SysUser;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户视图对象（不包含密码等敏感字段）
 */
@Data
public class UserVO implements Serializable {

    private Long id;

    private String username;

    private String realName;

    private String phone;

    private String email;

    /** 账号状态：0-禁用，1-正常 */
    private Integer status;

    /** 角色编码列表 */
    private List<String> roles;

    /** 关联学生 ID */
    private Long studentId;

    /** 关联班级 ID */
    private Long classId;

    private String remark;

    private LocalDateTime createTime;

    public static UserVO from(SysUser user, List<String> roles) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setPhone(user.getPhone());
        vo.setEmail(user.getEmail());
        vo.setStatus(user.getStatus());
        vo.setRoles(roles);
        vo.setStudentId(user.getStudentId());
        vo.setClassId(user.getClassId());
        vo.setRemark(user.getRemark());
        vo.setCreateTime(user.getCreateTime());
        return vo;
    }
}
