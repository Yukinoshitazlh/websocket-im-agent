<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import {
  Ban,
  Check,
  ChevronDown,
  ChevronUp,
  MessageCircle,
  Search,
  UserCheck,
  UserPlus,
  Users,
  X
} from 'lucide-vue-next';
import { useChatStore } from '../stores/chat';
import type { UserProfile } from '../types/im';
import UserAvatar from './UserAvatar.vue';
import { vGlow } from '../directives/glow';

const store = useChatStore();

const searchKeyword = ref('');
const searchHits = ref<UserProfile[]>([]);
const searching = ref(false);
const remarkMap = ref<Record<string, string>>({});
const showBlocked = ref(false);
const busyId = ref<string | null>(null);
let searchTimer: number | null = null;

const incomingPending = computed(() =>
  store.friendRequests.filter((r) => r.direction === 'INCOMING' && r.status === 'PENDING')
);

const handledOrOutgoing = computed(() =>
  store.friendRequests.filter((r) => !(r.direction === 'INCOMING' && r.status === 'PENDING')).slice(0, 8)
);

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
    <div class="contacts-head">
      <div class="head-title">
        <Users :size="16" />
        <span>通讯录</span>
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

    <div class="contacts-scroll">
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

      <section class="contact-section">
        <div class="section-cap">
          <span>新的朋友</span>
          <span v-if="incomingPending.length > 0" class="cap-badge">{{ incomingPending.length }}</span>
        </div>
        <div v-if="incomingPending.length === 0 && handledOrOutgoing.length === 0" class="empty-hint">
          暂无新的好友申请
        </div>
        <div v-for="req in incomingPending" :key="req.id" class="req-card pending">
          <div class="user-main" @click="store.openProfile(req.peerUserId)">
            <UserAvatar :name="req.peerNickname" :seed="req.peerUsername" :src="req.peerAvatar" size="sm" clickable />
            <div class="user-meta">
              <strong>{{ req.peerNickname }}</strong>
              <small>{{ req.remark || '请求添加你为好友' }}</small>
            </div>
          </div>
          <div class="req-btns">
            <button type="button" class="btn btn-primary btn-xs" :disabled="busyId === req.id" @click="handleAccept(req.id)">
              同意
            </button>
            <button type="button" class="btn btn-ghost btn-xs" :disabled="busyId === req.id" @click="handleReject(req.id)">
              拒绝
            </button>
          </div>
        </div>
        <div v-for="req in handledOrOutgoing" :key="req.id" class="req-card">
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
            {{ req.status === 'PENDING' ? '等待验证' : req.status === 'ACCEPTED' ? '已通过' : '已拒绝' }}
          </span>
        </div>
      </section>

      <section class="contact-section">
        <div class="section-cap">
          <span>我的好友</span>
          <span class="cap-count">{{ store.friends.length }}</span>
        </div>
        <div v-if="store.friends.length === 0" class="empty-hint">
          还没有好友，在上方搜索账号或邮箱添加吧
        </div>
        <div v-for="f in store.friends" :key="f.id" class="user-card friend-row">
          <div class="user-main" @click="store.openProfile(f.id)">
            <UserAvatar
              :name="f.nickname"
              :seed="f.username"
              :src="f.avatar"
              :online="Boolean(f.online)"
              size="sm"
              clickable
            />
            <div class="user-meta">
              <strong>{{ f.nickname }}</strong>
              <small v-if="f.statusText">{{ f.statusEmoji }} {{ f.statusText }}</small>
              <small v-else>@{{ f.username }}</small>
            </div>
          </div>
          <button type="button" class="btn-icon chat-go-btn" title="发消息" @click="handleStartChat(f.id)">
            <MessageCircle :size="15" />
          </button>
        </div>
      </section>

      <section class="contact-section">
        <button type="button" class="block-head" @click="showBlocked = !showBlocked">
          <span class="block-left">
            <Ban :size="13" />
            <span>黑名单 ({{ store.blockedUsers.length }})</span>
          </span>
          <ChevronUp v-if="showBlocked" :size="14" />
          <ChevronDown v-else :size="14" />
        </button>
        <div v-if="showBlocked" class="blocked-list">
          <div v-if="store.blockedUsers.length === 0" class="empty-hint">黑名单为空</div>
          <div v-for="b in store.blockedUsers" :key="b.id" class="user-card">
            <div class="user-main" @click="store.openProfile(b.id)">
              <UserAvatar :name="b.nickname" :seed="b.username" :src="b.avatar" size="sm" />
              <div class="user-meta">
                <strong>{{ b.nickname }}</strong>
                <small>@{{ b.username }}</small>
              </div>
            </div>
            <button type="button" class="btn btn-ghost btn-xs" :disabled="busyId === b.id" @click="handleUnblock(b.id)">
              <UserCheck :size="12" />
              <span>解除</span>
            </button>
          </div>
        </div>
      </section>
    </div>
  </aside>
</template>

<style scoped>
.contacts-rail { width: 296px; flex-shrink: 0; height: 100%; border-radius: var(--radius-panel); display: flex; flex-direction: column; overflow: hidden; animation: rise-in 0.65s var(--ease-out) 0.04s both; }
.contacts-head { padding: 14px 12px 10px; border-bottom: 1px solid var(--border); display: flex; flex-direction: column; gap: 10px; }
.head-title { display: inline-flex; align-items: center; gap: 7px; font-size: 14px; font-weight: 700; color: var(--ink); padding: 0 2px; }
.search-wrap { position: relative; display: flex; align-items: center; }
.search-icon { position: absolute; left: 10px; color: var(--ink-muted); pointer-events: none; }
.search-input { width: 100%; height: 34px; padding: 0 28px 0 30px; border-radius: 11px; border: 1px solid var(--border); background: var(--surface); color: var(--ink); font-size: 12.5px; outline: none; transition: border-color 0.2s, background 0.2s; }
.search-input:focus { border-color: var(--accent); background: var(--surface-strong); }
.search-clear { position: absolute; right: 6px; border: none; background: transparent; color: var(--ink-muted); cursor: pointer; padding: 3px; display: inline-flex; }
.contacts-scroll { flex: 1; overflow-y: auto; padding: 10px 10px 14px; display: flex; flex-direction: column; gap: 14px; }
.contact-section { display: flex; flex-direction: column; gap: 6px; }
.section-cap { display: flex; align-items: center; justify-content: space-between; font-size: 11.5px; font-weight: 700; color: var(--ink-muted); padding: 0 4px; }
.cap-badge { min-width: 18px; height: 18px; padding: 0 5px; border-radius: 999px; background: var(--danger); color: #fff; font-size: 11px; display: inline-flex; align-items: center; justify-content: center; }
.cap-count { font-weight: 600; }
.empty-hint { font-size: 12px; color: var(--ink-muted); text-align: center; padding: 12px 8px; border-radius: 12px; background: var(--rail); }
.user-card, .req-card { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 8px 9px; border-radius: 13px; background: var(--surface); border: 1px solid var(--border); transition: background 0.18s, border-color 0.18s; }
.req-card.pending { border-color: var(--accent-soft); background: var(--surface-strong); }
.user-card:hover { background: var(--hover); }
.user-main { display: flex; align-items: center; gap: 9px; min-width: 0; flex: 1; cursor: pointer; }
.user-meta { display: flex; flex-direction: column; min-width: 0; }
.user-meta strong { font-size: 13px; font-weight: 650; color: var(--ink); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.user-meta small { font-size: 11.5px; color: var(--ink-muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.user-actions, .req-btns { display: inline-flex; align-items: center; gap: 5px; flex-shrink: 0; }
.rel-pill { font-size: 11px; padding: 2px 8px; border-radius: 999px; background: var(--rail); color: var(--ink-muted); flex-shrink: 0; }
.rel-pill.accepted { background: rgba(19, 138, 71, 0.14); color: var(--ok); }
.rel-pill.danger, .rel-pill.rejected { background: rgba(224, 49, 59, 0.12); color: var(--danger); }
.chat-go-btn { width: 30px; height: 30px; flex-shrink: 0; }
.block-head { width: 100%; border: 1px solid var(--border); background: var(--rail); color: var(--ink-secondary); border-radius: 11px; padding: 8px 10px; font-size: 12px; font-weight: 600; display: flex; align-items: center; justify-content: space-between; cursor: pointer; }
.block-left { display: inline-flex; align-items: center; gap: 6px; }
.blocked-list { display: flex; flex-direction: column; gap: 6px; }
@media (max-width: 768px) { .contacts-rail { width: 100%; flex: 1; border-radius: 18px; } }
</style>
