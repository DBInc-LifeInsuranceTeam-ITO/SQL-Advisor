<template>
  <div class="awr-page">
    <div class="awr-page-header">
      <div>
        <h1>권한 관리</h1>
        <p>사용자 계정의 권한을 ADMIN / USER / MONITOR로 변경합니다.</p>
      </div>
      <button class="awr-btn" type="button" :disabled="isLoading" @click="loadUsers">
        {{ isLoading ? 'Loading...' : 'Refresh' }}
      </button>
    </div>

    <div v-if="message" class="awr-muted">{{ message }}</div>
    <div v-if="errorMessage" class="awr-empty">{{ errorMessage }}</div>

    <section class="awr-panel">
      <div class="awr-table-wrap">
        <table class="awr-table compact">
          <thead>
            <tr>
              <th>사용자</th>
              <th>이메일</th>
              <th>로그인 방식</th>
              <th>상태</th>
              <th>권한</th>
              <th>변경</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="user in users" :key="user.id">
              <td>
                <div class="user-cell">
                  <img
                    v-if="user.pictureUrl"
                    class="user-avatar"
                    :src="user.pictureUrl"
                    alt=""
                    referrerpolicy="no-referrer"
                  />
                  <div v-else class="user-avatar user-avatar-fallback">
                    {{ initial(user) }}
                  </div>
                  <span>{{ user.displayName || '-' }}</span>
                </div>
              </td>
              <td>{{ user.email }}</td>
              <td>{{ user.authProviders?.join(', ') || '-' }}</td>
              <td>{{ user.enabled ? '사용' : '비활성' }}</td>
              <td>
                <select v-model="roleDrafts[user.id]" class="awr-input compact">
                  <option value="ADMIN">ADMIN</option>
                  <option value="USER">USER</option>
                  <option value="MONITOR">MONITOR</option>
                </select>
              </td>
              <td>
                <button
                  class="awr-btn compact primary"
                  type="button"
                  :disabled="isSaving[user.id] || roleDrafts[user.id] === user.role"
                  @click="saveRole(user.id)"
                >
                  {{ isSaving[user.id] ? 'Saving...' : '저장' }}
                </button>
              </td>
            </tr>
            <tr v-if="!isLoading && !users.length">
              <td colspan="6">사용자가 없습니다.</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { getUsers, updateUserRole } from '@/api/auth'
import type { UserRole, UserSummaryResponse } from '@/types/auth'
import './awr.css'

const users = ref<UserSummaryResponse[]>([])
const roleDrafts = reactive<Record<number, UserRole>>({})
const isSaving = reactive<Record<number, boolean>>({})
const isLoading = ref(false)
const message = ref('')
const errorMessage = ref('')

onMounted(loadUsers)

async function loadUsers() {
  isLoading.value = true
  message.value = ''
  errorMessage.value = ''
  try {
    users.value = await getUsers()
    users.value.forEach((user) => {
      roleDrafts[user.id] = normalizeRole(user.role)
    })
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '사용자 목록 조회 실패'
  } finally {
    isLoading.value = false
  }
}

async function saveRole(userId: number) {
  const role = roleDrafts[userId]
  if (!role) return

  isSaving[userId] = true
  message.value = ''
  errorMessage.value = ''
  try {
    const updated = await updateUserRole(userId, { role })
    const index = users.value.findIndex((user) => user.id === userId)
    if (index >= 0) {
      users.value[index] = updated
    }
    roleDrafts[userId] = normalizeRole(updated.role)
    message.value = '권한이 변경되었습니다.'
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '권한 변경 실패'
  } finally {
    isSaving[userId] = false
  }
}

function normalizeRole(role?: string | null): UserRole {
  const normalized = role?.trim().toUpperCase()
  if (normalized === 'ADMIN') return 'ADMIN'
  if (normalized === 'MONITOR') return 'MONITOR'
  return 'USER'
}

function initial(user: UserSummaryResponse) {
  return (user.displayName || user.email || 'U').trim().charAt(0).toUpperCase()
}
</script>

<style scoped>
.user-cell {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.user-avatar {
  width: 1.9rem;
  height: 1.9rem;
  border-radius: 999px;
  object-fit: cover;
}

.user-avatar-fallback {
  display: inline-grid;
  place-items: center;
  background: #e8f5ef;
  color: #007a45;
  font-weight: 800;
}
</style>
