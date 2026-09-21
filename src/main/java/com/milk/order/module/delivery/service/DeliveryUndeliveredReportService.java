package com.milk.order.module.delivery.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.milk.order.module.delivery.dto.UndeliveredReportRequest;
import com.milk.order.module.delivery.entity.DeliveryUndeliveredReport;
import com.milk.order.module.delivery.vo.UndeliveredReportVO;

/**
 * 当日未送达申报服务（奶站 → 管理端人工跟进）
 *
 * <p>唯一职责：把「已点已送出、物理上没送到」的任务标记下来，使其**排除出自动签收候选集**，
 * 并在管理端形成待跟进列表。**不新增状态、不自动改任何状态**——人工处置仍走既有出口
 * （签收 / 拒收 / 取消），处置后任务自然离开「配送中」。</p>
 */
public interface DeliveryUndeliveredReportService extends IService<DeliveryUndeliveredReport> {

    /**
     * 申报某任务当日未送达。
     *
     * <p>只允许对「配送中」（已送出但未签收）且配送日不晚于今天的任务申报；
     * 同一任务重复申报由唯一键仲裁，返回业务提示而不是产生第二条。</p>
     */
    void report(UndeliveredReportRequest request);

    /** 待跟进列表（可筛配送日与跟进状态；班主任仅见本班） */
    IPage<UndeliveredReportVO> pageReports(Long pageNum, Long pageSize, String deliveryDate,
                                           Integer handleStatus, Long classId);

    /**
     * 标记「已跟进」：只写跟进说明与人/时间。
     *
     * <p><b>刻意不改任务与订单状态</b>——人工实际处置必须走既有业务出口（签收/拒收/取消），
     * 否则等于绕过状态机与迁移台账。</p>
     */
    void handle(Long id, String remark);
}
