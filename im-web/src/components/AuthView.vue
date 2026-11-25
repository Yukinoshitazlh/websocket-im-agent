<script setup lang="ts">
import { ref } from 'vue';
import { Moon, Sparkles, Sun } from 'lucide-vue-next';
import { useChatStore } from '../stores/chat';

const store = useChatStore();

const mode = ref<'login' | 'register'>('login');
const username = ref('alice');
const password = ref('123456');
const nickname = ref('');
const loading = ref(false);
const errorMsg = ref('');

function fillDemo(user: string) {
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
    <button class="theme-corner btn-icon" :title="store.theme === 'light' ? '切换深色模式' : '切换浅色模式'" @click="store.toggleTheme">
      <Moon v-if="store.theme === 'light'" :size="16" />
      <Sun v-else :size="16" />
    </button>

    <div class="auth-grid">
      <section class="brand-panel">
        <div class="brand-mark">
          <div class="brand-logo">P</div>
          <div>
            <div class="brand-name">PulseIM</div>
            <div class="brand-sub">Distributed WebSocket IM &amp; AI Agent Workbench</div>
          </div>
        </div>

        <h1 class="hero-title">分布式实时通信与异步 AI 协同工作台</h1>
        <p class="hero-desc">
          Netty 独立网关集群、Redis Lua 严格保序、RabbitMQ 异步 ReAct 智能体与对象存储秒传一体化演练平台。
        </p>

        <div class="feature-list">
          <div class="feature-item">
            <span class="feature-tag">IM 内核</span>
            <span>双网关跨节点定向投递 / Redis+MySQL 双重幂等 / 断线增量同步 / beforeSeq 游标分页</span>
          </div>
          <div class="feature-item">
            <span class="feature-tag">AI 引擎</span>
            <span>SSE 流式推送与秒级中断 / 人工审批挂起恢复 / Redis ZSet 延迟队列自动提醒</span>
          </div>
          <div class="feature-item">
            <span class="feature-tag">交互体验</span>
            <span>WebCrypto SHA-256 文件秒传 / 2 分钟撤回 / 引用回复 / IndexedDB 本地秒开缓存</span>
          </div>
        </div>
      </section>

      <section class="form-panel">
        <div class="tab-bar">
          <button
            type="button"
            class="tab-btn"
            :class="{ active: mode === 'login' }"
            @click="mode = 'login'; errorMsg = ''"
          >
            账号登录
          </button>
          <button
            type="button"
            class="tab-btn"
            :class="{ active: mode === 'register' }"
            @click="mode = 'register'; errorMsg = ''"
          >
            快速注册
          </button>
        </div>

        <form class="auth-form" @submit.prevent="handleSubmit">
          <label class="field">
            <span>用户名</span>
            <input v-model="username" class="input" placeholder="如 alice / bob" autocomplete="username" />
          </label>

          <label v-if="mode === 'register'" class="field">
            <span>昵称</span>
            <input v-model="nickname" class="input" placeholder="用于聊天显示的昵称" />
          </label>

          <label class="field">
            <span>密码</span>
            <input
              v-model="password"
              type="password"
              class="input"
              placeholder="默认演示密码 123456"
              autocomplete="current-password"
            />
          </label>

          <div v-if="errorMsg" class="error-box">{{ errorMsg }}</div>

          <button type="submit" class="btn btn-primary submit-btn" :disabled="loading">
            <Sparkles :size="15" />
            <span>{{ loading ? '正在进入工作台...' : mode === 'login' ? '登录进入 PulseIM' : '注册并进入' }}</span>
          </button>
        </form>

        <div class="demo-accounts">
          <span class="demo-label">演示账号一键填充（密码均为 123456）：</span>
          <div class="demo-btns">
            <button type="button" class="btn btn-ghost btn-xs" @click="fillDemo('alice')">Alice</button>
            <button type="button" class="btn btn-ghost btn-xs" @click="fillDemo('bob')">Bob</button>
            <button type="button" class="btn btn-ghost btn-xs" @click="fillDemo('charlie')">Charlie</button>
          </div>
        </div>
      </section>
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
  background: var(--canvas);
  position: relative;
}

.theme-corner {
  position: fixed;
  top: 18px;
  right: 18px;
}

.auth-grid {
  width: 100%;
  max-width: 920px;
  display: grid;
  grid-template-columns: 1.15fr 0.85fr;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 16px;
  overflow: hidden;
  box-shadow: var(--shadow-md);
}

.brand-panel {
  padding: 40px 36px;
  background: var(--rail);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: 24px;
}

.brand-mark {
  display: flex;
  align-items: center;
  gap: 12px;
}

.brand-logo {
  width: 40px;
  height: 40px;
  border-radius: 11px;
  background: var(--accent);
  color: #fff;
  font-weight: 800;
  font-size: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.brand-name {
  font-size: 18px;
  font-weight: 800;
  letter-spacing: -0.02em;
}

.brand-sub {
  font-size: 11.5px;
  color: var(--ink-muted);
}

.hero-title {
  font-size: 24px;
  line-height: 1.3;
  font-weight: 800;
  letter-spacing: -0.02em;
}

.hero-desc {
  color: var(--ink-secondary);
  font-size: 13.5px;
  line-height: 1.65;
}

.feature-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.feature-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--surface);
  border: 1px solid var(--border);
  font-size: 12.5px;
  color: var(--ink-secondary);
}

.feature-tag {
  font-size: 11px;
  font-weight: 700;
  color: var(--accent);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.form-panel {
  padding: 40px 32px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 20px;
}

.tab-bar {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
  padding: 4px;
  background: var(--rail);
  border-radius: 9px;
  border: 1px solid var(--border);
}

.tab-btn {
  border: none;
  background: transparent;
  color: var(--ink-secondary);
  font-weight: 600;
  padding: 8px;
  border-radius: 6px;
  cursor: pointer;
}

.tab-btn.active {
  background: var(--surface);
  color: var(--ink);
  box-shadow: var(--shadow-sm);
}

.auth-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink-secondary);
}

.error-box {
  padding: 8px 11px;
  border-radius: 7px;
  background: rgba(220, 38, 38, 0.1);
  color: var(--danger);
  font-size: 12.5px;
}

.submit-btn {
  width: 100%;
  padding: 10px 14px;
  margin-top: 4px;
}

.demo-accounts {
  border-top: 1px solid var(--border);
  padding-top: 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.demo-label {
  font-size: 12px;
  color: var(--ink-muted);
}

.demo-btns {
  display: flex;
  gap: 8px;
}

@media (max-width: 780px) {
  .auth-grid {
    grid-template-columns: 1fr;
  }
  .brand-panel {
    display: none;
  }
}
</style>
