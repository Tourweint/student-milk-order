package com.milk.order.module.nutrition.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.milk.order.module.nutrition.dto.NutritionInfoRequest;
import com.milk.order.module.nutrition.entity.NutritionInfo;
import com.milk.order.module.nutrition.vo.NutritionInfoVO;
import com.milk.order.module.nutrition.vo.NutritionIntakeVO;
import com.milk.order.module.nutrition.vo.NutritionSummaryVO;

import java.util.List;

public interface NutritionInfoService extends IService<NutritionInfo> {

    /** 营养成分列表（回填奶品名/规格） */
    List<NutritionInfoVO> listWithProduct();

    /** 按奶品 ID 查询营养成分 */
    NutritionInfoVO getByProductId(Long productId);

    /** 保存或更新营养成分（按 productId 幂等） */
    void saveOrUpdateByProduct(NutritionInfoRequest request);

    /** 营养摄入记录分页（学生/日期区间筛选，回填学生名/奶品名） */
    IPage<NutritionIntakeVO> pageIntakes(Long pageNum, Long pageSize, Long studentId,
                                           String startDate, String endDate);

    /** 营养摄入按日汇总（按学生+日期区间，按日期分组） */
    List<NutritionSummaryVO> summaryByStudent(Long studentId, String startDate, String endDate);
}
