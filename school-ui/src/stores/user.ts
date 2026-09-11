import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getUserInfo, setUserInfo, removeUserInfo, getToken, setToken, removeToken } from '@/utils/auth'

export const useUserStore = defineStore('user', () => {
  const token = ref<string>(getToken() || '')
  const userInfo = ref<any>(getUserInfo())
  const roles = ref<string[]>(userInfo.value?.roles || [])

  function login(data: { token: string; userId: number; username: string; realName: string; roles: string[] }) {
    token.value = data.token
    userInfo.value = data
    roles.value = data.roles
    setToken(data.token)
    setUserInfo(data)
  }

  function logout() {
    token.value = ''
    userInfo.value = null
    roles.value = []
    removeToken()
    removeUserInfo()
  }

  return {
    token,
    userInfo,
    roles,
    login,
    logout
  }
})
