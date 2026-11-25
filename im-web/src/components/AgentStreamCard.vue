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
  animation: msg-in-left 0.45s var(--ease-spring) both;
}

.stream-row > .avatar {
  animation: ai-pulse 1.8s ease-in-out infinite;
}

@keyframes ai-pulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(18, 180, 140, 0.45), inset 0 1px 0 rgba(255, 255, 255, 0.35); }
  50% { box-shadow: 0 0 0 7px rgba(18, 180, 140, 0), inset 0 1px 0 rgba(255, 255, 255, 0.35); }
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
  position: relative;
  background: var(--ai-soft);
  color: var(--ai-ink);
  border-radius: 18px 18px 18px 6px;
  padding: 10px 14px;
  -webkit-backdrop-filter: blur(14px) saturate(170%);
  backdrop-filter: blur(14px) saturate(170%);
  box-shadow: 0 6px 20px rgba(18, 180, 140, 0.14), inset 0 1px 0 rgba(255, 255, 255, 0.45);
}

/* 流动渐变描边：表示 SSE 正在持续输出 */
.stream-bubble::before {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  padding: 1.5px;
  background: linear-gradient(90deg, #12B48C, #3A6BFF, #8466FF, #12B48C);
  background-size: 200% 100%;
  animation: border-flow 2.4s linear infinite;
  -webkit-mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
  -webkit-mask-composite: xor;
  mask-composite: exclude;
  pointer-events: none;
}

.stream-bubble .md-body > :last-child::after {
  content: '';
  display: inline-block;
  width: 7px;
  height: 14px;
  margin-left: 3px;
  vertical-align: -2px;
  border-radius: 2px;
  background: var(--ai);
  animation: caret-blink 1s steps(2) infinite;
}

@keyframes caret-blink {
  50% { opacity: 0; }
}

.stream-placeholder {
  font-size: 13px;
  background: linear-gradient(90deg, var(--ink-muted) 0%, var(--ai) 50%, var(--ink-muted) 100%);
  background-size: 200% 100%;
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  animation: border-flow 1.8s linear infinite;
}

.spin {
  animation: spin 1s linear infinite;
}
</style>
