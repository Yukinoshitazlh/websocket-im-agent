<script setup lang="ts">
import { Check, ShieldAlert, X } from 'lucide-vue-next';
import type { ApprovalCardState } from '../types/im';

defineProps<{
  card: ApprovalCardState;
}>();

const emit = defineEmits<{
  (e: 'decide', card: ApprovalCardState, approved: boolean): void;
}>();
</script>

<template>
  <div class="approval-wrap">
    <div class="approval-card">
      <div class="approval-head">
        <ShieldAlert :size="15" />
        <span>高风险动作人工审批（Human-in-the-Loop）</span>
        <span class="action-tag">{{ card.actionName }}</span>
      </div>

      <div class="approval-summary">{{ card.summary }}</div>

      <div class="approval-footer">
        <template v-if="!card.decided">
          <button
            type="button"
            class="btn btn-success btn-xs"
            @click="emit('decide', card, true)"
          >
            <Check :size="13" />
            <span>同意执行</span>
          </button>
          <button
            type="button"
            class="btn btn-ghost btn-xs"
            @click="emit('decide', card, false)"
          >
            <X :size="13" />
            <span>拒绝</span>
          </button>
        </template>
        <span
          v-else
          class="decided-pill"
          :class="card.decided"
        >
          {{ card.decided === 'approved' ? '已同意，正在恢复 Agent 执行...' : '已拒绝该高风险动作' }}
        </span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.approval-wrap {
  margin: 12px 0 6px 42px;
  max-width: 540px;
  animation: pop-in 0.55s var(--ease-spring) both;
}

.approval-card {
  background: var(--warn-soft);
  border: 1px solid var(--warn-border);
  border-radius: 18px;
  padding: 13px 15px;
  display: flex;
  flex-direction: column;
  gap: 9px;
  -webkit-backdrop-filter: blur(16px) saturate(170%);
  backdrop-filter: blur(16px) saturate(170%);
  box-shadow: 0 10px 28px rgba(245, 158, 11, 0.16), inset 0 1px 0 rgba(255, 255, 255, 0.5);
  animation: approval-glow 2.4s ease-in-out infinite;
}

@keyframes approval-glow {
  0%, 100% { box-shadow: 0 10px 28px rgba(245, 158, 11, 0.14), inset 0 1px 0 rgba(255, 255, 255, 0.5); }
  50% { box-shadow: 0 10px 34px rgba(245, 158, 11, 0.3), 0 0 0 4px rgba(245, 158, 11, 0.08), inset 0 1px 0 rgba(255, 255, 255, 0.5); }
}

.approval-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  font-weight: 700;
  color: var(--warn);
}

.action-tag {
  margin-left: auto;
  font-family: var(--font-mono);
  font-size: 11px;
  background: rgba(180, 83, 9, 0.12);
  padding: 1px 6px;
  border-radius: 4px;
}

.approval-summary {
  font-size: 13px;
  color: var(--ink);
  white-space: pre-wrap;
}

.approval-footer {
  display: flex;
  gap: 8px;
  align-items: center;
}

.decided-pill {
  font-size: 12px;
  font-weight: 600;
  animation: pop-in 0.4s var(--ease-spring);
}

.decided-pill.approved {
  color: var(--ok);
}

.decided-pill.rejected {
  color: var(--danger);
}
</style>
