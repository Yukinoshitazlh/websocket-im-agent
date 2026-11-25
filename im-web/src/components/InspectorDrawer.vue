<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  Bell,
  Clock,
  Crown,
  LogOut,
  Pencil,
  RefreshCw,
  Search,
  Sparkles,
  UserMinus,
  UserPlus,
  Users
} from 'lucide-vue-next';
import { useChatStore } from '../stores/chat';
import type { UserProfile } from '../types/im';
import { formatIsoDateTime, formatTimeShort } from '../utils/format';
import UserAvatar from './UserAvatar.vue';
import { vGlow } from '../directives/glow';

const props = defineProps<{
  requestedTab?: 'metrics' | 'group' | 'search';
}>();

const emit = defineEmits<{
  (e: 'jump-to-seq', seq: number): void;
}>();

const store = useChatStore();

const isGroup = computed(() => store.activeConversation?.type === 2);
const activeTab = ref<'metrics' | 'group' | 'search'>('metrics');
const keyword = ref('');

// 群管理状态
const editingTitle = ref('');
const inviteKeyword = ref('');
const inviteCandidates = ref<UserProfile[]>([]);
const savingTitle = ref(false);

watch(
  () => props.requestedTab,
  (next) => {
    if (next) {
      activeTab.value = next === 'group' && !isGroup.value ? 'metrics' : next;
    }
  }
);

watch(
  () => store.activeConversation,
  (conv) => {
    if (!conv || conv.type !== 2) {
      if (activeTab.value === 'group') activeTab.value = 'metrics';
      editingTitle.value = '';
    } else {
      editingTitle.value = conv.title;
    }
    inviteKeyword.value = '';
    inviteCandidates.value = [];
  },
  { immediate: true }
);

const isOwner = computed(() => {
  if (!store.currentUser || !store.activeMembers.length) return false;
  const self = store.activeMembers.find((m) => String(m.userId) === String(store.currentUser?.id));
  return self?.role === 3;
});

const tabIndex = computed(() => {
  if (!isGroup.value) return activeTab.value === 'search' ? 1 : 0;
  if (activeTab.value === 'group') return 1;
  if (activeTab.value === 'search') return 2;
  return 0;
});

async function runSearch() {
  await store.searchConversationHistory(keyword.value);
}

async function handleRename() {
  if (!store.activeConvId || !editingTitle.value.trim()) return;
  savingTitle.value = true;
  try {
    await store.renameGroup(store.activeConvId, editingTitle.value.trim());
  } catch (e: any) {
    store.pushToast(e.message || '修改群名失败', 'error');
  } finally {
    savingTitle.value = false;
  }
}

async function searchInviteUsers() {
  const kw = inviteKeyword.value.trim();
  if (!kw) {
    inviteCandidates.value = [];
    return;
  }
  try {
    const all = await store.searchUsers(kw);
    const existingIds = new Set(store.activeMembers.map((m) => String(m.userId)));
    inviteCandidates.value = all.filter((u) => !existingIds.has(String(u.id)));
  } catch {
    inviteCandidates.value = [];
  }
}

async function handleInvite(uid: string) {
  if (!store.activeConvId) return;
  try {
    await store.inviteGroupMembers(store.activeConvId, [uid]);
    inviteCandidates.value = inviteCandidates.value.filter((u) => u.id !== uid);
  } catch (e: any) {
    store.pushToast(e.message || '邀请失败', 'error');
  }
}

async function handleKick(uid: string, nick: string) {
  if (!store.activeConvId) return;
  if (!window.confirm(`确认将「${nick}」移出群聊？`)) return;
  try {
    await store.kickGroupMember(store.activeConvId, uid);
  } catch (e: any) {
    store.pushToast(e.message || '移出失败', 'error');
  }
}

async function handleLeave() {
  if (!store.activeConvId) return;
  if (!window.confirm('确认退出当前群聊？')) return;
  try {
    await store.leaveGroup(store.activeConvId);
  } catch (e: any) {
    store.pushToast(e.message || '退群失败', 'error');
  }
}
</script>

<template>
  <aside v-glow class="inspector glass">
    <div class="inspector-tabs" :class="{ triple: isGroup }">
      <span
        class="tab-indicator"
        :style="{
          width: isGroup ? 'calc(33.333% - 3px)' : 'calc(50% - 4px)',
          transform: `translateX(${tabIndex * 100}%)`
        }"
      />
      <button
        type="button"
        class="ins-tab"
        :class="{ active: activeTab === 'metrics' }"
        @click="activeTab = 'metrics'"
      >
        <Sparkles :size="13" />
        <span>AI 观测</span>
      </button>
      <button
        v-if="isGroup"
        type="button"
        class="ins-tab"
        :class="{ active: activeTab === 'group' }"
        @click="activeTab = 'group'"
      >
        <Users :size="13" />
        <span>群管理</span>
      </button>
      <button
        type="button"
        class="ins-tab"
        :class="{ active: activeTab === 'search' }"
        @click="activeTab = 'search'"
      >
        <Search :size="13" />
        <span>搜索</span>
      </button>
    </div>

    <Transition name="tab-swap" mode="out-in">
      <!-- Tab 1: AI 观测与日程提醒 -->
      <div v-if="activeTab === 'metrics'" key="metrics" class="inspector-body">
        <div class="section-head">
          <span>Agent 执行指标</span>
          <button type="button" class="btn-icon" title="刷新观测" @click="store.loadAgentMetrics">
            <RefreshCw :size="13" />
          </button>
        </div>

        <div v-if="store.metrics" class="kpi-grid">
          <div class="kpi-card">
            <div class="kpi-label">总任务 / 完成</div>
            <div class="kpi-val tabular">
              {{ store.metrics.totalTasks }} / {{ store.metrics.doneCount }}
            </div>
          </div>
          <div class="kpi-card">
            <div class="kpi-label">平均 TTFT</div>
            <div class="kpi-val tabular">
              {{ store.metrics.avgTtftMs != null ? `${Math.round(store.metrics.avgTtftMs)}ms` : '-' }}
            </div>
          </div>
          <div class="kpi-card">
            <div class="kpi-label">平均总耗时</div>
            <div class="kpi-val tabular">
              {{ store.metrics.avgTotalMs != null ? `${Math.round(store.metrics.avgTotalMs)}ms` : '-' }}
            </div>
          </div>
          <div class="kpi-card">
            <div class="kpi-label">累计 Tokens</div>
            <div class="kpi-val tabular">
              {{ (store.metrics.totalPromptTokens || 0) + (store.metrics.totalCompletionTokens || 0) }}
            </div>
          </div>
        </div>
        <div v-else class="empty-tip">选择会话后自动加载 Agent 观测数据</div>

        <div class="section-head" style="margin-top: 16px;">
          <span>最近 Agent 任务</span>
        </div>
        <div v-if="store.metrics?.recentTasks?.length" class="list-stack">
          <div v-for="t in store.metrics.recentTasks" :key="t.id" class="info-card">
            <div class="info-row">
              <span class="mono-id">#{{ t.id.slice(-6) }}</span>
              <span class="pill" :class="`pill-${t.status}`">{{ t.status }}</span>
            </div>
            <div class="info-sub tabular">
              <span>步数 {{ t.currentStep }}</span>
              <span v-if="t.ttftMs != null">TTFT {{ t.ttftMs }}ms</span>
              <span v-if="t.totalMs != null">总计 {{ t.totalMs }}ms</span>
            </div>
          </div>
        </div>
        <div v-else class="empty-tip">暂无 Agent 执行记录</div>

        <div class="section-head" style="margin-top: 16px;">
          <span>群日程定时提醒（ZSet 延迟队列）</span>
        </div>
        <div v-if="store.metrics?.reminders?.length" class="list-stack">
          <div v-for="r in store.metrics.reminders" :key="r.id" class="info-card">
            <div class="info-row">
              <span class="reminder-title">
                <Bell :size="12" />
                <span>{{ r.title }}</span>
              </span>
              <span class="pill" :class="`pill-${r.status}`">{{ r.status }}</span>
            </div>
            <div class="info-text">{{ r.content }}</div>
            <div class="info-sub tabular">
              <Clock :size="11" />
              <span>{{ formatIsoDateTime(r.remindAt) }}</span>
            </div>
          </div>
        </div>
        <div v-else class="empty-tip">
          试试发送「@AI 15秒后提醒大家代码评审」，审批通过后将进入 Redis ZSet 延迟队列自动推送！
        </div>
      </div>

      <!-- Tab 2: 群成员管理闭环 -->
      <div v-else-if="activeTab === 'group' && isGroup" key="group" class="inspector-body">
        <div class="section-head">
          <span>群名称</span>
        </div>
        <form class="inline-form" @submit.prevent="handleRename">
          <input v-model="editingTitle" class="input" placeholder="输入新的群名称" />
          <button type="submit" class="btn btn-primary btn-xs" :disabled="savingTitle">
            <Pencil :size="12" />
            <span>改名</span>
          </button>
        </form>

        <div class="section-head" style="margin-top: 16px;">
          <span>邀请新成员入群</span>
        </div>
        <div class="invite-box">
          <input
            v-model="inviteKeyword"
            class="input"
            placeholder="搜索用户名邀请加入..."
            @input="searchInviteUsers"
          />
          <div v-if="inviteCandidates.length" class="invite-list">
            <div v-for="u in inviteCandidates" :key="u.id" class="invite-row">
              <UserAvatar :name="u.nickname" :seed="u.username" :src="u.avatar" size="sm" />
              <span class="invite-meta">
                <strong>{{ u.nickname }}</strong>
                <small>@{{ u.username }}</small>
              </span>
              <button type="button" class="btn btn-primary btn-xs" @click="handleInvite(u.id)">
                <UserPlus :size="12" />
                <span>邀请</span>
              </button>
            </div>
          </div>
        </div>

        <div class="section-head" style="margin-top: 16px;">
          <span>群成员 ({{ store.activeMembers.length }})</span>
          <button
            type="button"
            class="btn-icon"
            title="刷新成员在线状态"
            @click="store.activeConvId && store.loadActiveMembers(store.activeConvId)"
          >
            <RefreshCw :size="13" />
          </button>
        </div>

        <div class="list-stack">
          <div
            v-for="m in store.activeMembers"
            :key="m.userId"
            class="info-card member-card clickable-card"
            title="点击查看资料名片"
            @click="store.openProfile(m.userId)"
          >
            <UserAvatar
              :name="m.nickname"
              :seed="m.username"
              :src="m.avatar"
              :online="m.online"
              size="sm"
              clickable
            />
            <div class="member-meta">
              <div class="member-name-row">
                <strong>{{ m.nickname }}</strong>
                <span v-if="m.role === 3" class="owner-badge">
                  <Crown :size="10" /> 群主
                </span>
              </div>
              <small v-if="m.statusText">{{ m.statusEmoji }} {{ m.statusText }}</small>
              <small v-else-if="m.signature">{{ m.signature }}</small>
              <small v-else>@{{ m.username }} | {{ m.online ? '在线' : '离线' }}</small>
            </div>
            <button
              v-if="isOwner && String(m.userId) !== String(store.currentUser?.id)"
              type="button"
              class="btn-icon danger-icon"
              title="移出群聊"
              @click.stop="handleKick(m.userId, m.nickname)"
            >
              <UserMinus :size="13" />
            </button>
          </div>
        </div>

        <div class="leave-wrap">
          <button type="button" class="btn btn-danger leave-btn" @click="handleLeave">
            <LogOut :size="14" />
            <span>退出群聊</span>
          </button>
        </div>
      </div>

      <!-- Tab 3: 会话历史全文搜索 -->
      <div v-else key="search" class="inspector-body">
        <form class="inline-form" @submit.prevent="runSearch">
          <input v-model="keyword" class="input" placeholder="搜索当前会话历史消息..." />
          <button type="submit" class="btn btn-primary btn-xs" :disabled="store.searchLoading">
            {{ store.searchLoading ? '...' : '搜索' }}
          </button>
        </form>

        <div v-if="store.searchResults.length" class="list-stack" style="margin-top: 12px;">
          <button
            v-for="m in store.searchResults"
            :key="m.id || m.seq"
            type="button"
            class="info-card search-hit"
            title="点击定位到该消息"
            @click="emit('jump-to-seq', m.seq)"
          >
            <div class="info-row">
              <strong>{{ m.senderNickname }}</strong>
              <span class="mono-id tabular">#{{ m.seq }} {{ formatTimeShort(m.createdAt) }}</span>
            </div>
            <div class="info-text">{{ m.content }}</div>
          </button>
        </div>
        <div v-else class="empty-tip">
          {{ keyword.trim() ? '未搜索到匹配消息' : '输入关键字检索会话内历史记录，点击结果可直接定位' }}
        </div>
      </div>
    </Transition>
  </aside>
</template>

<style scoped>
.inspector {
  width: 316px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  height: 100%;
  border-radius: var(--radius-panel);
  overflow: hidden;
  animation: rise-in 0.7s var(--ease-out) 0.16s both;
}

.inspector > * {
  position: relative;
  z-index: 1;
}

.inspector-tabs {
  position: relative;
  display: grid;
  grid-template-columns: 1fr 1fr;
  margin: 10px 10px 0;
  padding: 4px;
  border-radius: 14px;
  background: var(--rail);
  border: 1px solid var(--border);
}

.inspector-tabs.triple {
  grid-template-columns: 1fr 1fr 1fr;
}

.tab-indicator {
  position: absolute;
  top: 4px;
  bottom: 4px;
  left: 4px;
  border-radius: 10px;
  background: var(--surface-strong);
  border: 1px solid var(--glass-edge);
  box-shadow: 0 4px 14px rgba(34, 52, 110, 0.12), inset 0 1px 0 rgba(255, 255, 255, 0.6);
  transition: transform 0.5s var(--ease-spring), width 0.35s var(--ease-out);
}

.ins-tab {
  position: relative;
  z-index: 1;
  border: none;
  background: transparent;
  color: var(--ink-secondary);
  padding: 8px 4px;
  font-size: 12px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  cursor: pointer;
  transition: color 0.3s;
}

.ins-tab.active {
  color: var(--accent);
}

.inspector-body {
  flex: 1;
  overflow-y: auto;
  padding: 14px;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  font-weight: 700;
  color: var(--ink-secondary);
  margin-bottom: 8px;
}

.kpi-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.kpi-card {
  background: var(--surface);
  border: 1px solid var(--glass-edge);
  border-radius: 14px;
  padding: 10px 11px;
  box-shadow: var(--shadow-sm);
  transition: transform 0.35s var(--ease-spring), box-shadow 0.3s;
  animation: pop-in 0.5s var(--ease-spring) both;
}

.kpi-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 20px rgba(34, 52, 110, 0.12);
}

.kpi-label {
  font-size: 11px;
  color: var(--ink-muted);
}

.kpi-val {
  font-size: 16px;
  font-weight: 800;
  margin-top: 2px;
}

.list-stack {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.info-card {
  background: var(--surface);
  border: 1px solid var(--glass-edge);
  border-radius: 13px;
  padding: 9px 11px;
  display: flex;
  flex-direction: column;
  gap: 5px;
  box-shadow: var(--shadow-sm);
  animation: msg-in-left 0.45s var(--ease-spring) both;
  transition: transform 0.35s var(--ease-spring);
}

.info-card:hover {
  transform: translateX(3px);
}

.search-hit {
  width: 100%;
  text-align: left;
  color: var(--ink);
  cursor: pointer;
}

.member-card {
  flex-direction: row;
  align-items: center;
  gap: 9px;
}

.clickable-card {
  cursor: pointer;
}

.member-meta {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  font-size: 12.5px;
}

.member-meta small {
  font-size: 11px;
  color: var(--ink-muted);
}

.member-name-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.owner-badge {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  font-size: 10px;
  font-weight: 700;
  padding: 1px 6px;
  border-radius: 999px;
  background: var(--warn-soft);
  color: var(--warn);
}

.danger-icon:hover {
  color: var(--danger);
}

.inline-form {
  display: flex;
  gap: 6px;
}

.invite-box {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.invite-list {
  display: flex;
  flex-direction: column;
  gap: 5px;
  max-height: 160px;
  overflow-y: auto;
}

.invite-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 11px;
  background: var(--surface);
  border: 1px solid var(--border);
}

.invite-meta {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  font-size: 12px;
}

.invite-meta small {
  font-size: 10.5px;
  color: var(--ink-muted);
}

.leave-wrap {
  margin-top: 20px;
  padding-top: 14px;
  border-top: 1px solid var(--border);
}

.leave-btn {
  width: 100%;
}

.info-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  font-size: 12px;
}

.reminder-title {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-weight: 700;
  color: var(--ink);
}

.mono-id {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--ink-muted);
}

.info-text {
  font-size: 12.5px;
  color: var(--ink-secondary);
  word-break: break-word;
}

.info-sub {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  color: var(--ink-muted);
}

.empty-tip {
  font-size: 12px;
  color: var(--ink-muted);
  padding: 10px 6px;
  line-height: 1.6;
}
</style>
