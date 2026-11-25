<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue';
import {
  Bell,
  BellOff,
  CheckCheck,
  LogOut,
  Moon,
  Pin,
  PinOff,
  Plus,
  Search,
  Sun,
  Users,
  Wifi,
  WifiOff
} from 'lucide-vue-next';
import { useChatStore } from '../stores/chat';
import type { Conversation, UserProfile } from '../types/im';
import { avatarColor, avatarInitial, formatTimeShort } from '../utils/format';
import { vGlow } from '../directives/glow';

const emit = defineEmits<{
  (e: 'open-group-modal'): void;
}>();

const store = useChatStore();

const searchKeyword = ref('');
const searchHits = ref<UserProfile[]>([]);
const searching = ref(false);
let searchTimer: number | null = null;

// 右键液态玻璃菜单状态
const ctxMenu = ref<{ conv: Conversation; x: number; y: number } | null>(null);

function closeCtxMenu() {
  ctxMenu.value = null;
}

onMounted(() => window.addEventListener('click', closeCtxMenu));
onUnmounted(() => window.removeEventListener('click', closeCtxMenu));

function openCtxMenu(ev: MouseEvent, conv: Conversation) {
  const x = Math.min(ev.clientX, window.innerWidth - 190);
  const y = Math.min(ev.clientY, window.innerHeight - 160);
  ctxMenu.value = { conv, x, y };
}

function handleSearchInput() {
  if (searchTimer) window.clearTimeout(searchTimer);
  const kw = searchKeyword.value.trim();
  if (!kw) {
    searchHits.value = [];
    return;
  }
  searchTimer = window.setTimeout(async () => {
    searching.value = true;
    try {
      searchHits.value = await store.searchUsers(kw);
    } catch {
      searchHits.value = [];
    } finally {
      searching.value = false;
    }
  }, 220);
}

async function handlePickUser(user: UserProfile) {
  searchKeyword.value = '';
  searchHits.value = [];
  try {
    await store.startDirect(user.id);
  } catch (e: any) {
    store.pushToast(e.message || '创建私聊失败', 'error');
  }
}

function getDraftText(convId: string): string {
  if (convId === store.activeConvId) return '';
  return store.drafts[convId] || '';
}
</script>

<template>
  <aside v-glow class="sidebar glass">
    <!-- 顶部个人信息 -->
    <div class="profile-bar">
      <div class="profile-left">
        <div
          class="avatar"
          :style="{ background: avatarColor(store.currentUser?.username || 'u') }"
        >
          {{ avatarInitial(store.currentUser?.nickname || 'U') }}
        </div>
        <div class="profile-meta">
          <div class="profile-name">{{ store.currentUser?.nickname }}</div>
          <div class="profile-sub">@{{ store.currentUser?.username }}</div>
        </div>
      </div>

      <div class="profile-actions">
        <button
          type="button"
          class="btn-icon"
          :title="store.theme === 'light' ? '切换深色主题' : '切换浅色主题'"
          @click="store.toggleTheme"
        >
          <Moon v-if="store.theme === 'light'" :size="15" />
          <Sun v-else :size="15" />
        </button>
        <button type="button" class="btn-icon" title="退出登录" @click="store.logout">
          <LogOut :size="15" />
        </button>
      </div>
    </div>

    <!-- 网关状态与演练栏 -->
    <div class="gateway-panel">
      <div class="ws-line">
        <span class="ws-indicator" :class="store.wsState">
          <Wifi v-if="store.wsState === 'connected'" :size="12" />
          <WifiOff v-else :size="12" />
          <span>{{ store.wsStatusText }}</span>
        </span>
        <span v-if="store.wsRttMs !== null" class="rtt-badge tabular">
          RTT {{ store.wsRttMs }}ms
        </span>
      </div>

      <div class="gateway-controls">
        <select
          class="gw-select"
          :value="store.wsUrl"
          @change="store.switchGateway(($event.target as HTMLSelectElement).value)"
        >
          <option value="ws://127.0.0.1:9001/ws">网关 1 (:9001)</option>
          <option value="ws://127.0.0.1:9002/ws">网关 2 (:9002)</option>
        </select>

        <button
          type="button"
          class="btn btn-xs"
          :class="store.manualOffline ? 'btn-success' : 'btn-ghost'"
          @click="store.toggleManualOffline"
        >
          {{ store.manualOffline ? '恢复联网' : '模拟断网' }}
        </button>
      </div>
    </div>

    <!-- 搜索用户 + 建群 -->
    <div class="search-row">
      <div class="search-wrap">
        <Search :size="14" class="search-icon" />
        <input
          v-model="searchKeyword"
          class="search-input"
          placeholder="搜索用户发起私聊..."
          @input="handleSearchInput"
        />
        <div v-if="searchKeyword.trim()" class="search-dropdown">
          <div v-if="searching" class="search-empty">搜索中...</div>
          <div v-else-if="searchHits.length === 0" class="search-empty">未找到匹配用户</div>
          <button
            v-for="u in searchHits"
            :key="u.id"
            type="button"
            class="search-item"
            @click="handlePickUser(u)"
          >
            <span class="avatar avatar-sm" :style="{ background: avatarColor(u.username) }">
              {{ avatarInitial(u.nickname) }}
            </span>
            <span class="search-item-meta">
              <strong>{{ u.nickname }}</strong>
              <small>@{{ u.username }}</small>
            </span>
          </button>
        </div>
      </div>

      <button
        type="button"
        class="btn btn-primary btn-xs group-btn"
        title="新建群聊"
        @click="emit('open-group-modal')"
      >
        <Plus :size="14" />
        <span>建群</span>
      </button>
    </div>

    <!-- 会话列表（右键置顶 / 免打扰 / 标为已读） -->
    <div class="conv-scroll">
      <div v-if="store.conversations.length === 0" class="conv-empty">
        暂无会话，搜索用户名发起单聊或点击「建群」
      </div>

      <TransitionGroup tag="div" name="conv" class="conv-list">
        <button
          v-for="conv in store.conversations"
          :key="conv.id"
          type="button"
          class="conv-item"
          :class="{ active: conv.id === store.activeConvId, pinned: conv.pinned }"
          title="右键可置顶、免打扰或标为已读"
          @click="store.selectConversation(conv.id)"
          @contextmenu.prevent="openCtxMenu($event, conv)"
        >
          <div
            class="avatar"
            :style="{ background: conv.type === 2 ? '#0D7A5F' : avatarColor(conv.title) }"
          >
            <Users v-if="conv.type === 2" :size="16" />
            <span v-else>{{ avatarInitial(conv.title) }}</span>
            <span v-if="conv.type === 1 && conv.peerOnline" class="online-dot" title="当前在线" />
          </div>

          <div class="conv-body">
            <div class="conv-top">
              <span class="conv-title-wrap">
                <Pin v-if="conv.pinned" :size="11" class="pin-mark" />
                <span class="conv-title">{{ conv.title }}</span>
              </span>
              <span class="conv-time tabular">{{ formatTimeShort(conv.lastMsgTime) }}</span>
            </div>

            <div class="conv-bottom">
              <span class="conv-preview">
                <template v-if="getDraftText(conv.id)">
                  <span class="alert-prefix">[草稿]</span> {{ getDraftText(conv.id) }}
                </template>
                <template v-else-if="conv.mentionSeq && conv.id !== store.activeConvId">
                  <span class="alert-prefix">[有人@我]</span> {{ conv.lastMsgPreview || '提到了你' }}
                </template>
                <template v-else>
                  {{ conv.lastMsgPreview || '暂无消息' }}
                </template>
              </span>

              <span class="conv-badges">
                <BellOff v-if="conv.muted" :size="12" class="mute-icon" title="已开启免打扰" />
                <span
                  v-if="conv.unreadCount > 0"
                  :key="conv.unreadCount"
                  class="unread-badge tabular"
                  :class="{ muted: conv.muted }"
                >
                  {{ conv.unreadCount > 99 ? '99+' : conv.unreadCount }}
                </span>
              </span>
            </div>
          </div>
        </button>
      </TransitionGroup>
    </div>

    <!-- 右键液态玻璃上下文菜单 -->
    <Teleport to="body">
      <Transition name="pop">
        <div
          v-if="ctxMenu"
          class="ctx-menu glass-strong"
          :style="{ left: ctxMenu.x + 'px', top: ctxMenu.y + 'px' }"
          @click.stop
        >
          <button
            type="button"
            class="ctx-item"
            @click="store.updateConvPreferences(ctxMenu.conv.id, { pinned: !ctxMenu.conv.pinned }); closeCtxMenu()"
          >
            <PinOff v-if="ctxMenu.conv.pinned" :size="14" />
            <Pin v-else :size="14" />
            <span>{{ ctxMenu.conv.pinned ? '取消置顶' : '置顶会话' }}</span>
          </button>

          <button
            type="button"
            class="ctx-item"
            @click="store.updateConvPreferences(ctxMenu.conv.id, { muted: !ctxMenu.conv.muted }); closeCtxMenu()"
          >
            <Bell v-if="ctxMenu.conv.muted" :size="14" />
            <BellOff v-else :size="14" />
            <span>{{ ctxMenu.conv.muted ? '取消免打扰' : '消息免打扰' }}</span>
          </button>

          <button
            v-if="ctxMenu.conv.unreadCount > 0"
            type="button"
            class="ctx-item"
            @click="store.markConvAsReadManual(ctxMenu.conv.id); closeCtxMenu()"
          >
            <CheckCheck :size="14" />
            <span>标为已读</span>
          </button>
        </div>
      </Transition>
    </Teleport>
  </aside>
</template>

<style scoped>
.sidebar {
  width: 296px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  height: 100%;
  border-radius: var(--radius-panel);
  animation: rise-in 0.7s var(--ease-out) both;
}

.sidebar > * {
  position: relative;
  z-index: 1;
}

.sidebar > .search-row {
  z-index: 10;
}

.profile-bar {
  padding: 14px 14px 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--border);
}

.profile-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.profile-meta {
  min-width: 0;
}

.profile-name {
  font-weight: 700;
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.profile-sub {
  font-size: 11.5px;
  color: var(--ink-muted);
}

.profile-actions {
  display: flex;
  gap: 6px;
}

.gateway-panel {
  padding: 10px 14px;
  border-bottom: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.ws-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}

.ws-indicator {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 999px;
}

.ws-indicator.connected {
  background: rgba(21, 128, 61, 0.12);
  color: var(--ok);
}

.ws-indicator.connecting {
  background: var(--warn-soft);
  color: var(--warn);
}

.ws-indicator.disconnected {
  background: rgba(220, 38, 38, 0.12);
  color: var(--danger);
}

.rtt-badge {
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-muted);
  font-family: var(--font-mono);
}

.gateway-controls {
  display: flex;
  gap: 6px;
}

.gw-select {
  flex: 1;
  min-width: 0;
  padding: 4px 8px;
  border-radius: 8px;
  border: 1px solid var(--border-strong);
  background: var(--surface);
  color: var(--ink);
  font-size: 12px;
  outline: none;
}

.search-row {
  padding: 10px 14px;
  display: flex;
  gap: 8px;
  border-bottom: 1px solid var(--border);
}

.search-wrap {
  position: relative;
  flex: 1;
}

.search-icon {
  position: absolute;
  left: 9px;
  top: 50%;
  transform: translateY(-50%);
  color: var(--ink-muted);
  pointer-events: none;
}

.search-input {
  width: 100%;
  padding: 6px 9px 6px 28px;
  border-radius: 10px;
  border: 1px solid var(--border-strong);
  background: var(--surface);
  color: var(--ink);
  font-size: 12.5px;
  outline: none;
}

.search-input:focus {
  border-color: var(--accent);
}

.search-dropdown {
  position: absolute;
  left: 0;
  right: 0;
  top: calc(100% + 8px);
  background: var(--surface-strong);
  border: 1px solid var(--glass-edge);
  border-radius: 14px;
  box-shadow: var(--shadow-md), inset 0 1px 0 rgba(255, 255, 255, 0.4);
  -webkit-backdrop-filter: blur(24px) saturate(180%);
  backdrop-filter: blur(24px) saturate(180%);
  z-index: 30;
  max-height: 240px;
  overflow-y: auto;
  padding: 5px;
  transform-origin: top center;
  animation: pop-in 0.35s var(--ease-spring);
}

.search-empty {
  padding: 12px;
  text-align: center;
  font-size: 12px;
  color: var(--ink-muted);
}

.search-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 8px;
  border: none;
  background: transparent;
  color: var(--ink);
  border-radius: 9px;
  cursor: pointer;
  text-align: left;
}

.search-item:hover {
  background: var(--hover);
}

.search-item-meta {
  display: flex;
  flex-direction: column;
  font-size: 12.5px;
}

.search-item-meta small {
  font-size: 11px;
  color: var(--ink-muted);
}

.group-btn {
  flex-shrink: 0;
}

.conv-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.conv-list {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.conv-empty {
  padding: 28px 14px;
  text-align: center;
  font-size: 12.5px;
  color: var(--ink-muted);
}

.conv-item {
  position: relative;
  width: 100%;
  background: transparent;
  color: var(--ink);
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 10px;
  border-radius: 14px;
  border: 1px solid transparent;
  cursor: pointer;
  text-align: left;
  transition:
    background 0.25s var(--ease-out),
    border-color 0.25s,
    box-shadow 0.35s var(--ease-out),
    transform 0.4s var(--ease-spring);
}

.conv-item.pinned {
  background: rgba(47, 91, 255, 0.05);
}

.conv-item:hover {
  background: var(--hover);
  transform: translateX(3px);
}

.conv-item:active {
  transform: scale(0.97);
  transition-duration: 0.08s;
}

.conv-item.active {
  background: var(--surface-strong);
  border-color: var(--glass-edge);
  box-shadow: 0 6px 20px rgba(34, 52, 110, 0.12), inset 0 1px 0 rgba(255, 255, 255, 0.6);
}

.conv-item.active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 22%;
  bottom: 22%;
  width: 3px;
  border-radius: 3px;
  background: var(--accent-grad);
  animation: pop-in 0.4s var(--ease-spring);
}

.conv-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.conv-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 6px;
}

.conv-title-wrap {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

.pin-mark {
  color: var(--accent);
  flex-shrink: 0;
}

.conv-title {
  font-weight: 600;
  font-size: 13.5px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.conv-time {
  font-size: 11px;
  color: var(--ink-muted);
  flex-shrink: 0;
}

.conv-bottom {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 6px;
}

.conv-preview {
  font-size: 12px;
  color: var(--ink-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.alert-prefix {
  color: var(--danger);
  font-weight: 700;
}

.conv-badges {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex-shrink: 0;
}

.mute-icon {
  color: var(--ink-muted);
}

.unread-badge {
  background: linear-gradient(135deg, #FF5A65, #E0313B);
  color: #fff;
  font-size: 10.5px;
  font-weight: 700;
  padding: 1px 7px;
  border-radius: 999px;
  flex-shrink: 0;
  box-shadow: 0 3px 10px rgba(224, 49, 59, 0.35);
  animation: badge-pop 0.5s var(--ease-spring);
}

.unread-badge.muted {
  background: var(--ink-muted);
  box-shadow: none;
}

@keyframes badge-pop {
  0% { transform: scale(0.3); }
  100% { transform: scale(1); }
}

.ctx-menu {
  position: fixed;
  z-index: 300;
  min-width: 164px;
  padding: 5px;
  border-radius: 14px;
  border: 1px solid var(--glass-edge);
  box-shadow: var(--shadow-md), inset 0 1px 0 rgba(255, 255, 255, 0.55);
  -webkit-backdrop-filter: blur(26px) saturate(190%);
  backdrop-filter: blur(26px) saturate(190%);
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.ctx-item {
  border: none;
  background: transparent;
  color: var(--ink);
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 9px;
  font-size: 12.5px;
  font-weight: 500;
  cursor: pointer;
  text-align: left;
  transition: background 0.18s;
}

.ctx-item:hover {
  background: var(--hover);
  color: var(--accent);
}
</style>
