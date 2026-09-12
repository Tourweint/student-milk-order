/**
 * 学生接口（微信绑定选学生用）
 */
const { get } = require('../utils/request')

/** 按学号/姓名搜索学生（绑定流程免认证接口），返回 [{ id, studentNo, studentName, className }] */
function searchStudents(keyword) {
  return get('/auth/bind-student/search', { keyword })
}

module.exports = {
  searchStudents
}
