<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { ArrowDown, Loader2, PanelRight, Paperclip, Send, Upload, X } from 'lucide-vue-next';
import { useChatStore } from './stores/chat';
import AuthView from './components/AuthView.vue';
import SidebarRail from './components/SidebarRail.vue';
import MessageItem from './components/MessageItem.vue';
import AgentStreamCard from './components/AgentStreamCard.vue';
import ApprovalCard from './components/ApprovalCard.vue';
import InspectorDrawer from './components/InspectorDrawer.vue';
import GroupModal from './components/GroupModal.vue';
import type { ChatMessage } from './types/im';
import { formatDateDivider } from './utils/format';

const store = useChatStore();

const showInspector = ref(true);
const showGroupModal = ref(false);
const draft = ref('');
const dragActive = ref(false);
const atBottom = ref(true);
const nowMs = ref(Date.now());

const msgListRef = ref<HTMLElement | null>(null);
const fileInputRef = ref<HTMLInputElement | null>(null);
const textareaRef = ref<HTMLTextAreaElement | null>(null);

let clockTimer: number | null = null;
let dragDepth = 0;

onMounted(() => {
  clockTimer = window.setInterval(() => {
    nowMs.value = Date.now();
  }, 5000);
  window.addEventListener('keydown', onGlobalKeyDown);
});

onUnmounted(() => {
  if (clockTimer) window.clearInterval(clockTimer);
  window.removeEventListener('keydown', onGlobalKeyDown);
});

function onGlobalKeyDown(e: KeyboardEvent) {
  if (e.key === 'Escape' && store.lightboxUrl) {
    store.lightboxUrl = null;
  }
}

function shouldShowDivider(index: number): boolean {
  if (index === 0) return true;
  const prev = store.messages[index - 1];
  const curr = store.messages[index];
  return curr.createdAt - prev.createdAt > 5 * 60 * 1000;
}

function isGroupedMessage(index: number): boolean {
  if (index === 0) return false;
  const prev = store.messages[index - 1];
  const curr = store.messages[index];
  if (prev.status === 2 || curr.status === 2) return false;
  if (prev.msgType === 'REMINDER' || curr.msgType === 'REMINDER') return false;
  if (prev.senderId !== curr.senderId) return false;
  return curr.createdAt - prev.createdAt < 2 * 60 * 1000;
}

function scrollToBottom(smooth = false) {
  nextTick(() => {
    const el = msgListRef.value;
    if (!el) return;
    el.scrollTo({ top: el.scrollHeight, behavior: smooth ? 'smooth' : 'auto' });
    atBottom.value = true;
    store.unreadBelowCount = 0;
  });
}

async function handleScroll() {
  const el = msgListRef.value;
  if (!el) return;
  const distFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
  atBottom.value = distFromBottom < 72;
  if (atBottom.value) {
    store.unreadBelowCount = 0;
  }
  if (el.scrollTop < 36 && store.hasMoreHistory && !store.loadingOlder) {
    await triggerLoadOlder();
  }
}

async function triggerLoadOlder() {
  const el = msgListRef.value;
  const prevHeight = el ? el.scrollHeight : 0;
  const added = await store.loadOlderMessages();
  if (added > 0 && el) {
    await nextTick();
    el.scrollTop = el.scrollHeight - prevHeight;
  }
}

// 切换会话时自动滚动到底部
watch(
  () => store.activeConvId,
  () => {
    scrollToBottom(false);
  }
);

// 新消息到达且用户位于底部时自动滚底
watch(
  () => store.messages.length,
  (nextLen, prevLen) => {
    if (nextLen > prevLen && atBottom.value) {
      scrollToBottom(false);
    }
  }
);

// 流式输出和审批卡片出现时如果在底部则跟随滚动
watch(
  () => [
    store.activeStreamsList.map((s) => s.text.length).join(','),
    store.activeApprovalCards.length
  ],
  () => {
    if (atBottom.value) {
      scrollToBottom(false);
    }
  }
);

function adjustTextarea() {
  const el = textareaRef.value;
  if (!el) return;
  el.style.height = 'auto';
  el.style.height = Math.min(el.scrollHeight, 136) + 'px';
}

function handleComposerInput() {
  adjustTextarea();
  if (draft.value.trim()) {
    store.sendTypingSignal();
  }
}

function handleSend(duplicateTest = false) {
  const text = draft.value.trim();
  if (!text) return;
  const ok = store.sendChatMessage(text, { duplicateTest });
  if (ok) {
    draft.value = '';
    nextTick(adjustTextarea);
    scrollToBottom(true);
  }
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    handleSend(false);
  }
}

function handleQuickDemo(preset: string, sendNow = false, duplicateTest = false) {
  draft.value = preset;
  nextTick(adjustTextarea);
  if (sendNow) {
    handleSend(duplicateTest);
  } else {
    textareaRef.value?.focus();
  }
}

function triggerFilePick() {
  fileInputRef.value?.click();
}

async function handleFileChange(e: Event) {
  const input = e.target as HTMLInputElement;
  const files = input.files;
  if (!files?.length) return;
  for (const f of Array.from(files)) {
    await store.sendFileMessage(f);
  }
  input.value = '';
}

async function handlePaste(e: ClipboardEvent) {
  const items = e.clipboardData?.items;
  if (!items) return;
  for (const item of Array.from(items)) {
    if (item.kind === 'file') {
      const file = item.getAsFile();
      if (file) {
        e.preventDefault();
        await store.sendFileMessage(file);
        return;
      }
    }
  }
}

function onDragEnter(e: DragEvent) {
  e.preventDefault();
  dragDepth++;
  dragActive.value = true;
}

function onDragLeave(e: DragEvent) {
  e.preventDefault();
  dragDepth = Math.max(0, dragDepth - 1);
  if (dragDepth === 0) {
    dragActive.value = false;
  }
}

function onDragOver(e: DragEvent) {
  e.preventDefault();
}

async function onDrop(e: DragEvent) {
  e.preventDefault();
  dragDepth = 0;
  dragActive.value = false;
  const files = e.dataTransfer?.files;
  if (!files?.length) return;
  for (const f of Array.from(files)) {
    await store.sendFileMessage(f);
  }
}

function setReplyTarget(msg: ChatMessage) {
  store.replyTarget = msg;
  textareaRef.value?.focus();
}
</script>

<template>
  <!-- 未登录 -->
  <AuthView v-if="!store.token || !store.currentUser" />

  <!-- 主工作台 -->
  <div v-else class="workbench">
    <SidebarRail @open-group-modal="showGroupModal = true" />

    <main
      class="chat-stage"
      @dragenter="onDragEnter"
      @dragleave="onDragLeave"
      @dragover="onDragOver"
      @drop="onDrop"
    >
      <!-- 拖拽上传遮罩 -->
      <div v-if="dragActive" class="drop-overlay">
        <div class="drop-box">
          <Upload :size="32" />
          <div>释放文件立即上传并发送</div>
          <small>支持图片与文档（最大 20MB，自动执行 WebCrypto SHA-256 秒传校验）</small>
        </div>
      </div>

      <!-- 顶栏 -->
      <header class="chat-header">
        <div class="header-left">
          <template v-if="store.activeConversation">
            <h2 class="conv-heading">{{ store.activeConversation.title }}</h2>
            <span
              v-if="store.activeConversation.type === 1"
              class="presence-pill"
              :class="{ online: store.activeConversation.peerOnline }"
            >
              <span class="dot" />
              <span>{{ store.activeConversation.peerOnline ? '在线' : '离线' }}</span>
            </span>
            <span v-else class="type-pill">群聊</span>
            <span v-if="store.typingHint" class="typing-pill">{{ store.typingHint }}</span>
          </template>
          <h2 v-else class="conv-heading muted">请选择左侧会话</h2>
        </div>

        <div class="header-right">
          <span v-if="store.syncNotice" class="sync-pill tabular">{{ store.syncNotice }}</span>
          <button
            type="button"
            class="btn-icon"
            :title="showInspector ? '收起右侧观测栏' : '展开右侧观测栏'"
            @click="showInspector = !showInspector"
          >
            <PanelRight :size="16" />
          </button>
        </div>
      </header>

      <!-- 空状态 -->
      <div v-if="!store.activeConversation" class="empty-stage">
        <div class="empty-card">
          <h3>欢迎回到 PulseIM 工作台</h3>
          <p>点击左侧会话开始实时沟通，或搜索用户发起私聊、创建群聊并输入 <code>@AI</code> 召唤智能体。</p>
        </div>
      </div>

      <!-- 消息滚动区 -->
      <template v-else>
        <div ref="msgListRef" class="msg-scroll" @scroll="handleScroll">
          <div class="history-top">
            <button
              v-if="store.hasMoreHistory"
              type="button"
              class="btn btn-ghost btn-xs"
              :disabled="store.loadingOlder"
              @click="triggerLoadOlder"
            >
              <Loader2 v-if="store.loadingOlder" :size="12" class="spin" />
              <span>{{ store.loadingOlder ? '正在拉取更早记录...' : '加载更早消息 (beforeSeq 游标)' }}</span>
            </button>
            <span v-else class="no-more">已加载全部历史消息</span>
          </div>

          <template v-for="(msg, idx) in store.messages" :key="msg.clientMsgId || msg.id || `${msg.seq}-${idx}`">
            <div v-if="shouldShowDivider(idx)" class="time-divider">
              <span>{{ formatDateDivider(msg.createdAt) }}</span>
            </div>

            <MessageItem
              :message="msg"
              :is-mine="msg.senderId === store.currentUser.id"
              :grouped="!shouldShowDivider(idx) && isGroupedMessage(idx)"
              :now-ms="nowMs"
              @reply="setReplyTarget"
              @recall="store.recallMessage"
              @preview-image="store.lightboxUrl = $event"
              @copied="store.pushToast($event)"
            />
          </template>

          <!-- 进行中的 AI 流式卡片 -->
          <AgentStreamCard
            v-for="stream in store.activeStreamsList"
            :key="stream.taskId"
            :stream="stream"
            @cancel="store.cancelAgentTask"
          />

          <!-- Human-in-the-Loop 审批卡片 -->
          <ApprovalCard
            v-for="card in store.activeApprovalCards"
            :key="card.approvalId"
            :card="card"
            @decide="store.submitApproval"
          />
        </div>

        <!-- 底部悬浮未读跳转条 -->
        <button
          v-if="!atBottom && store.unreadBelowCount > 0"
          type="button"
          class="jump-bottom-btn"
          @click="scrollToBottom(true)"
        >
          <ArrowDown :size="14" />
          <span>{{ store.unreadBelowCount }} 条新消息</span>
        </button>

        <!-- 输入区 -->
        <footer class="composer-area">
          <!-- 引用条 -->
          <div v-if="store.replyTarget" class="reply-bar">
            <div class="reply-preview">
              <strong>回复 {{ store.replyTarget.senderNickname }}：</strong>
              <span>{{ store.replyTarget.msgType === 'IMAGE' ? '[图片]' : store.replyTarget.content }}</span>
            </div>
            <button type="button" class="btn-icon" @click="store.replyTarget = null">
              <X :size="14" />
            </button>
          </div>

          <!-- 快捷演练 Chip -->
          <div class="chip-bar">
            <button
              type="button"
              class="demo-chip"
              @click="handleQuickDemo('@AI 15秒后提醒大家代码评审', true)"
            >
              @AI 15秒后定时提醒
            </button>
            <button
              type="button"
              class="demo-chip"
              @click="handleQuickDemo('@AI 总结一下大家最近聊了什么', true)"
            >
              @AI 总结群聊
            </button>
            <button
              type="button"
              class="demo-chip"
              @click="handleQuickDemo('@AI 用 Markdown 和 Java 代码详细讲解分布式 ID 与幂等设计', true)"
            >
              @AI 流式长回复
            </button>
            <button
              type="button"
              class="demo-chip"
              @click="handleQuickDemo('【幂等演练】同一 clientMsgId 双发去重测试', true, true)"
            >
              幂等重发测试
            </button>
          </div>

          <div class="composer-box">
            <input
              ref="fileInputRef"
              type="file"
              multiple
              hidden
              @change="handleFileChange"
            />
            <button
              type="button"
              class="btn-icon upload-btn"
              title="上传图片或文件（支持粘贴截图 / 拖拽 / SHA-256 秒传）"
              @click="triggerFilePick"
            >
              <Paperclip :size="17" />
            </button>

            <textarea
              ref="textareaRef"
              v-model="draft"
              rows="1"
              class="composer-input"
              placeholder="输入消息（Enter 发送，Shift+Enter 换行，支持 Ctrl+V 粘贴图片，@AI 召唤智能体）"
              @input="handleComposerInput"
              @keydown="handleKeydown"
              @paste="handlePaste"
            />

            <button
              type="button"
              class="btn btn-primary send-btn"
              :disabled="!draft.trim()"
              @click="handleSend(false)"
            >
              <Send :size="15" />
              <span>发送</span>
            </button>
          </div>
        </footer>
      </template>
    </main>

    <InspectorDrawer v-if="showInspector" />
  </div>

  <!-- 建群弹窗 -->
  <GroupModal v-if="showGroupModal" @close="showGroupModal = false" />

  <!-- 图片灯箱 -->
  <div v-if="store.lightboxUrl" class="lightbox" @click="store.lightboxUrl = null">
    <button type="button" class="lightbox-close" @click.stop="store.lightboxUrl = null">
      <X :size="20" />
    </button>
    <img :src="store.lightboxUrl" alt="大图预览" class="lightbox-img" @click.stop />
  </div>

  <!-- Toast 通知栈 -->
  <div class="toast-stack">
    <div
      v-for="t in store.toasts"
      :key="t.id"
      class="toast-item"
      :class="t.type"
    >
      {{ t.text }}
    </div>
  </div>
</template>

<style scoped>
.workbench {
  display: flex;
  height: 100%;
  width: 100%;
  overflow: hidden;
  background: var(--canvas);
}

.chat-stage {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  position: relative;
  background: var(--canvas);
}

.drop-overlay {
  position: absolute;
  inset: 0;
  z-index: 40;
  background: rgba(36, 87, 245, 0.14);
  backdrop-filter: blur(3px);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}

.drop-box {
  background: var(--surface);
  border: 2px dashed var(--accent);
  border-radius: 16px;
  padding: 36px 44px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  color: var(--accent);
  font-weight: 700;
  font-size: 16px;
  box-shadow: var(--shadow-md);
}

.drop-box small {
  font-weight: 400;
  font-size: 12.5px;
  color: var(--ink-secondary);
}

.chat-header {
  height: 58px;
  padding: 0 18px;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-shrink: 0;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.conv-heading {
  font-size: 15.5px;
  font-weight: 700;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.conv-heading.muted {
  color: var(--ink-muted);
}

.presence-pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--hover);
  color: var(--ink-muted);
}

.presence-pill .dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--ink-muted);
}

.presence-pill.online {
  background: rgba(21, 128, 61, 0.12);
  color: var(--ok);
}

.presence-pill.online .dot {
  background: var(--ok);
}

.type-pill {
  font-size: 11px;
  font-weight: 600;
  padding: 2px 7px;
  border-radius: 5px;
  background: var(--ai-soft);
  color: var(--ai);
}

.typing-pill {
  font-size: 12px;
  color: var(--accent);
  font-weight: 600;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.sync-pill {
  font-size: 11.5px;
  color: var(--ink-muted);
  background: var(--rail);
  border: 1px solid var(--border);
  padding: 3px 9px;
  border-radius: 999px;
}

.empty-stage {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}

.empty-card {
  max-width: 420px;
  text-align: center;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 14px;
  padding: 28px 24px;
}

.empty-card h3 {
  font-size: 16px;
  margin-bottom: 8px;
}

.empty-card p {
  font-size: 13px;
  color: var(--ink-secondary);
}

.msg-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 16px 22px 24px;
}

.history-top {
  display: flex;
  justify-content: center;
  margin-bottom: 10px;
}

.no-more {
  font-size: 11.5px;
  color: var(--ink-muted);
}

.time-divider {
  display: flex;
  justify-content: center;
  margin: 16px 0 8px;
}

.time-divider span {
  font-size: 11.5px;
  color: var(--ink-muted);
  background: var(--hover);
  padding: 2px 10px;
  border-radius: 999px;
}

.jump-bottom-btn {
  position: absolute;
  right: 24px;
  bottom: 138px;
  z-index: 20;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: 1px solid var(--accent);
  background: var(--surface);
  color: var(--accent);
  padding: 6px 12px;
  border-radius: 999px;
  font-size: 12.5px;
  font-weight: 600;
  box-shadow: var(--shadow-md);
  cursor: pointer;
}

.composer-area {
  background: var(--surface);
  border-top: 1px solid var(--border);
  padding: 10px 16px 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.reply-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 6px 10px;
  background: var(--rail);
  border-left: 3px solid var(--accent);
  border-radius: 0 7px 7px 0;
  font-size: 12.5px;
}

.reply-preview {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--ink-secondary);
}

.chip-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.demo-chip {
  border: 1px solid var(--border-strong);
  background: var(--rail);
  color: var(--ink-secondary);
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 500;
  cursor: pointer;
  transition: border-color 0.14s, color 0.14s, background 0.14s;
}

.demo-chip:hover {
  border-color: var(--accent);
  color: var(--accent);
  background: var(--accent-soft);
}

.composer-box {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  border: 1px solid var(--border-strong);
  border-radius: 11px;
  padding: 7px 8px;
  background: var(--rail);
}

.composer-box:focus-within {
  border-color: var(--accent);
  background: var(--surface);
}

.upload-btn {
  border: none;
}

.composer-input {
  flex: 1;
  border: none;
  background: transparent;
  color: var(--ink);
  resize: none;
  outline: none;
  line-height: 1.5;
  max-height: 136px;
  padding: 4px 2px;
}

.send-btn {
  flex-shrink: 0;
}

.lightbox {
  position: fixed;
  inset: 0;
  z-index: 200;
  background: rgba(10, 14, 23, 0.86);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}

.lightbox-close {
  position: fixed;
  top: 20px;
  right: 20px;
  width: 38px;
  height: 38px;
  border-radius: 50%;
  border: none;
  background: rgba(255, 255, 255, 0.14);
  color: #fff;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.lightbox-img {
  max-width: 92vw;
  max-height: 90vh;
  border-radius: 10px;
  box-shadow: var(--shadow-md);
}

.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

@media (max-width: 1060px) {
  .inspector {
    display: none;
  }
}
</style>
