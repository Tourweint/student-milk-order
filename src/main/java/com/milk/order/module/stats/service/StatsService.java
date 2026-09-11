package com.milk.order.module.stats.service;

import com.milk.order.module.stats.vo.ClassRankingVO;
import com.milk.order.module.stats.vo.CoverageVO;
import com.milk.order.module.stats.vo.DashboardVO;
import com.milk.order.module.stats.vo.TrendVO;

import java.util.List;
import java.util.Map;

public interface StatsService {

    /** 仪表盘总览 */
    DashboardVO dashboard();

    /** 订单趋势（type=day近30天 / month近12个月） */
    TrendVO orderTrend(String type, String startDate, String endDate);

    /** 奶品品类占比（饼图数据） */
    List<Map<String, Object>> orderCategory();

    /** 班级订购排行榜 */
    List<ClassRankingVO> classRanking(Integer limit);

    /** 营养摄入仪表盘 */
    Map<String, Object> nutritionDashboard(Long classId);

    /** 学生喝奶覆盖率 */
    CoverageVO coverage();
}
