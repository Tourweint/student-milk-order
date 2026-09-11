package com.milk.order.module.stats.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 学生喝奶覆盖率 VO
 */
@Data
public class CoverageVO implements Serializable {

    /** 总覆盖率（百分比，如 75.0） */
    private Double totalRate;

    /** 总学生数 */
    private Long totalStudents;

    /** 在订学生数 */
    private Long activeStudents;

    /** 各班级覆盖率 */
    private List<ClassCoverage> classList;

    @Data
    public static class ClassCoverage implements Serializable {
        private Long classId;
        private String className;
        private Long totalStudents;
        private Long activeStudents;
        private Double rate;
    }
}
