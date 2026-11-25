<script setup lang="ts">
import { MessageSquareText, X } from 'lucide-vue-next';
import type { ForwardBundleMeta } from '../types/im';
import { avatarColor, avatarInitial, formatDateDivider } from '../utils/format';

defineProps<{
  bundle: ForwardBundleMeta;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
}>();
</script>

<template>
  <div class="modal-backdrop" @click.self="emit('close')">
    <div class="modal-box bundle-modal">
      <div class="modal-head">
        <span class="modal-title">
          <MessageSquareText :size="16" class="head-icon" />
          <span>{{ bundle.title }}</span>
        </span>
        <button type="button" class="btn-icon" @click="emit('close')">
          <X :size="15" />
        </button>
      </div>

      <div class="bundle-list">
        <div v-for="(item, i) in bundle.items" :key="i" class="bundle-item">
          <div class="avatar avatar-sm" :style="{ background: avatarColor(item.senderNickname) }">
            {{ avatarInitial(item.senderNickname) }}
          </div>
          <div class="bundle-body">
            <div class="bundle-meta">
              <strong>{{ item.senderNickname }}</strong>
              <span class="tabular">{{ formatDateDivider(item.createdAt) }}</span>
            </div>
            <div class="bundle-text">{{ item.content }}</div>
          </div>
        </div>
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
  z-index: 95;
  padding: 16px;
}

.bundle-modal {
  width: 100%;
  max-width: 520px;
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

.head-icon {
  color: var(--accent);
}

.bundle-list {
  padding: 14px 16px;
  max-height: 65vh;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.bundle-item {
  display: flex;
  gap: 10px;
  padding-bottom: 11px;
  border-bottom: 1px solid var(--border);
}

.bundle-item:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.bundle-body {
  flex: 1;
  min-width: 0;
}

.bundle-meta {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  margin-bottom: 3px;
}

.bundle-meta span {
  color: var(--ink-muted);
  font-size: 11px;
}

.bundle-text {
  font-size: 13px;
  color: var(--ink-secondary);
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
