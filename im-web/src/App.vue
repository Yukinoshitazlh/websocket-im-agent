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

watch(
  () => store.activeConvId,
  () => {
    scrollToBottom(false);
  }
);

watch(
  () => store.messages.length,
  (nextLen, prevLen) => {
    if (nextLen > prevLen && atBottom.value) {
      scrollToBottom(true);
    }
  }
);

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
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
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
  <!-- 动态流体背景 -->
  <div class="liquid-bg" aria-hidden="true">
    <div class="blob b1" />
    <div class="blob b2" />
    <div class="blob b3" />
    <div class="blob b4" />
    <div class="grain" />
  </div>

  <!-- SVG 位移折射滤镜（Chromium 液态透镜） -->
  <svg class="svg-defs" aria-hidden="true" focusable="false">
    <filter id="liquid-refract" x="0" y="0" width="100%" height="100%" color-interpolation-filters="sRGB">
      <feTurbulence type="fractalNoise" baseFrequency="0.012 0.018" numOctaves="2" seed="11" result="noise" />
      <feGaussianBlur in="noise" stdDeviation="2.4" result="softNoise" />
      <feDisplacementMap in="SourceGraphic" in2="softNoise" scale="26" xChannelSelector="R" yChannelSelector="G" />
    </filter>
  </svg>

  <Transition name="fade" mode="out-in">
    <!-- 未登录 -->
    <AuthView v-if="!store.token || !store.currentUser" key="auth" />

    <!-- 主工作台 -->
    <div v-else key="workbench" class="workbench">
      <SidebarRail @open-group-modal="showGroupModal = true" />

      <main
        class="chat-stage glass"
        @dragenter="onDragEnter"
        @dragleave="onDragLeave"
        @dragover="onDragOver"
        @drop="onDrop"
      >
        <!-- 拖拽上传遮罩 -->
        <Transition name="fade">
          <div v-if="dragActive" class="drop-overlay">
            <div class="drop-box">
              <Upload :size="34" class="drop-icon" />
              <div>释放文件立即上传并发送</div>
              <small>支持图片与文档（最大 20MB，自动执行 WebCrypto SHA-256 秒传校验）</small>
            </div>
          </div>
        </Transition>

        <!-- 顶栏 -->
        <header class="chat-header">
          <div class="header-left">
            <template v-if="store.activeConversation">
              <Transition name="tab-swap" mode="out-in">
                <h2 :key="store.activeConversation.id" class="conv-heading">
                  {{ store.activeConversation.title }}
                </h2>
              </Transition>
              <span
                v-if="store.activeConversation.type === 1"
                class="presence-pill"
                :class="{ online: store.activeConversation.peerOnline }"
              >
                <span class="dot" />
                <span>{{ store.activeConversation.peerOnline ? '在线' : '离线' }}</span>
              </span>
              <span v-else class="type-pill">群聊</span>
              <Transition name="slide-up">
                <span v-if="store.typingHint" class="typing-pill">
                  <span class="typing-dots"><i /><i /><i /></span>
                  <span>{{ store.typingHint }}</span>
                </span>
              </Transition>
            </template>
            <h2 v-else class="conv-heading muted">请选择左侧会话</h2>
          </div>

          <div class="header-right">
            <Transition name="fade" mode="out-in">
              <span v-if="store.syncNotice" :key="store.syncNotice" class="sync-pill liquid-lens tabular">
                {{ store.syncNotice }}
              </span>
            </Transition>
            <button
              type="button"
              class="btn-icon"
              :class="{ 'is-active': showInspector }"
              :title="showInspector ? '收起右侧观测栏' : '展开右侧观测栏'"
              @click="showInspector = !showInspector"
            >
              <PanelRight :size="16" />
            </button>
          </div>
        </header>

        <!-- 空状态 -->
        <div v-if="!store.activeConversation" class="empty-stage">
          <div class="empty-card glass">
            <div class="empty-orb" />
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

            <AgentStreamCard
              v-for="stream in store.activeStreamsList"
              :key="stream.taskId"
              :stream="stream"
              @cancel="store.cancelAgentTask"
            />

            <ApprovalCard
              v-for="card in store.activeApprovalCards"
              :key="card.approvalId"
              :card="card"
              @decide="store.submitApproval"
            />
          </div>

          <!-- 底部悬浮未读跳转胶囊 -->
          <Transition name="pop">
            <button
              v-if="!atBottom && store.unreadBelowCount > 0"
              type="button"
              class="jump-bottom-btn liquid-lens"
              @click="scrollToBottom(true)"
            >
              <ArrowDown :size="14" />
              <span>{{ store.unreadBelowCount }} 条新消息</span>
            </button>
          </Transition>

          <!-- 输入区 -->
          <footer class="composer-area">
            <Transition name="slide-up">
              <div v-if="store.replyTarget" class="reply-bar">
                <div class="reply-preview">
                  <strong>回复 {{ store.replyTarget.senderNickname }}：</strong>
                  <span>{{ store.replyTarget.msgType === 'IMAGE' ? '[图片]' : store.replyTarget.content }}</span>
                </div>
                <button type="button" class="btn-icon reply-close" @click="store.replyTarget = null">
                  <X :size="13" />
                </button>
              </div>
            </Transition>

            <div class="chip-bar">
              <button type="button" class="demo-chip liquid-lens" @click="handleQuickDemo('@AI 15秒后提醒大家代码评审', true)">
                @AI 15秒后定时提醒
              </button>
              <button type="button" class="demo-chip liquid-lens" @click="handleQuickDemo('@AI 总结一下大家最近聊了什么', true)">
                @AI 总结群聊
              </button>
              <button
                type="button"
                class="demo-chip liquid-lens"
                @click="handleQuickDemo('@AI 用 Markdown 和 Java 代码详细讲解分布式 ID 与幂等设计', true)"
              >
                @AI 流式长回复
              </button>
              <button
                type="button"
                class="demo-chip liquid-lens"
                @click="handleQuickDemo('【幂等演练】同一 clientMsgId 双发去重测试', true, true)"
              >
                幂等重发测试
              </button>
            </div>

            <div class="composer-box">
              <input ref="fileInputRef" type="file" multiple hidden @change="handleFileChange" />
              <button
                type="button"
                class="upload-btn"
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
                :class="{ ready: draft.trim() }"
                :disabled="!draft.trim()"
                @click="handleSend(false)"
              >
                <Send :size="15" class="send-icon" />
                <span>发送</span>
              </button>
            </div>
          </footer>
        </template>
      </main>

      <Transition name="drawer">
        <InspectorDrawer v-if="showInspector" />
      </Transition>
    </div>
  </Transition>

  <!-- 建群弹窗 -->
  <Transition name="modal">
    <GroupModal v-if="showGroupModal" @close="showGroupModal = false" />
  </Transition>

  <!-- 图片灯箱 -->
  <Transition name="zoom">
    <div v-if="store.lightboxUrl" class="lightbox" @click="store.lightboxUrl = null">
      <button type="button" class="lightbox-close" @click.stop="store.lightboxUrl = null">
        <X :size="20" />
      </button>
      <img :src="store.lightboxUrl" alt="大图预览" class="lightbox-img" @click.stop />
    </div>
  </Transition>

  <!-- Toast 通知栈 -->
  <TransitionGroup tag="div" name="toast" class="toast-stack">
    <div v-for="t in store.toasts" :key="t.id" class="toast-item" :class="t.type">
      {{ t.text }}
    </div>
  </TransitionGroup>
</template>

<style scoped>
.workbench {
  display: flex;
  gap: 12px;
  height: 100%;
  width: 100%;
  padding: 12px;
  overflow: hidden;
}

.chat-stage {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  border-radius: var(--radius-panel);
  overflow: hidden;
  animation: rise-in 0.7s var(--ease-out) 0.08s both;
}

.drop-overlay {
  position: absolute;
  inset: 0;
  z-index: 40;
  background: rgba(47, 91, 255, 0.12);
  -webkit-backdrop-filter: blur(8px) saturate(160%);
  backdrop-filter: blur(8px) saturate(160%);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}

.drop-box {
  background: var(--surface-strong);
  border: 2px dashed var(--accent);
  border-radius: 22px;
  padding: 38px 46px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  color: var(--accent);
  font-weight: 700;
  font-size: 16px;
  box-shadow: var(--shadow-md);
  animation: pop-in 0.45s var(--ease-spring);
}

.drop-icon {
  animation: typing-bounce 1.4s ease-in-out infinite;
}

.drop-box small {
  font-weight: 400;
  font-size: 12.5px;
  color: var(--ink-secondary);
}

.chat-header {
  position: relative;
  z-index: 5;
  height: 62px;
  padding: 0 20px;
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
  font-size: 16px;
  font-weight: 750;
  letter-spacing: -0.01em;
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
  gap: 6px;
  font-size: 11.5px;
  font-weight: 600;
  padding: 3px 10px;
  border-radius: 999px;
  background: var(--hover);
  border: 1px solid var(--border);
  color: var(--ink-muted);
  transition: all 0.4s var(--ease-out);
}

.presence-pill .dot {
  position: relative;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--ink-muted);
}

.presence-pill.online {
  background: rgba(31, 196, 106, 0.13);
  border-color: rgba(31, 196, 106, 0.3);
  color: var(--ok);
}

.presence-pill.online .dot {
  background: #1FC46A;
}

.presence-pill.online .dot::after {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: 50%;
  background: #1FC46A;
  animation: ping 2s var(--ease-out) infinite;
}

.type-pill {
  font-size: 11px;
  font-weight: 650;
  padding: 3px 9px;
  border-radius: 999px;
  background: var(--ai-soft);
  border: 1px solid var(--ai-border);
  color: var(--ai);
}

.typing-pill {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  color: var(--accent);
  font-weight: 600;
}

.typing-dots {
  display: inline-flex;
  gap: 3px;
}

.typing-dots i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
  animation: typing-bounce 1.1s ease-in-out infinite;
}

.typing-dots i:nth-child(2) { animation-delay: 0.15s; }
.typing-dots i:nth-child(3) { animation-delay: 0.3s; }

.header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.btn-icon.is-active {
  color: var(--accent);
  background: var(--accent-soft);
}

.sync-pill {
  font-size: 11.5px;
  color: var(--ink-secondary);
  background: var(--surface);
  border: 1px solid var(--glass-edge);
  padding: 4px 11px;
  border-radius: 999px;
  box-shadow: var(--shadow-sm);
}

.empty-stage {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}

.empty-card {
  max-width: 440px;
  text-align: center;
  border-radius: 24px;
  padding: 34px 28px 30px;
  overflow: hidden;
  animation: rise-in 0.8s var(--ease-out);
}

.empty-orb {
  width: 64px;
  height: 64px;
  margin: 0 auto 16px;
  border-radius: 50%;
  background: var(--accent-grad);
  box-shadow: 0 12px 32px rgba(58, 107, 255, 0.4), inset 0 2px 6px rgba(255, 255, 255, 0.5);
  animation: orb-float 4s ease-in-out infinite;
}

@keyframes orb-float {
  0%, 100% { transform: translateY(0) scale(1); border-radius: 50%; }
  50% { transform: translateY(-8px) scale(1.04); border-radius: 46% 54% 52% 48%; }
}

.empty-card h3 {
  font-size: 17px;
  margin-bottom: 8px;
}

.empty-card p {
  font-size: 13px;
  color: var(--ink-secondary);
}

.msg-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 18px 24px 26px;
  -webkit-mask-image: linear-gradient(to bottom, transparent 0, #000 16px, #000 calc(100% - 16px), transparent 100%);
  mask-image: linear-gradient(to bottom, transparent 0, #000 16px, #000 calc(100% - 16px), transparent 100%);
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
  margin: 18px 0 8px;
  animation: pop-in 0.4s var(--ease-out);
}

.time-divider span {
  font-size: 11.5px;
  color: var(--ink-muted);
  background: var(--surface);
  border: 1px solid var(--border);
  padding: 2px 11px;
  border-radius: 999px;
  -webkit-backdrop-filter: blur(8px);
  backdrop-filter: blur(8px);
}

.jump-bottom-btn {
  position: absolute;
  right: 26px;
  bottom: 148px;
  z-index: 20;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--glass-edge);
  background: rgba(47, 91, 255, 0.14);
  color: var(--accent);
  padding: 7px 14px;
  border-radius: 999px;
  font-size: 12.5px;
  font-weight: 650;
  box-shadow: var(--shadow-md), inset 0 1px 0 rgba(255, 255, 255, 0.5);
  cursor: pointer;
  transition: transform 0.35s var(--ease-spring);
}

.jump-bottom-btn:hover {
  transform: translateY(-2px) scale(1.04);
}

.composer-area {
  position: relative;
  z-index: 5;
  border-top: 1px solid var(--border);
  padding: 10px 18px 16px;
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.reply-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 7px 8px 7px 12px;
  background: var(--accent-soft);
  border: 1px solid var(--border);
  border-left: 3px solid var(--accent);
  border-radius: 6px 12px 12px 6px;
  font-size: 12.5px;
}

.reply-close {
  padding: 4px;
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
  gap: 7px;
}

.demo-chip {
  border: 1px solid var(--glass-edge);
  background: var(--surface);
  color: var(--ink-secondary);
  padding: 4px 12px;
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 550;
  cursor: pointer;
  box-shadow: var(--shadow-sm);
  transition:
    transform 0.35s var(--ease-spring),
    border-color 0.2s,
    color 0.2s,
    background 0.2s,
    box-shadow 0.3s;
}

.demo-chip:hover {
  transform: translateY(-2px);
  border-color: var(--accent);
  color: var(--accent);
  background: var(--accent-soft);
  box-shadow: 0 6px 16px rgba(47, 91, 255, 0.18);
}

.demo-chip:active {
  transform: scale(0.94);
  transition-duration: 0.08s;
}

.composer-box {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  border: 1px solid var(--glass-edge);
  border-radius: 16px;
  padding: 7px 8px;
  background: var(--surface);
  box-shadow: var(--shadow-sm);
  transition: border-color 0.25s, box-shadow 0.35s var(--ease-out), background 0.25s;
}

.composer-box:focus-within {
  border-color: var(--accent);
  background: var(--surface-strong);
  box-shadow: 0 0 0 4px var(--accent-soft), 0 10px 28px rgba(47, 91, 255, 0.12);
}

.upload-btn {
  width: 34px;
  height: 34px;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: var(--ink-secondary);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: transform 0.35s var(--ease-spring), background 0.2s, color 0.2s;
}

.upload-btn:hover {
  background: var(--hover);
  color: var(--accent);
  transform: rotate(-12deg) scale(1.08);
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
  padding: 6px 2px;
  transition: height 0.18s var(--ease-out);
}

.composer-input::placeholder {
  color: var(--ink-muted);
}

.send-btn {
  flex-shrink: 0;
  border-radius: 12px;
  padding: 8px 15px;
}

.send-btn.ready .send-icon {
  animation: send-wiggle 0.5s var(--ease-spring);
}

.send-btn:hover:not(:disabled) .send-icon {
  transform: translate(2px, -2px) rotate(-8deg);
}

.send-icon {
  transition: transform 0.35s var(--ease-spring);
}

@keyframes send-wiggle {
  0% { transform: scale(0.6) rotate(-20deg); }
  100% { transform: none; }
}

.lightbox {
  position: fixed;
  inset: 0;
  z-index: 200;
  background: rgba(8, 12, 22, 0.62);
  -webkit-backdrop-filter: blur(22px) saturate(140%);
  backdrop-filter: blur(22px) saturate(140%);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}

.lightbox-close {
  position: fixed;
  top: 20px;
  right: 20px;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  border: 1px solid rgba(255, 255, 255, 0.25);
  background: rgba(255, 255, 255, 0.14);
  -webkit-backdrop-filter: blur(12px);
  backdrop-filter: blur(12px);
  color: #fff;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: transform 0.35s var(--ease-spring), background 0.2s;
}

.lightbox-close:hover {
  background: rgba(255, 255, 255, 0.24);
  transform: rotate(90deg);
}

.lightbox-img {
  max-width: 92vw;
  max-height: 90vh;
  border-radius: 16px;
  box-shadow: 0 30px 80px rgba(0, 0, 0, 0.5);
}

.spin {
  animation: spin 1s linear infinite;
}

@media (max-width: 1060px) {
  .workbench :deep(.inspector) {
    display: none;
  }
}
</style>
