package com.milk.order.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.milk.order.experiment.ExperimentSupport;
import com.milk.order.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 退款域 HTTP 契约测试（契约矩阵的"接口层"补充，不是并发实验）。
 *
 * <p><b>它守什么</b>：前端与后端的**路径、HTTP 方法、请求报文、响应字段**以及**角色闸门**。
 * 单元/服务层实验（实验十五）证明退款逻辑正确，但前端是照 `接口文档.md` §10 调用的——
 * 一旦有人改了路径或参数名，前后端会"各自都对、合起来不通"，这类漂移只能由接口层断言拦住。</p>
 *
 * <p><b>角色闸门为什么重要</b>：退款涉资金。班主任（TEACHER）与配送站（DELIVERY）不接触退款，
 * 家长只能操作本人绑定学生的订单——这三条是安全底线，必须在接口层被验证为 403，而不是"前端不显示按钮"。</p>
 *
 * <p>管理员/教师账号复用实验库中的种子用户（`data.sql`：admin / 123456）；家长账号由本测试按需创建
 * （复制种子密码哈希）并在用例结束后清理，避免污染实验库。</p>
 */
@AutoConfigureMockMvc
@DisplayName("退款域 HTTP 契约：路径 / 报文 / 角色闸门")
class RefundApiContractTest extends ExperimentSupport {

    private static final String PASSWORD = "123456";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String parentUsername;

    @AfterEach
    void cleanCreatedUsers() {
        if (parentUsername != null) {
            jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id IN "
                    + "(SELECT id FROM sys_user WHERE username = ?)", parentUsername);
            jdbcTemplate.update("DELETE FROM sys_user WHERE username = ?", parentUsername);
            parentUsername = null;
        }
    }

    @Test
    @DisplayName("未登录访问退款接口一律 401")
    void unauthenticatedIsRejected() throws Exception {
        mockMvc.perform(get("/api/refund/list"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/refund/1/execute"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/student/1/settle"))
                .andExpect(status().isUnauthorized());

        report("退款契约 · 未登录闸门",
                "GET /api/refund/list", 401,
                "PUT /api/refund/{id}/execute", 401,
                "POST /api/student/{id}/settle", 401);
    }

    @Test
    @DisplayName("班主任与配送站不接触退款：列表/审核/执行/清算一律 403")
    void teacherAndDeliveryAreForbidden() throws Exception {
        String teacherToken = login("teacher");
        String deliveryToken = login("delivery");

        int teacherList = mockMvc.perform(get("/api/refund/list").header("Authorization", bearer(teacherToken)))
                .andReturn().getResponse().getStatus();
        int teacherExecute = mockMvc.perform(put("/api/refund/1/execute").header("Authorization", bearer(teacherToken)))
                .andReturn().getResponse().getStatus();
        int teacherSettle = mockMvc.perform(post("/api/student/1/settle").header("Authorization", bearer(teacherToken)))
                .andReturn().getResponse().getStatus();
        int deliveryList = mockMvc.perform(get("/api/refund/list").header("Authorization", bearer(deliveryToken)))
                .andReturn().getResponse().getStatus();

        report("退款契约 · 角色闸门（班主任 / 配送站）",
                "TEACHER GET /api/refund/list", teacherList,
                "TEACHER PUT /api/refund/{id}/execute", teacherExecute,
                "TEACHER POST /api/student/{id}/settle", teacherSettle,
                "DELIVERY GET /api/refund/list", deliveryList);

        assertThat(teacherList).isEqualTo(403);
        assertThat(teacherExecute).isEqualTo(403);
        assertThat(teacherSettle).isEqualTo(403);
        assertThat(deliveryList).isEqualTo(403);
    }

    @Test
    @DisplayName("管理员全链路：预览 → 申请 → 列表 → 审核 → 执行 → 预览归零（重复执行被拒）")
    void adminRunsRefundLifecycleOverHttp() throws Exception {
        LocalDate start = LocalDate.now().plusDays(1);
        long orderId = newPaidOrder(start, start.plusDays(2), 1, 1L); // 3 天 × 1 盒 → 实付 9.00
        String token = login("admin");

        // 1. 预览（只读探测）：可退 3 盒、预估 9.00
        mockMvc.perform(get("/api/refund/order/{orderId}/preview", orderId).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.refundableBoxes").value(3))
                .andExpect(jsonPath("$.data.estimatedAmount").value(9.00))
                .andExpect(jsonPath("$.data.hasActiveRefund").value(false));

        // 2. 申请（报文：applyBoxCount + reason）
        long refundId = postForDataId("/api/refund/order/" + orderId, token,
                "{\"applyBoxCount\":3,\"reason\":\"契约测试申请\"}");

        // 3. 列表（管理员，按订单号模糊筛选，报文：pageNum/pageSize/orderNo）
        mockMvc.perform(get("/api/refund/list").param("pageNum", "1").param("pageSize", "10")
                        .param("orderNo", TAG + "-P")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.list[0].refundNo").exists())
                .andExpect(jsonPath("$.data.list[0].status").value(1));

        // 4. 审核通过（报文：approved）
        mockMvc.perform(put("/api/refund/{id}/audit", refundId).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true,\"remark\":\"契约测试通过\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 5. 执行退款
        mockMvc.perform(put("/api/refund/{id}/execute", refundId).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 6. 执行后：可退归零、已退累计 3 盒 / 9.00，任务全部作废
        mockMvc.perform(get("/api/refund/order/{orderId}/preview", orderId).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refundableBoxes").value(0))
                .andExpect(jsonPath("$.data.refundedBoxes").value(3))
                .andExpect(jsonPath("$.data.refundedAmount").value(9.00))
                .andExpect(jsonPath("$.data.hasActiveRefund").value(false));

        // 7. 重复执行被拒（业务异常 → HTTP 200 + 业务码非 200，前端统一提示）
        mockMvc.perform(put("/api/refund/{id}/execute", refundId).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(200)));

        int refundedRows = count("SELECT COUNT(*) FROM refund_order WHERE order_id = ? AND status = 3", orderId);
        int cancelledTasks = count("SELECT COUNT(*) FROM delivery_task WHERE order_id = ? AND status = 4", orderId);

        report("退款契约 · 管理员全链路",
                "预览可退盒数 / 预估金额", "3 / 9.00",
                "已退款单数（重复执行不新增）", refundedRows,
                "已作废任务数", cancelledTasks,
                "重复执行返回业务码", "非 200（被闸门拒绝）");

        assertThat(refundedRows).isEqualTo(1);
        assertThat(cancelledTasks).isEqualTo(3);
    }

    @Test
    @DisplayName("家长只能操作本人绑定学生：本人订单可申请，他人订单 403，审核接口 403")
    void parentScopeIsEnforced() throws Exception {
        LocalDate start = LocalDate.now().plusDays(1);
        long ownOrderId = newPaidOrder(start, start.plusDays(1), 1, 1L);       // 绑定学生（夹具学生）
        long otherOrderId = newPaidOrderForOtherStudent(start, start.plusDays(1), 1);

        String parentToken = loginAsParent();

        // 本人订单：预览 + 申请
        mockMvc.perform(get("/api/refund/order/{orderId}/preview", ownOrderId)
                        .header("Authorization", bearer(parentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        long refundId = postForDataId("/api/refund/order/" + ownOrderId, parentToken,
                "{\"applyBoxCount\":2,\"reason\":\"家长契约测试\"}");

        // 我的退款单列表（家长）
        mockMvc.perform(get("/api/refund/order/my").param("pageNum", "1").param("pageSize", "10")
                        .header("Authorization", bearer(parentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.list[0].id").value((int) refundId));

        // 他人订单：越权（Service 层数据范围校验 → 业务码 403，HTTP 仍 200，由前端拦截器统一提示）
        int otherPreviewCode = bodyOf(get("/api/refund/order/{orderId}/preview", otherOrderId)
                .header("Authorization", bearer(parentToken))).path("code").asInt();
        int otherApplyCode = bodyOf(post("/api/refund/order/{orderId}", otherOrderId)
                .header("Authorization", bearer(parentToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"applyBoxCount\":1}")).path("code").asInt();

        // 家长不得审核/执行/清算（SecurityConfig 写操作仅 ADMIN → HTTP 403）
        int parentAudit = mockMvc.perform(put("/api/refund/{id}/audit", refundId)
                        .header("Authorization", bearer(parentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true}"))
                .andReturn().getResponse().getStatus();
        int parentSettle = mockMvc.perform(post("/api/student/{id}/settle", studentId)
                        .header("Authorization", bearer(parentToken)))
                .andReturn().getResponse().getStatus();

        report("退款契约 · 家长数据范围与写操作隔离",
                "本人订单预览 HTTP", 200,
                "本人订单申请（退款单号）", refundId,
                "他人订单预览业务码", otherPreviewCode,
                "他人订单申请业务码", otherApplyCode,
                "家长审核 HTTP", parentAudit,
                "家长清算 HTTP", parentSettle);

        assertThat(otherPreviewCode).isEqualTo(403);
        assertThat(otherApplyCode).isEqualTo(403);
        assertThat(parentAudit).isEqualTo(403);
        assertThat(parentSettle).isEqualTo(403);
    }

    @Test
    @DisplayName("毕业清算接口：待支付单取消 + 已支付单退款，重复执行 0 单（幂等）")
    void settleOverHttpIsIdempotent() throws Exception {
        LocalDate date = LocalDate.now().plusDays(1);
        newPendingOrder(date, date, 1);                              // 待支付单
        long paidOrderId = newPaidOrder(date, date.plusDays(2), 1, 1L); // 已支付 3 天 × 1 盒
        String token = login("admin");

        JsonNode first = postForData("/api/student/" + studentId + "/settle", token, null);
        drainPendingTasks(50);
        JsonNode second = postForData("/api/student/" + studentId + "/settle", token, null);

        report("退款契约 · 毕业清算接口",
                "第一轮待清算订单数", first.path("totalOrders").asInt(),
                "取消待支付 / 已退款 / 跳过 / 失败",
                first.path("cancelledCount").asInt() + " / " + first.path("refundedCount").asInt()
                        + " / " + first.path("skippedCount").asInt() + " / " + first.path("failedCount").asInt(),
                "第一轮退款合计", first.path("totalRefundAmount").asText(),
                "第二轮待清算订单数（幂等）", second.path("totalOrders").asInt(),
                "已支付单状态（收敛后）", orderStatus(paidOrderId));

        assertThat(first.path("totalOrders").asInt()).isEqualTo(2);
        assertThat(first.path("cancelledCount").asInt()).isEqualTo(1);
        assertThat(first.path("refundedCount").asInt()).isEqualTo(1);
        assertThat(first.path("failedCount").asInt()).isZero();
        assertThat(first.path("totalRefundAmount").decimalValue()).isEqualByComparingTo("9.00");
        assertThat(second.path("totalOrders").asInt()).isZero();
        assertThat(orderStatus(paidOrderId)).isEqualTo(4);
    }

    // ==================== HTTP 辅助 ====================

    private String bearer(String token) {
        return "Bearer " + token;
    }

    /** 登录并返回 token（种子账号 admin / teacher / delivery） */
    private String login(String username) throws Exception {
        return postForData("/api/auth/login", null,
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}").path("token").asText();
    }

    /**
     * 创建家长账号并签发其 JWT。
     *
     * <p>不经 `/api/auth/login`：该入口按设计**拒绝纯家长账号密码登录**（家长走小程序微信登录），
     * 小程序拿到的同样是 JWT，因此这里直接用 {@link JwtTokenProvider} 签发，等价于家长端登录态。</p>
     */
    private String loginAsParent() {
        parentUsername = TAG + "-parent";
        String hash = jdbcTemplate.queryForObject(
                "SELECT password FROM sys_user WHERE username = 'admin'", String.class);
        jdbcTemplate.update("INSERT INTO sys_user (username, password, real_name, student_id, status, deleted) "
                + "VALUES (?, ?, ?, ?, 1, 0)", parentUsername, hash, TAG + "家长", studentId);
        Long userId = id("SELECT id FROM sys_user WHERE username = ?", parentUsername);
        Long roleId = id("SELECT id FROM sys_role WHERE role_code = 'PARENT'");
        jdbcTemplate.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)", userId, roleId);
        return jwtTokenProvider.generateToken(userId, parentUsername, List.of("PARENT"));
    }

    /** POST 并返回 data 中的 id（申请退款返回退款单 ID） */
    private long postForDataId(String url, String token, String body) throws Exception {
        return postForData(url, token, body).asLong();
    }

    /** 发请求并返回响应体 JSON（不校验状态码，用于断言业务码） */
    private JsonNode bodyOf(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 发请求并返回 ApiResponse.data（断言 code=200） */
    private JsonNode postForData(String url, String token, String body) throws Exception {
        var request = post(url);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        if (token != null) {
            request.header("Authorization", bearer(token));
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return root.path("data");
    }

    /** 为「非绑定学生」构造一张已支付订单（越权用例用） */
    private long newPaidOrderForOtherStudent(LocalDate start, LocalDate end, int quantity) {
        String studentNo = TAG + "-S2";
        jdbcTemplate.update("INSERT INTO student (student_no, student_name, class_id, parent_id, deleted) "
                + "VALUES (?, ?, ?, ?, 0)", studentNo, TAG + "学生2", classId, PARENT_USER_ID + 1);
        long otherStudentId = id("SELECT id FROM student WHERE student_no = ?", studentNo);
        String orderNo = TAG + "-OTHER";
        jdbcTemplate.update("INSERT INTO order_info (order_no, student_id, user_id, class_id, package_id, order_type, "
                        + "status, total_amount, pay_amount, discount_amount, delivery_start_date, delivery_end_date, "
                        + "remark, deleted) VALUES (?, ?, ?, ?, 1, 2, 2, 6.00, 6.00, 0, ?, ?, ?, 0)",
                orderNo, otherStudentId, PARENT_USER_ID + 1, classId, start, end, TAG);
        long orderId = id("SELECT id FROM order_info WHERE order_no = ?", orderNo);
        jdbcTemplate.update("INSERT INTO order_item (order_id, product_id, product_name, spec, price, quantity, "
                        + "subtotal, deleted) VALUES (?, ?, ?, '250ml', ?, ?, ?, 0)",
                orderId, productId, TAG + "奶", UNIT_PRICE, quantity,
                UNIT_PRICE.multiply(java.math.BigDecimal.valueOf(quantity)));
        deliveryTaskService.generateTasksForOrder(orderInfoService.getById(orderId));
        return orderId;
    }
}
