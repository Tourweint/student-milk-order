import { ref } from 'vue'
import { getAllergyOptions } from '@/api/order'

/**
 * 过敏原受控枚举的前端缓存与文案映射。
 *
 * 选项**只有后端一份**（`GET /api/order/allergy-options`）：命中判定靠编码相等，
 * 两端各自硬编码中文必然漂移成"配了却永不命中"。这里只做一次性缓存与编码→文案映射。
 */
export interface AllergenOption {
  code: string
  /** 奶品侧文案（成分，如 乳糖） */
  text: string
  /** 学生侧文案（禁忌说法，如 乳糖不耐） */
  studentText: string
}

const options = ref<AllergenOption[]>([])
let loaded = false

/** 加载选项（首次请求后端，之后复用缓存） */
export async function ensureAllergenOptions(): Promise<AllergenOption[]> {
  if (!loaded) {
    const res: any = await getAllergyOptions()
    options.value = (res?.data ?? []) as AllergenOption[]
    loaded = true
  }
  return options.value
}

/** 逗号分隔编码 → 编码数组（忽略空白与未知项） */
export function parseAllergenCodes(codes?: string | null): string[] {
  if (!codes) return []
  return codes.split(',').map((c) => c.trim()).filter(Boolean)
}

/** 编码数组 → 文案数组；side 决定用哪一侧说法 */
export function allergenTexts(codes?: string | null, side: 'product' | 'student' = 'product'): string[] {
  const known = parseAllergenCodes(codes)
  if (!known.length) return []
  return known.map((code) => {
    const hit = options.value.find((o) => o.code === code)
    if (!hit) return code
    return side === 'student' ? hit.studentText : hit.text
  })
}

/** 编码串 → 一行展示文案（无标签显示占位符） */
export function allergenLabel(codes?: string | null, side: 'product' | 'student' = 'product'): string {
  const texts = allergenTexts(codes, side)
  return texts.length ? texts.join('、') : '—'
}
