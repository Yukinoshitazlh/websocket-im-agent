<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import {
  Check,
  EyeOff,
  MoonStar,
  Radio,
  Search,
  ShieldCheck,
  UserCheck,
  UserX,
  X
} from 'lucide-vue-next';
import { useChatStore } from '../stores/chat';
import type { PresenceMode, UserProfile } from '../types/im';
import { groupByInitial } from '../utils/pinyin';
import UserAvatar from './UserAvatar.vue';

const emit = defineEmits<{
  (e: 'close'): void;
}>();

const store = useChatStore();

const mode = ref<PresenceMode>(store.presenceSettings.mode || 'ALL');
const exceptIds = ref<string[]>([...store.presenceSettings.exceptUserIds]);
const onlyIds = ref<string[]>([...store.presenceSettings.onlyUserIds]);
const friendKeyword = ref('');
const loading = ref(false);
const saving = ref(false);

const MODE_CARDS: Array<{
  key: PresenceMode;
  title: string;
  desc: string;
}> = [
  {
    key: 'ALL',
    title: '在线可见',
    desc: '所有人可见在线与正常已读'
  },
  {
    key: 'NONE',
    title: '全员隐身',
    desc: '对所有人离线 · 看消息不显已读'
  },
  {
    key: 'EXCEPT',
    title: '不给谁看',
    desc: '对选中的好友隐藏在线与已读'
  },
  {
    key: 'ONLY',
    title: '部分可见',
    desc: '仅白名单好友可见，其余人隐身'
  }
];

const needsPicker = computed(() => mode.value === 'EXCEPT' || mode.value === 'ONLY');

const activeSelectedIds = computed<string[]>({
  get: () => (mode.value === 'EXCEPT' ? exceptIds.value : onlyIds.value),
  set: (val) => {
    if (mode.value === 'EXCEPT') {
      exceptIds.value = val;
    } else if (mode.value === 'ONLY') {
      onlyIds.value = val;
    }
  }
});

const selectedFriendProfiles = computed<UserProfile[]>(() => {
  const idSet = new Set(activeSelectedIds.value);
  return store.friends.filter((f) => idSet.has(String(f.id)));
});

const filteredFriends = computed(() => {
  const q = friendKeyword.value.trim().toLowerCase();
  if (!q) return store.friends;
  return store.friends.filter(
    (f) =>
      f.nickname.toLowerCase().includes(q) ||
      f.username.toLowerCase().includes(q) ||
      (f.email || '').toLowerCase().includes(q)
  );
});

const groupedFriends = computed(() =>
  groupByInitial(filteredFriends.value, (f) => f.nickname || f.username)
);

const radarCaption = computed(() => {
  if (mode.value === 'ALL') return '在线信号广播中 · 好友与群成员均可看到你在线';
  if (mode.value === 'NONE') return '静默隐身中 · 所有人看到你离线，看消息不发已读';
  if (mode.value === 'EXCEPT') {
    return exceptIds.value.length > 0
      ? `定向隐身 · 已对 ${exceptIds.value.length} 位好友隐藏在线与已读`
      : '请选择不希望看到你在线状态的好友';
  }
  return onlyIds.value.length > 0
    ? `白名单可见 · 仅 ${onlyIds.value.length} 位好友可见你在线，其余人隐身`
    : '未选白名单时，效果等同于对所有好友隐身';
});

function toggleUser(uid: string) {
  const strId = String(uid);
  const current = activeSelectedIds.value;
  if (current.includes(strId)) {
    activeSelectedIds.value = current.filter((id) => id !== strId);
  } else {
    activeSelectedIds.value = [...current, strId];
  }
}

async function handleSave() {
  if (saving.value) return;
  saving.value = true;
  try {
    await store.saveMyPresence({
      mode: mode.value,
      exceptUserIds: exceptIds.value,
      onlyUserIds: onlyIds.value
    });
    emit('close');
  } catch (e: any) {
    store.pushToast(e.message || '保存在线状态设置失败', 'error');
  } finally {
    saving.value = false;
  }
}

onMounted(async () => {
  loading.value = true;
  try {
    await Promise.all([store.loadMyPresence(), store.loadContactsData()]);
    mode.value = store.presenceSettings.mode || 'ALL';
    exceptIds.value = [...store.presenceSettings.exceptUserIds];
    onlyIds.value = [...store.presenceSettings.onlyUserIds];
  } finally {
    loading.value = false;
  }
});
</script>

<template>
  <div class="modal-backdrop" @click.self="emit('close')">
    <div class="presence-modal glass-strong">
      <!-- 顶部关闭与标题 -->
      <div class="pm-head">
        <div class="pm-title-wrap">
          <span class="pm-title">在线信号与隐身</span>
          <span class="pm-sub">自由掌控谁能看到你在线与已读</span>
        </div>
        <button type="button" class="btn-icon pm-close" title="关闭" @click="emit('close')">
          <X :size="15" />
        </button>
      </div>

      <!-- 创意雷达视窗：直观可视化当前隐身信号态 -->
      <div class="radar-stage" :class="`mode-${mode.toLowerCase()}`">
        <div class="radar-rings" aria-hidden="true">
          <span class="ring r1" />
          <span class="ring r2" />
          <span class="ring r3" />
        </div>
        <div class="radar-avatar-wrap">
          <UserAvatar
            :name="store.currentUser?.nickname || 'U'"
            :seed="store.currentUser?.username || 'u'"
            :src="store.currentUser?.avatar"
            size="lg"
          />
          <span class="radar-badge" :class="`mode-${mode.toLowerCase()}`">
            <Radio v-if="mode === 'ALL'" :size="11" />
            <MoonStar v-else-if="mode === 'NONE'" :size="11" />
            <EyeOff v-else-if="mode === 'EXCEPT'" :size="11" />
            <UserCheck v-else :size="11" />
          </span>
        </div>
        <p class="radar-caption">{{ radarCaption }}</p>
      </div>

      <!-- 2x2 创意模式卡片矩阵 -->
      <div class="pm-body">
        <div class="mode-grid">
          <button
            v-for="item in MODE_CARDS"
            :key="item.key"
            type="button"
            class="mode-card"
            :class="{ active: mode === item.key, [`tone-${item.key.toLowerCase()}`]: true }"
            @click="mode = item.key"
          >
            <div class="mc-top">
              <span class="mc-icon">
                <Radio v-if="item.key === 'ALL'" :size="15" />
                <MoonStar v-else-if="item.key === 'NONE'" :size="15" />
                <UserX v-else-if="item.key === 'EXCEPT'" :size="15" />
                <UserCheck v-else :size="15" />
              </span>
              <span
                v-if="item.key === 'EXCEPT' && exceptIds.length > 0"
                class="mc-count tabular"
              >
                {{ exceptIds.length }}人
              </span>
              <span
                v-else-if="item.key === 'ONLY' && onlyIds.length > 0"
                class="mc-count tabular"
              >
                {{ onlyIds.length }}人
              </span>
              <span v-else-if="mode === item.key" class="mc-check">
                <Check :size="11" />
              </span>
            </div>
            <strong class="mc-title">{{ item.title }}</strong>
            <small class="mc-desc">{{ item.desc }}</small>
          </button>
        </div>

        <!-- 定向名单选择器（不给谁看 / 部分可见 时滑出） -->
        <div v-if="needsPicker" class="picker-panel">
          <div class="picker-head">
            <span class="picker-title">
              {{ mode === 'EXCEPT' ? '选择不给看在线状态的好友' : '选择允许看到你在线的好友' }}
            </span>
            <span class="picker-count tabular">已选 {{ activeSelectedIds.length }} 人</span>
          </div>

          <!-- 已选好友胶囊条 -->
          <div v-if="selectedFriendProfiles.length > 0" class="selected-chips">
            <button
              v-for="u in selectedFriendProfiles"
              :key="u.id"
              type="button"
              class="sel-chip"
              title="点击移除"
              @click="toggleUser(u.id)"
            >
              <span>{{ u.nickname }}</span>
              <X :size="11" />
            </button>
          </div>

          <div class="picker-search">
            <Search :size="13" class="ps-ic" />
            <input
              v-model="friendKeyword"
              class="ps-input"
              placeholder="按昵称或账号筛选好友..."
            />
          </div>

          <div v-if="loading && store.friends.length === 0" class="picker-empty">正在加载好友列表...</div>
          <div v-else-if="store.friends.length === 0" class="picker-empty">暂无好友可选择</div>
          <div v-else-if="groupedFriends.length === 0" class="picker-empty">未匹配到好友</div>
          <div v-else class="picker-list">
            <div v-for="grp in groupedFriends" :key="grp.letter" class="picker-group">
              <div class="pg-letter">{{ grp.letter }}</div>
              <button
                v-for="f in grp.items"
                :key="f.id"
                type="button"
                class="picker-row"
                :class="{ selected: activeSelectedIds.includes(String(f.id)) }"
                @click="toggleUser(f.id)"
              >
                <span class="pick-circle" :class="{ checked: activeSelectedIds.includes(String(f.id)) }">
                  <Check v-if="activeSelectedIds.includes(String(f.id))" :size="11" />
                </span>
                <UserAvatar :name="f.nickname" :seed="f.username" :src="f.avatar" size="sm" />
                <span class="pick-meta">
                  <strong>{{ f.nickname }}</strong>
                  <small>@{{ f.username }}</small>
                </span>
              </button>
            </div>
          </div>
        </div>

        <!-- 隐身已读机制说明条 -->
        <div class="stealth-tip">
          <ShieldCheck :size="14" class="st-ic" />
          <span>
            隐身生效期间，打开消息阅读不会自动发送已读回执；如需让对方知道你已读，可随时在聊天内或右键菜单点击「手动标为已读」。
          </span>
        </div>
      </div>

      <div class="pm-foot">
        <button type="button" class="btn btn-ghost" @click="emit('close')">取消</button>
        <button type="button" class="btn btn-primary" :disabled="saving" @click="handleSave">
          {{ saving ? '保存中...' : '应用信号设置' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(12, 18, 36, 0.36);
  -webkit-backdrop-filter: blur(10px) saturate(140%);
  backdrop-filter: blur(10px) saturate(140%);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 95;
  padding: 16px;
}

.presence-modal {
  width: 100%;
  max-width: 460px;
  max-height: 88vh;
  display: flex;
  flex-direction: column;
  border-radius: 24px;
  border: 1px solid var(--glass-edge);
  background: var(--surface-strong);
  box-shadow: var(--shadow-md), inset 0 1px 0 rgba(255, 255, 255, 0.55);
  overflow: hidden;
  animation: pop-in 0.28s var(--ease-spring) both;
}

.pm-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 15px 18px 10px;
}

.pm-title-wrap {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.pm-title {
  font-size: 15.5px;
  font-weight: 750;
  color: var(--ink);
}

.pm-sub {
  font-size: 11.5px;
  color: var(--ink-muted);
}

.pm-close {
  border-radius: 10px;
}

/* ==================== 顶部动态信号雷达视窗 ==================== */
.radar-stage {
  position: relative;
  margin: 4px 18px 12px;
  padding: 20px 16px 14px;
  border-radius: 18px;
  border: 1px solid var(--border);
  background: var(--rail);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  overflow: hidden;
  transition: background 0.35s, border-color 0.35s;
}

.radar-stage.mode-all {
  background: radial-gradient(circle at 50% 40%, rgba(16, 185, 129, 0.14), transparent 72%), var(--rail);
  border-color: rgba(16, 185, 129, 0.25);
}

.radar-stage.mode-none {
  background: radial-gradient(circle at 50% 40%, rgba(139, 92, 246, 0.14), transparent 72%), var(--rail);
  border-color: rgba(139, 92, 246, 0.25);
}

.radar-stage.mode-except {
  background: radial-gradient(circle at 50% 40%, rgba(245, 158, 11, 0.13), transparent 72%), var(--rail);
  border-color: rgba(245, 158, 11, 0.24);
}

.radar-stage.mode-only {
  background: radial-gradient(circle at 50% 40%, rgba(58, 107, 255, 0.14), transparent 72%), var(--rail);
  border-color: rgba(58, 107, 255, 0.25);
}

.radar-rings {
  position: absolute;
  top: 44px;
  left: 50%;
  width: 0;
  height: 0;
  pointer-events: none;
}

.ring {
  position: absolute;
  border-radius: 50%;
  transform: translate(-50%, -50%);
  border: 1px solid currentColor;
  opacity: 0.18;
}

.radar-stage.mode-all .ring { color: #10b981; animation: pulse-ring 3s ease-out infinite; }
.radar-stage.mode-none .ring { color: #8b5cf6; border-style: dashed; opacity: 0.16; }
.radar-stage.mode-except .ring { color: #f59e0b; opacity: 0.16; }
.radar-stage.mode-only .ring { color: #3a6bff; opacity: 0.18; }

.r1 { width: 74px; height: 74px; }
.r2 { width: 108px; height: 108px; animation-delay: 0.9s !important; }
.r3 { width: 144px; height: 144px; animation-delay: 1.8s !important; }

@keyframes pulse-ring {
  0% { transform: translate(-50%, -50%) scale(0.86); opacity: 0.36; }
  100% { transform: translate(-50%, -50%) scale(1.18); opacity: 0; }
}

.radar-avatar-wrap {
  position: relative;
  z-index: 1;
}

.radar-badge {
  position: absolute;
  right: -4px;
  bottom: -4px;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 0 0 2.5px var(--surface-strong);
  transition: background 0.25s;
}

.radar-badge.mode-all { background: #10b981; }
.radar-badge.mode-none { background: #7c3aed; }
.radar-badge.mode-except { background: #d97706; }
.radar-badge.mode-only { background: #3a6bff; }

.radar-caption {
  position: relative;
  z-index: 1;
  font-size: 12px;
  font-weight: 550;
  color: var(--ink-secondary);
  text-align: center;
  margin: 0;
}

/* ==================== 2x2 模式卡片 ==================== */
.pm-body {
  flex: 1;
  overflow-y: auto;
  padding: 0 18px 14px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.mode-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 9px;
}

.mode-card {
  border: 1.5px solid var(--border);
  background: var(--surface);
  border-radius: 15px;
  padding: 11px 12px;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.2s, background 0.2s, transform 0.2s var(--ease-spring), box-shadow 0.2s;
}

.mode-card:hover {
  transform: translateY(-1px);
  background: var(--hover);
}

.mc-top {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 2px;
}

.mc-icon {
  width: 28px;
  height: 28px;
  border-radius: 9px;
  background: var(--rail);
  color: var(--ink-secondary);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s, color 0.2s;
}

.mode-card.active.tone-all { border-color: #10b981; background: rgba(16, 185, 129, 0.08); }
.mode-card.active.tone-all .mc-icon { background: #10b981; color: #fff; }

.mode-card.active.tone-none { border-color: #8b5cf6; background: rgba(139, 92, 246, 0.09); }
.mode-card.active.tone-none .mc-icon { background: #7c3aed; color: #fff; }

.mode-card.active.tone-except { border-color: #f59e0b; background: rgba(245, 158, 11, 0.08); }
.mode-card.active.tone-except .mc-icon { background: #d97706; color: #fff; }

.mode-card.active.tone-only { border-color: var(--accent); background: rgba(58, 107, 255, 0.09); }
.mode-card.active.tone-only .mc-icon { background: var(--accent); color: #fff; }

.mc-check {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--ink);
  color: var(--surface-strong);
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.mc-count {
  font-size: 10.5px;
  font-weight: 700;
  padding: 2px 7px;
  border-radius: 999px;
  background: var(--rail);
  color: var(--ink-secondary);
}

.mc-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--ink);
}

.mc-desc {
  font-size: 11px;
  color: var(--ink-muted);
  line-height: 1.4;
}

/* ==================== 定向好友选择器 ==================== */
.picker-panel {
  border: 1px solid var(--border);
  border-radius: 15px;
  background: var(--rail);
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.picker-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
}

.picker-title {
  font-weight: 650;
  color: var(--ink);
}

.picker-count {
  font-size: 11px;
  font-weight: 700;
  color: var(--accent);
}

.selected-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.sel-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  font-weight: 600;
  padding: 3px 8px;
  border-radius: 999px;
  border: 1px solid var(--border-strong);
  background: var(--surface-strong);
  color: var(--ink);
  cursor: pointer;
}

.sel-chip:hover {
  border-color: var(--danger);
  color: var(--danger);
}

.picker-search {
  position: relative;
  display: flex;
  align-items: center;
}

.ps-ic {
  position: absolute;
  left: 9px;
  color: var(--ink-muted);
  pointer-events: none;
}

.ps-input {
  width: 100%;
  height: 31px;
  padding: 0 10px 0 28px;
  border-radius: 9px;
  border: 1px solid var(--border);
  background: var(--surface-strong);
  color: var(--ink);
  font-size: 12px;
  outline: none;
}

.ps-input:focus {
  border-color: var(--accent);
}

.picker-list {
  max-height: 170px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.picker-group {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.pg-letter {
  font-family: var(--font-mono);
  font-size: 10.5px;
  font-weight: 700;
  color: var(--ink-muted);
  padding: 0 4px;
}

.picker-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 6px 8px;
  border-radius: 10px;
  border: 1px solid transparent;
  background: var(--surface);
  cursor: pointer;
  text-align: left;
  color: var(--ink);
  transition: background 0.16s, border-color 0.16s;
}

.picker-row:hover {
  background: var(--hover);
}

.picker-row.selected {
  border-color: var(--accent);
  background: rgba(58, 107, 255, 0.08);
}

.pick-circle {
  width: 17px;
  height: 17px;
  border-radius: 50%;
  border: 1.5px solid var(--ink-muted);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.pick-circle.checked {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}

.pick-meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
  font-size: 12px;
}

.pick-meta strong {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.pick-meta small {
  font-size: 10.5px;
  color: var(--ink-muted);
}

.picker-empty {
  text-align: center;
  font-size: 12px;
  color: var(--ink-muted);
  padding: 14px 6px;
}

.stealth-tip {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  font-size: 11.5px;
  line-height: 1.5;
  color: var(--ink-muted);
  background: var(--rail);
  border-radius: 11px;
  padding: 8px 10px;
}

.st-ic {
  color: var(--accent);
  flex-shrink: 0;
  margin-top: 2px;
}

.pm-foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 18px 15px;
  border-top: 1px solid var(--border);
}
</style>
