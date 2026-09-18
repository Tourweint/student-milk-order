package com.milk.order.reliability;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 幂等守卫（可靠性层原语）：把数据库唯一约束当作幂等判定的权威依据。
 *
 * <p>“先查询再插入”的幂等只在单线程下成立：并发下两个执行者可能同时查不到、再同时插入，
 * 于是产生重复数据（典型现象是理论 78 条任务实际生成 156 条）。正确做法是给业务键加唯一约束，
 * 让数据库承担并发仲裁：一方插入成功，另一方得到唯一键冲突，被判定为“已存在”而幂等跳过。</p>
 *
 * <p>本守卫把这一模式收拢为一处，避免各处散落重复的 try/catch 与原因链判断。</p>
 */
@Slf4j
@Component
public class IdempotencyGuard {

    /**
     * 判断异常是否为唯一键冲突（沿原因链回溯，兼容各处包装层次不同）。
     */
    public boolean isDuplicate(Throwable e) {
        Set<Throwable> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable cursor = e;
        while (cursor != null && visited.add(cursor)) {
            if (cursor instanceof DuplicateKeyException) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    /**
     * 执行一次“写入并忽略重复”的操作。
     *
     * <p>用于幂等地建立子任务/台账等“同一业务键只应存在一行”的记录。</p>
     *
     * @param insert 真正执行插入的动作
     * @return true 表示本次插入成功；false 表示唯一键冲突，判定为已存在并幂等跳过
     */
    public boolean insertIgnoringDuplicate(Runnable insert) {
        try {
            insert.run();
            return true;
        } catch (Exception e) {
            if (isDuplicate(e)) {
                // MySQL/MariaDB 下唯一键冲突只回滚该条语句，事务可继续提交其余写入；
                // 这里把冲突翻译为“已存在”，并发重复写入因此收敛为一条
                log.debug("[幂等] 唯一键冲突，判定为已存在并跳过：{}", e.getMessage());
                return false;
            }
            throw e;
        }
    }
}
