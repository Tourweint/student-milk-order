package com.milk.order.module.delivery.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.delivery.dto.DeliveryExceptionSaveRequest;
import com.milk.order.module.delivery.entity.DeliveryException;
import com.milk.order.module.delivery.mapper.DeliveryExceptionMapper;
import com.milk.order.module.delivery.service.DeliveryExceptionService;
import com.milk.order.reliability.IdempotencyGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;

/**
 * 配送例外实现：日期唯一由数据库唯一键 {@code uk_exception_date} 仲裁，
 * 应用层把唯一键冲突翻译为「该日期已存在」业务异常（不使用"先查再插"，多实例下会重复）。
 */
@Service
@RequiredArgsConstructor
public class DeliveryExceptionServiceImpl extends ServiceImpl<DeliveryExceptionMapper, DeliveryException>
        implements DeliveryExceptionService {

    private final IdempotencyGuard idempotencyGuard;

    @Override
    public List<DeliveryException> listRange(String startDate, String endDate) {
        LambdaQueryWrapper<DeliveryException> wrapper = new LambdaQueryWrapper<DeliveryException>()
                .orderByAsc(DeliveryException::getExceptionDate);
        if (StringUtils.hasText(startDate)) {
            wrapper.ge(DeliveryException::getExceptionDate, parseDate(startDate, "开始日期"));
        }
        if (StringUtils.hasText(endDate)) {
            wrapper.le(DeliveryException::getExceptionDate, parseDate(endDate, "结束日期"));
        }
        return list(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long save(DeliveryExceptionSaveRequest request) {
        LocalDate date = parseDate(request.getExceptionDate(), "例外日期");
        if (date.isBefore(LocalDate.now())) {
            throw new BusinessException("只能维护今天及以后的例外日期（历史已配送/已签收的日期不可改动）");
        }
        Integer type = request.getType();
        if (type == null || (type != DeliveryException.TYPE_STOP && type != DeliveryException.TYPE_MAKE_UP)) {
            throw new BusinessException("例外类型只能是 1-停送 或 2-补送（补课）");
        }
        String remark = StringUtils.hasText(request.getRemark()) ? request.getRemark().trim() : null;

        DeliveryException entity;
        if (request.getId() != null) {
            entity = getById(request.getId());
            if (entity == null) {
                throw new BusinessException("例外记录不存在或已删除");
            }
        } else {
            entity = new DeliveryException();
        }
        entity.setExceptionDate(date);
        entity.setType(type);
        entity.setRemark(remark);
        try {
            saveOrUpdate(entity);
        } catch (Exception e) {
            if (idempotencyGuard.isDuplicate(e)) {
                throw new BusinessException("该日期已存在例外配置（" + date + "），请先删除原配置");
            }
            throw e;
        }
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        if (id == null || !removeById(id)) {
            throw new BusinessException("例外记录不存在或已删除");
        }
    }

    private LocalDate parseDate(String text, String fieldName) {
        if (!StringUtils.hasText(text)) {
            throw new BusinessException(fieldName + "不能为空");
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (Exception e) {
            throw new BusinessException(fieldName + "格式不正确");
        }
    }
}
