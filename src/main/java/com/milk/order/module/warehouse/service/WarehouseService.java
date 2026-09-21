package com.milk.order.module.warehouse.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.milk.order.module.warehouse.dto.WarehouseAdjustRequest;
import com.milk.order.module.warehouse.dto.WarehouseReceiptRequest;
import com.milk.order.module.warehouse.entity.WarehouseLedger;
import com.milk.order.module.warehouse.vo.WarehouseBalanceVO;
import com.milk.order.module.warehouse.vo.WarehouseReceiptVO;

import java.time.LocalDate;
import java.util.List;

/**
 * 仓库余量台账服务（供给侧）。
 *
 * <p><b>定位</b>：配额池建模的是"供货计划"，本服务为它补上**物理地基**——
 * 仓库余量是机动配额发行的唯一合法来源（"只有仓库余量才能卖"）。</p>
 *
 * <p><b>只增不改</b>：台账是事实记录，本接口不提供修改与删除；记错走
 * {@link #adjust} 的反向冲销（两条留痕，与 {@code process_transition_log} 同一原则）。</p>
 *
 * <p><b>出入库全自动</b>：唯一日常人工动作 = 收货点数时提交一次
 * {@link #receipt}；OUT / IN_BACK 由配送侧在业务事务内自动入账
 * （{@link #recordOutStock} / {@link #recordInBack}）。</p>
 *
 * @see com.milk.order.common.constant.WarehouseBizType
 */
public interface WarehouseService extends IService<WarehouseLedger> {

    /**
     * 到货登记（配送站 / 管理员）：每日每品种一行，实到数即入账。
     *
     * <p>多出的部分自动成为余量（不浪费）；少到的部分在响应里给**短交预警**（不阻断登记，
     * 因为企业可能分批到货）。同日同品种第二车请带不同的 {@code receiptNo}。</p>
     */
    WarehouseReceiptVO receipt(WarehouseReceiptRequest request);

    /** 当前余量（按品种列出 W；在售品种全列，未登记到货的为 0） */
    List<WarehouseBalanceVO> balance();

    /** 台账查询（只读，分页） */
    IPage<WarehouseLedger> pageLedger(Long pageNum, Long pageSize, String bizType, Long productId,
                                      String startDate, String endDate);

    /** 修正（仅管理员，必填原因）：{@code quantity} 带符号，台账留痕 */
    void adjust(WarehouseAdjustRequest request);

    // ==================== 供配额发行 / 短交预警 / 不变量复用的口径出口 ====================

    /**
     * 某品种当前仓库余量 W（恒等式见 Mapper）。
     *
     * <p>刻意只回传一个数而不是"取行到 Java 再求和"：余量会被发行校验与不变量体检反复读取。</p>
     */
    int balanceOf(Long productId);

    /**
     * 某品种在基准日 D 的**实物需求**（R5′ 与短交预警共用同一口径，避免两处口径漂移）：
     *
     * <pre>
     * 需求(p, D) = Σ_{池: quota_date ∈ [D−2, D]} GREATEST(total−used, 0)   -- D 当天可卖（含结转池）
     *            + Σ_{任务: product_id=p, status=1, delivery_date ≤ D} quantity
     * </pre>
     *
     * <p>三点必须一起看，缺一条就失衡（详见设计方案 §11.2）：</p>
     * <ol>
     *   <li><b>任务算 {@code ≤ D} 而不只是 {@code = D}</b>：池口径含结转（D−2、D−1 的未售），
     *       若任务只算当天，"早先已售未送出"与结转池会对同一批实物重复计入可卖额度 → 可超发；</li>
     *   <li><b>任务算全部类型</b>：套餐不经配额池，但实物同仓——只算套餐会漏掉"已售未送出的零散"；</li>
     *   <li><b>不算 {@code status=2}</b>：已送出即已记 OUT、W 已扣减，再计一次是重复。</li>
     * </ol>
     */
    int requiredStock(Long productId, LocalDate baseDate);

    /**
     * 发行封顶校验（R5′）：设置某品种某日机动配额**之前**调用，不满足则抛业务异常。
     *
     * <p>判定：{@code 结转池剩余 + 本次新总额中的未售部分 + 该日及逾期待送出任务 ≤ W}。</p>
     *
     * <p><b>并发语义</b>：本方法在事务内先对 {@code product} 行加锁，把"同品种发行"排成队。
     * 否则 W 的聚合读是无锁的——两个并发请求（同品种、不同日期）会各自读到旧 W 后双双通过，
     * 这是典型的 check-then-act 漏洞。加锁顺序固定 {@code product → daily_quota}
     * （扣减路径只锁 {@code daily_quota} 行），不会成环。</p>
     *
     * @param poolUsedQuota 该池当前已售数（池不存在时传 0）——传入口径而非让本方法再查一次，
     *                      保证与调用方 {@code selectForUpdate} 读到的是同一版本
     */
    void requireIssuanceCoverage(Long productId, LocalDate poolDate, int newTotalQuota, int poolUsedQuota);

    // ==================== 过程钩子（由配送侧在业务事务内调用） ====================

    /**
     * 送出即出库（OUT）：任务"开始配送"（待配送→配送中）的同一事务内调用。
     *
     * <p>出库时点定在**送出**而不是签收：物理移动发生在"仓库 → 领取点"，
     * 签收是消费确认——两者分开才对得上"奶去哪了"（拒收退回的账也才有解释）。</p>
     *
     * <p>{@code biz_date} 取任务配送日期（业务日账），实际送出时刻看 {@code delivery_task.dispatch_time}。</p>
     *
     * @return true=本次入账；false=该任务已出过库（唯一键仲裁）或参数不完整（已告警）
     */
    boolean recordOutStock(Long taskId, Long productId, LocalDate deliveryDate, int quantity);

    /**
     * 拒收即回仓（IN_BACK）：用户入口 {@code rejectRecord} 的同一事务内调用。
     *
     * <p><b>只能挂在这个用户入口</b>：任务作废联动的共享助手
     * （{@code markRecordRejected}）也会把未签收记录置为 {@code sign_status=3}，
     * 缺货取消与退订都在调它——挂错会凭空多记退回（"奶根本没被拒收，账上却回了仓"）。</p>
     *
     * @return true=本次入账；false=该记录已记过退回（唯一键仲裁）或参数不完整（已告警）
     */
    boolean recordInBack(Long recordId, Long productId, LocalDate bizDate, int quantity, String reason);
}
