<template>
  <div class="awr-page admin-page user-management-page">
    <div class="admin-hero">
      <div>
        <p class="admin-eyebrow">System Administration</p>

        <h1 class="awr-main-title">
          사용자 권한 관리
        </h1>

        <p class="admin-hero-description">
          사용자 계정의 서비스 접근 상태를 확인하고
          ADMIN, USER, MONITOR 권한을 관리합니다.
        </p>
      </div>

      <div class="admin-hero-actions">
        <button
          class="awr-btn admin-secondary-button"
          type="button"
          :disabled="isLoading"
          @click="loadUsers"
        >
          {{ isLoading ? '사용자 조회 중' : '사용자 새로고침' }}
        </button>
      </div>
    </div>

    <div
      v-if="message"
      class="admin-message success"
    >
      {{ message }}
    </div>

    <div
      v-if="errorMessage"
      class="admin-message error"
    >
      {{ errorMessage }}
    </div>

    <section
      class="awr-panel admin-panel user-management-panel"
    >
      <div class="admin-section-header">
        <div>
          <h2>사용자 목록</h2>

          <p>
            사용자별 로그인 방식과 현재 권한을 확인하고 변경할 수 있습니다.
          </p>
        </div>

        <span class="admin-count-badge">
          총 {{ users.length }}명
        </span>
      </div>

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
            <tr
              v-for="user in users"
              :key="user.id"
            >
              <td>
                <div class="user-cell">
                  <img
                    v-if="user.pictureUrl"
                    class="user-avatar"
                    :src="user.pictureUrl"
                    alt=""
                    referrerpolicy="no-referrer"
                  />

                  <div
                    v-else
                    class="user-avatar user-avatar-fallback"
                  >
                    {{ initial(user) }}
                  </div>

                  <span>
                    {{ user.displayName || '-' }}
                  </span>
                </div>
              </td>

              <td>
                {{ user.email }}
              </td>

              <td>
                {{ user.authProviders?.join(', ') || '-' }}
              </td>

              <td>
                <span
                  :class="[
                    'user-status-chip',
                    user.enabled ? 'enabled' : 'disabled'
                  ]"
                >
                  {{ user.enabled ? '사용' : '비활성' }}
                </span>
              </td>

              <td>
                <select
                  v-model="roleDrafts[user.id]"
                  class="awr-input compact"
                >
                  <option value="ADMIN">
                    ADMIN
                  </option>

                  <option value="USER">
                    USER
                  </option>

                  <option value="MONITOR">
                    MONITOR
                  </option>
                </select>
              </td>

              <td>
                <button
                  class="awr-btn compact admin-primary-button"
                  type="button"
                  :disabled="
                    isSaving[user.id] ||
                    roleDrafts[user.id] === user.role
                  "
                  @click="saveRole(user.id)"
                >
                  {{
                    isSaving[user.id]
                      ? '저장 중'
                      : '권한 저장'
                  }}
                </button>
              </td>
            </tr>

            <tr
              v-if="!isLoading && !users.length"
            >
              <td
                colspan="6"
                class="user-management-empty"
              >
                등록된 사용자가 없습니다.
              </td>
            </tr>

            <tr v-if="isLoading && !users.length">
              <td
                colspan="6"
                class="user-management-empty"
              >
                사용자 목록을 불러오는 중입니다.
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import {
  onMounted,
  reactive,
  ref
} from 'vue'
import {
  getUsers,
  updateUserRole
} from '@/api/auth'
import type {
  UserRole,
  UserSummaryResponse
} from '@/types/auth'
import './awr.css'

const users = ref<UserSummaryResponse[]>([])

const roleDrafts = reactive<
  Record<number, UserRole>
>({})

const isSaving = reactive<
  Record<number, boolean>
>({})

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
      roleDrafts[user.id] = normalizeRole(
        user.role
      )
    })
  } catch (error) {
    errorMessage.value =
      error instanceof Error
        ? error.message
        : '사용자 목록 조회에 실패했습니다.'
  } finally {
    isLoading.value = false
  }
}

async function saveRole(userId: number) {
  const role = roleDrafts[userId]

  if (!role) {
    return
  }

  isSaving[userId] = true
  message.value = ''
  errorMessage.value = ''

  try {
    const updated = await updateUserRole(
      userId,
      {
        role
      }
    )

    const index = users.value.findIndex(
      (user) => user.id === userId
    )

    if (index >= 0) {
      users.value[index] = updated
    }

    roleDrafts[userId] = normalizeRole(
      updated.role
    )

    message.value =
      '사용자 권한이 변경되었습니다.'
  } catch (error) {
    errorMessage.value =
      error instanceof Error
        ? error.message
        : '사용자 권한 변경에 실패했습니다.'
  } finally {
    isSaving[userId] = false
  }
}

function normalizeRole(
  role?: string | null
): UserRole {
  const normalized =
    role?.trim().toUpperCase()

  if (normalized === 'ADMIN') {
    return 'ADMIN'
  }

  if (normalized === 'MONITOR') {
    return 'MONITOR'
  }

  return 'USER'
}

function initial(
  user: UserSummaryResponse
) {
  return (
    user.displayName ||
    user.email ||
    'U'
  )
    .trim()
    .charAt(0)
    .toUpperCase()
}
</script>

<style scoped>
.user-cell {
  display: flex;
  align-items: center;
  gap: 0.55rem;
  min-width: 9rem;
}

.user-cell > span {
  overflow: hidden;
  font-weight: 800;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-avatar {
  width: 2rem;
  height: 2rem;
  flex: 0 0 auto;
  border-radius: 999px;
  object-fit: cover;
}

.user-avatar-fallback {
  display: inline-grid;
  place-items: center;
  border: 1px solid #d8d6f1;
  background: #eeecff;
  color: #554ab8;
  font-size: 0.78rem;
  font-weight: 900;
}

.user-status-chip {
  display: inline-flex;
  min-height: 1.55rem;
  align-items: center;
  justify-content: center;
  border: 1px solid;
  border-radius: 999px;
  padding: 0.12rem 0.55rem;
  font-size: 0.72rem;
  font-weight: 850;
  white-space: nowrap;
}

.user-status-chip.enabled {
  border-color: #c5ded0;
  background: #edf8f2;
  color: #217a52;
}

.user-status-chip.disabled {
  border-color: #e2e8f0;
  background: #f8fafc;
  color: #64748b;
}

.user-management-empty {
  padding: 2rem 1rem;
  color: #64748b;
  font-size: 0.84rem;
  text-align: center;
}
</style>
