<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import {
  Ban,
  Check,
  ChevronRight,
  MessageCircle,
  Search,
  UserCheck,
  UserPlus,
  Users,
  X
} from 'lucide-vue-next';
import { useChatStore } from '../stores/chat';
import type { UserProfile } from '../types/im';
import { groupByInitial } from '../utils/pinyin';
import UserAvatar from './UserAvatar.vue';
import { vGlow } from '../directives/glow';

const store = useChatStore();

const searchKeyword = ref('');
const searchHits = ref<UserProfile[]>([]);
const searching = ref(false);
const remarkMap = ref<Record<string, string>>({});
const showBlocked = ref(false);
const friendFilter = ref<'all' | 'online'>('all');
const busyId = ref<string | null>(null);
const highlightedLetter = ref<string | null>(null);
const scrollContainerRef = ref<HTMLElement | null>(null);
let searchTimer: number | null = null;
let highlightTimer: number | null = null;

const incomingPending = computed(() =>
  store.friendRequests.filter((r) => r.direction === 'INCOMING' && r.status === 'PENDING')
);

const handledOrOutgoing = computed(() =>
  store.friendRequests
    .filter((r) => !(r.direction === 'INCOMING' && r.status === 'PENDING'))
    .slice(0, 10)
);

const totalRequestRecords = computed(
  () => incomingPending.value.length + handledOrOutgoing.value.length
);

// 仅当有待处理的新好友邀请时默认展开，没有新邀请默认保持折叠清爽
const newFriendsOpen = ref(false);

watch(
  () => incomingPending.value.length,
  (next, prev) => {
    if (next > 0 && (prev ?? 0) === 0) {
      newFriendsOpen.value = true;
    } else if (next === 0 && (prev ?? 0) > 0) {
      newFriendsOpen.value = false;
    }
  },
  { immediate: true }
);

const onlineFriendCount = computed(
  () => store.friends.filter((f) => Boolean(f.online)).length
);

const visibleFriends = computed(() => {
  if (friendFilter.value === 'online') {
    return store.friends.filter((f) => Boolean(f.online));
  }
  return store.friends;
});

const friendGroups = computed(() =>
  groupByInitial(visibleFriends.value, (f) => f.nickname || f.username)
);

const availableLetters = computed(() => friendGroups.value.map((g) => g.letter));

function scrollToLetter(letter: string) {
  const container = scrollContainerRef.value;
  if (!container) return;
  const target = container.querySelector<HTMLElement>(`[data-alpha-group="${letter}"]`);
  if (!target) return;
  target.scrollIntoView({ behavior: 'smooth', block: 'start' });
  highlightedLetter.value = letter;
  if (highlightTimer) window.clearTimeout(highlightTimer);
  highlightTimer = window.setTimeout(() => {
    highlightedLetter.value = null;
  }, 1100);
}

function onSearchInput() {
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

function clearSearch() {
  searchKeyword.value = '';
  searchHits.value = [];
}

async function handleSendRequest(user: UserProfile) {
  busyId.value = user.id;
  try {
    await store.sendFriendRequest(user.id, remarkMap.value[user.id] || '');
    remarkMap.value[user.id] = '';
    if (searchKeyword.value.trim()) {
      searchHits.value = await store.searchUsers(searchKeyword.value.trim());
    }
  } finally {
    busyId.value = null;
  }
}

async function handleAccept(requestId: string) {
  busyId.value = requestId;
  try {
    await store.acceptFriendRequest(requestId);
    if (searchKeyword.value.trim()) {
      searchHits.value = await store.searchUsers(searchKeyword.value.trim());
    }
  } finally {
    busyId.value = null;
  }
}

async function handleReject(requestId: string) {
  busyId.value = requestId;
  try {
    await store.rejectFriendRequest(requestId);
  } finally {
    busyId.value = null;
  }
}

async function handleStartChat(userId: string) {
  try {
    await store.startDirect(userId);
    store.setNavTab('chats');
  } catch (e: any) {
    store.pushToast(e.message || '打开私聊失败', 'error');
  }
}

async function handleUnblock(userId: string) {
  busyId.value = userId;
  try {
    await store.unblockUser(userId);
  } finally {
    busyId.value = null;
  }
}

onMounted(() => {
  store.loadContactsData();
});
</script>

<template>
  <aside v-glow class="contacts-rail glass">
    <!-- 顶部标题 + 在线微胶囊 + 搜索栏 -->
    <div class="contacts-head">
      <div class="head-bar">
        <div class="head-title">
          <Users :size="16" />
          <span>通讯录</span>
        </div>
        <span v-if="store.friends.length > 0" class="online-chip tabular" title="当前在线好友数">
          <i class="live-dot" />
          <span>{{ onlineFriendCount }}/{{ store.friends.length }} 在线</span>
        </span>
      </div>

      <div class="search-wrap">
        <Search :size="14" class="search-icon" />
        <input
          v-model="searchKeyword"
          class="search-input"
          placeholder="搜索账号、昵称或邮箱添加好友..."
          @input="onSearchInput"
          @keydown.esc="clearSearch"
        />
        <button v-if="searchKeyword" type="button" class="search-clear" @click="clearSearch">
          <X :size="12" />
        </button>
      </div>
    </div>

    <div class="contacts-body">
      <div ref="scrollContainerRef" class="contacts-scroll">
        <!-- 搜索添加新朋友结果 -->
        <section v-if="searchKeyword.trim()" class="contact-section">
          <div class="section-cap">搜索结果</div>
          <div v-if="searching" class="empty-hint">搜索中...</div>
          <div v-else-if="searchHits.length === 0" class="empty-hint">未找到匹配用户</div>
          <div v-for="u in searchHits" :key="u.id" class="user-card">
            <div class="user-main" @click="store.openProfile(u.id)">
              <UserAvatar :name="u.nickname" :seed="u.username" :src="u.avatar" size="sm" clickable />
              <div class="user-meta">
                <strong>{{ u.nickname }}</strong>
                <small>@{{ u.username }}</small>
              </div>
            </div>

            <div class="user-actions">
              <button
                v-if="u.relationStatus === 'FRIEND'"
                type="button"
                class="btn btn-primary btn-xs"
                @click="handleStartChat(u.id)"
              >
                <MessageCircle :size="12" />
                <span>发消息</span>
              </button>
              <button
                v-else-if="u.relationStatus === 'PENDING_IN' && u.pendingRequestId"
                type="button"
                class="btn btn-primary btn-xs"
                :disabled="busyId === u.pendingRequestId"
                @click="handleAccept(u.pendingRequestId)"
              >
                <Check :size="12" />
                <span>同意</span>
              </button>
              <span v-else-if="u.relationStatus === 'PENDING_OUT'" class="rel-pill muted">已申请</span>
              <span v-else-if="u.relationStatus === 'BLOCKED'" class="rel-pill danger">已拉黑</span>
              <button
                v-else
                type="button"
                class="btn btn-ghost btn-xs"
                :disabled="busyId === u.id"
                @click="handleSendRequest(u)"
              >
                <UserPlus :size="12" />
                <span>加好友</span>
              </button>
            </div>
          </div>
        </section>

        <!-- 新的朋友（可折叠抽屉：有新邀请自动展开，无新邀请默认收起） -->
        <section class="accordion-card" :class="{ 'has-pending': incomingPending.length > 0, open: newFriendsOpen }">
          <button
            type="button"
            class="accordion-trigger"
            @click="newFriendsOpen = !newFriendsOpen"
          >
            <span class="accordion-left">
              <span class="accordion-ic" :class="{ alert: incomingPending.length > 0 }">
                <UserPlus :size="13" />
              </span>
              <span class="accordion-label">新的朋友</span>
            </span>
            <span class="accordion-right">
              <span v-if="incomingPending.length > 0" class="pending-pill tabular">
                {{ incomingPending.length }} 条新邀请
              </span>
              <span v-else-if="totalRequestRecords > 0" class="muted-count tabular">
                {{ totalRequestRecords }}
              </span>
              <ChevronRight :size="14" class="accordion-arrow" :class="{ rotated: newFriendsOpen }" />
            </span>
          </button>

          <div class="accordion-collapse" :class="{ expanded: newFriendsOpen }">
            <div class="accordion-inner">
              <div v-if="totalRequestRecords === 0" class="subtle-empty">
                暂无好友申请记录
              </div>

              <div v-for="req in incomingPending" :key="req.id" class="req-item is-pending">
                <div class="user-main" @click="store.openProfile(req.peerUserId)">
                  <UserAvatar :name="req.peerNickname" :seed="req.peerUsername" :src="req.peerAvatar" size="sm" clickable />
                  <div class="user-meta">
                    <strong>{{ req.peerNickname }}</strong>
                    <small>{{ req.remark || '请求添加你为好友' }}</small>
                  </div>
                </div>
                <div class="req-btns">
                  <button
                    type="button"
                    class="btn btn-primary btn-xs"
                    :disabled="busyId === req.id"
                    @click="handleAccept(req.id)"
                  >
                    同意
                  </button>
                  <button
                    type="button"
                    class="btn btn-ghost btn-xs"
                    :disabled="busyId === req.id"
                    @click="handleReject(req.id)"
                  >
                    拒绝
                  </button>
                </div>
              </div>

              <div v-for="req in handledOrOutgoing" :key="req.id" class="req-item">
                <div class="user-main" @click="store.openProfile(req.peerUserId)">
                  <UserAvatar :name="req.peerNickname" :seed="req.peerUsername" :src="req.peerAvatar" size="sm" clickable />
                  <div class="user-meta">
                    <strong>{{ req.peerNickname }}</strong>
                    <small>
                      {{ req.direction === 'OUTGOING' ? '我发起的申请' : '收到申请' }}
                      {{ req.remark ? ` · ${req.remark}` : '' }}
                    </small>
                  </div>
                </div>
                <span class="rel-pill" :class="req.status.toLowerCase()">
                  {{ req.status === 'PENDING' ? '等待验证' : req.status === 'ACCEPTED' ? '已添加' : '已拒绝' }}
                </span>
              </div>
            </div>
          </div>
        </section>

        <!-- 我的好友（A–Z 拼音首字母杂志目录式分组 + 在线筛选） -->
        <section class="friends-section">
          <div class="friends-header">
            <div class="friends-title-group">
              <span class="section-label">我的好友</span>
              <span class="friends-total tabular">{{ visibleFriends.length }}</span>
            </div>

            <div v-if="store.friends.length > 0" class="filter-segment">
              <button
                type="button"
                class="seg-btn"
                :class="{ active: friendFilter === 'all' }"
                @click="friendFilter = 'all'"
              >
                全部
              </button>
              <button
                type="button"
                class="seg-btn"
                :class="{ active: friendFilter === 'online' }"
                @click="friendFilter = 'online'"
              >
                在线
              </button>
            </div>
          </div>

          <div v-if="store.friends.length === 0" class="empty-hint">
            还没有好友，在上方搜索账号或邮箱添加吧
          </div>
          <div v-else-if="friendGroups.length === 0" class="empty-hint">
            当前暂无在线好友
          </div>

          <div v-else class="alpha-directory">
            <div
              v-for="group in friendGroups"
              :key="group.letter"
              :data-alpha-group="group.letter"
              class="alpha-group"
              :class="{ 'is-highlighted': highlightedLetter === group.letter }"
            >
              <!-- 左侧杂志风垂直首字母锚标 + 呼吸指引线 -->
              <div class="alpha-gutter">
                <span class="alpha-stamp">{{ group.letter }}</span>
                <span class="alpha-spine" />
              </div>

              <!-- 右侧轻盈好友行 -->
              <div class="alpha-rows">
                <div
                  v-for="f in group.items"
                  :key="f.id"
                  class="friend-item"
                  @click="store.openProfile(f.id)"
                >
                  <UserAvatar
                    :name="f.nickname"
                    :seed="f.username"
                    :src="f.avatar"
                    :online="Boolean(f.online)"
                    size="sm"
                    clickable
                  />
                  <div class="friend-meta">
                    <strong class="friend-name">{{ f.nickname }}</strong>
                    <small v-if="f.statusText" class="friend-status">
                      <span v-if="f.statusEmoji">{{ f.statusEmoji }}</span>
                      <span>{{ f.statusText }}</span>
                    </small>
                    <small v-else class="friend-sub">@{{ f.username }}</small>
                  </div>
                  <button
                    type="button"
                    class="quick-chat-btn"
                    title="发消息"
                    @click.stop="handleStartChat(f.id)"
                  >
                    <MessageCircle :size="14" />
                  </button>
                </div>
              </div>
            </div>
          </div>
        </section>

        <!-- 黑名单（同款极简折叠条） -->
        <section class="accordion-card" :class="{ open: showBlocked }">
          <button
            type="button"
            class="accordion-trigger"
            @click="showBlocked = !showBlocked"
          >
            <span class="accordion-left">
              <span class="accordion-ic muted">
                <Ban :size="13" />
              </span>
              <span class="accordion-label">黑名单</span>
            </span>
            <span class="accordion-right">
              <span class="muted-count tabular">{{ store.blockedUsers.length }}</span>
              <ChevronRight :size="14" class="accordion-arrow" :class="{ rotated: showBlocked }" />
            </span>
          </button>

          <div class="accordion-collapse" :class="{ expanded: showBlocked }">
            <div class="accordion-inner">
              <div v-if="store.blockedUsers.length === 0" class="subtle-empty">
                黑名单为空
              </div>
              <div v-for="b in store.blockedUsers" :key="b.id" class="req-item">
                <div class="user-main" @click="store.openProfile(b.id)">
                  <UserAvatar :name="b.nickname" :seed="b.username" :src="b.avatar" size="sm" />
                  <div class="user-meta">
                    <strong>{{ b.nickname }}</strong>
                    <small>@{{ b.username }}</small>
                  </div>
                </div>
                <button
                  type="button"
                  class="btn btn-ghost btn-xs"
                  :disabled="busyId === b.id"
                  @click="handleUnblock(b.id)"
                >
                  <UserCheck :size="12" />
                  <span>解除</span>
                </button>
              </div>
            </div>
          </div>
        </section>
      </div>

      <!-- 右侧悬浮快捷字母索引轨（仅存在 2 个及以上首字母组时优雅浮现） -->
      <nav
        v-if="!searchKeyword.trim() && availableLetters.length >= 2"
        class="alpha-rail"
        aria-label="好友首字母快捷定位"
      >
        <button
          v-for="letter in availableLetters"
          :key="letter"
          type="button"
          class="alpha-rail-btn"
          :class="{ active: highlightedLetter === letter }"
          :title="`跳转到 ${letter}`"
          @click="scrollToLetter(letter)"
        >
          {{ letter }}
        </button>
      </nav>
    </div>
  </aside>
</template>

<style scoped>
.contacts-rail {
  width: 296px;
  flex-shrink: 0;
  height: 100%;
  border-radius: var(--radius-panel);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  animation: rise-in 0.55s var(--ease-out) both;
}

.contacts-head {
  padding: 14px 12px 11px;
  border-bottom: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.head-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 2px;
}

.head-title {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 14.5px;
  font-weight: 700;
  color: var(--ink);
}

.online-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-secondary);
  background: var(--rail);
  border: 1px solid var(--border);
  padding: 2px 8px;
  border-radius: 999px;
}

.live-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ok);
  box-shadow: 0 0 0 2px rgba(19, 138, 71, 0.2);
}

.search-wrap {
  position: relative;
  display: flex;
  align-items: center;
}

.search-icon {
  position: absolute;
  left: 10px;
  color: var(--ink-muted);
  pointer-events: none;
}

.search-input {
  width: 100%;
  height: 34px;
  padding: 0 28px 0 30px;
  border-radius: 11px;
  border: 1px solid var(--border);
  background: var(--surface);
  color: var(--ink);
  font-size: 12.5px;
  outline: none;
  transition: border-color 0.2s, background 0.2s;
}

.search-input:focus {
  border-color: var(--accent);
  background: var(--surface-strong);
}

.search-clear {
  position: absolute;
  right: 6px;
  border: none;
  background: transparent;
  color: var(--ink-muted);
  cursor: pointer;
  padding: 3px;
  display: inline-flex;
}

.contacts-body {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
}

.contacts-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 10px 10px 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.contact-section {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.section-cap {
  font-size: 11.5px;
  font-weight: 700;
  color: var(--ink-muted);
  padding: 0 4px;
}

/* ==================== 统一极简折叠抽屉卡片（新的朋友 / 黑名单） ==================== */
.accordion-card {
  border-radius: 14px;
  border: 1px solid var(--border);
  background: var(--surface);
  overflow: hidden;
  transition: border-color 0.22s, background 0.22s, box-shadow 0.22s;
}

.accordion-card.has-pending {
  border-color: rgba(58, 107, 255, 0.3);
  background: var(--surface-strong);
  box-shadow: 0 4px 16px rgba(58, 107, 255, 0.08);
}

.accordion-trigger {
  width: 100%;
  border: none;
  background: transparent;
  padding: 9px 10px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  cursor: pointer;
  color: var(--ink);
  transition: background 0.16s;
}

.accordion-trigger:hover {
  background: var(--hover);
}

.accordion-left {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.accordion-ic {
  width: 24px;
  height: 24px;
  border-radius: 8px;
  background: var(--accent-soft);
  color: var(--accent);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.accordion-ic.alert {
  background: var(--accent-grad);
  color: #fff;
}

.accordion-ic.muted {
  background: var(--rail);
  color: var(--ink-muted);
}

.accordion-label {
  font-size: 12.5px;
  font-weight: 650;
  color: var(--ink);
}

.accordion-right {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.pending-pill {
  font-size: 10.5px;
  font-weight: 700;
  padding: 2px 7px;
  border-radius: 999px;
  background: var(--danger);
  color: #fff;
}

.muted-count {
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-muted);
}

.accordion-arrow {
  color: var(--ink-muted);
  transition: transform 0.24s var(--ease-spring);
}

.accordion-arrow.rotated {
  transform: rotate(90deg);
}

.accordion-collapse {
  display: grid;
  grid-template-rows: 0fr;
  transition: grid-template-rows 0.26s var(--ease-out);
}

.accordion-collapse.expanded {
  grid-template-rows: 1fr;
}

.accordion-inner {
  overflow: hidden;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.accordion-collapse.expanded .accordion-inner {
  padding: 2px 8px 9px;
  border-top: 1px solid var(--border);
}

.subtle-empty {
  font-size: 11.5px;
  color: var(--ink-muted);
  text-align: center;
  padding: 8px 4px 2px;
}

.req-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 6px;
  border-radius: 10px;
  transition: background 0.16s;
}

.req-item:hover {
  background: var(--hover);
}

.req-item.is-pending {
  background: var(--rail);
  padding: 7px 8px;
}

/* ==================== 杂志目录式 A-Z 好友列表 ==================== */
.friends-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.friends-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 2px 4px 0;
}

.friends-title-group {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.section-label {
  font-size: 11.5px;
  font-weight: 700;
  color: var(--ink-muted);
  letter-spacing: 0.03em;
}

.friends-total {
  font-size: 11px;
  font-weight: 700;
  color: var(--ink-secondary);
  background: var(--rail);
  padding: 1px 6px;
  border-radius: 999px;
}

.filter-segment {
  display: inline-flex;
  padding: 2px;
  border-radius: 9px;
  background: var(--rail);
  border: 1px solid var(--border);
}

.seg-btn {
  border: none;
  background: transparent;
  color: var(--ink-muted);
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 7px;
  cursor: pointer;
  transition: background 0.18s, color 0.18s;
}

.seg-btn.active {
  background: var(--surface-strong);
  color: var(--accent);
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.08);
}

.alpha-directory {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-right: 16px;
}

.alpha-group {
  display: grid;
  grid-template-columns: 24px minmax(0, 1fr);
  gap: 6px;
  align-items: stretch;
  scroll-margin-top: 8px;
}

.alpha-gutter {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-top: 6px;
}

.alpha-stamp {
  position: sticky;
  top: 6px;
  width: 22px;
  height: 22px;
  border-radius: 7px;
  background: var(--rail);
  border: 1px solid var(--border);
  color: var(--ink-secondary);
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 800;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: background 0.25s, color 0.25s, border-color 0.25s, transform 0.25s;
}

.alpha-group.is-highlighted .alpha-stamp {
  background: var(--accent-grad);
  border-color: transparent;
  color: #fff;
  transform: scale(1.12);
}

.alpha-spine {
  flex: 1;
  width: 1px;
  margin-top: 5px;
  border-radius: 999px;
  background: linear-gradient(180deg, var(--border-strong) 0%, transparent 100%);
}

.alpha-rows {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.friend-item {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 7px 8px;
  border-radius: 12px;
  cursor: pointer;
  min-width: 0;
  transition: background 0.16s, transform 0.2s var(--ease-spring);
}

.friend-item:hover {
  background: var(--hover);
  transform: translateX(2px);
}

.friend-meta {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.friend-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.friend-status,
.friend-sub {
  font-size: 11px;
  color: var(--ink-muted);
  display: inline-flex;
  align-items: center;
  gap: 3px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.quick-chat-btn {
  width: 28px;
  height: 28px;
  border-radius: 9px;
  border: 1px solid transparent;
  background: transparent;
  color: var(--ink-muted);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  flex-shrink: 0;
  opacity: 0;
  transform: translateX(4px);
  transition: opacity 0.18s, transform 0.2s var(--ease-spring), background 0.16s, color 0.16s, border-color 0.16s;
}

.friend-item:hover .quick-chat-btn {
  opacity: 1;
  transform: translateX(0);
  background: var(--surface-strong);
  border-color: var(--border);
  color: var(--accent);
}

.quick-chat-btn:hover {
  background: var(--accent-soft);
}

/* ==================== 右侧悬浮字母快跳轨道 ==================== */
.alpha-rail {
  position: absolute;
  right: 4px;
  top: 50%;
  transform: translateY(-50%);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1px;
  padding: 4px 2px;
  border-radius: 999px;
  background: var(--surface);
  border: 1px solid var(--border);
  box-shadow: var(--shadow-sm);
  z-index: 5;
}

.alpha-rail-btn {
  width: 16px;
  height: 16px;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: var(--ink-muted);
  font-family: var(--font-mono);
  font-size: 9.5px;
  font-weight: 700;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  transition: background 0.16s, color 0.16s, transform 0.16s;
}

.alpha-rail-btn:hover,
.alpha-rail-btn.active {
  background: var(--accent);
  color: #fff;
  transform: scale(1.1);
}

/* ==================== 搜索结果与通用胶囊 ==================== */
.empty-hint {
  font-size: 12px;
  color: var(--ink-muted);
  text-align: center;
  padding: 14px 8px;
  border-radius: 12px;
  background: var(--rail);
}

.user-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 9px;
  border-radius: 13px;
  background: var(--surface);
  border: 1px solid var(--border);
  transition: background 0.18s;
}

.user-card:hover {
  background: var(--hover);
}

.user-main {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
  flex: 1;
  cursor: pointer;
}

.user-meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.user-meta strong {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.user-meta small {
  font-size: 11px;
  color: var(--ink-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.user-actions,
.req-btns {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex-shrink: 0;
}

.rel-pill {
  font-size: 10.5px;
  padding: 2px 7px;
  border-radius: 999px;
  background: var(--rail);
  color: var(--ink-muted);
  flex-shrink: 0;
}

.rel-pill.accepted {
  background: rgba(19, 138, 71, 0.14);
  color: var(--ok);
}

.rel-pill.danger,
.rel-pill.rejected {
  background: rgba(224, 49, 59, 0.12);
  color: var(--danger);
}

@media (max-width: 768px) {
  .contacts-rail {
    width: 100%;
    flex: 1;
    border-radius: 18px;
  }

  .quick-chat-btn {
    opacity: 1;
    transform: none;
  }
}
</style>
