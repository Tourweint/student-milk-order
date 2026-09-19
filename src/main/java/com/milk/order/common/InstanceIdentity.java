package com.milk.order.common;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 本进程实例标识：把「按实例自增」的编号（订单号、支付流水号）变成**跨实例不冲突**。
 *
 * <p><b>为什么需要它</b>：这类编号原先用「时间戳 + JVM 内自增序列」生成。单实例没问题，
 * 多实例下两个实例的计数器互不知情——同一秒内可能生成同一个号，
 * 撞上唯一键就是一次"下单失败"（订单号）或一次难解释的重复主键。</p>
 *
 * <p>做法：每个进程启动时随机生成一个 5 位标识并固定在编号里。
 * 于是在"同一秒 + 同一序列值"之外还要"实例标识也相同"才会冲突，
 * 而不同进程的标识由随机数决定（32^5 ≈ 3300 万种），实际上不可能撞。
 * 编号里带上它还有一个好处：排查时一眼能看出是**哪个实例**生成的。</p>
 *
 * <p><b>边界（写清楚）</b>：这是"把碰撞概率降到实际不可能"，不是数学上的保证。
 * 真正的唯一性仍由数据库唯一键兜底——万一撞了，插入会失败并报错（不会静默写坏数据）。
 * 若将来需要严格保证，应改为数据库序列/号段服务。</p>
 */
public final class InstanceIdentity {

    private InstanceIdentity() {
    }

    /** 本进程标识（5 位大写 base36，启动时随机生成一次） */
    public static final String TAG = randomTag();

    private static String randomTag() {
        String chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder(5);
        for (int i = 0; i < 5; i++) {
            sb.append(chars.charAt(ThreadLocalRandom.current().nextInt(chars.length())));
        }
        return sb.toString();
    }
}
