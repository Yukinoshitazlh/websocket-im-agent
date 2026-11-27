<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import {
  ArrowLeft,
  ArrowRight,
  Eye,
  EyeOff,
  KeyRound,
  Lock,
  Mail,
  Moon,
  RefreshCw,
  ShieldCheck,
  Smile,
  Sun,
  Ticket,
  User
} from 'lucide-vue-next';
import { useChatStore } from '../stores/chat';
import { vGlow } from '../directives/glow';
import BrandLogo from './BrandLogo.vue';

const store = useChatStore();

const mode = ref<'login' | 'register' | 'reset'>('login');
const account = ref('');
const email = ref('');
const emailCode = ref('');
const username = ref('');
const password = ref('');
const confirmPassword = ref('');
const nickname = ref('');
const inviteCode = ref('');
const captchaId = ref('');
const captchaSvg = ref('');
const captchaCode = ref('');

const showPwd = ref(false);
const showConfirmPwd = ref(false);
const loading = ref(false);
const sendingCode = ref(false);
const cooldown = ref(0);
const errorMsg = ref('');
const infoMsg = ref('');

let cooldownTimer: number | null = null;

async function refreshCaptcha() {
  if (!store.authConfig.requireCaptcha) {
    captchaId.value = '';
    captchaSvg.value = '';
    return;
  }
  try {
    const res = await store.fetchCaptcha();
    captchaId.value = res.captchaId;
    captchaSvg.value = res.svgDataUri;
    captchaCode.value = '';
  } catch {
    // ignore transient captcha fetch error
  }
}

function switchMode(next: 'login' | 'register' | 'reset') {
  mode.value = next;
  errorMsg.value = '';
  infoMsg.value = '';
  password.value = '';
  confirmPassword.value = '';
  emailCode.value = '';
  showPwd.value = false;
  showConfirmPwd.value = false;
  refreshCaptcha();
}

function startCooldown(seconds: number) {
  cooldown.value = seconds;
  if (cooldownTimer) window.clearInterval(cooldownTimer);
  cooldownTimer = window.setInterval(() => {
    cooldown.value--;
    if (cooldown.value <= 0 && cooldownTimer) {
      window.clearInterval(cooldownTimer);
      cooldownTimer = null;
    }
  }, 1000);
}

async function handleSendEmailCode() {
  errorMsg.value = '';
  infoMsg.value = '';
  const trimmedEmail = email.value.trim();
  if (!trimmedEmail || !trimmedEmail.includes('@')) {
    errorMsg.value = '请先输入正确的邮箱地址';
    return;
  }
  if (store.authConfig.requireCaptcha && !captchaCode.value.trim()) {
    errorMsg.value = '请先输入上方图形验证码再获取邮箱验证码';
    return;
  }
  sendingCode.value = true;
  try {
    const res =
      mode.value === 'reset'
        ? await store.sendPasswordResetCode(trimmedEmail, captchaId.value, captchaCode.value.trim())
        : await store.sendEmailCode(trimmedEmail, captchaId.value, captchaCode.value.trim());
    startCooldown(res.cooldownSeconds || 60);
    if (res.devCodeHint) {
      infoMsg.value = `本地开发模式未配置 SMTP，测试验证码：${res.devCodeHint}`;
    } else {
      infoMsg.value = '验证码已发送至你的邮箱，10 分钟内有效';
    }
    refreshCaptcha();
  } catch (e: any) {
    errorMsg.value = e.message || '发送验证码失败';
    refreshCaptcha();
  } finally {
    sendingCode.value = false;
  }
}

function fillQuick(user: string) {
  mode.value = 'login';
  account.value = user;
  password.value = '123456';
  errorMsg.value = '';
  infoMsg.value = '';
}

async function handleSubmit() {
  errorMsg.value = '';
  infoMsg.value = '';
  loading.value = true;
  try {
    if (mode.value === 'login') {
      if (!account.value.trim() || !password.value.trim()) {
        errorMsg.value = '请输入邮箱或用户名和密码';
        return;
      }
      if (store.authConfig.requireCaptcha && !captchaCode.value.trim()) {
        errorMsg.value = '请输入图形验证码';
        return;
      }
      await store.login({
        account: account.value.trim(),
        password: password.value,
        captchaId: captchaId.value,
        captchaCode: captchaCode.value.trim()
      });
    } else if (mode.value === 'reset') {
      if (!email.value.trim() || !email.value.includes('@')) {
        errorMsg.value = '请输入绑定的邮箱地址';
        return;
      }
      if (!emailCode.value.trim()) {
        errorMsg.value = '请输入 6 位邮箱重置验证码';
        return;
      }
      if (password.value.length < 8) {
        errorMsg.value = '新密码长度至少 8 位，需包含字母和数字';
        return;
      }
      if (password.value !== confirmPassword.value) {
        errorMsg.value = '两次输入的新密码不一致';
        return;
      }
      await store.resetPasswordByEmail({
        email: email.value.trim(),
        emailCode: emailCode.value.trim(),
        newPassword: password.value
      });
      const resetEmail = email.value.trim();
      switchMode('login');
      account.value = resetEmail;
      infoMsg.value = '密码已重置成功，请使用新密码登录';
    } else {
      if (!email.value.trim() || !email.value.includes('@')) {
        errorMsg.value = '请输入有效的邮箱地址';
        return;
      }
      if (store.authConfig.requireEmailCode && !emailCode.value.trim()) {
        errorMsg.value = '请输入 6 位邮箱验证码';
        return;
      }
      if (!store.authConfig.requireEmailCode && store.authConfig.requireCaptcha && !captchaCode.value.trim()) {
        errorMsg.value = '请输入图形验证码';
        return;
      }
      if (password.value.length < 8) {
        errorMsg.value = '密码长度至少 8 位，需包含字母和数字';
        return;
      }
      if (store.authConfig.inviteCodeRequired && !inviteCode.value.trim()) {
        errorMsg.value = '当前为内测阶段，请输入内测邀请码';
        return;
      }
      await store.register({
        email: email.value.trim(),
        emailCode: emailCode.value.trim() || undefined,
        username: username.value.trim() || undefined,
        password: password.value,
        nickname: nickname.value.trim() || undefined,
        captchaId: captchaId.value || undefined,
        captchaCode: captchaCode.value.trim() || undefined,
        inviteCode: inviteCode.value.trim() || undefined
      });
    }
  } catch (e: any) {
    errorMsg.value = e.message || '操作失败';
    refreshCaptcha();
  } finally {
    loading.value = false;
  }
}

onMounted(async () => {
  await store.loadAuthConfig();
  await refreshCaptcha();
});

onBeforeUnmount(() => {
  if (cooldownTimer) window.clearInterval(cooldownTimer);
});
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
        <div class="brand-logo"><BrandLogo :size="56" /></div>
        <h1 class="brand-name">脉信<span class="brand-en">PulseIM</span></h1>
        <p class="brand-tip">
          {{
            mode === 'login'
              ? '欢迎回来，安全登录工作台'
              : mode === 'register'
                ? '使用邮箱创建你的账号'
                : '通过绑定邮箱重置登录密码'
          }}
        </p>
      </header>

      <Transition name="slide-up">
        <div v-if="store.authNotice" class="kick-banner">
          {{ store.authNotice }}
        </div>
      </Transition>

      <div v-if="mode !== 'reset'" class="tab-bar">
        <span class="tab-slider" :class="{ right: mode === 'register' }" />
        <button
          type="button"
          class="tab-btn"
          :class="{ active: mode === 'login' }"
          @click="switchMode('login')"
        >
          登录
        </button>
        <button
          type="button"
          class="tab-btn"
          :class="{ active: mode === 'register' }"
          @click="switchMode('register')"
        >
          邮箱注册
        </button>
      </div>

      <div v-else class="reset-bar">
        <button type="button" class="back-login-btn" @click="switchMode('login')">
          <ArrowLeft :size="14" />
          <span>返回登录</span>
        </button>
        <span class="reset-title">找回密码</span>
      </div>

      <form class="auth-form" @submit.prevent="handleSubmit">
        <!-- 登录：邮箱或用户名 -->
        <div v-if="mode === 'login'" class="input-wrap">
          <User :size="15" class="field-icon" />
          <input
            v-model="account"
            class="input with-icon"
            placeholder="邮箱或用户名"
            autocomplete="username"
            autofocus
          />
        </div>

        <!-- 注册 / 找回密码：邮箱地址 -->
        <div v-if="mode === 'register' || mode === 'reset'" class="input-wrap">
          <Mail :size="15" class="field-icon" />
          <input
            v-model="email"
            type="email"
            class="input with-icon"
            :placeholder="mode === 'reset' ? '输入账号绑定的邮箱地址' : '邮箱地址'"
            autocomplete="email"
            autofocus
          />
        </div>

        <!-- 图形验证码（登录或发送邮箱验证码防刷） -->
        <div v-if="store.authConfig.requireCaptcha" class="captcha-row">
          <div class="input-wrap flex-1">
            <ShieldCheck :size="15" class="field-icon" />
            <input
              v-model="captchaCode"
              class="input with-icon"
              placeholder="图形验证码"
              maxlength="6"
              autocomplete="off"
            />
          </div>
          <button
            type="button"
            class="captcha-box"
            title="点击刷新验证码"
            @click="refreshCaptcha"
          >
            <img v-if="captchaSvg" :src="captchaSvg" alt="图形验证码" />
            <RefreshCw v-else :size="15" />
          </button>
        </div>

        <!-- 注册 / 找回密码：6 位邮箱验证码 -->
        <div
          v-if="mode === 'reset' || (mode === 'register' && store.authConfig.requireEmailCode)"
          class="captcha-row"
        >
          <div class="input-wrap flex-1">
            <KeyRound :size="15" class="field-icon" />
            <input
              v-model="emailCode"
              class="input with-icon"
              placeholder="6 位邮箱验证码"
              maxlength="6"
              autocomplete="one-time-code"
            />
          </div>
          <button
            type="button"
            class="btn btn-ghost send-code-btn"
            :disabled="sendingCode || cooldown > 0"
            @click="handleSendEmailCode"
          >
            {{ cooldown > 0 ? `${cooldown}s` : sendingCode ? '发送中' : '获取验证码' }}
          </button>
        </div>

        <!-- 密码 -->
        <div class="input-wrap">
          <Lock :size="15" class="field-icon" />
          <input
            v-model="password"
            :type="showPwd ? 'text' : 'password'"
            class="input with-icon with-eye"
            :placeholder="
              mode === 'login'
                ? '密码'
                : mode === 'reset'
                  ? '新密码（至少 8 位，含字母与数字）'
                  : '密码（至少 8 位，含字母与数字）'
            "
            :autocomplete="mode === 'login' ? 'current-password' : 'new-password'"
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

        <!-- 找回密码：确认新密码 -->
        <div v-if="mode === 'reset'" class="input-wrap">
          <Lock :size="15" class="field-icon" />
          <input
            v-model="confirmPassword"
            :type="showConfirmPwd ? 'text' : 'password'"
            class="input with-icon with-eye"
            placeholder="再次确认新密码"
            autocomplete="new-password"
          />
          <button
            type="button"
            class="eye-btn"
            :title="showConfirmPwd ? '隐藏密码' : '显示密码'"
            @click="showConfirmPwd = !showConfirmPwd"
          >
            <EyeOff v-if="showConfirmPwd" :size="15" />
            <Eye v-else :size="15" />
          </button>
        </div>

        <!-- 注册附加信息：昵称 & 可选用户名 -->
        <div v-if="mode === 'register'" class="split-row">
          <div class="input-wrap flex-1">
            <Smile :size="15" class="field-icon" />
            <input v-model="nickname" class="input with-icon" placeholder="昵称（可选）" />
          </div>
          <div class="input-wrap flex-1">
            <User :size="15" class="field-icon" />
            <input v-model="username" class="input with-icon" placeholder="用户名（可选）" />
          </div>
        </div>

        <!-- 可选内测邀请码 -->
        <div v-if="mode === 'register' && store.authConfig.inviteCodeRequired" class="input-wrap">
          <Ticket :size="15" class="field-icon" />
          <input
            v-model="inviteCode"
            class="input with-icon"
            placeholder="内测邀请码"
            autocomplete="off"
          />
        </div>

        <div v-if="mode === 'login'" class="forgot-row">
          <button type="button" class="forgot-link" @click="switchMode('reset')">忘记密码？</button>
        </div>

        <Transition name="slide-up">
          <div v-if="infoMsg" :key="infoMsg" class="info-box">{{ infoMsg }}</div>
        </Transition>

        <Transition name="slide-up">
          <div v-if="errorMsg" :key="errorMsg" class="error-box">{{ errorMsg }}</div>
        </Transition>

        <button type="submit" class="btn btn-primary submit-btn" :disabled="loading">
          <span>
            {{
              loading
                ? '请稍候...'
                : mode === 'login'
                  ? '登录'
                  : mode === 'register'
                    ? '创建账号'
                    : '确认重置密码'
            }}
          </span>
          <ArrowRight v-if="!loading" :size="15" />
        </button>
      </form>

      <!-- 仅在开发环境 allowDemoQuickLogin=true 时展示快捷演示入口 -->
      <footer v-if="mode === 'login' && store.authConfig.allowDemoQuickLogin" class="quick-row">
        <span class="quick-label">本地快捷体验</span>
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
  padding: 20px;
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
  max-width: 416px;
  padding: 34px 30px 26px;
  border-radius: 28px;
  display: flex;
  flex-direction: column;
  gap: 18px;
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
  width: 56px;
  height: 56px;
  border-radius: 16px;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 4px;
  box-shadow: 0 10px 28px rgba(58, 107, 255, 0.42), inset 0 1px 0 rgba(255, 255, 255, 0.48);
  animation: logo-breathe 4.5s ease-in-out infinite;
}

@keyframes logo-breathe {
  0%, 100% { transform: translateY(0) rotate(0); }
  50% { transform: translateY(-3px) rotate(-3deg); }
}

.brand-name {
  display: inline-flex;
  align-items: baseline;
  gap: 8px;
  font-size: 22px;
  font-weight: 800;
  letter-spacing: 0.04em;
}

.brand-en {
  font-size: 14px;
  font-weight: 700;
  letter-spacing: -0.01em;
  background: var(--accent-grad);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
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

.reset-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 2px;
}

.back-login-btn {
  border: none;
  background: transparent;
  color: var(--accent);
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.reset-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--ink);
}

.auth-form {
  display: flex;
  flex-direction: column;
  gap: 11px;
}

.captcha-row,
.split-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.flex-1 {
  flex: 1;
  min-width: 0;
}

.captcha-box {
  width: 118px;
  height: 40px;
  flex-shrink: 0;
  border-radius: 11px;
  border: 1px solid var(--border);
  background: var(--surface-strong);
  padding: 0;
  overflow: hidden;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.captcha-box img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.send-code-btn {
  height: 40px;
  min-width: 104px;
  flex-shrink: 0;
  border-radius: 11px;
  font-size: 12.5px;
  padding: 0 12px;
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
  right: 7px;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--ink-secondary);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: color 0.18s, background 0.18s;
}

.eye-btn:hover {
  color: var(--ink);
  background: var(--hover);
}

.forgot-row {
  display: flex;
  justify-content: flex-end;
  margin-top: -4px;
}

.forgot-link {
  border: none;
  background: transparent;
  color: var(--accent);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.forgot-link:hover {
  text-decoration: underline;
}

.info-box {
  padding: 8px 12px;
  border-radius: 10px;
  background: rgba(58, 107, 255, 0.1);
  color: var(--accent);
  font-size: 12.5px;
}

.kick-banner {
  padding: 9px 12px;
  border-radius: 12px;
  background: rgba(245, 158, 11, 0.14);
  border: 1px solid rgba(245, 158, 11, 0.35);
  color: #b45309;
  font-size: 12.5px;
  line-height: 1.45;
  text-align: center;
}

[data-theme='dark'] .kick-banner {
  color: #fbbf24;
  background: rgba(245, 158, 11, 0.16);
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

@media (max-width: 520px) {
  .auth-card {
    padding: 26px 20px 22px;
    border-radius: 22px;
  }
  .split-row {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
