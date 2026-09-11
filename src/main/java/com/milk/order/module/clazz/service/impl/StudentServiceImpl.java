package com.milk.order.module.clazz.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.milk.order.common.constant.SystemConstants;
import com.milk.order.exception.BusinessException;
import com.milk.order.module.clazz.entity.ClassInfo;
import com.milk.order.module.clazz.entity.Student;
import com.milk.order.module.clazz.mapper.StudentMapper;
import com.milk.order.module.clazz.service.ClassInfoService;
import com.milk.order.module.clazz.service.StudentService;
import com.milk.order.module.clazz.vo.ImportResultVO;
import com.milk.order.module.clazz.vo.StudentVO;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StudentServiceImpl extends ServiceImpl<StudentMapper, Student> implements StudentService {

    private final ClassInfoService classInfoService;

    @Override
    public IPage<StudentVO> pageStudents(Long pageNum, Long pageSize, Long classId, String keyword) {
        Page<Student> page = new Page<>(
                pageNum == null ? SystemConstants.DEFAULT_PAGE_NUM : pageNum,
                pageSize == null ? SystemConstants.DEFAULT_PAGE_SIZE : Math.min(pageSize, SystemConstants.MAX_PAGE_SIZE));

        IPage<Student> studentPage = lambdaQuery()
                .eq(classId != null, Student::getClassId, classId)
                .and(StringUtils.hasText(keyword), wrapper -> wrapper
                        .like(Student::getStudentNo, keyword)
                        .or().like(Student::getStudentName, keyword)
                        .or().like(Student::getParentName, keyword)
                        .or().like(Student::getParentPhone, keyword))
                .orderByAsc(Student::getClassId)
                .orderByAsc(Student::getStudentNo)
                .page(page);

        List<StudentVO> voList = convertToVOList(studentPage.getRecords());

        Page<StudentVO> result = new Page<>(studentPage.getCurrent(), studentPage.getSize(), studentPage.getTotal());
        result.setRecords(voList);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createStudent(Student student) {
        validateStudent(student, null);
        save(student);
        classInfoService.refreshStudentCount(student.getClassId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStudent(Student student) {
        if (student.getId() == null) {
            throw new BusinessException("学生ID不能为空");
        }
        Student exists = getById(student.getId());
        if (exists == null) {
            throw new BusinessException("学生不存在");
        }
        validateStudent(student, student.getId());
        updateById(student);
        // 班级发生变化时，新旧两个班级的人数都要刷新
        classInfoService.refreshStudentCount(exists.getClassId());
        if (!Objects.equals(exists.getClassId(), student.getClassId())) {
            classInfoService.refreshStudentCount(student.getClassId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeStudent(Long id) {
        Student exists = getById(id);
        if (exists == null) {
            throw new BusinessException("学生不存在");
        }
        removeById(id);
        classInfoService.refreshStudentCount(exists.getClassId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImportResultVO importStudents(MultipartFile file, Long classId) {
        if (classId == null) {
            throw new BusinessException("请先选择要导入的班级");
        }
        ClassInfo clazz = classInfoService.getById(classId);
        if (clazz == null) {
            throw new BusinessException("所选班级不存在");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请上传 Excel 文件");
        }

        ImportResultVO result = new ImportResultVO();
        List<Student> toSave = new ArrayList<>();
        // 文件内学号去重
        Set<String> fileStudentNos = new HashSet<>();

        try (InputStream in = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                throw new BusinessException("Excel 中没有工作表");
            }

            int lastRow = sheet.getLastRowNum();
            // 第 0 行为表头，从第 1 行开始
            for (int i = 1; i <= lastRow; i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                int rowNum = i + 1;
                String studentNo = getCellString(row.getCell(0));
                String studentName = getCellString(row.getCell(1));
                String genderText = getCellString(row.getCell(2));
                String parentName = getCellString(row.getCell(3));
                String parentPhone = getCellString(row.getCell(4));
                String remark = getCellString(row.getCell(5));

                // 整行空则跳过
                if (!StringUtils.hasText(studentNo) && !StringUtils.hasText(studentName)) {
                    continue;
                }
                if (!StringUtils.hasText(studentNo)) {
                    result.addError("第" + rowNum + "行：学号为空，已跳过");
                    continue;
                }
                if (!StringUtils.hasText(studentName)) {
                    result.addError("第" + rowNum + "行：姓名为空，已跳过");
                    continue;
                }
                if (isStudentNoExists(studentNo, null)) {
                    result.addError("第" + rowNum + "行：学号「" + studentNo + "」在系统中已存在");
                    continue;
                }
                if (!fileStudentNos.add(studentNo)) {
                    result.addError("第" + rowNum + "行：学号「" + studentNo + "」在文件中重复");
                    continue;
                }

                Student student = new Student();
                student.setStudentNo(studentNo);
                student.setStudentName(studentName);
                student.setGender(parseGender(genderText));
                student.setClassId(classId);
                student.setParentName(StringUtils.hasText(parentName) ? parentName : null);
                student.setParentPhone(StringUtils.hasText(parentPhone) ? parentPhone : null);
                student.setRemark(StringUtils.hasText(remark) ? remark : null);
                toSave.add(student);
                result.addSuccess();
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("Excel 解析失败：" + e.getMessage());
        }

        if (!toSave.isEmpty()) {
            saveBatch(toSave);
            classInfoService.refreshStudentCount(classId);
        }
        return result;
    }

    @Override
    public boolean isStudentNoExists(String studentNo, Long excludeId) {
        return lambdaQuery()
                .eq(Student::getStudentNo, studentNo)
                .ne(excludeId != null, Student::getId, excludeId)
                .count() > 0;
    }

    @Override
    public byte[] buildImportTemplate() throws java.io.IOException {
        try (Workbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("学生导入模板");

            // 表头样式
            CellStyle headerStyle = workbook.createCellStyle();
            org.apache.poi.ss.usermodel.Font font = workbook.createFont();
            font.setBold(true);
            headerStyle.setFont(font);

            String[] headers = {"学号", "姓名", "性别（男/女）", "家长姓名", "家长电话", "备注"};
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 18 * 256);
            }
            // 一行示例
            Row example = sheet.createRow(1);
            String[] sample = {"2026010101", "张三", "男", "张父", "13900000000", "示例行，导入前请删除"};
            for (int i = 0; i < sample.length; i++) {
                example.createCell(i).setCellValue(sample[i]);
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /**
     * 校验学生必填项、班级存在、学号唯一
     */
    private void validateStudent(Student student, Long excludeId) {
        if (!StringUtils.hasText(student.getStudentNo())) {
            throw new BusinessException("学号不能为空");
        }
        if (!StringUtils.hasText(student.getStudentName())) {
            throw new BusinessException("学生姓名不能为空");
        }
        if (student.getClassId() == null) {
            throw new BusinessException("请选择所属班级");
        }
        ClassInfo clazz = classInfoService.getById(student.getClassId());
        if (clazz == null) {
            throw new BusinessException("所选班级不存在");
        }
        if (isStudentNoExists(student.getStudentNo(), excludeId)) {
            throw new BusinessException("学号「" + student.getStudentNo() + "」已存在");
        }
    }

    private List<StudentVO> convertToVOList(List<Student> students) {
        if (CollectionUtils.isEmpty(students)) {
            return Collections.emptyList();
        }
        Set<Long> classIds = students.stream()
                .map(Student::getClassId)
                .collect(Collectors.toSet());
        Map<Long, ClassInfo> classMap = classInfoService.listByIds(classIds).stream()
                .collect(Collectors.toMap(ClassInfo::getId, Function.identity()));

        return students.stream().map(student -> {
            ClassInfo clazz = classMap.get(student.getClassId());
            return StudentVO.from(student, clazz == null ? null : clazz.getClassName());
        }).collect(Collectors.toList());
    }

    /**
     * 读取单元格文本（兼容数字、公式等类型）
     */
    private String getCellString(Cell cell) {
        if (cell == null) {
            return "";
        }
        DataFormatter formatter = new DataFormatter();
        return formatter.formatCellValue(cell).trim();
    }

    /**
     * 性别文本转编码：男→1，女→0，其余→null
     */
    private Integer parseGender(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String t = text.trim();
        if ("男".equals(t) || "1".equals(t) || "M".equalsIgnoreCase(t)) {
            return 1;
        }
        if ("女".equals(t) || "0".equals(t) || "F".equalsIgnoreCase(t)) {
            return 0;
        }
        return null;
    }
}
