<script setup lang="ts">
import { ref } from 'vue';
import { LogOut, Moon, Plus, Search, Sun, Users, Wifi, WifiOff } from 'lucide-vue-next';
import { useChatStore } from '../stores/chat';
import type { UserProfile } from '../types/im';
import { avatarColor, avatarInitial, formatTimeShort } from '../utils/format';

const emit = defineEmits<{
  (e: 'open-group-modal'): void;
}>();

const store = useChatStore();

const searchKeyword = ref('');
const searchHits = ref<UserProfile[]>([]);
const searching = ref(false);
let searchTimer: number | null = null;

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
</script>

<template>
  <aside class="sidebar">
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

    <!-- 会话列表 -->
    <div class="conv-list">
      <div v-if="store.conversations.length === 0" class="conv-empty">
        暂无会话，搜索用户名发起单聊或点击「建群」
      </div>

      <button
        v-for="conv in store.conversations"
        :key="conv.id"
        type="button"
        class="conv-item"
        :class="{ active: conv.id === store.activeConvId }"
        @click="store.selectConversation(conv.id)"
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
            <span class="conv-title">{{ conv.title }}</span>
            <span class="conv-time tabular">{{ formatTimeShort(conv.lastMsgTime) }}</span>
          </div>
          <div class="conv-bottom">
            <span class="conv-preview">{{ conv.lastMsgPreview || '暂无消息' }}</span>
            <span v-if="conv.unreadCount > 0" class="unread-badge tabular">
              {{ conv.unreadCount > 99 ? '99+' : conv.unreadCount }}
            </span>
          </div>
        </div>
      </button>
    </div>
  </aside>
</template>

<style scoped>
.sidebar {
  width: 296px;
  flex-shrink: 0;
  background: var(--rail);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  height: 100%;
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
  border-radius: 6px;
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
  border-radius: 7px;
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
  top: calc(100% + 6px);
  background: var(--surface);
  border: 1px solid var(--border-strong);
  border-radius: 9px;
  box-shadow: var(--shadow-md);
  z-index: 30;
  max-height: 240px;
  overflow-y: auto;
  padding: 4px;
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
  border-radius: 7px;
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

.conv-list {
  flex: 1;
  overflow-y: auto;
  padding: 6px 8px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.conv-empty {
  padding: 28px 14px;
  text-align: center;
  font-size: 12.5px;
  color: var(--ink-muted);
}

.conv-item {
  width: 100%;
  border: none;
  background: transparent;
  color: var(--ink);
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 10px;
  border-radius: 10px;
  cursor: pointer;
  text-align: left;
  transition: background 0.12s;
}

.conv-item:hover {
  background: var(--hover);
}

.conv-item.active {
  background: var(--accent-soft);
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

.unread-badge {
  background: var(--danger);
  color: #fff;
  font-size: 10.5px;
  font-weight: 700;
  padding: 1px 6px;
  border-radius: 999px;
  flex-shrink: 0;
}
</style>
