package com.milk.order.experiment;

import com.milk.order.common.enums.AllergenType;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.order.service.AllergyWarningService;
import com.milk.order.module.order.vo.AllergyOptionVO;
import com.milk.order.module.order.vo.AllergyWarningVO;
import com.milk.order.module.product.entity.Product;
import com.milk.order.module.product.service.ProductService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 实验十九：过敏/禁忌软警示（受控枚举 + 交集命中 + 只提示不拦截）。
 *
 * <p>命题（"警示优于拦截"）：</p>
 * <ul>
 *   <li>① 命中判定 = 学生禁忌 ∩ 奶品过敏原（编码级），两侧同源于 {@link AllergenType}；</li>
 *   <li>② 两侧若各写各的自由文本（"乳糖不耐" vs "乳糖"）会**静默永不命中**，因此写入必须规范化、
 *       未知编码必须被拒绝——本实验用"脏输入"验证写入路径；</li>
 *   <li>③ 清空标签必须能真正落库（MyBatis-Plus 默认跳过 null，空串语义就是为了修这个坑）；</li>
 *   <li>④ 无标签的学生不产生任何警示（也不该多查产品）。</li>
 * </ul>
 */
@DisplayName("实验十九：过敏/禁忌软警示（受控枚举 + 交集命中）")
class Experiment19AllergyWarningTest extends ExperimentSupport {

    @Autowired
    private AllergyWarningService allergyWarningService;

    @Autowired
    private ProductService productService;

    @Test
    @DisplayName("命中：学生禁忌与奶品过敏原交集非空 → 返回命中项（含中文提示）")
    void hitsWhenTagsIntersect() {
        setTags("LACTOSE,NUTS", "LACTOSE,SOY");

        List<AllergyWarningVO> warnings = allergyWarningService.check(studentId, List.of(productId));
        List<AllergyOptionVO> options = allergyWarningService.options();

        report("实验十九 · 交集命中",
                "命中条数（期望 1）", warnings.size(),
                "命中奶品", warnings.isEmpty() ? "—" : warnings.get(0).getProductName(),
                "命中过敏原编码（期望 [LACTOSE]）", warnings.isEmpty() ? "—" : warnings.get(0).getAllergens(),
                "命中过敏原中文", warnings.isEmpty() ? "—" : warnings.get(0).getAllergenTexts(),
                "提示语", warnings.isEmpty() ? "—" : warnings.get(0).getMessage(),
                "受控选项数（期望 7，含两侧文案）", options.size(),
                "乳糖选项文案（奶品侧/学生侧）",
                options.stream().filter(o -> "LACTOSE".equals(o.getCode()))
                        .map(o -> o.getText() + "/" + o.getStudentText()).findFirst().orElse("—"));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getProductId()).isEqualTo(productId);
        assertThat(warnings.get(0).getAllergens()).containsExactly("LACTOSE");
        assertThat(warnings.get(0).getAllergenTexts()).containsExactly("乳糖");
        assertThat(warnings.get(0).getMessage()).contains("乳糖");
        assertThat(options).hasSize(AllergenType.values().length);
    }

    @Test
    @DisplayName("不命中：交集为空 / 学生无标签 → 空清单（且不误报）")
    void noWarningWhenNoIntersectionOrNoAllergy() {
        // 学生乳糖不耐，奶品含坚果 → 不命中
        setTags("LACTOSE", "NUTS");
        List<AllergyWarningVO> noIntersection = allergyWarningService.check(studentId, List.of(productId));

        // 学生无禁忌标签 → 不命中（也不应产生任何警示）
        setTags(null, "LACTOSE,NUTS");
        List<AllergyWarningVO> noAllergy = allergyWarningService.check(studentId, List.of(productId));

        // 奶品无过敏原标签 → 不命中
        setTags("LACTOSE", null);
        List<AllergyWarningVO> noAllergen = allergyWarningService.check(studentId, List.of(productId));

        report("实验十九 · 不命中",
                "交集为空（期望 0）", noIntersection.size(),
                "学生无禁忌（期望 0）", noAllergy.size(),
                "奶品无过敏原（期望 0）", noAllergen.size());

        assertThat(noIntersection).isEmpty();
        assertThat(noAllergy).isEmpty();
        assertThat(noAllergen).isEmpty();
    }

    @Test
    @DisplayName("写入规范化：脏输入被规整、未知编码被拒、清空能真正落库")
    void writePathNormalizesAndRejectsUnknown() {
        // 脏输入：小写、空格、重复项 → 规范化后落库
        Product product = new Product();
        product.setId(productId);
        product.setProductName(TAG + "奶");
        product.setCategoryId(categoryId);
        product.setPrice(UNIT_PRICE);
        product.setAllergenTags(" lactose , NUTS ,lactose, ");
        productService.updateProduct(product);
        String stored = jdbcTemplate.queryForObject(
                "SELECT allergen_tags FROM product WHERE id = ?", String.class, productId);

        // 未知编码：写入直接被拒绝（避免"配了却永不命中"的静默失效）
        Product bad = new Product();
        bad.setId(productId);
        bad.setProductName(TAG + "奶");
        bad.setCategoryId(categoryId);
        bad.setPrice(UNIT_PRICE);
        bad.setAllergenTags("LACTOSE,NOT_A_CODE");
        Throwable unknown = catchThrowable(() -> productService.updateProduct(bad));

        // 清空：空串语义能真正落库（MP 默认跳过 null，用 null 会静默清不掉）
        Product cleared = new Product();
        cleared.setId(productId);
        cleared.setProductName(TAG + "奶");
        cleared.setCategoryId(categoryId);
        cleared.setPrice(UNIT_PRICE);
        cleared.setAllergenTags("");
        productService.updateProduct(cleared);
        String afterClear = jdbcTemplate.queryForObject(
                "SELECT allergen_tags FROM product WHERE id = ?", String.class, productId);

        report("实验十九 · 写入规范化",
                "脏输入落库结果（期望 LACTOSE,NUTS）", stored,
                "未知编码异常（期望 BusinessException）",
                unknown == null ? "无" : unknown.getClass().getSimpleName(),
                "未知编码提示", unknown == null ? "—" : unknown.getMessage(),
                "清空后落库值（期望 空串）", "「" + afterClear + "」",
                "枚举解析空串（期望 空集合）", AllergenType.parse("").size(),
                "枚举规范化 null（期望 空串）", "「" + AllergenType.normalizeStrict(null) + "」",
                "枚举宽松解析未知编码（期望 空集合）", AllergenType.parse("LACTOSE,BOGUS").size()
                        + "（只保留 LACTOSE：" + AllergenType.parse("LACTOSE,BOGUS") + "）");

        assertThat(stored).isEqualTo("LACTOSE,NUTS");
        assertThat(unknown).isInstanceOf(BusinessException.class);
        assertThat(afterClear).isEmpty();
        assertThat(AllergenType.parse("")).isEmpty();
        assertThat(AllergenType.normalizeStrict(null)).isEmpty();
        assertThat(AllergenType.parse("LACTOSE,BOGUS")).containsExactly("LACTOSE");
        assertThat(AllergenType.parse(Collections.singletonList("LACTOSE").toString())).isEmpty();
    }

    /** 直接改库设置夹具的学生/奶品标签（NULL 表示清空） */
    private void setTags(String studentTags, String productTags) {
        jdbcTemplate.update("UPDATE student SET allergy_tags = ? WHERE id = ?", studentTags, studentId);
        jdbcTemplate.update("UPDATE product SET allergen_tags = ? WHERE id = ?", productTags, productId);
    }
}
