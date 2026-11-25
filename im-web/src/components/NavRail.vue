<script setup lang="ts">
import { computed } from 'vue';
import {
  FlaskConical,
  LogOut,
  MessageSquare,
  Moon,
  Sun,
  Users
} from 'lucide-vue-next';
import { useChatStore } from '../stores/chat';
import UserAvatar from './UserAvatar.vue';
import { vGlow } from '../directives/glow';

const emit = defineEmits<{
  (e: 'open-group-modal'): void;
}>();

const store = useChatStore();

const totalUnread = computed(() =>
  store.conversations.reduce((sum, c) => (c.muted ? sum : sum + (c.unreadCount || 0)), 0)
);
</script>

<template>
  <nav v-glow class="nav-rail glass" aria-label="主导航">
    <!-- 顶部当前用户头像（点击编辑个人资料 / 头像 / 签名 / 状态） -->
    <button
      type="button"
      class="nav-avatar-btn"
      :title="`${store.currentUser?.nickname} (@${store.currentUser?.username}) | 点击编辑个人资料`"
      @click="store.openProfile()"
    >
      <UserAvatar
        :name="store.currentUser?.nickname || 'U'"
        :seed="store.currentUser?.username || 'u'"
        :src="store.currentUser?.avatar"
        size="lg"
        clickable
      />
      <span
        v-if="store.currentUser?.statusEmoji"
        class="nav-status-emoji"
        :title="store.currentUser.statusText || '当前状态'"
      >
        {{ store.currentUser.statusEmoji }}
      </span>
      <span v-else class="ws-dot" :class="store.wsState" />
    </button>

    <!-- 中部核心入口 -->
    <div class="nav-group">
      <button type="button" class="nav-btn active" title="消息">
        <MessageSquare :size="20" />
        <span v-if="totalUnread > 0" class="nav-badge tabular">
          {{ totalUnread > 99 ? '99+' : totalUnread }}
        </span>
      </button>

      <button
        type="button"
        class="nav-btn"
        title="发起群聊"
        @click="emit('open-group-modal')"
      >
        <Users :size="20" />
      </button>
    </div>

    <!-- 底部设置与开关 -->
    <div class="nav-group nav-bottom">
      <button
        type="button"
        class="nav-btn"
        :class="{ 'demo-on': store.demoMode }"
        :title="store.demoMode ? '关闭面试演练模式（切回日常纯净模式）' : '开启面试演练模式（显示网关切换/断网/#seq/幂等测试）'"
        @click="store.toggleDemoMode"
      >
        <FlaskConical :size="19" />
      </button>

      <button
        type="button"
        class="nav-btn"
        :title="store.theme === 'light' ? '切换深色主题' : '切换浅色主题'"
        @click="store.toggleTheme"
      >
        <Moon v-if="store.theme === 'light'" :size="19" />
        <Sun v-else :size="19" />
      </button>

      <button
        type="button"
        class="nav-btn danger"
        title="退出登录"
        @click="store.logout"
      >
        <LogOut :size="19" />
      </button>
    </div>
  </nav>
</template>

<style scoped>
.nav-rail {
  width: 60px;
  flex-shrink: 0;
  height: 100%;
  border-radius: var(--radius-panel);
  padding: 14px 0 12px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 18px;
  animation: rise-in 0.65s var(--ease-out) both;
}

.nav-rail > * {
  position: relative;
  z-index: 1;
}

.nav-avatar-btn {
  position: relative;
  border: none;
  background: transparent;
  padding: 0;
  cursor: pointer;
  display: inline-flex;
}

.nav-status-emoji {
  position: absolute;
  right: -5px;
  bottom: -4px;
  width: 19px;
  height: 19px;
  border-radius: 50%;
  background: var(--surface-strong);
  border: 1px solid var(--glass-edge);
  font-size: 11px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  box-shadow: var(--shadow-sm);
}

.ws-dot {
  position: absolute;
  right: -2px;
  bottom: -2px;
  width: 11px;
  height: 11px;
  border-radius: 50%;
  box-shadow: 0 0 0 2px var(--surface-strong);
  background: var(--danger);
}

.ws-dot.connected {
  background: #1FC46A;
}

.ws-dot.connecting {
  background: #F59E0B;
}

.nav-group {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.nav-bottom {
  margin-top: auto;
}

.nav-btn {
  position: relative;
  width: 40px;
  height: 40px;
  border: 1px solid transparent;
  border-radius: 13px;
  background: transparent;
  color: var(--ink-secondary);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition:
    transform 0.32s var(--ease-spring),
    background 0.2s,
    color 0.2s,
    border-color 0.2s,
    box-shadow 0.25s;
}

.nav-btn:hover {
  background: var(--hover);
  color: var(--ink);
  transform: translateY(-1px);
}

.nav-btn:active {
  transform: scale(0.92);
}

.nav-btn.active {
  background: var(--accent-soft);
  color: var(--accent);
  border-color: var(--glass-edge);
  box-shadow: var(--shadow-sm);
}

.nav-btn.demo-on {
  background: rgba(18, 180, 140, 0.18);
  color: var(--ai);
  border-color: var(--ai-border);
  box-shadow: 0 4px 14px rgba(18, 180, 140, 0.22);
}

.nav-btn.danger:hover {
  color: var(--danger);
  background: rgba(224, 49, 59, 0.12);
}

.nav-badge {
  position: absolute;
  top: 2px;
  right: 1px;
  min-width: 17px;
  height: 17px;
  padding: 0 4px;
  border-radius: 999px;
  background: linear-gradient(135deg, #FF5A65, #E0313B);
  color: #fff;
  font-size: 10px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 2px 8px rgba(224, 49, 59, 0.35);
}
</style>
