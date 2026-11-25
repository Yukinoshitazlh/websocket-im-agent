<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { Users, X } from 'lucide-vue-next';
import { useChatStore } from '../stores/chat';
import type { UserProfile } from '../types/im';
import { avatarColor, avatarInitial } from '../utils/format';

const emit = defineEmits<{
  (e: 'close'): void;
}>();

const store = useChatStore();

const title = ref('');
const filterKw = ref('');
const candidates = ref<UserProfile[]>([]);
const selectedIds = ref<Set<string>>(new Set());
const submitting = ref(false);

async function loadCandidates() {
  try {
    candidates.value = await store.searchUsers(filterKw.value.trim());
  } catch {
    candidates.value = [];
  }
}

function toggleMember(uid: string) {
  const next = new Set(selectedIds.value);
  if (next.has(uid)) {
    next.delete(uid);
  } else {
    next.add(uid);
  }
  selectedIds.value = next;
}

async function handleCreate() {
  if (!title.value.trim()) {
    store.pushToast('请输入群名称', 'error');
    return;
  }
  submitting.value = true;
  try {
    await store.createGroup(title.value.trim(), Array.from(selectedIds.value));
    emit('close');
  } catch (e: any) {
    store.pushToast(e.message || '建群失败', 'error');
  } finally {
    submitting.value = false;
  }
}

onMounted(loadCandidates);
</script>

<template>
  <div class="modal-backdrop" @click.self="emit('close')">
    <div class="modal-box">
      <div class="modal-head">
        <span class="modal-title">
          <Users :size="16" />
          <span>创建协作群聊</span>
        </span>
        <button type="button" class="btn-icon" @click="emit('close')">
          <X :size="15" />
        </button>
      </div>

      <div class="modal-body">
        <label class="field">
          <span>群聊名称</span>
          <input v-model="title" class="input" placeholder="例如：后端架构评审群" />
        </label>

        <label class="field">
          <span>选择群成员（已选 {{ selectedIds.size }} 人，默认包含自己与 AI 助手）</span>
          <input
            v-model="filterKw"
            class="input"
            placeholder="输入用户名或昵称筛选..."
            @input="loadCandidates"
          />
        </label>

        <div class="member-list">
          <label
            v-for="u in candidates"
            :key="u.id"
            class="member-row"
          >
            <input
              type="checkbox"
              :checked="selectedIds.has(u.id)"
              @change="toggleMember(u.id)"
            />
            <span class="avatar avatar-sm" :style="{ background: avatarColor(u.username) }">
              {{ avatarInitial(u.nickname) }}
            </span>
            <span class="member-info">
              <strong>{{ u.nickname }}</strong>
              <small>@{{ u.username }}</small>
            </span>
          </label>
          <div v-if="candidates.length === 0" class="member-empty">未找到其他可选用户</div>
        </div>
      </div>

      <div class="modal-footer">
        <button type="button" class="btn btn-ghost" @click="emit('close')">取消</button>
        <button type="button" class="btn btn-primary" :disabled="submitting" @click="handleCreate">
          {{ submitting ? '创建中...' : '确认创建' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(12, 18, 36, 0.28);
  -webkit-backdrop-filter: blur(10px) saturate(140%);
  backdrop-filter: blur(10px) saturate(140%);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 80;
  padding: 16px;
}

.modal-box {
  position: relative;
  width: 100%;
  max-width: 440px;
  background: var(--surface-strong);
  border: 1px solid var(--glass-edge);
  border-radius: 24px;
  box-shadow: var(--shadow-md), inset 0 1px 0 rgba(255, 255, 255, 0.55);
  -webkit-backdrop-filter: blur(30px) saturate(190%);
  backdrop-filter: blur(30px) saturate(190%);
  overflow: hidden;
}

.modal-head {
  padding: 14px 16px;
  border-bottom: 1px solid var(--border);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.modal-title {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-weight: 700;
  font-size: 15px;
}

.modal-body {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink-secondary);
}

.member-list {
  max-height: 210px;
  overflow-y: auto;
  border: 1px solid var(--border);
  border-radius: 9px;
  padding: 4px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.member-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 8px;
  border-radius: 11px;
  cursor: pointer;
  transition: background 0.2s, transform 0.35s var(--ease-spring);
}

.member-row:active {
  transform: scale(0.97);
}

.member-row input[type='checkbox'] {
  accent-color: var(--accent);
  width: 15px;
  height: 15px;
}

.member-row:hover {
  background: var(--hover);
}

.member-info {
  display: flex;
  flex-direction: column;
  font-size: 13px;
}

.member-info small {
  font-size: 11px;
  color: var(--ink-muted);
}

.member-empty {
  padding: 18px;
  text-align: center;
  font-size: 12px;
  color: var(--ink-muted);
}

.modal-footer {
  padding: 12px 16px;
  border-top: 1px solid var(--border);
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
