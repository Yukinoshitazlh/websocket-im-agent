<script setup lang="ts">
import { Forward, Users, X } from 'lucide-vue-next';
import { useChatStore } from '../stores/chat';
import { avatarColor, avatarInitial } from '../utils/format';

const emit = defineEmits<{
  (e: 'close'): void;
}>();

const store = useChatStore();

function handleForwardTo(convId: string) {
  const ok = store.sendForwardBundle(convId);
  if (ok) {
    emit('close');
  }
}
</script>

<template>
  <div class="modal-backdrop" @click.self="emit('close')">
    <div class="modal-box fwd-modal">
      <div class="modal-head">
        <span class="modal-title">
          <Forward :size="16" />
          <span>合并转发 {{ store.selectedMsgIds.length }} 条消息给</span>
        </span>
        <button type="button" class="btn-icon" @click="emit('close')">
          <X :size="15" />
        </button>
      </div>

      <div class="target-list">
        <button
          v-for="c in store.conversations"
          :key="c.id"
          type="button"
          class="target-row"
          @click="handleForwardTo(c.id)"
        >
          <div
            class="avatar avatar-sm"
            :style="{ background: c.type === 2 ? '#0D7A5F' : avatarColor(c.title) }"
          >
            <Users v-if="c.type === 2" :size="14" />
            <span v-else>{{ avatarInitial(c.title) }}</span>
          </div>
          <div class="target-meta">
            <strong>{{ c.title }}</strong>
            <small>{{ c.type === 2 ? `群聊 (${c.memberCount || 2}人)` : '单聊会话' }}</small>
          </div>
          <span class="send-tag">转发</span>
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
  z-index: 90;
  padding: 16px;
}

.fwd-modal {
  width: 100%;
  max-width: 420px;
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
  font-size: 14.5px;
}

.target-list {
  padding: 10px;
  max-height: 360px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.target-row {
  width: 100%;
  border: 1px solid transparent;
  background: transparent;
  color: var(--ink);
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 10px;
  border-radius: 13px;
  cursor: pointer;
  text-align: left;
  transition: background 0.2s, transform 0.35s var(--ease-spring), border-color 0.2s;
}

.target-row:hover {
  background: var(--hover);
  border-color: var(--glass-edge);
  transform: translateX(3px);
}

.target-meta {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  font-size: 13px;
}

.target-meta small {
  font-size: 11px;
  color: var(--ink-muted);
}

.send-tag {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--accent);
  background: var(--accent-soft);
  padding: 3px 10px;
  border-radius: 999px;
}
</style>
