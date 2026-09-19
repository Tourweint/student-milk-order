package com.milk.order.chaos;

/**
 * 混沌注入异常：模拟"事务执行到一半时进程/依赖突然失败"。
 *
 * <p>它是 RuntimeException，因此会穿过业务方法向上抛，触发外层事务回滚——
 * 这正是要验证的场景：<b>中间失败会不会留下半截数据</b>。</p>
 */
public class ChaosInjectedException extends RuntimeException {

    public ChaosInjectedException(String message) {
        super(message);
    }
}
