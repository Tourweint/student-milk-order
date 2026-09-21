package com.milk.order.module.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.milk.order.common.enums.AllergenType;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.clazz.entity.Student;
import com.milk.order.module.clazz.mapper.StudentMapper;
import com.milk.order.module.order.service.AllergyWarningService;
import com.milk.order.module.order.vo.AllergyOptionVO;
import com.milk.order.module.order.vo.AllergyWarningVO;
import com.milk.order.module.product.entity.Product;
import com.milk.order.module.product.mapper.ProductMapper;
import com.milk.order.module.user.dto.DataScope;
import com.milk.order.module.user.service.DataScopeResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 过敏/禁忌软警示实现（下单前预检）
 *
 * <p>命中判定：`学生禁忌 ∩ 奶品过敏原 ≠ ∅`（编码级比较，两侧同源于 {@link AllergenType}）。</p>
 *
 * <p><b>为什么只警示不拦截</b>：禁忌标签由管理端人工维护，可能滞后或不精确；家长也可能明知而仍要购买
 * （例如只有这一款含香精）。硬拦截等于用一份可能过期的档案阻断交易；软警示把判断权交还用户，
 * 并留下"系统提示过"的事实。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AllergyWarningServiceImpl implements AllergyWarningService {

    private final StudentMapper studentMapper;
    private final ProductMapper productMapper;
    private final DataScopeResolver dataScopeResolver;

    @Override
    public List<AllergyOptionVO> options() {
        List<AllergyOptionVO> list = new ArrayList<>();
        for (AllergenType type : AllergenType.values()) {
            AllergyOptionVO vo = new AllergyOptionVO();
            vo.setCode(type.name());
            vo.setText(type.getText());
            vo.setStudentText(type.getStudentText());
            list.add(vo);
        }
        return list;
    }

    @Override
    public List<AllergyWarningVO> check(Long studentId, Collection<Long> productIds) {
        if (studentId == null || productIds == null || productIds.isEmpty()) {
            return Collections.emptyList();
        }
        Student student = studentMapper.selectById(studentId);
        if (student == null) {
            throw new BusinessException("学生不存在");
        }
        checkScope(student);
        Set<String> allergies = AllergenType.parse(student.getAllergyTags());
        if (allergies.isEmpty()) {
            return Collections.emptyList(); // 无禁忌标签：不产生警示（也不做多余查询）
        }
        List<Product> products = productMapper.selectBatchIds(productIds.stream().distinct().collect(Collectors.toList()));
        Map<Long, Product> productMap = products.stream()
                .collect(Collectors.toMap(Product::getId, Function.identity(), (a, b) -> a));

        List<AllergyWarningVO> warnings = new ArrayList<>();
        for (Long productId : new LinkedHashSet<>(productIds)) {
            Product product = productMap.get(productId);
            if (product == null) {
                continue; // 奶品不存在交给下单校验报错，这里不重复报
            }
            Set<String> allergens = AllergenType.parse(product.getAllergenTags());
            if (allergens.isEmpty()) {
                continue;
            }
            // 交集：保序（按学生标签顺序），便于前端稳定展示
            List<String> hits = allergies.stream().filter(allergens::contains).collect(Collectors.toList());
            if (hits.isEmpty()) {
                continue;
            }
            AllergyWarningVO vo = new AllergyWarningVO();
            vo.setProductId(productId);
            vo.setProductName(product.getProductName());
            vo.setAllergens(hits);
            vo.setAllergenTexts(hits.stream().map(AllergenType::textOf).collect(Collectors.toList()));
            vo.setMessage("「" + product.getProductName() + "」含 " + String.join("、", vo.getAllergenTexts())
                    + "，与「" + student.getStudentName() + "」的禁忌标签一致，请确认是否仍要下单");
            warnings.add(vo);
        }
        if (!warnings.isEmpty()) {
            log.info(String.format("[过敏预检] 学生 %s（禁忌 %s）命中 %d 个奶品：%s",
                    studentId, student.getAllergyTags(), warnings.size(),
                    warnings.stream().map(AllergyWarningVO::getProductName).collect(Collectors.joining("、"))));
        }
        return warnings;
    }

    /**
     * 数据范围：与下单入口同口径（家长仅本人孩子、班主任仅本班、管理员不限）。
     *
     * <p>用 {@code resolveQuietly} 兼容无登录上下文的内部调用（如系统内部预览），
     * 接口层已由 SecurityConfig 限定角色。</p>
     */
    private void checkScope(Student student) {
        DataScope scope = dataScopeResolver.resolveQuietly();
        if (scope.isAll()) {
            return;
        }
        if (scope.getStudentId() != null) {
            if (!scope.getStudentId().equals(student.getId())) {
                throw new BusinessException(403, "只能为自己的孩子下单");
            }
            return;
        }
        if (scope.getClassId() != null && !scope.getClassId().equals(student.getClassId())) {
            throw new BusinessException(403, "只能为本班学生下单");
        }
    }
}
