/**
 * 学生接口（微信绑定选学生用）
 */
const { get } = require('../utils/request')

/** 按学号/姓名搜索学生：{ keyword }，返回分页 StudentVO（含 id/studentNo/studentName/className） */
function searchStudents(keyword, pageSize = 10) {
  return get('/clazz/student/list', { pageNum: 1, pageSize, keyword })
}

module.exports = {
  searchStudents
}
