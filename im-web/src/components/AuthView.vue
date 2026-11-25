<script setup lang="ts">
import { ref } from 'vue';
import { ArrowRight, Eye, EyeOff, Lock, Moon, Smile, Sun, User } from 'lucide-vue-next';
import { useChatStore } from '../stores/chat';
import { vGlow } from '../directives/glow';

const store = useChatStore();

const mode = ref<'login' | 'register'>('login');
const username = ref('');
const password = ref('');
const nickname = ref('');
const showPwd = ref(false);
const loading = ref(false);
const errorMsg = ref('');

function fillQuick(user: string) {
  mode.value = 'login';
  username.value = user;
  password.value = '123456';
  errorMsg.value = '';
}

async function handleSubmit() {
  errorMsg.value = '';
  if (!username.value.trim() || !password.value.trim()) {
    errorMsg.value = '请输入用户名和密码';
    return;
  }
  loading.value = true;
  try {
    if (mode.value === 'login') {
      await store.login(username.value.trim(), password.value);
    } else {
      await store.register(
        username.value.trim(),
        password.value,
        nickname.value.trim() || username.value.trim()
      );
    }
  } catch (e: any) {
    errorMsg.value = e.message || '认证失败';
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="auth-shell">
    <button
      type="button"
      class="theme-corner btn-icon"
      :title="store.theme === 'light' ? '切换深色模式' : '切换浅色模式'"
      @click="store.toggleTheme"
    >
      <Moon v-if="store.theme === 'light'" :size="16" />
      <Sun v-else :size="16" />
    </button>

    <div v-glow class="auth-card glass">
      <header class="auth-header">
        <div class="brand-logo">P</div>
        <h1 class="brand-name">PulseIM</h1>
        <p class="brand-tip">{{ mode === 'login' ? '欢迎回来' : '创建你的账号' }}</p>
      </header>

      <div class="tab-bar">
        <span class="tab-slider" :class="{ right: mode === 'register' }" />
        <button
          type="button"
          class="tab-btn"
          :class="{ active: mode === 'login' }"
          @click="mode = 'login'; errorMsg = ''"
        >
          登录
        </button>
        <button
          type="button"
          class="tab-btn"
          :class="{ active: mode === 'register' }"
          @click="mode = 'register'; errorMsg = ''"
        >
          注册
        </button>
      </div>

      <form class="auth-form" @submit.prevent="handleSubmit">
        <div class="input-wrap">
          <User :size="15" class="field-icon" />
          <input
            v-model="username"
            class="input with-icon"
            placeholder="用户名"
            autocomplete="username"
            autofocus
          />
        </div>

        <Transition name="slide-up">
          <div v-if="mode === 'register'" class="input-wrap">
            <Smile :size="15" class="field-icon" />
            <input
              v-model="nickname"
              class="input with-icon"
              placeholder="昵称（可选）"
            />
          </div>
        </Transition>

        <div class="input-wrap">
          <Lock :size="15" class="field-icon" />
          <input
            v-model="password"
            :type="showPwd ? 'text' : 'password'"
            class="input with-icon with-eye"
            placeholder="密码"
            autocomplete="current-password"
          />
          <button
            type="button"
            class="eye-btn"
            :title="showPwd ? '隐藏密码' : '显示密码'"
            @click="showPwd = !showPwd"
          >
            <EyeOff v-if="showPwd" :size="15" />
            <Eye v-else :size="15" />
          </button>
        </div>

        <Transition name="slide-up">
          <div v-if="errorMsg" :key="errorMsg" class="error-box">{{ errorMsg }}</div>
        </Transition>

        <button type="submit" class="btn btn-primary submit-btn" :disabled="loading">
          <span>{{ loading ? '请稍候...' : mode === 'login' ? '登录' : '注册' }}</span>
          <ArrowRight v-if="!loading" :size="15" />
        </button>
      </form>

      <footer class="quick-row">
        <span class="quick-label">快捷体验</span>
        <div class="quick-links">
          <button type="button" class="quick-btn" @click="fillQuick('alice')">Alice</button>
          <button type="button" class="quick-btn" @click="fillQuick('bob')">Bob</button>
          <button type="button" class="quick-btn" @click="fillQuick('charlie')">Charlie</button>
        </div>
      </footer>
    </div>
  </div>
</template>

<style scoped>
.auth-shell {
  min-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  position: relative;
}

.theme-corner {
  position: fixed;
  top: 20px;
  right: 20px;
  z-index: 5;
}

.auth-card {
  width: 100%;
  max-width: 388px;
  padding: 36px 30px 26px;
  border-radius: 28px;
  display: flex;
  flex-direction: column;
  gap: 22px;
  animation: rise-in 0.8s var(--ease-out) both;
}

.auth-card > * {
  position: relative;
  z-index: 1;
}

.auth-header {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: 6px;
}

.brand-logo {
  width: 50px;
  height: 50px;
  border-radius: 16px;
  background: var(--accent-grad);
  color: #fff;
  font-weight: 800;
  font-size: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 4px;
  box-shadow: 0 10px 28px rgba(58, 107, 255, 0.42), inset 0 1px 0 rgba(255, 255, 255, 0.48);
  animation: logo-breathe 4.5s ease-in-out infinite;
}

@keyframes logo-breathe {
  0%, 100% { transform: translateY(0) rotate(0); border-radius: 16px; }
  50% { transform: translateY(-3px) rotate(-3deg); border-radius: 19px 14px 18px 15px; }
}

.brand-name {
  font-size: 22px;
  font-weight: 800;
  letter-spacing: -0.02em;
}

.brand-tip {
  font-size: 13px;
  color: var(--ink-muted);
}

.tab-bar {
  position: relative;
  display: grid;
  grid-template-columns: 1fr 1fr;
  padding: 4px;
  background: var(--rail);
  border-radius: 14px;
  border: 1px solid var(--border);
}

.tab-slider {
  position: absolute;
  top: 4px;
  bottom: 4px;
  left: 4px;
  width: calc(50% - 4px);
  border-radius: 10px;
  background: var(--surface-strong);
  border: 1px solid var(--glass-edge);
  box-shadow: 0 4px 14px rgba(34, 52, 110, 0.12), inset 0 1px 0 rgba(255, 255, 255, 0.6);
  transition: transform 0.5s var(--ease-spring);
}

.tab-slider.right {
  transform: translateX(100%);
}

.tab-btn {
  position: relative;
  z-index: 1;
  border: none;
  background: transparent;
  color: var(--ink-secondary);
  font-weight: 600;
  padding: 8px;
  border-radius: 10px;
  cursor: pointer;
  transition: color 0.25s;
}

.tab-btn.active {
  color: var(--ink);
}

.auth-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.input-wrap {
  position: relative;
  display: flex;
  align-items: center;
}

.field-icon {
  position: absolute;
  left: 13px;
  color: var(--ink-muted);
  pointer-events: none;
}

.with-icon {
  padding-left: 38px;
  padding-top: 10px;
  padding-bottom: 10px;
  border-radius: 12px;
}

.with-eye {
  padding-right: 38px;
}

.eye-btn {
  position: absolute;
  right: 10px;
  border: none;
  background: transparent;
  color: var(--ink-muted);
  cursor: pointer;
  padding: 4px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: color 0.18s;
}

.eye-btn:hover {
  color: var(--ink);
}

.error-box {
  padding: 8px 12px;
  border-radius: 10px;
  background: rgba(220, 38, 38, 0.1);
  color: var(--danger);
  font-size: 12.5px;
  animation: shake 0.45s var(--ease-out);
}

@keyframes shake {
  0%, 100% { transform: translateX(0); }
  20% { transform: translateX(-6px); }
  40% { transform: translateX(5px); }
  60% { transform: translateX(-3px); }
  80% { transform: translateX(2px); }
}

.submit-btn {
  width: 100%;
  padding: 11px 16px;
  margin-top: 4px;
  border-radius: 13px;
  font-size: 14px;
}

.quick-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 14px;
  border-top: 1px solid var(--border);
}

.quick-label {
  font-size: 11.5px;
  color: var(--ink-muted);
}

.quick-links {
  display: flex;
  gap: 6px;
}

.quick-btn {
  border: 1px solid var(--border);
  background: var(--surface);
  color: var(--ink-secondary);
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 11.5px;
  cursor: pointer;
  transition: background 0.18s, color 0.18s, border-color 0.18s;
}

.quick-btn:hover {
  background: var(--accent-soft);
  border-color: var(--accent);
  color: var(--accent);
}
</style>
