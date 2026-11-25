<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import {
  ArrowDown,
  AtSign,
  Bell,
  BellOff,
  CheckSquare,
  Copy,
  FileText,
  Forward,
  Loader2,
  MoreHorizontal,
  Paperclip,
  Pin,
  PinOff,
  Reply,
  Search,
  Send,
  Smile,
  Sparkles,
  Undo2,
  Upload,
  User,
  Users,
  X
} from 'lucide-vue-next';
import { useChatStore } from './stores/chat';
import AuthView from './components/AuthView.vue';
import NavRail from './components/NavRail.vue';
import SidebarRail from './components/SidebarRail.vue';
import MessageItem from './components/MessageItem.vue';
import AgentStreamCard from './components/AgentStreamCard.vue';
import ApprovalCard from './components/ApprovalCard.vue';
import InspectorDrawer from './components/InspectorDrawer.vue';
import GroupModal from './components/GroupModal.vue';
import ReadDetailModal from './components/ReadDetailModal.vue';
import ForwardModal from './components/ForwardModal.vue';
import BundleViewerModal from './components/BundleViewerModal.vue';
import ProfileModal from './components/ProfileModal.vue';
import UserAvatar from './components/UserAvatar.vue';
import type { ChatMessage } from './types/im';
import { avatarColor, avatarInitial, formatDateDivider, formatFileSize } from './utils/format';

interface MentionOption {
  id: string;
  label: string;
  insertText: string;
  sub: string;
  isAi?: boolean;
  isAll?: boolean;
  online?: boolean;
}

interface PendingFile {
  id: string;
  file: File;
  isImage: boolean;
  previewUrl?: string;
}

const CONTEXT_EMOJIS = ['👍', '🔥', '🎉', '❤️', '😂', '👌'];
const PICKER_EMOJIS = [
  '😀', '😂', '🤣', '😅', '😊', '😍', '🥰', '😎',
  '🤔', '😮', '😭', '😡', '🥳', '🤯', '😴', '🫡',
  '👍', '👎', '👌', '👏', '🙏', '🤝', '💪', '🎉',
  '🔥', '✨', '❤️', '💯', '🚀', '📌', '✅', '❌',
  '⚡', '💡', '📅', '📝', '💻', '🐛', '☕', '🧋'
];

const store = useChatStore();

// 日常模式下右侧抽屉默认收起，回归宽敞双栏；点击顶栏功能图标随时滑出对应 Tab
const showInspector = ref(false);
const currentDrawerTab = ref<'metrics' | 'group' | 'search'>('metrics');
const inspectorTabReq = ref<'metrics' | 'group' | 'search' | undefined>(undefined);

const showGroupModal = ref(false);
const showForwardModal = ref(false);
const showAllPins = ref(false);
const showEmojiPicker = ref(false);
const showAiMenu = ref(false);
const showChatMenu = ref(false);

// 全局消息右键/更多菜单
const msgMenu = ref<{ msg: ChatMessage; x: number; y: number } | null>(null);

const draft = ref('');
const pendingFiles = ref<PendingFile[]>([]);
const pendingAtIds = ref<string[]>([]);
const mentionOpen = ref(false);
const mentionFilter = ref('');
const mentionActiveIdx = ref(0);
const mentionAnchorPos = ref(0);

const dragActive = ref(false);
const atBottom = ref(true);
const nowMs = ref(Date.now());

const msgListRef = ref<HTMLElement | null>(null);
const fileInputRef = ref<HTMLInputElement | null>(null);
const textareaRef = ref<HTMLTextAreaElement | null>(null);

let clockTimer: number | null = null;
let dragDepth = 0;

function closeFloatingMenus() {
  msgMenu.value = null;
  showEmojiPicker.value = false;
  showAiMenu.value = false;
  showAllPins.value = false;
  showChatMenu.value = false;
}

// 演练模式联动 AI 观测面板：开启时自动滑出观测面板，关闭时自动收起观测页
watch(
  () => store.demoMode,
  (on) => {
    if (on && store.activeConversation) {
      openDrawerTab('metrics');
    } else if (!on && currentDrawerTab.value === 'metrics') {
      showInspector.value = false;
    }
  }
);

onMounted(() => {
  clockTimer = window.setInterval(() => {
    nowMs.value = Date.now();
  }, 5000);
  window.addEventListener('keydown', onGlobalKeyDown);
  window.addEventListener('click', closeFloatingMenus);
});

onUnmounted(() => {
  if (clockTimer) window.clearInterval(clockTimer);
  window.removeEventListener('keydown', onGlobalKeyDown);
  window.removeEventListener('click', closeFloatingMenus);
  clearPendingFiles();
});

function onGlobalKeyDown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    if (store.lightboxUrl) store.lightboxUrl = null;
    else if (store.viewingProfile) store.viewingProfile = null;
    else if (store.viewingBundle) store.viewingBundle = null;
    else if (store.readDetailMsg) store.readDetailMsg = null;
    else if (showForwardModal.value) showForwardModal.value = false;
    else if (msgMenu.value || showEmojiPicker.value || showAiMenu.value || showAllPins.value || showChatMenu.value) {
      closeFloatingMenus();
    } else if (mentionOpen.value) mentionOpen.value = false;
    else if (store.multiSelectMode) store.exitMultiSelect();
  }
}

const latestPin = computed(() => store.activePins[0] || null);

// 第一条未读消息的下标，用于展示「以下是新消息」分割线
const firstUnreadIndex = computed(() => {
  if (store.unreadStartSeq === null) return -1;
  const threshold = store.unreadStartSeq;
  return store.messages.findIndex((m) => m.seq > threshold);
});

const canSendComposer = computed(() => draft.value.trim().length > 0 || pendingFiles.value.length > 0);

const mentionOptions = computed<MentionOption[]>(() => {
  const list: MentionOption[] = [
    {
      id: 'AI',
      label: 'AI 助手',
      insertText: '@AI ',
      sub: '智能总结 / 定时提醒 / 问答',
      isAi: true,
      online: true
    }
  ];
  if (store.activeConversation?.type === 2) {
    list.push({
      id: 'ALL',
      label: '所有人',
      insertText: '@所有人 ',
      sub: `提醒全群 ${store.activeMembers.length} 位成员`,
      isAll: true,
      online: true
    });
  }
  for (const m of store.activeMembers) {
    if (String(m.userId) === String(store.currentUser?.id)) continue;
    list.push({
      id: String(m.userId),
      label: m.nickname,
      insertText: `@${m.nickname} `,
      sub: `@${m.username}`,
      online: m.online
    });
  }
  const kw = mentionFilter.value.trim().toLowerCase();
  if (!kw) return list;
  return list.filter(
    (item) =>
      item.label.toLowerCase().includes(kw) || item.sub.toLowerCase().includes(kw)
  );
});

function openDrawerTab(tab: 'metrics' | 'group' | 'search') {
  currentDrawerTab.value = tab;
  showInspector.value = true;
  inspectorTabReq.value = tab;
  nextTick(() => {
    inspectorTabReq.value = undefined;
  });
}

function toggleDrawerTab(tab: 'metrics' | 'group' | 'search') {
  if (showInspector.value && currentDrawerTab.value === tab) {
    showInspector.value = false;
    return;
  }
  openDrawerTab(tab);
}

function openMsgContextMenu(ev: MouseEvent, msg: ChatMessage) {
  showEmojiPicker.value = false;
  showAiMenu.value = false;
  const x = Math.min(ev.clientX, window.innerWidth - 224);
  const y = Math.min(ev.clientY, window.innerHeight - 250);
  msgMenu.value = { msg, x: Math.max(12, x), y: Math.max(12, y) };
}

function canRecallMsg(msg: ChatMessage): boolean {
  if (!store.currentUser || !msg.id) return false;
  if (String(msg.senderId) !== String(store.currentUser.id)) return false;
  if (msg.status === 2 || msg.msgType === 'REMINDER' || msg.msgType === 'SYSTEM') return false;
  return nowMs.value - msg.createdAt < 120_000;
}

async function copyMessageContent(msg: ChatMessage) {
  try {
    await navigator.clipboard.writeText(msg.content);
    store.pushToast('已复制到剪贴板');
  } catch {
    store.pushToast('复制失败，请检查剪贴板权限', 'error');
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
  if (prev.msgType === 'SYSTEM' || curr.msgType === 'SYSTEM') return false;
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

async function jumpToSeq(seq: number) {
  if (!seq || seq <= 0) return;
  showAllPins.value = false;
  for (let i = 0; i < 3; i++) {
    const exists = store.messages.some((m) => m.seq === seq);
    if (exists || !store.hasMoreHistory) break;
    await store.loadOlderMessages();
  }
  await nextTick();
  const container = msgListRef.value;
  const targetEl = container?.querySelector(`[data-msg-seq="${seq}"]`) as HTMLElement | null;
  if (targetEl) {
    targetEl.scrollIntoView({ behavior: 'smooth', block: 'center' });
    store.flashHighlightSeq(seq);
    if (store.mentionJumpSeq === seq) {
      store.mentionJumpSeq = null;
    }
  } else {
    store.pushToast('目标消息较早，请继续向上滚动加载历史');
  }
}

async function jumpToMsgId(msgId: string) {
  if (!msgId) return;
  for (let i = 0; i < 3; i++) {
    const found = store.messages.find((m) => String(m.id) === String(msgId));
    if (found) {
      await jumpToSeq(found.seq);
      return;
    }
    if (!store.hasMoreHistory) break;
    await store.loadOlderMessages();
  }
  store.pushToast('被引用的原消息较早，请向上滚动加载更多历史');
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

// 切换会话：保存旧草稿 -> 恢复新草稿 -> 自动聚焦输入框（微信肌肉记忆）
watch(
  () => store.activeConvId,
  (nextId, prevId) => {
    if (prevId) {
      store.setDraft(prevId, draft.value);
    }
    draft.value = nextId ? store.getDraft(nextId) : '';
    clearPendingFiles();
    pendingAtIds.value = [];
    mentionOpen.value = false;
    closeFloatingMenus();
    nextTick(() => {
      adjustTextarea();
      if (nextId) textareaRef.value?.focus();
    });
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

function updateMentionTrigger() {
  const el = textareaRef.value;
  if (!el) return;
  const cursor = el.selectionStart || 0;
  const before = draft.value.slice(0, cursor);
  const atIdx = before.lastIndexOf('@');
  if (atIdx >= 0) {
    const prevChar = atIdx === 0 ? ' ' : before[atIdx - 1];
    const query = before.slice(atIdx + 1);
    if ((prevChar === ' ' || prevChar === '\n') && !query.includes(' ') && query.length <= 20) {
      mentionAnchorPos.value = atIdx;
      mentionFilter.value = query;
      mentionActiveIdx.value = 0;
      mentionOpen.value = true;
      return;
    }
  }
  mentionOpen.value = false;
}

function selectMentionOption(opt: MentionOption) {
  const el = textareaRef.value;
  const cursor = el ? el.selectionStart : draft.value.length;
  const before = draft.value.slice(0, mentionAnchorPos.value);
  const after = draft.value.slice(cursor);
  draft.value = before + opt.insertText + after;
  if (!opt.isAi && !pendingAtIds.value.includes(opt.id)) {
    pendingAtIds.value.push(opt.id);
  }
  mentionOpen.value = false;
  if (store.activeConvId) {
    store.setDraft(store.activeConvId, draft.value);
  }
  nextTick(() => {
    adjustTextarea();
    const pos = before.length + opt.insertText.length;
    el?.focus();
    el?.setSelectionRange(pos, pos);
  });
}

function insertEmoji(emoji: string) {
  const el = textareaRef.value;
  const start = el ? el.selectionStart : draft.value.length;
  const end = el ? el.selectionEnd : draft.value.length;
  draft.value = draft.value.slice(0, start) + emoji + draft.value.slice(end);
  if (store.activeConvId) {
    store.setDraft(store.activeConvId, draft.value);
  }
  showEmojiPicker.value = false;
  nextTick(() => {
    adjustTextarea();
    const pos = start + emoji.length;
    el?.focus();
    el?.setSelectionRange(pos, pos);
  });
}

function stageFilesForConfirm(files: File[]) {
  for (const file of files) {
    if (file.size > 20 * 1024 * 1024) {
      store.pushToast(`「${file.name}」超过 20MB 限制`, 'error');
      continue;
    }
    const isImage = file.type.startsWith('image/');
    pendingFiles.value.push({
      id: `${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
      file,
      isImage,
      previewUrl: isImage ? URL.createObjectURL(file) : undefined
    });
  }
  nextTick(() => textareaRef.value?.focus());
}

function removePendingFile(id: string) {
  const target = pendingFiles.value.find((f) => f.id === id);
  if (target?.previewUrl) URL.revokeObjectURL(target.previewUrl);
  pendingFiles.value = pendingFiles.value.filter((f) => f.id !== id);
}

function clearPendingFiles() {
  for (const item of pendingFiles.value) {
    if (item.previewUrl) URL.revokeObjectURL(item.previewUrl);
  }
  pendingFiles.value = [];
}

function handleComposerInput() {
  adjustTextarea();
  updateMentionTrigger();
  if (store.activeConvId) {
    store.setDraft(store.activeConvId, draft.value);
  }
  if (draft.value.trim()) {
    store.sendTypingSignal();
  }
}

async function handleSend(duplicateTest = false) {
  const filesToSend = [...pendingFiles.value];
  const text = draft.value.trim();
  if (filesToSend.length === 0 && !text) return;

  if (filesToSend.length > 0) {
    clearPendingFiles();
    for (const item of filesToSend) {
      await store.sendFileMessage(item.file);
    }
  }

  if (text) {
    const atIds = [...pendingAtIds.value];
    if (text.includes('@所有人') && !atIds.includes('ALL')) {
      atIds.push('ALL');
    }
    const ok = store.sendChatMessage(text, { atUserIds: atIds, duplicateTest });
    if (ok) {
      draft.value = '';
      pendingAtIds.value = [];
      mentionOpen.value = false;
      nextTick(adjustTextarea);
    }
  }
  store.unreadStartSeq = null;
  scrollToBottom(true);
}

function handleKeydown(e: KeyboardEvent) {
  if (mentionOpen.value && mentionOptions.value.length > 0) {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      mentionActiveIdx.value = (mentionActiveIdx.value + 1) % mentionOptions.value.length;
      return;
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault();
      mentionActiveIdx.value =
        (mentionActiveIdx.value - 1 + mentionOptions.value.length) % mentionOptions.value.length;
      return;
    }
    if (e.key === 'Enter' || e.key === 'Tab') {
      e.preventDefault();
      selectMentionOption(mentionOptions.value[mentionActiveIdx.value]);
      return;
    }
    if (e.key === 'Escape') {
      e.preventDefault();
      mentionOpen.value = false;
      return;
    }
  }
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault();
    handleSend(false);
  }
}

function handleQuickAction(preset: string, sendNow = false, duplicateTest = false) {
  showAiMenu.value = false;
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

function handleFileChange(e: Event) {
  const input = e.target as HTMLInputElement;
  const files = input.files;
  if (!files?.length) return;
  stageFilesForConfirm(Array.from(files));
  input.value = '';
}

// 微信同款：Ctrl+V 贴图/粘贴文件先进待发预览区确认，按 Enter 发送，防止误发！
function handlePaste(e: ClipboardEvent) {
  const items = e.clipboardData?.items;
  if (!items) return;
  const pastedFiles: File[] = [];
  for (const item of Array.from(items)) {
    if (item.kind === 'file') {
      const file = item.getAsFile();
      if (file) pastedFiles.push(file);
    }
  }
  if (pastedFiles.length > 0) {
    e.preventDefault();
    stageFilesForConfirm(pastedFiles);
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

function onDrop(e: DragEvent) {
  e.preventDefault();
  dragDepth = 0;
  dragActive.value = false;
  const files = e.dataTransfer?.files;
  if (!files?.length) return;
  stageFilesForConfirm(Array.from(files));
}

function setReplyTarget(msg: ChatMessage) {
  store.replyTarget = msg;
  textareaRef.value?.focus();
}

function handleAiSummarizeSelected() {
  if (store.selectedMsgIds.length === 0) {
    store.pushToast('请先勾选至少一条消息', 'error');
    return;
  }
  store.triggerAiSummary('SELECTED', { msgIds: store.selectedMsgIds });
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

    <!-- 主工作台（58px 极简导航竖条 + 276px 会话栏 + 宽敞主聊天区 + 按需滑出右侧抽屉） -->
    <div v-else key="workbench" class="workbench">
      <NavRail @open-group-modal="showGroupModal = true" />
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
              <div>释放文件加入待发送区</div>
              <small>支持图片与文档（最大 20MB，确认后按 Enter 发送）</small>
            </div>
          </div>
        </Transition>

        <!-- 顶栏（极简微信/飞书风格：搜索 / 群管理 / AI 助手 + 右上角个人头像资料入口） -->
        <header class="chat-header">
          <div class="header-left">
            <template v-if="store.activeConversation">
              <Transition name="tab-swap" mode="out-in">
                <button
                  v-if="store.activeConversation.type === 1 && store.activeConversation.peerUserId"
                  :key="store.activeConversation.id"
                  type="button"
                  class="peer-title-btn"
                  title="点击查看对方资料名片"
                  @click="store.openProfile(store.activeConversation.peerUserId)"
                >
                  <UserAvatar
                    :name="store.activeConversation.title"
                    :src="store.activeConversation.peerAvatar"
                    :online="Boolean(store.activeConversation.peerOnline)"
                    size="xs"
                    clickable
                  />
                  <span class="conv-heading">{{ store.activeConversation.title }}</span>
                </button>
                <h2 v-else :key="'g-' + store.activeConversation.id" class="conv-heading">
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
              <span
                v-if="store.activeConversation.type === 1 && store.activeConversation.peerStatusText"
                class="peer-status-pill"
              >
                {{ store.activeConversation.peerStatusEmoji }} {{ store.activeConversation.peerStatusText }}
              </span>

              <button
                v-if="store.activeConversation.type === 2"
                type="button"
                class="type-pill clickable"
                title="查看和管理群成员"
                @click="toggleDrawerTab('group')"
              >
                <Users :size="12" />
                <span>{{ store.activeMembers.length || store.activeConversation.memberCount || 2 }}</span>
              </button>
              <Transition name="slide-up">
                <span v-if="store.typingHint" class="typing-pill">
                  <span class="typing-dots"><i /><i /><i /></span>
                  <span>{{ store.typingHint }}</span>
                </span>
              </Transition>
            </template>
            <h2 v-else class="conv-heading muted">PulseIM</h2>
          </div>

          <div v-if="store.activeConversation" class="header-right">
            <Transition name="fade" mode="out-in">
              <span
                v-if="store.syncNotice && (store.demoMode || store.syncNotice.includes('补齐'))"
                :key="store.syncNotice"
                class="sync-pill liquid-lens tabular"
              >
                {{ store.syncNotice }}
              </span>
            </Transition>

            <!-- 右上角统一集成功能按钮（查找聊天记录 / 群管理 / 置顶 / 免打扰 / 演练模式 AI 观测） -->
            <div class="chat-menu-wrap" @click.stop>
              <button
                type="button"
                class="btn-icon"
                :class="{ 'is-active': showChatMenu }"
                title="会话设置与更多功能"
                @click="showChatMenu = !showChatMenu; showEmojiPicker = false; showAiMenu = false; showAllPins = false"
              >
                <MoreHorizontal :size="18" />
              </button>

              <Transition name="pop">
                <div v-if="showChatMenu" class="chat-menu-popover glass-strong">
                  <button
                    type="button"
                    class="ctx-item"
                    @click="openDrawerTab('search'); showChatMenu = false"
                  >
                    <Search :size="14" />
                    <span>查找聊天记录</span>
                  </button>

                  <button
                    v-if="store.activeConversation.type === 2"
                    type="button"
                    class="ctx-item"
                    @click="openDrawerTab('group'); showChatMenu = false"
                  >
                    <Users :size="14" />
                    <span>群成员与群管理</span>
                  </button>

                  <button
                    v-else-if="store.activeConversation.peerUserId"
                    type="button"
                    class="ctx-item"
                    @click="store.openProfile(store.activeConversation.peerUserId); showChatMenu = false"
                  >
                    <User :size="14" />
                    <span>查看对方资料</span>
                  </button>

                  <div class="ctx-divider" />

                  <button
                    type="button"
                    class="ctx-item"
                    @click="store.updateConvPreferences(store.activeConversation.id, { pinned: !store.activeConversation.pinned }); showChatMenu = false"
                  >
                    <PinOff v-if="store.activeConversation.pinned" :size="14" />
                    <Pin v-else :size="14" />
                    <span>{{ store.activeConversation.pinned ? '取消置顶会话' : '置顶该会话' }}</span>
                    <span v-if="store.activeConversation.pinned" class="menu-state-dot" />
                  </button>

                  <button
                    type="button"
                    class="ctx-item"
                    @click="store.updateConvPreferences(store.activeConversation.id, { muted: !store.activeConversation.muted }); showChatMenu = false"
                  >
                    <Bell v-if="store.activeConversation.muted" :size="14" />
                    <BellOff v-else :size="14" />
                    <span>{{ store.activeConversation.muted ? '取消消息免打扰' : '消息免打扰' }}</span>
                    <span v-if="store.activeConversation.muted" class="menu-state-dot" />
                  </button>

                  <button
                    type="button"
                    class="ctx-item"
                    @click="store.enterMultiSelect(); showChatMenu = false"
                  >
                    <CheckSquare :size="14" />
                    <span>批量选择消息</span>
                  </button>

                  <template v-if="store.demoMode">
                    <div class="ctx-divider" />
                    <button
                      type="button"
                      class="ctx-item ai-item"
                      @click="openDrawerTab('metrics'); showChatMenu = false"
                    >
                      <Sparkles :size="14" />
                      <span>AI 观测与日程（演练）</span>
                    </button>
                  </template>
                </div>
              </Transition>
            </div>
          </div>
        </header>

        <!-- 飞书式群置顶公告栏 -->
        <Transition name="slide-up">
          <div v-if="store.activeConversation && latestPin" class="pin-banner">
            <button type="button" class="pin-main" @click="jumpToSeq(latestPin.seq)">
              <Pin :size="13" class="pin-ic" />
              <span class="pin-label">置顶</span>
              <strong class="pin-sender">{{ latestPin.senderNickname }}:</strong>
              <span class="pin-text">{{ latestPin.preview }}</span>
            </button>

            <div class="pin-actions">
              <button
                v-if="store.activePins.length > 1"
                type="button"
                class="pin-more-btn"
                @click.stop="showAllPins = !showAllPins"
              >
                全部 {{ store.activePins.length }} 条
              </button>
              <button
                type="button"
                class="pin-close-btn"
                title="取消置顶"
                @click="store.unpinMessage(latestPin.msgId)"
              >
                <X :size="13" />
              </button>
            </div>

            <Transition name="pop">
              <div v-if="showAllPins" class="pin-popover glass-strong" @click.stop>
                <div v-for="p in store.activePins" :key="p.msgId" class="pin-pop-row">
                  <button type="button" class="pin-pop-jump" @click="jumpToSeq(p.seq)">
                    <strong>{{ p.senderNickname }}</strong>
                    <span>{{ p.preview }}</span>
                  </button>
                  <button type="button" class="pin-close-btn" title="取消置顶" @click="store.unpinMessage(p.msgId)">
                    <X :size="12" />
                  </button>
                </div>
              </div>
            </Transition>
          </div>
        </Transition>

        <!-- 空状态 -->
        <div v-if="!store.activeConversation" class="empty-stage">
          <div class="empty-card glass">
            <div class="empty-orb" />
            <h3>开始一段对话</h3>
            <p>选择左侧会话开始聊天，右键会话可置顶或免打扰；左下角烧瓶图标可随时开启面试演练模式。</p>
          </div>
        </div>

        <!-- 消息滚动区 -->
        <template v-else>
          <div class="stage-banners">
            <Transition name="pop">
              <div
                v-if="store.unreadDigestBanner && store.unreadDigestBanner.conversationId === store.activeConvId"
                class="digest-capsule liquid-lens"
              >
                <Sparkles :size="15" class="digest-ic" />
                <span>有 <strong>{{ store.unreadDigestBanner.count }}</strong> 条未读消息，让 AI 3 秒帮你省流总结</span>
                <button
                  type="button"
                  class="btn btn-primary btn-xs"
                  :disabled="store.summarizing"
                  @click="store.triggerAiSummary('UNREAD', { afterSeq: store.unreadDigestBanner.afterSeq })"
                >
                  {{ store.summarizing ? '正在提炼...' : '一键省流' }}
                </button>
                <button type="button" class="capsule-close" @click="store.unreadDigestBanner = null">
                  <X :size="13" />
                </button>
              </div>
            </Transition>

            <Transition name="pop">
              <button
                v-if="store.mentionJumpSeq"
                type="button"
                class="mention-capsule liquid-lens"
                @click="jumpToSeq(store.mentionJumpSeq)"
              >
                <AtSign :size="14" />
                <span>有人 @ 了你，点击定位原消息</span>
              </button>
            </Transition>
          </div>

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
                <span>{{ store.loadingOlder ? '正在加载...' : '查看更多历史消息' }}</span>
              </button>
              <span v-else class="no-more">已显示全部消息</span>
            </div>

            <template v-for="(msg, idx) in store.messages" :key="msg.clientMsgId || msg.id || `${msg.seq}-${idx}`">
              <!-- 「以下是新消息」分割线 -->
              <div v-if="idx === firstUnreadIndex" class="unread-divider">
                <span>以下是新消息</span>
              </div>

              <div v-if="shouldShowDivider(idx)" class="time-divider">
                <span>{{ formatDateDivider(msg.createdAt) }}</span>
              </div>

              <MessageItem
                :message="msg"
                :is-mine="msg.senderId === store.currentUser.id"
                :is-group="store.activeConversation.type === 2"
                :demo-mode="store.demoMode"
                :grouped="!shouldShowDivider(idx) && idx !== firstUnreadIndex && isGroupedMessage(idx)"
                :members="store.activeMembers"
                :multi-select="store.multiSelectMode"
                :selected="Boolean(msg.id && store.selectedMsgIds.includes(msg.id))"
                :highlighted="Boolean(msg.seq > 0 && store.highlightedSeq === msg.seq)"
                @reply="setReplyTarget"
                @react="store.toggleReaction"
                @open-context-menu="openMsgContextMenu"
                @toggle-select="store.toggleSelectMsg"
                @open-read-detail="store.readDetailMsg = $event"
                @open-bundle="store.viewingBundle = $event"
                @open-profile="store.openProfile"
                @jump-to-msg-id="jumpToMsgId"
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

          <!-- 多选操作条 -->
          <footer v-if="store.multiSelectMode" class="multiselect-bar">
            <span class="ms-count">已勾选 <strong>{{ store.selectedMsgIds.length }}</strong> 条消息</span>
            <div class="ms-actions">
              <button
                type="button"
                class="btn btn-ghost"
                :disabled="store.selectedMsgIds.length === 0"
                @click="showForwardModal = true"
              >
                <Forward :size="15" />
                <span>合并转发</span>
              </button>
              <button
                type="button"
                class="btn btn-primary"
                :disabled="store.selectedMsgIds.length === 0 || store.summarizing"
                @click="handleAiSummarizeSelected"
              >
                <Sparkles :size="15" />
                <span>{{ store.summarizing ? 'AI 提炼中...' : '交给 AI 提炼纪要' }}</span>
              </button>
              <button type="button" class="btn btn-ghost" @click="store.exitMultiSelect">
                取消
              </button>
            </div>
          </footer>

          <!-- 微信/飞书风格日常输入区 -->
          <footer v-else class="composer-area">
            <!-- @某人 联想浮层 -->
            <Transition name="pop">
              <div v-if="mentionOpen && mentionOptions.length > 0" class="mention-popover glass-strong">
                <div class="mention-head">选择要提醒的人（↑↓ 切换，Enter 确认）</div>
                <button
                  v-for="(opt, idx) in mentionOptions"
                  :key="opt.id"
                  type="button"
                  class="mention-item"
                  :class="{ active: idx === mentionActiveIdx }"
                  @mousedown.prevent="selectMentionOption(opt)"
                >
                  <div
                    class="avatar avatar-sm"
                    :class="{ 'avatar-ai': opt.isAi }"
                    :style="opt.isAi ? undefined : { background: opt.isAll ? '#C2410C' : avatarColor(opt.label) }"
                  >
                    <Sparkles v-if="opt.isAi" :size="13" />
                    <Users v-else-if="opt.isAll" :size="13" />
                    <span v-else>{{ avatarInitial(opt.label) }}</span>
                    <span v-if="opt.online && !opt.isAi && !opt.isAll" class="online-dot" />
                  </div>
                  <div class="mention-meta">
                    <strong>{{ opt.label }}</strong>
                    <small>{{ opt.sub }}</small>
                  </div>
                </button>
              </div>
            </Transition>

            <!-- Emoji 液态玻璃选择器 -->
            <Transition name="pop">
              <div v-if="showEmojiPicker" class="emoji-popover glass-strong" @click.stop>
                <div class="emoji-grid">
                  <button
                    v-for="emo in PICKER_EMOJIS"
                    :key="emo"
                    type="button"
                    class="emoji-cell"
                    @click="insertEmoji(emo)"
                  >
                    {{ emo }}
                  </button>
                </div>
              </div>
            </Transition>

            <!-- AI 快捷指令浮层 -->
            <Transition name="pop">
              <div v-if="showAiMenu" class="ai-popover glass-strong" @click.stop>
                <button
                  type="button"
                  class="ai-menu-item"
                  @click="handleQuickAction('@AI 总结一下大家最近聊了什么', true)"
                >
                  <strong>总结最近聊天</strong>
                  <small>在当前会话权限范围内梳理要点</small>
                </button>
                <button
                  type="button"
                  class="ai-menu-item"
                  @click="handleQuickAction('@AI 15秒后提醒大家代码评审', true)"
                >
                  <strong>创建定时群提醒</strong>
                  <small>体验人工审批 + 到点自动推送</small>
                </button>
                <button
                  type="button"
                  class="ai-menu-item"
                  @click="handleQuickAction('@AI ', false)"
                >
                  <strong>向 @AI 自由提问</strong>
                  <small>在输入框插入 @AI 并继续输入</small>
                </button>
              </div>
            </Transition>

            <!-- 引用条 -->
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

            <!-- Ctrl+V 贴图 / 文件待发送确认区 -->
            <Transition name="slide-up">
              <div v-if="pendingFiles.length > 0" class="staged-tray">
                <div v-for="item in pendingFiles" :key="item.id" class="staged-card">
                  <img v-if="item.isImage && item.previewUrl" :src="item.previewUrl" :alt="item.file.name" class="staged-thumb" />
                  <div v-else class="staged-file-ic">
                    <FileText :size="18" />
                  </div>
                  <div class="staged-info">
                    <div class="staged-name">{{ item.file.name }}</div>
                    <small class="tabular">{{ formatFileSize(item.file.size) }}</small>
                  </div>
                  <button type="button" class="staged-rm" title="移除" @click="removePendingFile(item.id)">
                    <X :size="12" />
                  </button>
                </div>
                <span class="staged-hint">按 Enter 确认发送</span>
              </div>
            </Transition>

            <!-- 极简微信/飞书工具栏 -->
            <div class="composer-toolbar">
              <div class="toolbar-left">
                <button
                  type="button"
                  class="tool-btn"
                  :class="{ active: showEmojiPicker }"
                  title="选择表情"
                  @click.stop="showEmojiPicker = !showEmojiPicker; showAiMenu = false"
                >
                  <Smile :size="17" />
                </button>

                <input ref="fileInputRef" type="file" multiple hidden @change="handleFileChange" />
                <button
                  type="button"
                  class="tool-btn"
                  title="发送图片或文件（也支持 Ctrl+V 粘贴与拖拽）"
                  @click="triggerFilePick"
                >
                  <Paperclip :size="17" />
                </button>

                <button
                  type="button"
                  class="ai-trigger-btn"
                  :class="{ active: showAiMenu }"
                  title="召唤 AI 助手"
                  @click.stop="showAiMenu = !showAiMenu; showEmojiPicker = false"
                >
                  <Sparkles :size="13" />
                  <span>AI 助手</span>
                </button>
              </div>

              <!-- 仅面试演练模式下显示的极简压测/打断/幂等演练按钮 -->
              <div v-if="store.demoMode" class="toolbar-demo">
                <button
                  type="button"
                  class="demo-chip liquid-lens"
                  @click="handleQuickAction('@AI 用 Markdown 和 Java 代码详细讲解分布式 ID 与幂等设计', true)"
                >
                  AI 长流式打断
                </button>
                <button
                  type="button"
                  class="demo-chip liquid-lens"
                  @click="handleQuickAction('【幂等演练】同一 clientMsgId 双发去重测试', true, true)"
                >
                  幂等重发测试
                </button>
              </div>
            </div>

            <div class="composer-box">
              <textarea
                ref="textareaRef"
                v-model="draft"
                rows="1"
                class="composer-input"
                placeholder="发送消息（Enter 发送，Shift+Enter 换行，输入 @ 提醒成员）"
                @input="handleComposerInput"
                @keydown="handleKeydown"
                @paste="handlePaste"
              />

              <button
                type="button"
                class="btn btn-primary send-btn"
                :class="{ ready: canSendComposer }"
                :disabled="!canSendComposer"
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
        <InspectorDrawer
          v-if="showInspector"
          :requested-tab="inspectorTabReq"
          @jump-to-seq="jumpToSeq"
          @close="showInspector = false"
        />
      </Transition>
    </div>
  </Transition>

  <!-- 全局消息右键/更多 液态玻璃菜单 -->
  <Teleport to="body">
    <Transition name="pop">
      <div
        v-if="msgMenu"
        class="msg-ctx-menu glass-strong"
        :style="{ left: msgMenu.x + 'px', top: msgMenu.y + 'px' }"
        @click.stop
      >
        <div class="ctx-emoji-row">
          <button
            v-for="emo in CONTEXT_EMOJIS"
            :key="emo"
            type="button"
            class="ctx-emo-btn"
            @click="msgMenu.msg.id && store.toggleReaction(msgMenu.msg.id, emo); msgMenu = null"
          >
            {{ emo }}
          </button>
        </div>
        <div class="ctx-divider" />
        <button type="button" class="ctx-item" @click="setReplyTarget(msgMenu.msg); msgMenu = null">
          <Reply :size="14" />
          <span>引用回复</span>
        </button>
        <button type="button" class="ctx-item" @click="copyMessageContent(msgMenu.msg); msgMenu = null">
          <Copy :size="14" />
          <span>复制内容</span>
        </button>
        <button
          v-if="msgMenu.msg.id"
          type="button"
          class="ctx-item"
          @click="store.pinMessage(msgMenu.msg.id); msgMenu = null"
        >
          <Pin :size="14" />
          <span>置顶到公告栏</span>
        </button>
        <button
          v-if="msgMenu.msg.id"
          type="button"
          class="ctx-item"
          @click="store.enterMultiSelect(msgMenu.msg.id); msgMenu = null"
        >
          <CheckSquare :size="14" />
          <span>多选（转发 / AI 提炼）</span>
        </button>
        <button
          v-if="canRecallMsg(msgMenu.msg) && msgMenu.msg.id"
          type="button"
          class="ctx-item danger"
          @click="store.recallMessage(msgMenu.msg.id); msgMenu = null"
        >
          <Undo2 :size="14" />
          <span>撤回消息</span>
        </button>
      </div>
    </Transition>
  </Teleport>

  <!-- 建群弹窗 -->
  <Transition name="modal">
    <GroupModal v-if="showGroupModal" @close="showGroupModal = false" />
  </Transition>

  <!-- 个人资料编辑 / 他人资料名片弹窗 -->
  <Transition name="modal">
    <ProfileModal
      v-if="store.viewingProfile"
      :profile="store.viewingProfile"
      @close="store.viewingProfile = null"
    />
  </Transition>

  <!-- 飞书已读明细弹窗 -->
  <Transition name="modal">
    <ReadDetailModal
      v-if="store.readDetailMsg"
      :message="store.readDetailMsg"
      :members="store.activeMembers"
      @close="store.readDetailMsg = null"
    />
  </Transition>

  <!-- 合并转发选择会话弹窗 -->
  <Transition name="modal">
    <ForwardModal v-if="showForwardModal" @close="showForwardModal = false" />
  </Transition>

  <!-- 合并转发聊天记录详情弹窗 -->
  <Transition name="modal">
    <BundleViewerModal
      v-if="store.viewingBundle"
      :bundle="store.viewingBundle"
      @close="store.viewingBundle = null"
    />
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
  z-index: 6;
  height: 60px;
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

.peer-title-btn {
  border: none;
  background: transparent;
  color: var(--ink);
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px 4px 4px;
  border-radius: 12px;
  cursor: pointer;
  min-width: 0;
  transition: background 0.18s;
}

.peer-title-btn:hover {
  background: var(--hover);
}

.peer-status-pill {
  font-size: 11.5px;
  color: var(--ink-secondary);
  background: var(--surface);
  border: 1px solid var(--border);
  padding: 2px 9px;
  border-radius: 999px;
  white-space: nowrap;
}

.chat-menu-wrap {
  position: relative;
}

.chat-menu-popover {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  width: 214px;
  padding: 6px;
  border-radius: 16px;
  border: 1px solid var(--glass-edge);
  box-shadow: var(--shadow-md), inset 0 1px 0 rgba(255, 255, 255, 0.55);
  -webkit-backdrop-filter: blur(28px) saturate(190%);
  backdrop-filter: blur(28px) saturate(190%);
  display: flex;
  flex-direction: column;
  gap: 2px;
  z-index: 50;
  transform-origin: top right;
}

.menu-state-dot {
  margin-left: auto;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--accent);
}

.ctx-item.ai-item {
  color: var(--ai);
}

.ctx-item.ai-item:hover {
  background: var(--ai-soft);
  color: var(--ai);
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
  padding: 2px 9px;
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
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11.5px;
  font-weight: 650;
  padding: 2px 9px;
  border-radius: 999px;
  background: var(--ai-soft);
  border: 1px solid var(--ai-border);
  color: var(--ai);
}

.type-pill.clickable {
  cursor: pointer;
  transition: transform 0.3s var(--ease-spring), background 0.2s;
}

.type-pill.clickable:hover {
  transform: translateY(-1px);
  background: var(--accent-soft);
  color: var(--accent);
  border-color: var(--accent);
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
  gap: 7px;
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

.pin-banner {
  position: relative;
  z-index: 5;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 7px 16px;
  background: rgba(47, 91, 255, 0.08);
  border-bottom: 1px solid var(--border);
  font-size: 12.5px;
}

.pin-main {
  flex: 1;
  min-width: 0;
  border: none;
  background: transparent;
  color: var(--ink);
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  text-align: left;
}

.pin-ic {
  color: var(--accent);
  flex-shrink: 0;
}

.pin-label {
  font-size: 10.5px;
  font-weight: 700;
  color: var(--accent);
  background: var(--accent-soft);
  padding: 1px 6px;
  border-radius: 5px;
  flex-shrink: 0;
}

.pin-sender {
  flex-shrink: 0;
  font-size: 12px;
}

.pin-text {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--ink-secondary);
}

.pin-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.pin-more-btn {
  border: 1px solid var(--border-strong);
  background: var(--surface);
  color: var(--ink-secondary);
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  cursor: pointer;
}

.pin-close-btn {
  border: none;
  background: transparent;
  color: var(--ink-muted);
  cursor: pointer;
  display: inline-flex;
  padding: 3px;
  border-radius: 6px;
}

.pin-close-btn:hover {
  background: var(--hover);
  color: var(--ink);
}

.pin-popover {
  position: absolute;
  top: calc(100% + 6px);
  right: 16px;
  width: min(420px, calc(100% - 32px));
  border-radius: 14px;
  border: 1px solid var(--glass-edge);
  box-shadow: var(--shadow-md);
  padding: 6px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  z-index: 25;
}

.pin-pop-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 8px;
  border-radius: 9px;
}

.pin-pop-row:hover {
  background: var(--hover);
}

.pin-pop-jump {
  flex: 1;
  min-width: 0;
  border: none;
  background: transparent;
  color: var(--ink);
  display: flex;
  gap: 6px;
  font-size: 12px;
  cursor: pointer;
  text-align: left;
}

.pin-pop-jump span {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--ink-secondary);
}

.stage-banners {
  position: relative;
  z-index: 4;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 0 16px;
}

.digest-capsule {
  margin-top: 8px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  border-radius: 999px;
  border: 1px solid var(--glass-edge);
  background: rgba(18, 180, 140, 0.14);
  color: var(--ink);
  font-size: 12.5px;
  box-shadow: 0 8px 24px rgba(18, 180, 140, 0.18), inset 0 1px 0 rgba(255, 255, 255, 0.5);
}

.digest-ic {
  color: var(--ai);
}

.capsule-close {
  border: none;
  background: transparent;
  color: var(--ink-muted);
  cursor: pointer;
  display: inline-flex;
}

.mention-capsule {
  margin-top: 6px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 14px;
  border-radius: 999px;
  border: 1px solid rgba(224, 49, 59, 0.35);
  background: rgba(224, 49, 59, 0.12);
  color: var(--danger);
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  box-shadow: 0 6px 18px rgba(224, 49, 59, 0.16);
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
  border-radius: 24px;
  padding: 34px 28px 30px;
  overflow: hidden;
  animation: rise-in 0.8s var(--ease-out);
}

.empty-orb {
  width: 60px;
  height: 60px;
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

.unread-divider {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 18px 0 10px;
  color: var(--accent);
  font-size: 11.5px;
  font-weight: 600;
}

.unread-divider::before,
.unread-divider::after {
  content: '';
  flex: 1;
  height: 1px;
  background: linear-gradient(90deg, transparent, var(--accent), transparent);
  opacity: 0.45;
}

.time-divider {
  display: flex;
  justify-content: center;
  margin: 16px 0 8px;
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
  bottom: 146px;
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

.multiselect-bar {
  position: relative;
  z-index: 5;
  border-top: 1px solid var(--border);
  padding: 14px 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  background: var(--surface);
}

.ms-count {
  font-size: 13.5px;
  color: var(--ink-secondary);
}

.ms-actions {
  display: flex;
  gap: 8px;
}

.composer-area {
  position: relative;
  z-index: 5;
  border-top: 1px solid var(--border);
  padding: 8px 18px 15px;
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.composer-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 4px;
}

.tool-btn {
  width: 30px;
  height: 30px;
  border: none;
  border-radius: 9px;
  background: transparent;
  color: var(--ink-secondary);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: transform 0.3s var(--ease-spring), background 0.2s, color 0.2s;
}

.tool-btn:hover,
.tool-btn.active {
  background: var(--hover);
  color: var(--accent);
}

.ai-trigger-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: 1px solid var(--ai-border);
  background: var(--ai-soft);
  color: var(--ai);
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 650;
  cursor: pointer;
  margin-left: 4px;
  transition: transform 0.3s var(--ease-spring), box-shadow 0.25s;
}

.ai-trigger-btn:hover,
.ai-trigger-btn.active {
  transform: translateY(-1px);
  box-shadow: 0 5px 14px rgba(18, 180, 140, 0.22);
}

.toolbar-demo {
  display: flex;
  align-items: center;
  gap: 6px;
}

.demo-chip {
  border: 1px solid var(--glass-edge);
  background: var(--surface);
  color: var(--ink-secondary);
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 550;
  cursor: pointer;
  box-shadow: var(--shadow-sm);
  transition: transform 0.3s var(--ease-spring), border-color 0.2s, color 0.2s;
}

.demo-chip:hover {
  transform: translateY(-1px);
  border-color: var(--accent);
  color: var(--accent);
}

/* Emoji 浮层 */
.emoji-popover {
  position: absolute;
  left: 18px;
  bottom: calc(100% + 8px);
  width: 304px;
  padding: 10px;
  border-radius: 18px;
  border: 1px solid var(--glass-edge);
  box-shadow: var(--shadow-md), inset 0 1px 0 rgba(255, 255, 255, 0.55);
  -webkit-backdrop-filter: blur(26px) saturate(190%);
  backdrop-filter: blur(26px) saturate(190%);
  z-index: 35;
}

.emoji-grid {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 4px;
}

.emoji-cell {
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 9px;
  background: transparent;
  font-size: 18px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: transform 0.25s var(--ease-spring), background 0.15s;
}

.emoji-cell:hover {
  transform: scale(1.22);
  background: var(--hover);
}

/* AI 快捷菜单 */
.ai-popover {
  position: absolute;
  left: 86px;
  bottom: calc(100% + 8px);
  width: 248px;
  padding: 6px;
  border-radius: 16px;
  border: 1px solid var(--glass-edge);
  box-shadow: var(--shadow-md), inset 0 1px 0 rgba(255, 255, 255, 0.55);
  -webkit-backdrop-filter: blur(26px) saturate(190%);
  backdrop-filter: blur(26px) saturate(190%);
  display: flex;
  flex-direction: column;
  gap: 3px;
  z-index: 35;
}

.ai-menu-item {
  border: none;
  background: transparent;
  color: var(--ink);
  text-align: left;
  padding: 8px 10px;
  border-radius: 10px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  cursor: pointer;
  transition: background 0.16s;
}

.ai-menu-item strong {
  font-size: 12.5px;
}

.ai-menu-item small {
  font-size: 11px;
  color: var(--ink-muted);
}

.ai-menu-item:hover {
  background: var(--hover);
  color: var(--ai);
}

/* 待发送贴图/文件预览托盘 */
.staged-tray {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 7px 10px;
  border-radius: 13px;
  background: var(--surface);
  border: 1px solid var(--glass-edge);
}

.staged-card {
  position: relative;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 24px 5px 6px;
  border-radius: 10px;
  background: var(--surface-strong);
  border: 1px solid var(--border-strong);
  max-width: 220px;
}

.staged-thumb {
  width: 38px;
  height: 38px;
  border-radius: 7px;
  object-fit: cover;
  flex-shrink: 0;
}

.staged-file-ic {
  width: 36px;
  height: 36px;
  border-radius: 7px;
  background: var(--accent-soft);
  color: var(--accent);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.staged-info {
  min-width: 0;
  display: flex;
  flex-direction: column;
  font-size: 12px;
}

.staged-name {
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.staged-info small {
  font-size: 10.5px;
  color: var(--ink-muted);
}

.staged-rm {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  border: none;
  background: var(--hover);
  color: var(--ink-secondary);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}

.staged-rm:hover {
  background: var(--danger);
  color: #fff;
}

.staged-hint {
  font-size: 11.5px;
  color: var(--ink-muted);
  margin-left: auto;
}

.mention-popover {
  position: absolute;
  left: 18px;
  bottom: calc(100% + 8px);
  width: 280px;
  max-height: 260px;
  overflow-y: auto;
  border-radius: 16px;
  border: 1px solid var(--glass-edge);
  box-shadow: var(--shadow-md), inset 0 1px 0 rgba(255, 255, 255, 0.55);
  -webkit-backdrop-filter: blur(26px) saturate(190%);
  backdrop-filter: blur(26px) saturate(190%);
  padding: 6px;
  z-index: 35;
}

.mention-head {
  font-size: 11px;
  color: var(--ink-muted);
  padding: 4px 8px 6px;
}

.mention-item {
  width: 100%;
  border: none;
  background: transparent;
  color: var(--ink);
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 7px 8px;
  border-radius: 10px;
  cursor: pointer;
  text-align: left;
  transition: background 0.15s;
}

.mention-item.active,
.mention-item:hover {
  background: var(--accent-soft);
}

.mention-meta {
  display: flex;
  flex-direction: column;
  font-size: 12.5px;
  min-width: 0;
}

.mention-meta small {
  font-size: 11px;
  color: var(--ink-muted);
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

.composer-box {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  border: 1px solid var(--glass-edge);
  border-radius: 16px;
  padding: 7px 8px 7px 12px;
  background: var(--surface);
  box-shadow: var(--shadow-sm);
  transition: border-color 0.25s, box-shadow 0.35s var(--ease-out), background 0.25s;
}

.composer-box:focus-within {
  border-color: var(--accent);
  background: var(--surface-strong);
  box-shadow: 0 0 0 4px var(--accent-soft), 0 10px 28px rgba(47, 91, 255, 0.12);
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

/* 消息右键/更多 竖排玻璃菜单 */
.msg-ctx-menu {
  position: fixed;
  z-index: 320;
  width: 210px;
  padding: 6px;
  border-radius: 16px;
  border: 1px solid var(--glass-edge);
  box-shadow: var(--shadow-md), inset 0 1px 0 rgba(255, 255, 255, 0.55);
  -webkit-backdrop-filter: blur(26px) saturate(190%);
  backdrop-filter: blur(26px) saturate(190%);
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.ctx-emoji-row {
  display: flex;
  justify-content: space-between;
  padding: 2px 3px 4px;
}

.ctx-emo-btn {
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 8px;
  background: transparent;
  font-size: 16px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: transform 0.25s var(--ease-spring), background 0.15s;
}

.ctx-emo-btn:hover {
  transform: scale(1.22);
  background: var(--hover);
}

.ctx-divider {
  height: 1px;
  background: var(--border);
  margin: 2px 4px 4px;
}

.ctx-item {
  border: none;
  background: transparent;
  color: var(--ink);
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 10px;
  font-size: 12.5px;
  font-weight: 500;
  cursor: pointer;
  text-align: left;
  transition: background 0.16s, color 0.16s;
}

.ctx-item:hover {
  background: var(--hover);
  color: var(--accent);
}

.ctx-item.danger:hover {
  background: rgba(224, 49, 59, 0.12);
  color: var(--danger);
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
