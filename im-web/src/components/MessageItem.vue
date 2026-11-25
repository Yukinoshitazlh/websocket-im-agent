<script setup lang="ts">
import { computed } from 'vue';
import {
  Bell,
  Check,
  Download,
  FileText,
  MessageSquareText,
  MoreHorizontal,
  Reply,
  Sparkles
} from 'lucide-vue-next';
import type { ChatMessage, ConversationMember, ForwardBundleMeta } from '../types/im';
import {
  avatarColor,
  avatarInitial,
  formatFileSize,
  formatTimeShort,
  parseFileMeta,
  parseForwardBundle
} from '../utils/format';
import { renderMarkdownSafe } from '../utils/markdown';

const SLIM_EMOJIS = ['👍', '❤️', '🔥'];

const props = defineProps<{
  message: ChatMessage;
  isMine: boolean;
  isGroup: boolean;
  demoMode: boolean;
  grouped: boolean;
  members: ConversationMember[];
  multiSelect: boolean;
  selected: boolean;
  highlighted: boolean;
}>();

const emit = defineEmits<{
  (e: 'reply', msg: ChatMessage): void;
  (e: 'react', msgId: string, emoji: string): void;
  (e: 'open-context-menu', ev: MouseEvent, msg: ChatMessage): void;
  (e: 'toggle-select', msgId: string): void;
  (e: 'open-read-detail', msg: ChatMessage): void;
  (e: 'open-bundle', meta: ForwardBundleMeta): void;
  (e: 'jump-to-msg-id', msgId: string): void;
  (e: 'preview-image', url: string): void;
  (e: 'copied', text: string): void;
}>();

const isAi = computed(() => props.message.msgType === 'AGENT_TEXT' || props.message.senderId === '10000');
const isRecalled = computed(() => props.message.status === 2);
const isReminder = computed(() => props.message.msgType === 'REMINDER');
const isSystem = computed(() => props.message.msgType === 'SYSTEM');
const fileMeta = computed(() => parseFileMeta(props.message.extra));
const bundleMeta = computed(() =>
  props.message.msgType === 'FORWARD_BUNDLE' ? parseForwardBundle(props.message.extra) : null
);

// 微信/飞书节奏：单聊日常模式隐藏重复昵称，群聊/AI/演练模式才显示头部信息
const showMetaHeader = computed(() => {
  if (props.grouped) return false;
  if (props.demoMode) return true;
  if (isAi.value) return true;
  return props.isGroup && !props.isMine;
});

const renderedMarkdown = computed(() =>
  isAi.value ? renderMarkdownSafe(props.message.content) : ''
);

// 飞书零写放大已读进度环计算
const readStats = computed(() => {
  if (!props.isMine || props.message.seq <= 0 || isRecalled.value || isSystem.value) {
    return null;
  }
  const others = props.members.filter((m) => String(m.userId) !== String(props.message.senderId));
  if (others.length === 0) return null;
  const readCount = others.filter((m) => (m.lastReadSeq || 0) >= props.message.seq).length;
  const total = others.length;
  const ratio = readCount / total;
  return {
    readCount,
    total,
    allRead: readCount >= total,
    ratio,
    dashOffset: 37.7 * (1 - ratio)
  };
});

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

function handleRowClick() {
  if (props.multiSelect && props.message.id && !isRecalled.value && !isSystem.value) {
    emit('toggle-select', props.message.id);
  }
}

function handleContextMenu(ev: MouseEvent) {
  if (props.multiSelect || isRecalled.value || isSystem.value || !props.message.id) return;
  ev.preventDefault();
  emit('open-context-menu', ev, props.message);
}
</script>

<template>
  <!-- 系统灰条消息 -->
  <div v-if="isSystem" class="recalled-row" :data-msg-seq="message.seq">
    <span class="recalled-pill system-pill">{{ message.content }}</span>
  </div>

  <!-- 已撤回消息居中提示 -->
  <div v-else-if="isRecalled" class="recalled-row" :data-msg-seq="message.seq">
    <span class="recalled-pill">{{ message.content }}</span>
  </div>

  <!-- 定时提醒卡片 -->
  <div
    v-else-if="isReminder"
    class="reminder-row"
    :class="{ highlighted }"
    :data-msg-seq="message.seq"
    :data-msg-id="message.id"
  >
    <div class="reminder-card">
      <div class="reminder-head">
        <Bell :size="14" />
        <span>群日程定时提醒</span>
        <span v-if="demoMode" class="reminder-seq tabular">#{{ message.seq }}</span>
        <span class="reminder-time tabular">{{ formatTimeShort(message.createdAt) }}</span>
      </div>
      <div class="reminder-body">{{ message.content }}</div>
    </div>
  </div>

  <!-- 普通 / 图片 / 文件 / 合并转发 / AI 消息 -->
  <div
    v-else
    class="msg-row"
    :class="{ mine: isMine, grouped, selectable: multiSelect, selected, highlighted }"
    :data-msg-seq="message.seq"
    :data-msg-id="message.id"
    @click="handleRowClick"
  >
    <!-- 多选勾选框 -->
    <div v-if="multiSelect && message.id" class="select-col" @click.stop="emit('toggle-select', message.id)">
      <span class="select-circle" :class="{ checked: selected }">
        <Check v-if="selected" :size="12" />
      </span>
    </div>

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
      <div v-if="showMetaHeader" class="msg-meta">
        <span class="sender-name">{{ message.senderNickname }}</span>
        <span v-if="isAi" class="ai-badge">AI</span>
        <span v-if="demoMode && message.seq > 0" class="meta-seq tabular">#{{ message.seq }}</span>
        <span v-if="message.localStatus === 'sending'" class="meta-sending">发送中...</span>
        <span v-if="message.localStatus === 'aborted'" class="meta-aborted">已中止</span>
      </div>

      <div class="bubble-wrap">
        <div
          class="bubble"
          :class="{
            'bubble-mine': isMine,
            'bubble-ai': isAi,
            'bubble-media': message.msgType === 'IMAGE',
            'bubble-bundle': message.msgType === 'FORWARD_BUNDLE'
          }"
          :title="formatTimeShort(message.createdAt)"
          @contextmenu="handleContextMenu"
        >
          <!-- 引用回复摘要（点击可平滑回跳原文并高亮） -->
          <button
            v-if="message.replyToContent"
            type="button"
            class="quote-box"
            title="点击定位到被引用的原消息"
            @click.stop="message.replyToMsgId && emit('jump-to-msg-id', message.replyToMsgId)"
          >
            <span class="quote-author">{{ message.replyToSender || '引用消息' }}</span>
            <span class="quote-text">{{ message.replyToContent }}</span>
          </button>

          <!-- 微信式合并转发 [聊天记录] 卡片 -->
          <div
            v-if="message.msgType === 'FORWARD_BUNDLE' && bundleMeta"
            class="bundle-card"
            @click.stop="emit('open-bundle', bundleMeta)"
          >
            <div class="bundle-card-title">
              <MessageSquareText :size="14" />
              <span>{{ bundleMeta.title }}</span>
            </div>
            <div class="bundle-card-lines">
              <div v-for="(it, idx) in bundleMeta.items.slice(0, 4)" :key="idx" class="bundle-line">
                <strong>{{ it.senderNickname }}:</strong> {{ it.content }}
              </div>
            </div>
            <div class="bundle-card-foot">
              聊天记录 (共 {{ bundleMeta.items.length }} 条，点击展开)
            </div>
          </div>

          <!-- 图片消息 -->
          <div v-else-if="message.msgType === 'IMAGE'" class="image-wrap">
            <img
              :src="fileMeta?.url || message.content"
              :alt="fileMeta?.fileName || '图片'"
              class="msg-image"
              loading="lazy"
              @click.stop="emit('preview-image', fileMeta?.url || message.content)"
            />
            <div v-if="demoMode && fileMeta?.instantHit" class="media-tag">SHA-256 秒传</div>
          </div>

          <!-- 文件消息 -->
          <a
            v-else-if="message.msgType === 'FILE'"
            class="file-card"
            :href="fileMeta?.url || '#'"
            :download="fileMeta?.fileName || message.content"
            target="_blank"
            rel="noopener"
            @click.stop
          >
            <div class="file-icon">
              <FileText :size="20" />
            </div>
            <div class="file-info">
              <div class="file-name">{{ fileMeta?.fileName || message.content }}</div>
              <div class="file-sub">
                <span>{{ formatFileSize(fileMeta?.fileSize) }}</span>
                <span v-if="demoMode && fileMeta?.storageType">{{ fileMeta.storageType }}</span>
                <span v-if="demoMode && fileMeta?.instantHit" class="instant-flag">秒传</span>
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

        <!-- 飞书已读进度圆环 -->
        <button
          v-if="readStats && !multiSelect"
          type="button"
          class="read-ring-btn"
          :class="{ all: readStats.allRead }"
          :title="
            readStats.allRead
              ? (readStats.total === 1 ? '对方已读（点击查看详情）' : `全部已读 ${readStats.readCount}/${readStats.total}`)
              : (readStats.total === 1 ? '对方未读' : `${readStats.readCount}/${readStats.total} 人已读（点击查看明细）`)
          "
          @click.stop="emit('open-read-detail', message)"
        >
          <svg viewBox="0 0 18 18" width="17" height="17">
            <circle cx="9" cy="9" r="6" class="ring-track" />
            <circle
              v-if="readStats.readCount > 0 && !readStats.allRead"
              cx="9"
              cy="9"
              r="6"
              class="ring-fill"
              :stroke-dasharray="37.7"
              :stroke-dashoffset="readStats.dashOffset"
            />
            <circle v-if="readStats.allRead" cx="9" cy="9" r="7" class="ring-done-bg" />
            <path
              v-if="readStats.allRead"
              d="M6.2 9.2 L8.2 11.1 L12 7.2"
              class="ring-check"
            />
          </svg>
        </button>

        <!-- 悬停显示精确时间（单聊清爽模式下的温和提示） -->
        <span v-if="!multiSelect && !showMetaHeader" class="hover-time tabular">
          {{ formatTimeShort(message.createdAt) }}
        </span>

        <!-- 精简悬浮浮条（仅 3 个高频表情 + 回复 + 更多；右键亦可呼出完整菜单） -->
        <div v-if="!multiSelect && message.id" class="hover-actions" @click.stop>
          <button
            v-for="emo in SLIM_EMOJIS"
            :key="emo"
            type="button"
            class="emoji-btn"
            :title="`表态 ${emo}`"
            @click="emit('react', message.id, emo)"
          >
            {{ emo }}
          </button>
          <span class="action-sep" />
          <button type="button" class="action-btn" title="引用回复" @click="emit('reply', message)">
            <Reply :size="13" />
          </button>
          <button
            type="button"
            class="action-btn"
            title="更多操作（复制 / 置顶 / 多选 / 撤回）"
            @click="emit('open-context-menu', $event, message)"
          >
            <MoreHorizontal :size="14" />
          </button>
        </div>
      </div>

      <!-- 气泡下方表情表态聚合胶囊 -->
      <div v-if="message.reactions && message.reactions.length > 0 && message.id" class="reaction-bar" @click.stop>
        <button
          v-for="g in message.reactions"
          :key="g.emoji"
          type="button"
          class="reaction-pill"
          :class="{ mine: g.reactedByMe }"
          :title="`${g.users.join('、')} 点了 ${g.emoji}`"
          @click="emit('react', message.id, g.emoji)"
        >
          <span>{{ g.emoji }}</span>
          <span class="tabular">{{ g.count }}</span>
        </button>
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
  -webkit-backdrop-filter: blur(8px);
  backdrop-filter: blur(8px);
}

.system-pill {
  color: var(--ink-secondary);
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

.reminder-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 700;
  color: var(--accent);
  margin-bottom: 5px;
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
  margin-top: 12px;
  padding: 3px 6px;
  border-radius: 16px;
  align-items: flex-start;
  transform-origin: left bottom;
  animation: msg-in-left 0.42s var(--ease-spring) both;
  transition: background 0.25s, box-shadow 0.35s;
}

.msg-row.grouped {
  margin-top: 3px;
}

.msg-row.mine {
  flex-direction: row-reverse;
  transform-origin: right bottom;
  animation-name: msg-in-right;
}

.msg-row.selectable {
  cursor: pointer;
}

.msg-row.selectable:hover {
  background: var(--hover);
}

.msg-row.selected {
  background: var(--accent-soft);
}

.msg-row.highlighted,
.reminder-row.highlighted {
  animation: jump-flash 1.8s var(--ease-out);
}

@keyframes jump-flash {
  0% { box-shadow: 0 0 0 0 rgba(58, 107, 255, 0.55); background: rgba(58, 107, 255, 0.16); }
  60% { box-shadow: 0 0 0 8px rgba(58, 107, 255, 0); background: rgba(58, 107, 255, 0.08); }
  100% { box-shadow: none; background: transparent; }
}

.select-col {
  display: flex;
  align-items: center;
  padding-top: 8px;
}

.select-circle {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  border: 2px solid var(--border-strong);
  background: var(--surface);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  transition: all 0.25s var(--ease-spring);
}

.select-circle.checked {
  border-color: var(--accent);
  background: var(--accent-grad);
  transform: scale(1.08);
}

.avatar-col {
  width: 32px;
  flex-shrink: 0;
}

.msg-col {
  max-width: min(76%, 660px);
  display: flex;
  flex-direction: column;
  gap: 3px;
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
  font-size: 10px;
  font-weight: 700;
  background: var(--ai-soft);
  color: var(--ai);
  border: 1px solid var(--ai-border);
  padding: 0 5px;
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
  position: relative;
  display: flex;
  align-items: flex-end;
  gap: 7px;
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

.bubble-bundle {
  padding: 11px 13px;
  min-width: 240px;
  max-width: 320px;
}

.bundle-card {
  cursor: pointer;
}

.bundle-card-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 700;
  font-size: 13.5px;
  margin-bottom: 6px;
}

.bundle-card-lines {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: 12px;
  opacity: 0.88;
  margin-bottom: 8px;
}

.bundle-line {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.bundle-card-foot {
  border-top: 1px solid rgba(148, 163, 184, 0.28);
  padding-top: 6px;
  font-size: 11px;
  opacity: 0.78;
}

.quote-box {
  width: 100%;
  border: none;
  text-align: left;
  color: inherit;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 5px 9px;
  margin-bottom: 7px;
  border-left: 3px solid var(--accent);
  background: rgba(23, 27, 38, 0.05);
  border-radius: 0 8px 8px 0;
  font-size: 12px;
  transition: background 0.18s;
}

.quote-box:hover {
  background: rgba(23, 27, 38, 0.1);
}

.bubble-mine .quote-box {
  border-left-color: rgba(255, 255, 255, 0.78);
  background: rgba(255, 255, 255, 0.16);
}

.bubble-mine .quote-box:hover {
  background: rgba(255, 255, 255, 0.24);
}

.quote-author {
  font-weight: 700;
  font-size: 11px;
  opacity: 0.88;
}

.quote-text {
  opacity: 0.86;
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
  border-radius: 10px;
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

.read-ring-btn {
  width: 20px;
  height: 20px;
  border: none;
  background: transparent;
  padding: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  flex-shrink: 0;
  transition: transform 0.3s var(--ease-spring);
}

.read-ring-btn:hover {
  transform: scale(1.22);
}

.ring-track {
  fill: none;
  stroke: var(--ink-muted);
  stroke-width: 1.8;
  opacity: 0.45;
}

.ring-fill {
  fill: none;
  stroke: #1FC46A;
  stroke-width: 2.2;
  stroke-linecap: round;
  transform: rotate(-90deg);
  transform-origin: 9px 9px;
  transition: stroke-dashoffset 0.55s var(--ease-out);
}

.ring-done-bg {
  fill: #1FC46A;
}

.ring-check {
  fill: none;
  stroke: #fff;
  stroke-width: 1.9;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.hover-time {
  font-size: 10.5px;
  color: var(--ink-muted);
  opacity: 0;
  transition: opacity 0.2s;
  flex-shrink: 0;
  user-select: none;
}

.msg-row:hover .hover-time {
  opacity: 0.85;
}

/* 精简悬浮浮条 */
.hover-actions {
  position: absolute;
  top: -34px;
  left: 0;
  z-index: 15;
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 3px 5px;
  border-radius: 999px;
  background: var(--surface-strong);
  border: 1px solid var(--glass-edge);
  box-shadow: 0 8px 24px rgba(34, 52, 110, 0.16), inset 0 1px 0 rgba(255, 255, 255, 0.55);
  -webkit-backdrop-filter: blur(18px) saturate(190%);
  backdrop-filter: blur(18px) saturate(190%);
  opacity: 0;
  transform: scale(0.88) translateY(5px);
  pointer-events: none;
  transition: opacity 0.18s var(--ease-out), transform 0.32s var(--ease-spring);
}

.msg-row.mine .hover-actions {
  left: auto;
  right: 0;
}

.msg-row:hover .hover-actions {
  opacity: 1;
  transform: none;
  pointer-events: auto;
}

.emoji-btn {
  width: 24px;
  height: 24px;
  border: none;
  border-radius: 50%;
  background: transparent;
  cursor: pointer;
  font-size: 13.5px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: transform 0.28s var(--ease-spring), background 0.15s;
}

.emoji-btn:hover {
  transform: scale(1.26);
  background: var(--hover);
}

.action-sep {
  width: 1px;
  height: 14px;
  background: var(--border-strong);
  margin: 0 2px;
}

.action-btn {
  width: 25px;
  height: 25px;
  border-radius: 50%;
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

.reaction-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-top: 2px;
}

.reaction-pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 9px;
  border-radius: 999px;
  border: 1px solid var(--glass-edge);
  background: var(--surface-strong);
  color: var(--ink-secondary);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  box-shadow: var(--shadow-sm);
  animation: pop-in 0.35s var(--ease-spring);
  transition: transform 0.28s var(--ease-spring), border-color 0.2s, background 0.2s;
}

.reaction-pill:hover {
  transform: translateY(-1px) scale(1.05);
}

.reaction-pill.mine {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent);
}
</style>
