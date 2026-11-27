<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import {
  ArrowLeft,
  Bell,
  Check,
  ChevronDown,
  ChevronRight,
  ChevronUp,
  Clock,
  Crown,
  Minus,
  Pencil,
  Plus,
  RefreshCw,
  Search,
  Sparkles,
  UserCheck,
  Users,
  X
} from 'lucide-vue-next';
import { useChatStore } from '../stores/chat';
import type { ConversationMember, UserProfile } from '../types/im';
import { formatIsoDateTime, formatTimeShort } from '../utils/format';
import UserAvatar from './UserAvatar.vue';
import { vGlow } from '../directives/glow';

type DrawerTab = 'metrics' | 'group' | 'search';
type GroupSubView = 'main' | 'invite' | 'announcement' | 'joinRequests' | 'search';

const props = defineProps<{
  requestedTab?: DrawerTab;
}>();

const emit = defineEmits<{
  (e: 'jump-to-seq', seq: number): void;
  (e: 'close'): void;
}>();

const store = useChatStore();

const isGroup = computed(() => store.activeConversation?.type === 2);

// 演练模式保留 AI 观测页；日常模式群聊直接以微信风格群设置为主界面（内嵌查找聊天内容）
const availableTabs = computed<DrawerTab[]>(() => {
  const tabs: DrawerTab[] = [];
  if (store.demoMode) tabs.push('metrics');
  if (isGroup.value) {
    tabs.push('group');
  } else {
    tabs.push('search');
  }
  return tabs;
});

const activeTab = ref<DrawerTab>('group');
const groupSubView = ref<GroupSubView>('main');

// 聊天记录搜索
const keyword = ref('');

// 群成员搜索、折叠与移出模式
const memberSearch = ref('');
const membersExpanded = ref(false);
const removeMode = ref(false);

// 行内编辑状态（群聊名称 / 备注 / 我在本群的昵称）
const editingField = ref<'title' | 'remark' | 'groupNickname' | null>(null);
const fieldDraft = ref('');
const savingField = ref(false);
const fieldInputRef = ref<HTMLInputElement | null>(null);

// 群公告编辑
const announcementDraft = ref('');
const savingAnnouncement = ref(false);

// 添加群成员多选面板
const inviteKeyword = ref('');
const inviteFriends = ref<UserProfile[]>([]);
const selectedInviteIds = ref<string[]>([]);
const loadingFriends = ref(false);
const submittingInvite = ref(false);

// 开关防连点
const togglingKey = ref<string | null>(null);

const selfMember = computed<ConversationMember | undefined>(() => {
  if (!store.currentUser || !store.activeMembers.length) return undefined;
  return store.activeMembers.find((m) => String(m.userId) === String(store.currentUser?.id));
});

const myRole = computed(() => selfMember.value?.role ?? 1);
const isAdminOrOwner = computed(() => myRole.value >= 2);

const removableMembers = computed(() => {
  if (!isAdminOrOwner.value || !store.currentUser) return [];
  return store.activeMembers.filter(
    (m) => String(m.userId) !== String(store.currentUser?.id) && (m.role ?? 1) < myRole.value
  );
});

const filteredMembers = computed(() => {
  const q = memberSearch.value.trim().toLowerCase();
  if (!q) return store.activeMembers;
  return store.activeMembers.filter((m) => {
    const gNick = (m.groupNickname || '').toLowerCase();
    const nick = (m.nickname || '').toLowerCase();
    const uname = (m.username || '').toLowerCase();
    return gNick.includes(q) || nick.includes(q) || uname.includes(q);
  });
});

// 默认最多展示 14 个成员头像（给 + 和 - 留出第 4 行位置，满 4 列整齐）
const MEMBER_COLLAPSE_LIMIT = 14;
const displayedMembers = computed(() => {
  if (memberSearch.value.trim() || membersExpanded.value) {
    return filteredMembers.value;
  }
  return filteredMembers.value.slice(0, MEMBER_COLLAPSE_LIMIT);
});

const hasHiddenMembers = computed(
  () => !memberSearch.value.trim() && filteredMembers.value.length > MEMBER_COLLAPSE_LIMIT
);

const filteredInviteCandidates = computed(() => {
  const existingIds = new Set(store.activeMembers.map((m) => String(m.userId)));
  const q = inviteKeyword.value.trim().toLowerCase();
  return inviteFriends.value.filter((u) => {
    if (existingIds.has(String(u.id))) return false;
    if (!q) return true;
    return (
      u.nickname.toLowerCase().includes(q) ||
      u.username.toLowerCase().includes(q) ||
      (u.email || '').toLowerCase().includes(q)
    );
  });
});

const pendingJoinCount = computed(() => {
  if (!isAdminOrOwner.value) return 0;
  return Math.max(
    store.activeJoinRequests.length,
    store.activeConversation?.pendingJoinRequestCount || 0
  );
});

const showMemberNicknameOn = computed(
  () => store.activeConversation?.showMemberNickname !== false
);

const tabIndex = computed(() => Math.max(0, availableTabs.value.indexOf(activeTab.value)));

function displayMemberName(m: ConversationMember): string {
  return (m.groupNickname && m.groupNickname.trim()) || m.nickname;
}

function canRemoveMember(m: ConversationMember): boolean {
  if (!isAdminOrOwner.value || !store.currentUser) return false;
  return String(m.userId) !== String(store.currentUser.id) && (m.role ?? 1) < myRole.value;
}

watch(
  () => props.requestedTab,
  (next) => {
    if (!next) return;
    if (next === 'search' && isGroup.value) {
      activeTab.value = 'group';
      groupSubView.value = 'search';
      return;
    }
    if (availableTabs.value.includes(next)) {
      activeTab.value = next;
      if (next === 'group') groupSubView.value = 'main';
    } else {
      activeTab.value = availableTabs.value[0];
    }
  },
  { immediate: true }
);

watch(
  availableTabs,
  (tabs) => {
    if (!tabs.includes(activeTab.value)) {
      activeTab.value = tabs[0];
    }
  },
  { immediate: true }
);

watch(
  () => [store.activeConvId, isAdminOrOwner.value] as const,
  ([convId, admin]) => {
    groupSubView.value = 'main';
    memberSearch.value = '';
    membersExpanded.value = false;
    removeMode.value = false;
    editingField.value = null;
    keyword.value = '';
    if (convId && isGroup.value && admin) {
      store.loadActiveJoinRequests(convId);
    }
  },
  { immediate: true }
);

async function runSearch() {
  await store.searchConversationHistory(keyword.value);
}

async function startEditField(field: 'title' | 'remark' | 'groupNickname') {
  const conv = store.activeConversation;
  if (!conv) return;
  editingField.value = field;
  if (field === 'title') {
    fieldDraft.value = conv.title || '';
  } else if (field === 'remark') {
    fieldDraft.value = conv.groupRemark || '';
  } else {
    fieldDraft.value = conv.myGroupNickname || '';
  }
  await nextTick();
  fieldInputRef.value?.focus();
  fieldInputRef.value?.select();
}

function cancelEditField() {
  editingField.value = null;
  fieldDraft.value = '';
}

async function commitEditField() {
  const conv = store.activeConversation;
  const field = editingField.value;
  if (!conv || !field || savingField.value) return;

  const val = fieldDraft.value.trim();
  if (field === 'title') {
    if (!val || val === conv.title) {
      cancelEditField();
      return;
    }
  } else if (field === 'remark') {
    if (val === (conv.groupRemark || '')) {
      cancelEditField();
      return;
    }
  } else if (field === 'groupNickname') {
    if (val === (conv.myGroupNickname || '')) {
      cancelEditField();
      return;
    }
  }

  savingField.value = true;
  try {
    if (field === 'title') {
      await store.renameGroup(conv.id, val);
    } else if (field === 'remark') {
      await store.updateConvPreferences(conv.id, { groupRemark: val });
    } else {
      await store.updateConvPreferences(conv.id, { groupNickname: val });
    }
    editingField.value = null;
  } catch (e: any) {
    store.pushToast(e.message || '保存失败', 'error');
  } finally {
    savingField.value = false;
  }
}

function openAnnouncementView() {
  announcementDraft.value = store.activeConversation?.announcement || '';
  groupSubView.value = 'announcement';
}

async function handleSaveAnnouncement(clear = false) {
  if (!store.activeConvId || savingAnnouncement.value) return;
  const content = clear ? '' : announcementDraft.value.trim();
  if (!clear && !content) {
    store.pushToast('请输入群公告内容，或点击清空公告', 'error');
    return;
  }
  if (clear && !window.confirm('确认清空当前群公告？')) return;
  savingAnnouncement.value = true;
  try {
    await store.updateGroupAnnouncement(store.activeConvId, content);
    announcementDraft.value = content;
    groupSubView.value = 'main';
  } catch (e: any) {
    store.pushToast(e.message || '发布群公告失败', 'error');
  } finally {
    savingAnnouncement.value = false;
  }
}

async function openInviteView() {
  removeMode.value = false;
  inviteKeyword.value = '';
  selectedInviteIds.value = [];
  groupSubView.value = 'invite';
  loadingFriends.value = true;
  try {
    inviteFriends.value = await store.loadFriends('');
  } catch {
    inviteFriends.value = [];
  } finally {
    loadingFriends.value = false;
  }
}

function toggleInviteSelect(uid: string) {
  if (selectedInviteIds.value.includes(uid)) {
    selectedInviteIds.value = selectedInviteIds.value.filter((id) => id !== uid);
  } else {
    selectedInviteIds.value = [...selectedInviteIds.value, uid];
  }
}

async function submitInvites() {
  if (!store.activeConvId || selectedInviteIds.value.length === 0 || submittingInvite.value) return;
  submittingInvite.value = true;
  try {
    await store.inviteGroupMembers(store.activeConvId, selectedInviteIds.value);
    selectedInviteIds.value = [];
    groupSubView.value = 'main';
  } catch (e: any) {
    store.pushToast(e.message || '邀请成员失败', 'error');
  } finally {
    submittingInvite.value = false;
  }
}

function handleMemberTileClick(m: ConversationMember) {
  if (removeMode.value) {
    if (canRemoveMember(m)) {
      handleKick(m.userId, displayMemberName(m));
    }
    return;
  }
  store.openProfile(m.userId);
}

async function handleKick(uid: string, nick: string) {
  if (!store.activeConvId) return;
  if (!window.confirm(`确认将「${nick}」移出群聊？`)) return;
  try {
    await store.kickGroupMember(store.activeConvId, uid);
    if (removableMembers.value.length <= 1) {
      removeMode.value = false;
    }
  } catch (e: any) {
    store.pushToast(e.message || '移出失败', 'error');
  }
}

async function openJoinRequestsView() {
  if (!store.activeConvId) return;
  groupSubView.value = 'joinRequests';
  await store.loadActiveJoinRequests(store.activeConvId);
}

async function handleReviewJoin(requestId: string, approve: boolean) {
  if (!store.activeConvId) return;
  try {
    if (approve) {
      await store.approveJoinRequest(store.activeConvId, requestId);
    } else {
      await store.rejectJoinRequest(store.activeConvId, requestId);
    }
  } catch (e: any) {
    store.pushToast(e.message || '处理入群申请失败', 'error');
  }
}

async function toggleJoinApproval() {
  const conv = store.activeConversation;
  if (!conv || togglingKey.value) return;
  if (!isAdminOrOwner.value) {
    store.pushToast('仅群主或群管理员可修改此设置', 'info');
    return;
  }
  togglingKey.value = 'joinApproval';
  try {
    await store.updateGroupSettings(conv.id, {
      joinApprovalRequired: !conv.joinApprovalRequired
    });
  } catch (e: any) {
    store.pushToast(e.message || '更新群设置失败', 'error');
  } finally {
    togglingKey.value = null;
  }
}

async function togglePref(key: 'muted' | 'pinned' | 'showMemberNickname') {
  const conv = store.activeConversation;
  if (!conv || togglingKey.value) return;
  togglingKey.value = key;
  try {
    if (key === 'muted') {
      await store.updateConvPreferences(conv.id, { muted: !conv.muted });
    } else if (key === 'pinned') {
      await store.updateConvPreferences(conv.id, { pinned: !conv.pinned });
    } else {
      await store.updateConvPreferences(conv.id, {
        showMemberNickname: !showMemberNicknameOn.value
      });
    }
  } catch (e: any) {
    store.pushToast(e.message || '保存设置失败', 'error');
  } finally {
    togglingKey.value = null;
  }
}

async function handleLeave() {
  if (!store.activeConvId) return;
  if (!window.confirm('确认退出当前群聊？')) return;
  try {
    await store.leaveGroup(store.activeConvId);
    emit('close');
  } catch (e: any) {
    store.pushToast(e.message || '退群失败', 'error');
  }
}
</script>

<template>
  <aside v-glow class="inspector glass">
    <!-- 顶部栏：仅在单聊搜索或演练模式双 Tab 时展示头部；日常群聊直接采用极简微信风格顶部搜索栏 -->
    <div v-if="availableTabs.length > 1" class="tabs-bar-wrap">
      <div class="inspector-tabs" :style="{ gridTemplateColumns: `repeat(${availableTabs.length}, 1fr)` }">
        <span
          class="tab-indicator"
          :style="{
            width: `calc(${100 / availableTabs.length}% - 4px)`,
            transform: `translateX(${tabIndex * 100}%)`
          }"
        />
        <button
          v-if="store.demoMode"
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
          <span>群聊信息</span>
        </button>
        <button
          v-else
          type="button"
          class="ins-tab"
          :class="{ active: activeTab === 'search' }"
          @click="activeTab = 'search'"
        >
          <Search :size="13" />
          <span>搜索</span>
        </button>
      </div>
      <button type="button" class="btn-icon close-drawer-btn" title="关闭面板" @click="emit('close')">
        <X :size="14" />
      </button>
    </div>

    <div v-else-if="!isGroup" class="single-head">
      <span class="single-title">
        <Search :size="14" />
        <span>查找聊天记录</span>
      </span>
      <button type="button" class="btn-icon close-drawer-btn" title="关闭面板" @click="emit('close')">
        <X :size="14" />
      </button>
    </div>

    <Transition name="tab-swap" mode="out-in">
      <!-- Tab 1: AI 观测与日程提醒（仅面试演练模式可见） -->
      <div v-if="activeTab === 'metrics' && store.demoMode" key="metrics" class="inspector-body">
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

      <!-- Tab 2: 微信风格群聊信息面板（主界面 + 子视图） -->
      <div v-else-if="activeTab === 'group' && isGroup && store.activeConversation" key="group" class="wx-group-shell">
        <!-- 主视图：群成员网格 + 群信息项 + 开关组 -->
        <div v-if="groupSubView === 'main'" class="wx-scroll">
          <!-- 顶部搜索群成员 -->
          <div class="wx-search-bar">
            <div class="wx-search-input-wrap">
              <Search :size="14" class="wx-search-ic" />
              <input
                v-model="memberSearch"
                class="wx-search-input"
                placeholder="搜索群成员"
              />
              <button
                v-if="memberSearch"
                type="button"
                class="wx-clear-ic"
                @click="memberSearch = ''"
              >
                <X :size="12" />
              </button>
            </div>
            <button
              v-if="availableTabs.length === 1"
              type="button"
              class="btn-icon close-drawer-btn"
              title="关闭面板"
              @click="emit('close')"
            >
              <X :size="15" />
            </button>
          </div>

          <!-- 移出模式提示条 -->
          <div v-if="removeMode" class="wx-remove-banner">
            <span>点击成员头像右上角 <strong>-</strong> 移出群聊</span>
            <button type="button" class="wx-text-btn" @click="removeMode = false">完成</button>
          </div>

          <!-- 4 列群成员头像网格 + 虚线方框 添加/移出 -->
          <div class="wx-member-grid">
            <button
              v-for="m in displayedMembers"
              :key="m.userId"
              type="button"
              class="wx-member-tile"
              :class="{ 'is-removable': removeMode && canRemoveMember(m), 'is-dimmed': removeMode && !canRemoveMember(m) }"
              :title="removeMode && canRemoveMember(m) ? `点击移出 ${displayMemberName(m)}` : `查看 ${displayMemberName(m)} 资料`"
              @click="handleMemberTileClick(m)"
            >
              <div class="wx-avatar-box">
                <UserAvatar
                  :name="displayMemberName(m)"
                  :seed="m.username"
                  :src="m.avatar"
                  :online="m.online"
                  size="md"
                />
                <span v-if="m.role === 3 && !removeMode" class="wx-owner-dot" title="群主">
                  <Crown :size="9" />
                </span>
                <span v-if="removeMode && canRemoveMember(m)" class="wx-kick-badge">
                  <Minus :size="10" />
                </span>
              </div>
              <span class="wx-member-name">{{ displayMemberName(m) }}</span>
            </button>

            <!-- 虚线方框：添加 -->
            <button
              v-if="!memberSearch.trim()"
              type="button"
              class="wx-member-tile wx-action-tile"
              title="邀请好友加入群聊"
              @click="openInviteView"
            >
              <span class="wx-dashed-box">
                <Plus :size="20" />
              </span>
              <span class="wx-member-name">添加</span>
            </button>

            <!-- 虚线方框：移出（仅群主/群管理员可见） -->
            <button
              v-if="!memberSearch.trim() && isAdminOrOwner && removableMembers.length > 0"
              type="button"
              class="wx-member-tile wx-action-tile"
              :class="{ 'is-active-remove': removeMode }"
              :title="removeMode ? '退出移出模式' : '移出群成员'"
              @click="removeMode = !removeMode"
            >
              <span class="wx-dashed-box">
                <Minus :size="20" />
              </span>
              <span class="wx-member-name">{{ removeMode ? '完成' : '移出' }}</span>
            </button>
          </div>

          <div v-if="memberSearch.trim() && filteredMembers.length === 0" class="wx-member-empty">
            未找到匹配的群成员
          </div>

          <button
            v-if="hasHiddenMembers"
            type="button"
            class="wx-expand-btn"
            @click="membersExpanded = !membersExpanded"
          >
            <span>{{ membersExpanded ? '收起群成员' : `查看更多群成员 (${filteredMembers.length})` }}</span>
            <ChevronUp v-if="membersExpanded" :size="14" />
            <ChevronDown v-else :size="14" />
          </button>

          <!-- 待审核入群申请入口（仅群主/管理员且有待处理申请时展示） -->
          <template v-if="isAdminOrOwner && pendingJoinCount > 0">
            <div class="wx-divider" />
            <button type="button" class="wx-row wx-row-inline wx-pending-row" @click="openJoinRequestsView">
              <span class="wx-row-label wx-pending-label">
                <UserCheck :size="15" />
                <span>待确认的入群申请</span>
              </span>
              <span class="wx-row-right">
                <span class="wx-count-pill">{{ pendingJoinCount }}</span>
                <ChevronRight :size="15" class="wx-chevron" />
              </span>
            </button>
          </template>

          <div class="wx-divider" />

          <!-- 群聊信息区：群聊名称 / 群公告 / 备注 / 我在本群的昵称 -->
          <div class="wx-section">
            <!-- 群聊名称 -->
            <div class="wx-field-item">
              <div class="wx-field-label">群聊名称</div>
              <div v-if="editingField === 'title'" class="wx-inline-edit">
                <input
                  ref="fieldInputRef"
                  v-model="fieldDraft"
                  class="wx-inline-input"
                  maxlength="40"
                  placeholder="输入群聊名称"
                  @keydown.enter.prevent="commitEditField"
                  @keydown.esc.prevent="cancelEditField"
                  @blur="commitEditField"
                />
              </div>
              <button v-else type="button" class="wx-field-value-btn" @click="startEditField('title')">
                <span class="wx-field-val">{{ store.activeConversation.title }}</span>
                <Pencil :size="12" class="wx-edit-hint" />
              </button>
            </div>

            <!-- 群公告 -->
            <button type="button" class="wx-field-item wx-field-clickable" @click="openAnnouncementView">
              <div class="wx-field-head-row">
                <span class="wx-field-label">群公告</span>
                <ChevronRight :size="15" class="wx-chevron" />
              </div>
              <div class="wx-field-val wx-announcement-preview">
                {{ store.activeConversation.announcement || '发布后会通知全部群成员' }}
              </div>
            </button>

            <!-- 备注（仅自己可见） -->
            <div class="wx-field-item">
              <div class="wx-field-label">备注</div>
              <div v-if="editingField === 'remark'" class="wx-inline-edit">
                <input
                  ref="fieldInputRef"
                  v-model="fieldDraft"
                  class="wx-inline-input"
                  maxlength="64"
                  placeholder="群聊的备注仅自己可见（留空恢复原名）"
                  @keydown.enter.prevent="commitEditField"
                  @keydown.esc.prevent="cancelEditField"
                  @blur="commitEditField"
                />
              </div>
              <button v-else type="button" class="wx-field-value-btn" @click="startEditField('remark')">
                <span class="wx-field-val">
                  {{ store.activeConversation.groupRemark || '群聊的备注仅自己可见' }}
                </span>
                <Pencil :size="12" class="wx-edit-hint" />
              </button>
            </div>

            <!-- 我在本群的昵称 -->
            <div class="wx-field-item">
              <div class="wx-field-label">我在本群的昵称</div>
              <div v-if="editingField === 'groupNickname'" class="wx-inline-edit">
                <input
                  ref="fieldInputRef"
                  v-model="fieldDraft"
                  class="wx-inline-input"
                  maxlength="64"
                  placeholder="输入在本群显示的昵称（留空恢复默认）"
                  @keydown.enter.prevent="commitEditField"
                  @keydown.esc.prevent="cancelEditField"
                  @blur="commitEditField"
                />
              </div>
              <button v-else type="button" class="wx-field-value-btn" @click="startEditField('groupNickname')">
                <span class="wx-field-val">
                  {{ store.activeConversation.myGroupNickname || store.currentUser?.nickname || '未设置' }}
                </span>
                <Pencil :size="12" class="wx-edit-hint" />
              </button>
            </div>
          </div>

          <div class="wx-divider" />

          <!-- 查找聊天内容 -->
          <button type="button" class="wx-row wx-row-inline" @click="groupSubView = 'search'">
            <span class="wx-row-label">查找聊天内容</span>
            <ChevronRight :size="15" class="wx-chevron" />
          </button>

          <div class="wx-divider" />

          <!-- 开关区：进群确认 / 免打扰 / 置顶 / 显示群成员昵称 -->
          <div class="wx-toggles">
            <div class="wx-toggle-row" :class="{ 'is-readonly': !isAdminOrOwner }" @click="toggleJoinApproval">
              <span class="wx-row-label">进群需要群主/群管理员确认</span>
              <button
                type="button"
                role="switch"
                :aria-checked="Boolean(store.activeConversation.joinApprovalRequired)"
                class="wx-switch"
                :class="{ on: Boolean(store.activeConversation.joinApprovalRequired), disabled: !isAdminOrOwner }"
                :disabled="togglingKey === 'joinApproval'"
                @click.stop="toggleJoinApproval"
              >
                <span class="wx-switch-thumb" />
              </button>
            </div>

            <div class="wx-toggle-row" @click="togglePref('muted')">
              <span class="wx-row-label">消息免打扰</span>
              <button
                type="button"
                role="switch"
                :aria-checked="Boolean(store.activeConversation.muted)"
                class="wx-switch"
                :class="{ on: Boolean(store.activeConversation.muted) }"
                :disabled="togglingKey === 'muted'"
                @click.stop="togglePref('muted')"
              >
                <span class="wx-switch-thumb" />
              </button>
            </div>

            <div class="wx-toggle-row" @click="togglePref('pinned')">
              <span class="wx-row-label">置顶聊天</span>
              <button
                type="button"
                role="switch"
                :aria-checked="Boolean(store.activeConversation.pinned)"
                class="wx-switch"
                :class="{ on: Boolean(store.activeConversation.pinned) }"
                :disabled="togglingKey === 'pinned'"
                @click.stop="togglePref('pinned')"
              >
                <span class="wx-switch-thumb" />
              </button>
            </div>

            <div class="wx-toggle-row" @click="togglePref('showMemberNickname')">
              <span class="wx-row-label">显示群成员昵称</span>
              <button
                type="button"
                role="switch"
                :aria-checked="showMemberNicknameOn"
                class="wx-switch"
                :class="{ on: showMemberNicknameOn }"
                :disabled="togglingKey === 'showMemberNickname'"
                @click.stop="togglePref('showMemberNickname')"
              >
                <span class="wx-switch-thumb" />
              </button>
            </div>
          </div>

          <div class="wx-divider" />

          <!-- 底部退出群聊 -->
          <button type="button" class="wx-leave-btn" @click="handleLeave">
            退出群聊
          </button>
        </div>

        <!-- 子视图 A：群公告详情与编辑 -->
        <div v-else-if="groupSubView === 'announcement'" class="wx-subview">
          <div class="wx-subhead">
            <button type="button" class="btn-icon" @click="groupSubView = 'main'">
              <ArrowLeft :size="15" />
            </button>
            <span>群公告</span>
            <button type="button" class="btn-icon" @click="emit('close')">
              <X :size="14" />
            </button>
          </div>
          <div class="wx-subbody">
            <div v-if="store.activeConversation.announcementUpdatedAt" class="wx-ann-meta">
              <span>{{ store.activeConversation.announcementPublisher || '群管理员' }}</span>
              <span class="tabular">{{ formatTimeShort(store.activeConversation.announcementUpdatedAt) }}</span>
            </div>

            <template v-if="isAdminOrOwner">
              <textarea
                v-model="announcementDraft"
                class="wx-ann-textarea"
                rows="8"
                maxlength="1000"
                placeholder="输入群公告内容，发布后会通知全部群成员..."
              />
              <div class="wx-ann-hint">
                <span>{{ announcementDraft.length }} / 1000</span>
                <span>发布后将在群内发出系统通知</span>
              </div>
              <div class="wx-ann-actions">
                <button
                  v-if="store.activeConversation.announcement"
                  type="button"
                  class="btn btn-ghost btn-xs"
                  :disabled="savingAnnouncement"
                  @click="handleSaveAnnouncement(true)"
                >
                  清空公告
                </button>
                <button
                  type="button"
                  class="btn btn-primary btn-xs"
                  :disabled="savingAnnouncement"
                  @click="handleSaveAnnouncement(false)"
                >
                  {{ savingAnnouncement ? '发布中...' : '发布公告' }}
                </button>
              </div>
            </template>

            <template v-else>
              <div class="wx-ann-readonly">
                {{ store.activeConversation.announcement || '暂无群公告' }}
              </div>
              <p class="empty-tip">仅群主或群管理员可编辑和发布群公告。</p>
            </template>
          </div>
        </div>

        <!-- 子视图 B：添加群成员（好友多选） -->
        <div v-else-if="groupSubView === 'invite'" class="wx-subview">
          <div class="wx-subhead">
            <button type="button" class="btn-icon" @click="groupSubView = 'main'">
              <ArrowLeft :size="15" />
            </button>
            <span>添加群成员</span>
            <button type="button" class="btn-icon" @click="emit('close')">
              <X :size="14" />
            </button>
          </div>
          <div class="wx-subbody">
            <div
              v-if="store.activeConversation.joinApprovalRequired && !isAdminOrOwner"
              class="wx-notice-box"
            >
              群主已开启「进群需要群主/群管理员确认」，提交后需等待审核通过才会入群。
            </div>

            <div class="wx-search-input-wrap" style="margin-bottom: 10px;">
              <Search :size="14" class="wx-search-ic" />
              <input
                v-model="inviteKeyword"
                class="wx-search-input"
                placeholder="搜索好友昵称或账号..."
              />
            </div>

            <div v-if="loadingFriends" class="empty-tip">正在加载好友列表...</div>
            <div v-else-if="filteredInviteCandidates.length === 0" class="empty-tip">
              {{ inviteKeyword.trim() ? '未找到可邀请的匹配好友' : '暂无可邀请的新好友（你的好友均已在群内）' }}
            </div>
            <div v-else class="wx-pick-list">
              <button
                v-for="u in filteredInviteCandidates"
                :key="u.id"
                type="button"
                class="wx-pick-row"
                :class="{ selected: selectedInviteIds.includes(u.id) }"
                @click="toggleInviteSelect(u.id)"
              >
                <span class="wx-pick-check" :class="{ checked: selectedInviteIds.includes(u.id) }">
                  <Check v-if="selectedInviteIds.includes(u.id)" :size="11" />
                </span>
                <UserAvatar :name="u.nickname" :seed="u.username" :src="u.avatar" size="sm" />
                <span class="wx-pick-meta">
                  <strong>{{ u.nickname }}</strong>
                  <small>@{{ u.username }}</small>
                </span>
              </button>
            </div>

            <div class="wx-subfoot">
              <button
                type="button"
                class="btn btn-primary"
                style="width: 100%;"
                :disabled="selectedInviteIds.length === 0 || submittingInvite"
                @click="submitInvites"
              >
                {{
                  submittingInvite
                    ? '提交中...'
                    : store.activeConversation.joinApprovalRequired && !isAdminOrOwner
                      ? `提交入群申请 (${selectedInviteIds.length})`
                      : `确定邀请 (${selectedInviteIds.length})`
                }}
              </button>
            </div>
          </div>
        </div>

        <!-- 子视图 C：入群申请审核（群主/管理员） -->
        <div v-else-if="groupSubView === 'joinRequests'" class="wx-subview">
          <div class="wx-subhead">
            <button type="button" class="btn-icon" @click="groupSubView = 'main'">
              <ArrowLeft :size="15" />
            </button>
            <span>入群申请审核</span>
            <button type="button" class="btn-icon" @click="emit('close')">
              <X :size="14" />
            </button>
          </div>
          <div class="wx-subbody">
            <div v-if="store.activeJoinRequests.length === 0" class="empty-tip">
              暂无待确认的入群申请
            </div>
            <div v-else class="list-stack">
              <div v-for="req in store.activeJoinRequests" :key="req.id" class="info-card">
                <div class="wx-join-req-top">
                  <UserAvatar
                    :name="req.inviteeNickname"
                    :seed="req.inviteeUsername"
                    :src="req.inviteeAvatar"
                    size="sm"
                  />
                  <div class="wx-pick-meta">
                    <strong>{{ req.inviteeNickname }}</strong>
                    <small>{{ req.inviterNickname }} 邀请加入</small>
                  </div>
                </div>
                <div class="wx-join-req-actions">
                  <button
                    type="button"
                    class="btn btn-ghost btn-xs"
                    @click="handleReviewJoin(req.id, false)"
                  >
                    拒绝
                  </button>
                  <button
                    type="button"
                    class="btn btn-primary btn-xs"
                    @click="handleReviewJoin(req.id, true)"
                  >
                    同意入群
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 子视图 D：本群查找聊天内容 -->
        <div v-else-if="groupSubView === 'search'" class="wx-subview">
          <div class="wx-subhead">
            <button type="button" class="btn-icon" @click="groupSubView = 'main'">
              <ArrowLeft :size="15" />
            </button>
            <span>查找聊天内容</span>
            <button type="button" class="btn-icon" @click="emit('close')">
              <X :size="14" />
            </button>
          </div>
          <div class="wx-subbody">
            <form class="inline-form" @submit.prevent="runSearch">
              <input v-model="keyword" class="input" placeholder="输入关键词检索本群消息..." />
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
                  <span class="mono-id tabular">{{ formatTimeShort(m.createdAt) }}</span>
                </div>
                <div class="info-text">{{ m.content }}</div>
              </button>
            </div>
            <div v-else class="empty-tip">
              {{ keyword.trim() ? '未搜索到匹配消息' : '输入关键字检索本群历史记录，点击结果可直接定位' }}
            </div>
          </div>
        </div>
      </div>

      <!-- Tab 3: 单聊历史全文搜索 -->
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
              <span class="mono-id tabular">{{ formatTimeShort(m.createdAt) }}</span>
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
  width: 300px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  height: 100%;
  border-radius: var(--radius-panel);
  overflow: hidden;
  animation: rise-in 0.45s var(--ease-out) both;
}

.inspector > * {
  position: relative;
  z-index: 1;
}

.single-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  border-bottom: 1px solid var(--border);
}

.single-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13.5px;
  font-weight: 700;
  color: var(--ink);
}

.tabs-bar-wrap {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 10px 4px;
}

.close-drawer-btn {
  padding: 6px;
  border-radius: 10px;
  flex-shrink: 0;
}

.inspector-tabs {
  flex: 1;
  position: relative;
  display: grid;
  grid-template-columns: 1fr 1fr;
  padding: 4px;
  border-radius: 14px;
  background: var(--rail);
  border: 1px solid var(--border);
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

/* ==================== 微信风格群聊信息抽屉 ==================== */
.wx-group-shell {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.wx-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 14px 16px 20px;
}

.wx-search-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 16px;
}

.wx-search-input-wrap {
  flex: 1;
  position: relative;
  display: flex;
  align-items: center;
}

.wx-search-ic {
  position: absolute;
  left: 10px;
  color: var(--ink-muted);
  pointer-events: none;
}

.wx-search-input {
  width: 100%;
  height: 34px;
  padding: 0 28px 0 32px;
  border-radius: 8px;
  border: 1px solid var(--border);
  background: var(--rail);
  color: var(--ink);
  font-size: 12.5px;
  outline: none;
  transition: border-color 0.2s, background 0.2s;
}

.wx-search-input:focus {
  border-color: var(--accent);
  background: var(--surface-strong);
}

.wx-search-input::placeholder {
  color: var(--ink-muted);
}

.wx-clear-ic {
  position: absolute;
  right: 7px;
  border: none;
  background: transparent;
  color: var(--ink-muted);
  cursor: pointer;
  padding: 2px;
  display: inline-flex;
}

.wx-remove-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11.5px;
  color: var(--danger);
  background: rgba(239, 68, 68, 0.1);
  border: 1px solid rgba(239, 68, 68, 0.22);
  border-radius: 8px;
  padding: 6px 10px;
  margin-bottom: 12px;
}

.wx-text-btn {
  border: none;
  background: transparent;
  color: var(--accent);
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}

.wx-member-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px 8px;
}

.wx-member-tile {
  border: none;
  background: transparent;
  padding: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  color: var(--ink);
  min-width: 0;
  transition: transform 0.2s var(--ease-spring), opacity 0.2s;
}

.wx-member-tile:hover {
  transform: translateY(-1px);
}

.wx-member-tile.is-dimmed {
  opacity: 0.45;
  cursor: default;
}

.wx-avatar-box {
  position: relative;
  width: 44px;
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.wx-avatar-box :deep(.avatar) {
  width: 44px !important;
  height: 44px !important;
  border-radius: 8px !important;
}

.wx-avatar-box :deep(.avatar-img) {
  border-radius: 8px !important;
}

.wx-owner-dot {
  position: absolute;
  top: -3px;
  left: -3px;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: #f59e0b;
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.25);
}

.wx-kick-badge {
  position: absolute;
  top: -5px;
  right: -5px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #ef4444;
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 2px 6px rgba(239, 68, 68, 0.45);
}

.wx-dashed-box {
  width: 44px;
  height: 44px;
  border-radius: 8px;
  border: 1.5px dashed var(--ink-muted);
  opacity: 0.68;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--ink-secondary);
  transition: border-color 0.2s, color 0.2s, opacity 0.2s, background 0.2s;
}

.wx-action-tile:hover .wx-dashed-box {
  opacity: 1;
  border-color: var(--accent);
  color: var(--accent);
  background: var(--hover);
}

.wx-action-tile.is-active-remove .wx-dashed-box {
  opacity: 1;
  border-color: var(--danger);
  color: var(--danger);
  background: rgba(239, 68, 68, 0.08);
}

.wx-member-name {
  width: 100%;
  text-align: center;
  font-size: 11.5px;
  color: var(--ink-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.wx-member-empty {
  text-align: center;
  font-size: 12px;
  color: var(--ink-muted);
  padding: 14px 0 4px;
}

.wx-expand-btn {
  width: 100%;
  margin-top: 12px;
  padding: 6px;
  border: none;
  background: transparent;
  color: var(--ink-muted);
  font-size: 12px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  cursor: pointer;
}

.wx-expand-btn:hover {
  color: var(--ink);
}

.wx-divider {
  height: 1px;
  background: var(--border);
  margin: 14px 0;
}

.wx-section {
  display: flex;
  flex-direction: column;
  gap: 15px;
}

.wx-field-item {
  display: flex;
  flex-direction: column;
  gap: 5px;
  text-align: left;
  width: 100%;
  border: none;
  background: transparent;
  padding: 0;
}

.wx-field-clickable {
  cursor: pointer;
  border-radius: 8px;
  transition: opacity 0.15s;
}

.wx-field-clickable:hover .wx-chevron {
  transform: translateX(2px);
  color: var(--ink);
}

.wx-field-head-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.wx-field-label {
  font-size: 13.5px;
  font-weight: 500;
  color: var(--ink);
}

.wx-field-value-btn {
  border: none;
  background: transparent;
  padding: 2px 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  text-align: left;
  cursor: pointer;
  width: 100%;
}

.wx-field-value-btn:hover .wx-edit-hint {
  opacity: 0.9;
}

.wx-field-val {
  font-size: 13px;
  color: var(--ink-muted);
  line-height: 1.45;
  word-break: break-word;
}

.wx-announcement-preview {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.wx-edit-hint {
  flex-shrink: 0;
  color: var(--ink-muted);
  opacity: 0.35;
  transition: opacity 0.2s;
}

.wx-inline-edit {
  margin-top: 2px;
}

.wx-inline-input {
  width: 100%;
  height: 32px;
  padding: 0 9px;
  border-radius: 7px;
  border: 1px solid var(--accent);
  background: var(--surface-strong);
  color: var(--ink);
  font-size: 13px;
  outline: none;
}

.wx-row {
  width: 100%;
  border: none;
  background: transparent;
  padding: 2px 0;
  cursor: pointer;
  text-align: left;
}

.wx-row-inline {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.wx-row-inline:hover .wx-chevron {
  transform: translateX(2px);
  color: var(--ink);
}

.wx-row-label {
  font-size: 13.5px;
  font-weight: 500;
  color: var(--ink);
}

.wx-row-right {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.wx-chevron {
  color: var(--ink-muted);
  flex-shrink: 0;
  transition: transform 0.2s, color 0.2s;
}

.wx-pending-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--accent);
}

.wx-count-pill {
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 999px;
  background: #ef4444;
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.wx-toggles {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.wx-toggle-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  cursor: pointer;
}

.wx-toggle-row.is-readonly {
  opacity: 0.72;
}

/* 微信/iOS 风格圆润绿底拨动开关 */
.wx-switch {
  position: relative;
  width: 42px;
  height: 24px;
  border-radius: 999px;
  border: none;
  background: rgba(148, 163, 184, 0.38);
  padding: 2px;
  cursor: pointer;
  flex-shrink: 0;
  transition: background 0.24s ease;
}

.wx-switch.on {
  background: #07c160;
}

.wx-switch.disabled {
  cursor: not-allowed;
}

.wx-switch-thumb {
  display: block;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #ffffff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.24);
  transform: translateX(0);
  transition: transform 0.24s var(--ease-spring);
}

.wx-switch.on .wx-switch-thumb {
  transform: translateX(18px);
}

.wx-leave-btn {
  width: 100%;
  border: none;
  background: transparent;
  color: #ef4444;
  font-size: 13.5px;
  font-weight: 600;
  padding: 8px 0;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.2s;
}

.wx-leave-btn:hover {
  background: rgba(239, 68, 68, 0.1);
}

/* ==================== 子视图 ==================== */
.wx-subview {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.wx-subhead {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-bottom: 1px solid var(--border);
  font-size: 13.5px;
  font-weight: 700;
  color: var(--ink);
}

.wx-subbody {
  flex: 1;
  overflow-y: auto;
  padding: 14px;
  display: flex;
  flex-direction: column;
}

.wx-ann-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11.5px;
  color: var(--ink-muted);
  margin-bottom: 10px;
}

.wx-ann-textarea {
  width: 100%;
  border-radius: 10px;
  border: 1px solid var(--border);
  background: var(--surface);
  color: var(--ink);
  padding: 10px 11px;
  font-size: 13px;
  line-height: 1.6;
  resize: vertical;
  outline: none;
  font-family: inherit;
}

.wx-ann-textarea:focus {
  border-color: var(--accent);
}

.wx-ann-hint {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11px;
  color: var(--ink-muted);
  margin-top: 6px;
}

.wx-ann-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
}

.wx-ann-readonly {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 12px;
  font-size: 13px;
  line-height: 1.65;
  color: var(--ink);
  white-space: pre-wrap;
  word-break: break-word;
}

.wx-notice-box {
  font-size: 12px;
  line-height: 1.5;
  color: var(--accent);
  background: var(--accent-soft, rgba(58, 107, 255, 0.1));
  border: 1px solid rgba(58, 107, 255, 0.2);
  border-radius: 10px;
  padding: 8px 10px;
  margin-bottom: 10px;
}

.wx-pick-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.wx-pick-row {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 7px 9px;
  border-radius: 10px;
  border: 1px solid var(--border);
  background: var(--surface);
  cursor: pointer;
  text-align: left;
  color: var(--ink);
  transition: border-color 0.2s, background 0.2s;
}

.wx-pick-row.selected {
  border-color: var(--accent);
  background: rgba(58, 107, 255, 0.08);
}

.wx-pick-check {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  border: 1.5px solid var(--ink-muted);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.wx-pick-check.checked {
  background: #07c160;
  border-color: #07c160;
  color: #fff;
}

.wx-pick-meta {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  font-size: 12.5px;
}

.wx-pick-meta strong {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.wx-pick-meta small {
  font-size: 11px;
  color: var(--ink-muted);
}

.wx-subfoot {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid var(--border);
}

.wx-join-req-top {
  display: flex;
  align-items: center;
  gap: 9px;
}

.wx-join-req-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 6px;
}

/* ==================== 保留原 AI 观测 & 搜索通用样式 ==================== */
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
}

.search-hit {
  width: 100%;
  text-align: left;
  color: var(--ink);
  cursor: pointer;
}

.inline-form {
  display: flex;
  gap: 6px;
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
