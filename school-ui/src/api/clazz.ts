import { get, post, put, del } from '@/utils/request'

// ==================== 年级 ====================

/** 年级列表 */
export function getGradeList() {
  return get('/clazz/grade/list')
}

/** 新增年级 */
export function saveGrade(data: any) {
  return post('/clazz/grade', data)
}

/** 修改年级 */
export function updateGrade(data: any) {
  return put('/clazz/grade', data)
}

/** 删除年级 */
export function deleteGrade(id: number) {
  return del(`/clazz/grade/${id}`)
}

// ==================== 班级 ====================

/** 班级分页列表 */
export function getClassList(params: { pageNum: number; pageSize: number; gradeId?: number }) {
  return get('/clazz/class/list', params)
}

/** 全部班级（下拉用） */
export function getAllClass() {
  return get('/clazz/class/all')
}

/** 班主任下拉（TEACHER 角色用户） */
export function getTeacherOptions() {
  return get('/clazz/class/teachers')
}

/** 班级详情 */
export function getClassById(id: number) {
  return get(`/clazz/class/${id}`)
}

/** 新增班级 */
export function saveClass(data: any) {
  return post('/clazz/class', data)
}

/** 修改班级 */
export function updateClass(data: any) {
  return put('/clazz/class', data)
}

/** 删除班级 */
export function deleteClass(id: number) {
  return del(`/clazz/class/${id}`)
}
