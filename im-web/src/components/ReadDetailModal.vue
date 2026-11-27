<script setup lang="ts">
import { computed } from 'vue';
import { CheckCheck, Clock, X } from 'lucide-vue-next';
import type { ChatMessage, ConversationMember } from '../types/im';
import UserAvatar from './UserAvatar.vue';

const props = defineProps<{
  message: ChatMessage;
  members: ConversationMember[];
}>();

const emit = defineEmits<{
  (e: 'close'): void;
}>();

const others = computed(() =>
  props.members.filter((m) => String(m.userId) !== String(props.message.senderId))
);

const readMembers = computed(() =>
  others.value.filter((m) => (m.lastReadSeq || 0) >= props.message.seq)
);

const unreadMembers = computed(() =>
  others.value.filter((m) => (m.lastReadSeq || 0) < props.message.seq)
);
</script>

<template>
  <div class="modal-backdrop" @click.self="emit('close')">
    <div class="modal-box read-modal">
      <div class="modal-head">
        <div class="head-title">
          <CheckCheck :size="16" class="head-icon" />
          <span>消息已读详情</span>
          <span class="seq-tag tabular">#{{ message.seq }}</span>
        </div>
        <button type="button" class="btn-icon" @click="emit('close')">
          <X :size="15" />
        </button>
      </div>

      <div class="read-summary">
        <span class="stat ok tabular">已读 {{ readMembers.length }} 人</span>
        <span class="stat muted tabular">未读 {{ unreadMembers.length }} 人</span>
      </div>

      <div class="read-columns">
        <div class="col">
          <div class="col-title ok">
            <CheckCheck :size="13" />
            <span>已读 ({{ readMembers.length }})</span>
          </div>
          <div class="col-list">
            <div v-for="m in readMembers" :key="m.userId" class="person-row">
              <UserAvatar :name="m.nickname" :seed="m.username" :src="m.avatar" :online="m.online" size="sm" />
              <div class="person-meta">
                <strong>{{ m.nickname }}</strong>
                <small>已读</small>
              </div>
            </div>
            <div v-if="readMembers.length === 0" class="empty-col">暂无人已读</div>
          </div>
        </div>

        <div class="col">
          <div class="col-title">
            <Clock :size="13" />
            <span>未读 ({{ unreadMembers.length }})</span>
          </div>
          <div class="col-list">
            <div v-for="m in unreadMembers" :key="m.userId" class="person-row">
              <UserAvatar :name="m.nickname" :seed="m.username" :src="m.avatar" :online="m.online" size="sm" />
              <div class="person-meta">
                <strong>{{ m.nickname }}</strong>
                <small>{{ m.online ? '在线' : '离线' }}</small>
              </div>
            </div>
            <div v-if="unreadMembers.length === 0" class="empty-col">全员已读</div>
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
  z-index: 90;
  padding: 16px;
}

.read-modal {
  width: 100%;
  max-width: 500px;
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

.head-title {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-weight: 700;
  font-size: 15px;
}

.head-icon {
  color: var(--ok);
}

.seq-tag {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--ink-muted);
  background: var(--hover);
  padding: 1px 7px;
  border-radius: 999px;
}

.read-summary {
  display: flex;
  gap: 10px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--border);
  font-size: 12.5px;
  font-weight: 600;
}

.stat.ok {
  color: var(--ok);
}

.stat.muted {
  color: var(--ink-muted);
}

.read-columns {
  display: grid;
  grid-template-columns: 1fr 1fr;
  min-height: 210px;
  max-height: 340px;
}

.col {
  display: flex;
  flex-direction: column;
  padding: 12px;
  overflow-y: auto;
}

.col:first-child {
  border-right: 1px solid var(--border);
}

.col-title {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  font-weight: 700;
  color: var(--ink-secondary);
  margin-bottom: 8px;
}

.col-title.ok {
  color: var(--ok);
}

.col-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.person-row {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 6px 7px;
  border-radius: 10px;
  background: var(--surface);
  border: 1px solid var(--border);
}

.person-meta {
  display: flex;
  flex-direction: column;
  font-size: 12.5px;
  min-width: 0;
}

.person-meta small {
  font-size: 11px;
  color: var(--ink-muted);
}

.empty-col {
  padding: 20px 6px;
  text-align: center;
  font-size: 12px;
  color: var(--ink-muted);
}
</style>
