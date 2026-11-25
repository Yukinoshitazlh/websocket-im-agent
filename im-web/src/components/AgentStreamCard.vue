<script setup lang="ts">
import { computed } from 'vue';
import { Loader2, Sparkles, Square } from 'lucide-vue-next';
import type { AgentStreamState } from '../types/im';
import { renderMarkdownSafe } from '../utils/markdown';

const props = defineProps<{
  stream: AgentStreamState;
}>();

const emit = defineEmits<{
  (e: 'cancel', taskId: string, conversationId: string): void;
}>();

const stageLabel = computed(() => {
  if (props.stream.stopping) return '正在中断生成...';
  switch (props.stream.stage) {
    case 'thinking':
      return '正在规划思路...';
    case 'tool_call':
      return `正在调用工具：${props.stream.toolName || 'tool'}`;
    case 'delta':
      return 'SSE 流式生成中...';
    case 'error':
      return props.stream.errorMessage || '执行异常';
    default:
      return '处理中...';
  }
});

const htmlContent = computed(() => renderMarkdownSafe(props.stream.text));
</script>

<template>
  <div class="stream-row">
    <div class="avatar avatar-sm avatar-ai">
      <Sparkles :size="14" />
    </div>

    <div class="stream-col">
      <div class="stream-head">
        <span class="stream-name">AI 助手</span>
        <span class="stream-stage" :class="{ error: stream.stage === 'error' }">
          <Loader2 v-if="stream.stage !== 'error'" :size="12" class="spin" />
          <span>{{ stageLabel }}</span>
        </span>
        <button
          v-if="stream.stage !== 'error'"
          type="button"
          class="btn btn-danger btn-xs stop-btn"
          :disabled="stream.stopping"
          @click="emit('cancel', stream.taskId, stream.conversationId)"
        >
          <Square :size="11" />
          <span>{{ stream.stopping ? '中止中' : '停止生成' }}</span>
        </button>
      </div>

      <div class="stream-bubble">
        <div v-if="stream.text" class="md-body" v-html="htmlContent" />
        <div v-else class="stream-placeholder">{{ stageLabel }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.stream-row {
  display: flex;
  gap: 10px;
  margin-top: 14px;
  align-items: flex-start;
}

.stream-col {
  max-width: min(76%, 660px);
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.stream-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.stream-name {
  font-size: 12px;
  font-weight: 700;
  color: var(--ai);
}

.stream-stage {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
  color: var(--ink-secondary);
}

.stream-stage.error {
  color: var(--danger);
}

.stop-btn {
  padding: 2px 8px;
  font-size: 11px;
}

.stream-bubble {
  background: var(--ai-soft);
  border: 1px dashed var(--ai-border);
  color: var(--ai-ink);
  border-radius: 12px;
  padding: 10px 14px;
}

.stream-placeholder {
  font-size: 13px;
  color: var(--ink-secondary);
}

.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>
