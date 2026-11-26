<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import {
  FlaskConical,
  LogOut,
  MessageSquare,
  Moon,
  Settings,
  Sun,
  User,
  Volume2,
  VolumeX
} from 'lucide-vue-next';
import { useChatStore } from '../stores/chat';
import UserAvatar from './UserAvatar.vue';
import { vGlow } from '../directives/glow';

const store = useChatStore();
const showSettings = ref(false);

const totalUnread = computed(() =>
  store.conversations.reduce((sum, c) => (c.muted ? sum : sum + (c.unreadCount || 0)), 0)
);

function closeSettings() {
  showSettings.value = false;
}

function onKeyDown(e: KeyboardEvent) {
  if (e.key === 'Escape' && showSettings.value) {
    showSettings.value = false;
  }
}

onMounted(() => {
  window.addEventListener('click', closeSettings);
  window.addEventListener('keydown', onKeyDown);
});

onUnmounted(() => {
  window.removeEventListener('click', closeSettings);
  window.removeEventListener('keydown', onKeyDown);
});
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

    <!-- 中部消息入口（发起群聊已统一保留在会话栏搜索框旁 + 按钮） -->
    <div class="nav-group">
      <button type="button" class="nav-btn active" title="消息">
        <MessageSquare :size="20" />
        <span v-if="totalUnread > 0" class="nav-badge tabular">
          {{ totalUnread > 99 ? '99+' : totalUnread }}
        </span>
      </button>
    </div>

    <!-- 底部单个集成设置按钮 -->
    <div class="nav-group nav-bottom" @click.stop>
      <button
        type="button"
        class="nav-btn"
        :class="{ active: showSettings }"
        title="设置与偏好"
        @click="showSettings = !showSettings"
      >
        <Settings :size="20" :class="{ rotated: showSettings }" class="gear-ic" />
        <span v-if="store.demoMode" class="demo-dot" title="当前处于面试演练模式" />
      </button>

      <Transition name="pop">
        <div v-if="showSettings" class="settings-popover glass-strong">
          <button
            type="button"
            class="set-item"
            @click="store.openProfile(); closeSettings()"
          >
            <User :size="15" />
            <span>个人资料与状态</span>
          </button>

          <button
            type="button"
            class="set-item"
            @click="store.toggleTheme()"
          >
            <Moon v-if="store.theme === 'light'" :size="15" />
            <Sun v-else :size="15" />
            <span>{{ store.theme === 'light' ? '切换深色模式' : '切换浅色模式' }}</span>
            <span class="mini-tag">{{ store.theme === 'dark' ? '深色' : '浅色' }}</span>
          </button>

          <button
            type="button"
            class="set-item"
            @click="store.toggleSound()"
          >
            <Volume2 v-if="store.soundEnabled" :size="15" />
            <VolumeX v-else :size="15" />
            <span>新消息提示音</span>
            <span class="mini-switch" :class="{ on: store.soundEnabled }">
              <i />
            </span>
          </button>

          <button
            v-if="store.authConfig.showDemoMode"
            type="button"
            class="set-item"
            @click="store.toggleDemoMode()"
          >
            <FlaskConical :size="15" />
            <span>面试演练模式</span>
            <span class="mini-switch ai" :class="{ on: store.demoMode }">
              <i />
            </span>
          </button>

          <div class="set-divider" />

          <button
            type="button"
            class="set-item danger"
            @click="closeSettings(); store.logout()"
          >
            <LogOut :size="15" />
            <span>退出登录</span>
          </button>
        </div>
      </Transition>
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
  z-index: 30;
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
  position: relative;
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

.gear-ic {
  transition: transform 0.45s var(--ease-spring);
}

.gear-ic.rotated {
  transform: rotate(60deg);
}

.demo-dot {
  position: absolute;
  top: 7px;
  right: 7px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--ai);
  box-shadow: 0 0 8px var(--ai);
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

/* 底部集成设置液态玻璃浮层 */
.settings-popover {
  position: absolute;
  left: calc(100% + 14px);
  bottom: 0;
  width: 224px;
  padding: 6px;
  border-radius: 16px;
  border: 1px solid var(--glass-edge);
  box-shadow: var(--shadow-md), inset 0 1px 0 rgba(255, 255, 255, 0.55);
  -webkit-backdrop-filter: blur(28px) saturate(190%);
  backdrop-filter: blur(28px) saturate(190%);
  display: flex;
  flex-direction: column;
  gap: 2px;
  z-index: 80;
  transform-origin: bottom left;
}

.set-item {
  border: none;
  background: transparent;
  color: var(--ink);
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 9px 10px;
  border-radius: 10px;
  font-size: 12.5px;
  font-weight: 500;
  cursor: pointer;
  text-align: left;
  transition: background 0.16s, color 0.16s;
}

.set-item:hover {
  background: var(--hover);
  color: var(--accent);
}

.set-item.danger:hover {
  background: rgba(224, 49, 59, 0.12);
  color: var(--danger);
}

.set-divider {
  height: 1px;
  background: var(--border);
  margin: 3px 4px;
}

.mini-tag {
  margin-left: auto;
  font-size: 11px;
  color: var(--ink-muted);
  background: var(--surface);
  border: 1px solid var(--border);
  padding: 1px 7px;
  border-radius: 999px;
}

.mini-switch {
  margin-left: auto;
  width: 30px;
  height: 17px;
  border-radius: 999px;
  background: var(--border-strong);
  padding: 2px;
  display: inline-flex;
  align-items: center;
  transition: background 0.25s;
}

.mini-switch i {
  width: 13px;
  height: 13px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.2);
  transition: transform 0.28s var(--ease-spring);
}

.mini-switch.on {
  background: var(--accent);
}

.mini-switch.ai.on {
  background: var(--ai);
}

.mini-switch.on i {
  transform: translateX(13px);
}
</style>
