<script setup lang="ts">
import { computed } from 'vue';
import { Bell, Copy, Download, FileText, Reply, Sparkles, Undo2 } from 'lucide-vue-next';
import type { ChatMessage } from '../types/im';
import { avatarColor, avatarInitial, formatFileSize, formatTimeShort, parseFileMeta } from '../utils/format';
import { renderMarkdownSafe } from '../utils/markdown';

const props = defineProps<{
  message: ChatMessage;
  isMine: boolean;
  grouped: boolean;
  nowMs: number;
}>();

const emit = defineEmits<{
  (e: 'reply', msg: ChatMessage): void;
  (e: 'recall', msgId: string): void;
  (e: 'preview-image', url: string): void;
  (e: 'copied', text: string): void;
}>();

const isAi = computed(() => props.message.msgType === 'AGENT_TEXT' || props.message.senderId === '10000');
const isRecalled = computed(() => props.message.status === 2);
const isReminder = computed(() => props.message.msgType === 'REMINDER');
const fileMeta = computed(() => parseFileMeta(props.message.extra));

const canRecall = computed(() => {
  if (!props.isMine || isRecalled.value || !props.message.id) return false;
  if (props.message.msgType === 'REMINDER' || props.message.msgType === 'SYSTEM') return false;
  return props.nowMs - props.message.createdAt < 120_000;
});

const renderedMarkdown = computed(() =>
  isAi.value ? renderMarkdownSafe(props.message.content) : ''
);

async function copyMessageText() {
  try {
    await navigator.clipboard.writeText(props.message.content);
    emit('copied', '消息内容已复制');
  } catch {
    emit('copied', '复制失败，请检查浏览器剪贴板权限');
  }
}

async function handleMarkdownClick(ev: MouseEvent) {
  const target = ev.target as HTMLElement | null;
  if (!target || !target.classList.contains('code-copy-btn')) return;
  const codeBlock = target.closest('.code-block');
  const codeEl = codeBlock?.querySelector('pre code');
  const text = codeEl?.textContent || '';
  if (!text) return;
  try {
    await navigator.clipboard.writeText(text);
    const old = target.textContent;
    target.textContent = '已复制';
    window.setTimeout(() => {
      target.textContent = old || '复制';
    }, 1400);
  } catch {
    emit('copied', '代码复制失败');
  }
}
</script>

<template>
  <!-- 已撤回消息居中提示 -->
  <div v-if="isRecalled" class="recalled-row">
    <span class="recalled-pill">{{ message.content }}</span>
  </div>

  <!-- 定时提醒卡片 -->
  <div v-else-if="isReminder" class="reminder-row">
    <div class="reminder-card">
      <div class="reminder-head">
        <Bell :size="14" />
        <span>群日程定时提醒</span>
        <span class="reminder-seq tabular">#{{ message.seq }}</span>
        <span class="reminder-time tabular">{{ formatTimeShort(message.createdAt) }}</span>
      </div>
      <div class="reminder-body">{{ message.content }}</div>
    </div>
  </div>

  <!-- 普通 / 图片 / 文件 / AI 消息 -->
  <div
    v-else
    class="msg-row"
    :class="{ mine: isMine, grouped }"
  >
    <div class="avatar-col">
      <div
        v-if="!grouped"
        class="avatar avatar-sm"
        :class="{ 'avatar-ai': isAi }"
        :style="isAi ? undefined : { background: avatarColor(message.senderNickname) }"
      >
        <Sparkles v-if="isAi" :size="14" />
        <span v-else>{{ avatarInitial(message.senderNickname) }}</span>
      </div>
    </div>

    <div class="msg-col">
      <div v-if="!grouped" class="msg-meta">
        <span class="sender-name">{{ message.senderNickname }}</span>
        <span v-if="isAi" class="ai-badge">AI Agent</span>
        <span class="meta-time tabular">{{ formatTimeShort(message.createdAt) }}</span>
        <span v-if="message.seq > 0" class="meta-seq tabular">#{{ message.seq }}</span>
        <span v-else-if="message.localStatus === 'sending'" class="meta-sending">发送中...</span>
        <span v-if="message.localStatus === 'aborted'" class="meta-aborted">已中止</span>
      </div>

      <div class="bubble-wrap">
        <div
          class="bubble"
          :class="{
            'bubble-mine': isMine,
            'bubble-ai': isAi,
            'bubble-media': message.msgType === 'IMAGE'
          }"
        >
          <!-- 引用回复摘要 -->
          <div v-if="message.replyToContent" class="quote-box">
            <span class="quote-author">{{ message.replyToSender || '引用消息' }}</span>
            <span class="quote-text">{{ message.replyToContent }}</span>
          </div>

          <!-- 图片消息 -->
          <div v-if="message.msgType === 'IMAGE'" class="image-wrap">
            <img
              :src="fileMeta?.url || message.content"
              :alt="fileMeta?.fileName || '图片'"
              class="msg-image"
              loading="lazy"
              @click="emit('preview-image', fileMeta?.url || message.content)"
            />
            <div v-if="fileMeta?.instantHit" class="media-tag">SHA-256 秒传</div>
          </div>

          <!-- 文件消息 -->
          <a
            v-else-if="message.msgType === 'FILE'"
            class="file-card"
            :href="fileMeta?.url || '#'"
            :download="fileMeta?.fileName || message.content"
            target="_blank"
            rel="noopener"
          >
            <div class="file-icon">
              <FileText :size="20" />
            </div>
            <div class="file-info">
              <div class="file-name">{{ fileMeta?.fileName || message.content }}</div>
              <div class="file-sub">
                <span>{{ formatFileSize(fileMeta?.fileSize) }}</span>
                <span v-if="fileMeta?.storageType">{{ fileMeta.storageType }}</span>
                <span v-if="fileMeta?.instantHit" class="instant-flag">秒传</span>
              </div>
            </div>
            <Download :size="16" class="file-dl" />
          </a>

          <!-- AI Markdown 消息 -->
          <div
            v-else-if="isAi"
            class="md-body"
            v-html="renderedMarkdown"
            @click="handleMarkdownClick"
          />

          <!-- 纯文本消息 -->
          <div v-else class="text-body">{{ message.content }}</div>
        </div>

        <!-- 悬浮操作条 -->
        <div class="hover-actions">
          <button type="button" class="action-btn" title="复制" @click="copyMessageText">
            <Copy :size="13" />
          </button>
          <button type="button" class="action-btn" title="引用回复" @click="emit('reply', message)">
            <Reply :size="13" />
          </button>
          <button
            v-if="canRecall && message.id"
            type="button"
            class="action-btn danger"
            title="撤回消息（2分钟内）"
            @click="emit('recall', message.id)"
          >
            <Undo2 :size="13" />
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.recalled-row {
  display: flex;
  justify-content: center;
  margin: 8px 0;
  animation: recall-in 0.45s var(--ease-out);
}

@keyframes recall-in {
  from { opacity: 0; filter: blur(4px); transform: scale(0.94); }
  to { opacity: 1; filter: none; transform: none; }
}

.recalled-pill {
  font-size: 12px;
  color: var(--ink-muted);
  background: var(--surface);
  border: 1px solid var(--border);
  padding: 3px 12px;
  border-radius: 999px;
}

.reminder-row {
  display: flex;
  justify-content: center;
  margin: 12px 0;
  animation: pop-in 0.55s var(--ease-spring);
}

.reminder-card {
  position: relative;
  width: 100%;
  max-width: 540px;
  background: var(--reminder-soft);
  border: 1px solid var(--reminder-border);
  border-radius: 16px;
  padding: 12px 15px;
  overflow: hidden;
  -webkit-backdrop-filter: blur(14px) saturate(170%);
  backdrop-filter: blur(14px) saturate(170%);
  box-shadow: 0 10px 28px rgba(47, 91, 255, 0.14), inset 0 1px 0 rgba(255, 255, 255, 0.5);
}

.reminder-card::after {
  content: '';
  position: absolute;
  top: 0;
  left: -40%;
  width: 40%;
  height: 100%;
  background: linear-gradient(105deg, transparent, rgba(255, 255, 255, 0.4), transparent);
  transform: skewX(-18deg);
  animation: reminder-sheen 1.6s var(--ease-out) 0.3s;
  pointer-events: none;
}

@keyframes reminder-sheen {
  to { left: 130%; }
}

.reminder-head :deep(svg) {
  transform-origin: top center;
  animation: bell-ring 1s ease-in-out 0.2s 2;
}

@keyframes bell-ring {
  0%, 100% { transform: rotate(0); }
  20% { transform: rotate(16deg); }
  40% { transform: rotate(-14deg); }
  60% { transform: rotate(8deg); }
  80% { transform: rotate(-4deg); }
}

.reminder-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 700;
  color: var(--accent);
  margin-bottom: 5px;
}

.reminder-seq {
  font-family: var(--font-mono);
  font-weight: 500;
  opacity: 0.75;
}

.reminder-time {
  margin-left: auto;
  font-weight: 500;
  color: var(--ink-muted);
}

.reminder-body {
  font-size: 13.5px;
  white-space: pre-wrap;
  color: var(--ink);
}

.msg-row {
  display: flex;
  gap: 10px;
  margin-top: 14px;
  align-items: flex-start;
  transform-origin: left bottom;
  animation: msg-in-left 0.42s var(--ease-spring) both;
}

.msg-row.mine {
  transform-origin: right bottom;
  animation-name: msg-in-right;
}

.msg-row.grouped {
  margin-top: 4px;
}

.msg-row.mine {
  flex-direction: row-reverse;
}

.avatar-col {
  width: 32px;
  flex-shrink: 0;
}

.msg-col {
  max-width: min(74%, 640px);
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: flex-start;
}

.msg-row.mine .msg-col {
  align-items: flex-end;
}

.msg-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  color: var(--ink-muted);
  padding: 0 2px;
}

.msg-row.mine .msg-meta {
  flex-direction: row-reverse;
}

.sender-name {
  font-weight: 600;
  color: var(--ink-secondary);
}

.ai-badge {
  font-size: 10.5px;
  font-weight: 700;
  background: var(--ai-soft);
  color: var(--ai);
  border: 1px solid var(--ai-border);
  padding: 0 6px;
  border-radius: 4px;
}

.meta-seq {
  font-family: var(--font-mono);
  font-size: 10.5px;
}

.meta-sending {
  color: var(--accent);
}

.meta-aborted {
  color: var(--danger);
  font-weight: 600;
}

.bubble-wrap {
  display: flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
}

.msg-row.mine .bubble-wrap {
  flex-direction: row-reverse;
}

.bubble {
  position: relative;
  background: var(--surface-strong);
  border: 1px solid var(--glass-edge);
  color: var(--ink);
  border-radius: 18px 18px 18px 6px;
  padding: 9px 14px;
  box-shadow: 0 4px 14px rgba(34, 52, 110, 0.08), inset 0 1px 0 rgba(255, 255, 255, 0.55);
  word-break: break-word;
  max-width: 100%;
  transition: transform 0.35s var(--ease-spring), box-shadow 0.3s var(--ease-out);
}

.msg-row.grouped .bubble {
  border-radius: 18px 18px 18px 6px;
}

.msg-row:hover .bubble {
  transform: translateY(-1px);
  box-shadow: 0 8px 22px rgba(34, 52, 110, 0.13), inset 0 1px 0 rgba(255, 255, 255, 0.55);
}

.bubble-mine {
  background: var(--accent-grad);
  border-color: rgba(255, 255, 255, 0.22);
  color: #fff;
  border-radius: 18px 18px 6px 18px;
  box-shadow: 0 6px 18px rgba(58, 107, 255, 0.3), inset 0 1px 0 rgba(255, 255, 255, 0.35);
}

.msg-row.grouped .bubble-mine {
  border-radius: 18px 18px 6px 18px;
}

.msg-row:hover .bubble-mine {
  box-shadow: 0 10px 26px rgba(58, 107, 255, 0.38), inset 0 1px 0 rgba(255, 255, 255, 0.4);
}

.bubble-ai {
  background: var(--ai-soft);
  border-color: var(--ai-border);
  color: var(--ai-ink);
  -webkit-backdrop-filter: blur(14px) saturate(170%);
  backdrop-filter: blur(14px) saturate(170%);
}

.bubble-media {
  padding: 6px;
}

.quote-box {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 5px 9px;
  margin-bottom: 7px;
  border-left: 3px solid var(--accent);
  background: rgba(23, 27, 38, 0.05);
  border-radius: 0 6px 6px 0;
  font-size: 12px;
}

.bubble-mine .quote-box {
  border-left-color: rgba(255, 255, 255, 0.75);
  background: rgba(255, 255, 255, 0.16);
}

.quote-author {
  font-weight: 700;
  font-size: 11px;
  opacity: 0.85;
}

.quote-text {
  opacity: 0.85;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 320px;
}

.text-body {
  white-space: pre-wrap;
  line-height: 1.55;
}

.image-wrap {
  position: relative;
  display: inline-block;
}

.msg-image {
  display: block;
  max-width: 280px;
  max-height: 240px;
  border-radius: 13px;
  cursor: zoom-in;
  object-fit: cover;
  transition: transform 0.4s var(--ease-spring), filter 0.3s;
}

.msg-image:hover {
  transform: scale(1.025);
  filter: saturate(1.1);
}

.media-tag {
  position: absolute;
  left: 6px;
  bottom: 6px;
  background: rgba(15, 23, 42, 0.72);
  color: #fff;
  font-size: 10.5px;
  padding: 1px 6px;
  border-radius: 4px;
}

.file-card {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 220px;
  text-decoration: none;
  color: inherit;
}

.file-icon {
  width: 38px;
  height: 38px;
  border-radius: 8px;
  background: rgba(36, 87, 245, 0.12);
  color: var(--accent);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.bubble-mine .file-icon {
  background: rgba(255, 255, 255, 0.2);
  color: #fff;
}

.file-info {
  flex: 1;
  min-width: 0;
}

.file-name {
  font-weight: 600;
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.file-sub {
  display: flex;
  gap: 8px;
  font-size: 11px;
  opacity: 0.8;
}

.instant-flag {
  font-weight: 700;
}

.file-dl {
  flex-shrink: 0;
  opacity: 0.8;
}

.hover-actions {
  display: flex;
  gap: 2px;
  padding: 3px;
  border-radius: 11px;
  background: var(--surface-strong);
  border: 1px solid var(--glass-edge);
  box-shadow: 0 6px 18px rgba(34, 52, 110, 0.14), inset 0 1px 0 rgba(255, 255, 255, 0.5);
  -webkit-backdrop-filter: blur(14px) saturate(180%);
  backdrop-filter: blur(14px) saturate(180%);
  opacity: 0;
  transform: scale(0.85) translateY(4px);
  pointer-events: none;
  transition: opacity 0.2s var(--ease-out), transform 0.35s var(--ease-spring);
}

.msg-row:hover .hover-actions {
  opacity: 1;
  transform: none;
  pointer-events: auto;
}

.action-btn {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  border: none;
  background: transparent;
  color: var(--ink-secondary);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: background 0.2s, color 0.2s, transform 0.3s var(--ease-spring);
}

.action-btn:hover {
  background: var(--hover);
  color: var(--accent);
  transform: scale(1.12);
}

.action-btn:active {
  transform: scale(0.88);
}

.action-btn.danger:hover {
  color: var(--danger);
  background: rgba(224, 49, 59, 0.12);
}
</style>
