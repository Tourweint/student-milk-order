import { get, post, put, del, request } from '@/utils/request'

/** 学生分页列表 */
export function getStudentList(params: { pageNum: number; pageSize: number; classId?: number; keyword?: string }) {
  return get('/clazz/student/list', params)
}

/** 学生详情 */
export function getStudentById(id: number) {
  return get(`/clazz/student/${id}`)
}

/** 新增学生 */
export function saveStudent(data: any) {
  return post('/clazz/student', data)
}

/** 修改学生 */
export function updateStudent(data: any) {
  return put('/clazz/student', data)
}

/** 删除学生 */
export function deleteStudent(id: number) {
  return del(`/clazz/student/${id}`)
}

/** Excel 批量导入学生（需指定班级） */
export function importStudents(file: File, classId: number) {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('classId', String(classId))
  return post('/clazz/student/import', formData)
}

/** 下载学生导入模板（返回 Blob） */
export function downloadStudentTemplate() {
  return request({ url: '/clazz/student/template', method: 'GET', responseType: 'blob' })
}
