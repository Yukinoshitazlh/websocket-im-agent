<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import {
  Ban,
  Bell,
  BellOff,
  Camera,
  Check,
  ChevronDown,
  ChevronUp,
  Copy,
  Cpu,
  Eye,
  EyeOff,
  FlaskConical,
  KeyRound,
  Mail,
  MessageCircle,
  Moon,
  RotateCcw,
  Sun,
  UserCheck,
  UserMinus,
  UserPlus,
  Volume2,
  VolumeX,
  X
} from 'lucide-vue-next';
import { useChatStore } from '../stores/chat';
import type { UserProfile } from '../types/im';
import UserAvatar from './UserAvatar.vue';
import { playMessageChime } from '../utils/sound';

const STATUS_PRESETS = [
  { emoji: '🟢', text: '在线办公' },
  { emoji: '💻', text: '专注编码中' },
  { emoji: '📅', text: '正在开会' },
  { emoji: '☕', text: '午休茶歇中' },
  { emoji: '🎧', text: '专注勿扰' },
  { emoji: '🏠', text: '下班啦' }
];

const props = defineProps<{
  profile: UserProfile;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
}>();

const store = useChatStore();

const isSelf = computed(
  () => Boolean(store.currentUser && String(props.profile.id) === String(store.currentUser.id))
);

const formNickname = ref('');
const formAvatar = ref('');
const formSignature = ref('');
const formBio = ref('');
const formStatusEmoji = ref('');
const formStatusText = ref('');

const uploadingAvatar = ref(false);
const savingProfile = ref(false);

// 添加/更换绑定邮箱状态
const showEmailBox = ref(false);
const formEmail = ref('');
const emailVerifyCode = ref('');
const emailCurrentPwd = ref('');
const showEmailPwd = ref(false);
const sendingEmailCode = ref(false);
const emailCooldown = ref(0);
const emailDevHint = ref('');
const savingEmail = ref(false);
let emailCooldownTimer: number | null = null;

// 修改登录密码状态（每个输入框独立显隐开关）
const showPasswordBox = ref(false);
const oldPassword = ref('');
const newPassword = ref('');
const confirmPassword = ref('');
const showOldPwd = ref(false);
const showNewPwd = ref(false);
const showConfirmPwd = ref(false);
const savingPassword = ref(false);

// 自定义 AI 大模型（BYOK）状态
const showLlmBox = ref(false);
const llmEnabled = ref(false);
const llmBaseUrl = ref('https://api.deepseek.com/v1');
const llmModel = ref('deepseek-chat');
const llmApiKey = ref('');
const llmKeyMask = ref('');
const llmHasKey = ref(false);
const showLlmKey = ref(false);
const savingLlm = ref(false);

// 他人名片好友申请与关系操作
const friendRemark = ref('');
const relationBusy = ref(false);

const avatarInputRef = ref<HTMLInputElement | null>(null);

async function syncMyLlmConfig() {
  if (!isSelf.value) return;
  const cfg = await store.loadMyLlmConfig();
  if (cfg) {
    llmEnabled.value = cfg.enabled;
    llmBaseUrl.value = cfg.baseUrl || 'https://api.deepseek.com/v1';
    llmModel.value = cfg.model || 'deepseek-chat';
    llmKeyMask.value = cfg.apiKeyMask || '';
    llmHasKey.value = cfg.hasApiKey;
  }
}

watch(
  () => props.profile,
  (p) => {
    formNickname.value = p.nickname || '';
    formAvatar.value = p.avatar || '';
    formSignature.value = p.signature || '';
    formBio.value = p.bio || '';
    formStatusEmoji.value = p.statusEmoji || '';
    formStatusText.value = p.statusText || '';
    showEmailBox.value = false;
    formEmail.value = '';
    emailVerifyCode.value = '';
    emailCurrentPwd.value = '';
    showEmailPwd.value = false;
    emailDevHint.value = '';
    showPasswordBox.value = false;
    oldPassword.value = '';
    newPassword.value = '';
    confirmPassword.value = '';
    showOldPwd.value = false;
    showNewPwd.value = false;
    showConfirmPwd.value = false;
    showLlmBox.value = false;
    llmApiKey.value = '';
    showLlmKey.value = false;
    friendRemark.value = '';
    if (isSelf.value) {
      syncMyLlmConfig();
    }
  },
  { immediate: true }
);

onBeforeUnmount(() => {
  if (emailCooldownTimer) window.clearInterval(emailCooldownTimer);
});

function pickStatusPreset(emoji: string, text: string) {
  formStatusEmoji.value = emoji;
  formStatusText.value = text;
}

function clearStatus() {
  formStatusEmoji.value = '';
  formStatusText.value = '';
}

function triggerAvatarPick() {
  if (!isSelf.value) {
    if (props.profile.avatar) {
      store.lightboxUrl = props.profile.avatar;
    }
    return;
  }
  avatarInputRef.value?.click();
}

async function handleAvatarSelected(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;
  if (!file.type.startsWith('image/')) {
    store.pushToast('请选择图片格式文件作为头像', 'error');
    return;
  }
  uploadingAvatar.value = true;
  try {
    const url = await store.uploadAvatarFile(file);
    formAvatar.value = url;
    store.pushToast('头像已上传，点击「保存资料」即可生效', 'success');
  } catch (err: any) {
    store.pushToast(err.message || '头像上传失败', 'error');
  } finally {
    uploadingAvatar.value = false;
  }
}

async function handleSaveProfile() {
  if (!formNickname.value.trim()) {
    store.pushToast('昵称不能为空', 'error');
    return;
  }
  savingProfile.value = true;
  try {
    await store.updateMyProfile({
      nickname: formNickname.value.trim(),
      avatar: formAvatar.value.trim(),
      signature: formSignature.value.trim(),
      bio: formBio.value.trim(),
      statusEmoji: formStatusEmoji.value.trim(),
      statusText: formStatusText.value.trim()
    });
    emit('close');
  } catch (err: any) {
    store.pushToast(err.message || '保存失败', 'error');
  } finally {
    savingProfile.value = false;
  }
}

function startEmailCooldown(seconds: number) {
  emailCooldown.value = seconds;
  if (emailCooldownTimer) window.clearInterval(emailCooldownTimer);
  emailCooldownTimer = window.setInterval(() => {
    emailCooldown.value--;
    if (emailCooldown.value <= 0 && emailCooldownTimer) {
      window.clearInterval(emailCooldownTimer);
      emailCooldownTimer = null;
    }
  }, 1000);
}

async function handleSendBindEmailCode() {
  const email = formEmail.value.trim();
  if (!email || !email.includes('@')) {
    store.pushToast('请先输入正确的邮箱地址', 'error');
    return;
  }
  sendingEmailCode.value = true;
  emailDevHint.value = '';
  try {
    const res = await store.sendBindEmailCode(email);
    startEmailCooldown(res.cooldownSeconds || 60);
    if (res.devCodeHint) {
      emailDevHint.value = `本地开发模式未配置 SMTP，测试验证码：${res.devCodeHint}`;
    } else {
      store.pushToast('验证码已发送至新邮箱，10 分钟内有效', 'success');
    }
  } catch (err: any) {
    store.pushToast(err.message || '发送验证码失败', 'error');
  } finally {
    sendingEmailCode.value = false;
  }
}

async function handleUpdateEmail() {
  const email = formEmail.value.trim();
  if (!email || !email.includes('@')) {
    store.pushToast('请输入正确的邮箱地址', 'error');
    return;
  }
  if (store.authConfig.requireEmailCode && !emailVerifyCode.value.trim()) {
    store.pushToast('请输入 6 位邮箱验证码', 'error');
    return;
  }
  if (!emailCurrentPwd.value) {
    store.pushToast('请输入当前登录密码以验证身份', 'error');
    return;
  }
  savingEmail.value = true;
  try {
    await store.updateMyEmail({
      email,
      emailCode: emailVerifyCode.value.trim() || undefined,
      currentPassword: emailCurrentPwd.value
    });
    formEmail.value = '';
    emailVerifyCode.value = '';
    emailCurrentPwd.value = '';
    emailDevHint.value = '';
    showEmailPwd.value = false;
    showEmailBox.value = false;
  } catch (err: any) {
    store.pushToast(err.message || '更新邮箱失败', 'error');
  } finally {
    savingEmail.value = false;
  }
}

async function handleChangePassword() {
  if (!oldPassword.value || !newPassword.value) {
    store.pushToast('请填写原密码与新密码', 'error');
    return;
  }
  if (newPassword.value.length < 8) {
    store.pushToast('新密码长度至少 8 位，且需包含字母与数字', 'error');
    return;
  }
  if (newPassword.value !== confirmPassword.value) {
    store.pushToast('两次输入的新密码不一致', 'error');
    return;
  }
  savingPassword.value = true;
  try {
    await store.changeMyPassword(oldPassword.value, newPassword.value);
    oldPassword.value = '';
    newPassword.value = '';
    confirmPassword.value = '';
    showOldPwd.value = false;
    showNewPwd.value = false;
    showConfirmPwd.value = false;
    showPasswordBox.value = false;
  } catch (err: any) {
    store.pushToast(err.message || '修改密码失败', 'error');
  } finally {
    savingPassword.value = false;
  }
}

async function copyUsername() {
  try {
    await navigator.clipboard.writeText(props.profile.username);
    store.pushToast(`已复制账号 @${props.profile.username}`);
  } catch {
    store.pushToast('复制失败', 'error');
  }
}

async function handleSaveLlmConfig(clearKey = false) {
  savingLlm.value = true;
  try {
    const saved = await store.saveMyLlmConfig(
      clearKey
        ? { clearApiKey: true }
        : {
            enabled: llmEnabled.value,
            baseUrl: llmBaseUrl.value.trim(),
            model: llmModel.value.trim(),
            apiKey: llmApiKey.value.trim() || undefined
          }
    );
    if (saved) {
      llmEnabled.value = saved.enabled;
      llmBaseUrl.value = saved.baseUrl;
      llmModel.value = saved.model;
      llmKeyMask.value = saved.apiKeyMask;
      llmHasKey.value = saved.hasApiKey;
      llmApiKey.value = '';
      showLlmKey.value = false;
    }
  } catch (err: any) {
    store.pushToast(err.message || '保存自定义 AI 配置失败', 'error');
  } finally {
    savingLlm.value = false;
  }
}

async function handleSendFriendReq() {
  relationBusy.value = true;
  try {
    await store.sendFriendRequest(props.profile.id, friendRemark.value.trim());
    friendRemark.value = '';
  } catch (err: any) {
    store.pushToast(err.message || '发送好友申请失败', 'error');
  } finally {
    relationBusy.value = false;
  }
}

async function handleAcceptFriendReq() {
  if (!props.profile.pendingRequestId) return;
  relationBusy.value = true;
  try {
    await store.acceptFriendRequest(props.profile.pendingRequestId);
    emit('close');
  } catch (err: any) {
    store.pushToast(err.message || '同意好友申请失败', 'error');
  } finally {
    relationBusy.value = false;
  }
}

async function handleDeleteFriend() {
  if (!window.confirm(`确定解除与「${props.profile.nickname}」的好友关系吗？`)) return;
  relationBusy.value = true;
  try {
    await store.deleteFriend(props.profile.id);
  } catch (err: any) {
    store.pushToast(err.message || '删除好友失败', 'error');
  } finally {
    relationBusy.value = false;
  }
}

async function handleBlockUser() {
  if (!window.confirm(`拉黑「${props.profile.nickname}」后将拒收对方私聊与好友申请，确认继续？`)) return;
  relationBusy.value = true;
  try {
    await store.blockUser(props.profile.id);
  } catch (err: any) {
    store.pushToast(err.message || '拉黑失败', 'error');
  } finally {
    relationBusy.value = false;
  }
}

async function handleUnblockUser() {
  relationBusy.value = true;
  try {
    await store.unblockUser(props.profile.id);
  } catch (err: any) {
    store.pushToast(err.message || '解除拉黑失败', 'error');
  } finally {
    relationBusy.value = false;
  }
}

async function startDirectChat() {
  try {
    await store.startDirect(props.profile.id);
    store.setNavTab('chats');
    emit('close');
  } catch (err: any) {
    store.pushToast(err.message || '打开私聊失败', 'error');
  }
}

function previewSound() {
  playMessageChime();
}
</script>

<template>
  <div class="modal-backdrop" @click.self="emit('close')">
    <div class="modal-box profile-modal">
      <!-- 顶部液态流光头图 -->
      <div class="profile-banner">
        <button type="button" class="banner-close" title="关闭" @click="emit('close')">
          <X :size="16" />
        </button>

        <div class="banner-identity">
          <div class="avatar-editor" @click="triggerAvatarPick">
            <UserAvatar
              :name="isSelf ? (formNickname || profile.nickname) : profile.nickname"
              :seed="profile.username"
              :src="isSelf ? formAvatar : profile.avatar"
              size="xl"
              :online="isSelf ? store.wsState === 'connected' : Boolean(profile.online)"
              :is-ai="profile.userType === 2"
              clickable
            />
            <div v-if="isSelf" class="avatar-mask">
              <Camera :size="18" />
              <span>{{ uploadingAvatar ? '上传中' : '换头像' }}</span>
            </div>
            <input
              ref="avatarInputRef"
              type="file"
              accept="image/*"
              hidden
              @change="handleAvatarSelected"
            />
          </div>

          <div class="identity-text">
            <div class="name-line">
              <h3>{{ isSelf ? (formNickname || profile.nickname) : profile.nickname }}</h3>
              <span
                v-if="(isSelf ? formStatusText : profile.statusText)"
                class="status-chip"
              >
                {{ isSelf ? formStatusEmoji : profile.statusEmoji }}
                {{ isSelf ? formStatusText : profile.statusText }}
              </span>
            </div>

            <div class="handle-line">
              <span>@{{ profile.username }}</span>
              <button type="button" class="copy-id-btn" title="复制账号给好友搜索" @click="copyUsername">
                <Copy :size="11" />
                <span>复制账号</span>
              </button>
              <button
                v-if="isSelf && formAvatar"
                type="button"
                class="copy-id-btn"
                title="恢复默认字母头像"
                @click="formAvatar = ''"
              >
                <RotateCcw :size="11" />
                <span>恢复默认</span>
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- 模式 A：自己的个人资料与工作台偏好设置 -->
      <div v-if="isSelf" class="profile-body">
        <!-- 飞书/微信同款：当前状态快选 -->
        <section class="section">
          <div class="section-label">
            <span>我的状态（好友和群成员可见）</span>
            <button v-if="formStatusText || formStatusEmoji" type="button" class="text-link" @click="clearStatus">
              清除状态
            </button>
          </div>
          <div class="status-grid">
            <button
              v-for="item in STATUS_PRESETS"
              :key="item.text"
              type="button"
              class="preset-pill"
              :class="{ active: formStatusText === item.text }"
              @click="pickStatusPreset(item.emoji, item.text)"
            >
              <span>{{ item.emoji }}</span>
              <span>{{ item.text }}</span>
            </button>
          </div>
          <div class="custom-status-row">
            <input
              v-model="formStatusEmoji"
              class="input emoji-input"
              maxlength="4"
              placeholder="✨"
              title="自定义状态图标"
            />
            <input
              v-model="formStatusText"
              class="input"
              maxlength="24"
              placeholder="自定义今日状态，如：冲刺秋招简历中..."
            />
          </div>

          <button
            type="button"
            class="presence-entry-row"
            @click="emit('close'); store.showPresenceModal = true"
          >
            <span class="pe-left">
              <EyeOff v-if="store.presenceSettings.mode !== 'ALL'" :size="14" class="pe-ic stealth" />
              <Eye v-else :size="14" class="pe-ic" />
              <span>在线状态与隐身权限</span>
            </span>
            <span class="pe-right">
              <span class="pe-badge" :class="{ stealth: store.presenceSettings.mode !== 'ALL' }">
                {{ store.presenceSummaryText }}
              </span>
              <span class="pe-link">设置</span>
            </span>
          </button>
        </section>

        <!-- 基本资料编辑 -->
        <section class="section">
          <div class="field">
            <label>昵称</label>
            <input v-model="formNickname" class="input" maxlength="32" placeholder="展示在聊天中的昵称" />
          </div>

          <div class="field">
            <label>个性签名</label>
            <input
              v-model="formSignature"
              class="input"
              maxlength="100"
              placeholder="写一句展示在个人名片上的签名..."
            />
          </div>

          <div class="field">
            <label>个人简介 / 岗位与技术栈</label>
            <textarea
              v-model="formBio"
              rows="2"
              class="input bio-area"
              maxlength="220"
              placeholder="例如：Java 后端开发 | Netty / Redis / 分布式 IM 与 AI Agent"
            />
          </div>
        </section>

        <!-- 日常体验偏好开关 -->
        <section class="section prefs-box">
          <div class="pref-row">
            <div class="pref-info">
              <Volume2 v-if="store.soundEnabled" :size="15" />
              <VolumeX v-else :size="15" />
              <span>新消息清脆水滴提示音</span>
            </div>
            <div class="pref-actions">
              <button type="button" class="text-link" @click="previewSound">试听</button>
              <button
                type="button"
                class="switch-btn"
                :class="{ on: store.soundEnabled }"
                @click="store.toggleSound"
              >
                <span class="switch-knob" />
              </button>
            </div>
          </div>

          <div class="pref-row">
            <div class="pref-info">
              <Bell v-if="store.desktopNotifyEnabled" :size="15" />
              <BellOff v-else :size="15" />
              <span>浏览器系统桌面通知（切后台提醒）</span>
            </div>
            <button
              type="button"
              class="switch-btn"
              :class="{ on: store.desktopNotifyEnabled }"
              @click="store.toggleDesktopNotify"
            >
              <span class="switch-knob" />
            </button>
          </div>

          <div class="pref-row">
            <div class="pref-info">
              <Moon v-if="store.theme === 'dark'" :size="15" />
              <Sun v-else :size="15" />
              <span>深色暗夜液态玻璃</span>
            </div>
            <button
              type="button"
              class="switch-btn"
              :class="{ on: store.theme === 'dark' }"
              @click="store.toggleTheme"
            >
              <span class="switch-knob" />
            </button>
          </div>

          <div v-if="store.authConfig.showDemoMode" class="pref-row">
            <div class="pref-info">
              <FlaskConical :size="15" />
              <span>面试演练模式（双网关 / 断网 / 幂等）</span>
            </div>
            <button
              type="button"
              class="switch-btn"
              :class="{ on: store.demoMode }"
              @click="store.toggleDemoMode"
            >
              <span class="switch-knob" />
            </button>
          </div>
        </section>

        <!-- 折叠式：添加 / 更换绑定邮箱 -->
        <section class="section">
          <button
            type="button"
            class="collapse-head"
            @click="showEmailBox = !showEmailBox"
          >
            <span class="collapse-left">
              <Mail :size="14" />
              <span>{{ profile.email ? '更换绑定邮箱' : '添加绑定邮箱' }}</span>
            </span>
            <span class="collapse-right">
              <span class="email-badge" :class="{ empty: !profile.email }">
                {{ profile.email || '未绑定' }}
              </span>
              <ChevronUp v-if="showEmailBox" :size="15" />
              <ChevronDown v-else :size="15" />
            </span>
          </button>

          <Transition name="slide-up">
            <div v-if="showEmailBox" class="pwd-panel">
              <input
                v-model="formEmail"
                type="email"
                class="input"
                :placeholder="profile.email ? '输入要更换的新邮箱地址' : '输入要绑定的邮箱地址'"
                autocomplete="email"
              />

              <div v-if="store.authConfig.requireEmailCode" class="email-code-row">
                <input
                  v-model="emailVerifyCode"
                  class="input flex-1"
                  maxlength="6"
                  placeholder="输入 6 位邮箱验证码"
                  autocomplete="one-time-code"
                />
                <button
                  type="button"
                  class="btn btn-ghost btn-xs send-email-btn"
                  :disabled="sendingEmailCode || emailCooldown > 0"
                  @click="handleSendBindEmailCode"
                >
                  {{ emailCooldown > 0 ? `${emailCooldown}s` : sendingEmailCode ? '发送中' : '获取验证码' }}
                </button>
              </div>

              <div v-if="emailDevHint" class="dev-code-tip">{{ emailDevHint }}</div>

              <div class="pwd-input-wrap">
                <input
                  v-model="emailCurrentPwd"
                  :type="showEmailPwd ? 'text' : 'password'"
                  class="input with-eye"
                  placeholder="输入当前登录密码验证身份"
                  autocomplete="current-password"
                />
                <button
                  type="button"
                  class="pwd-eye-btn"
                  :title="showEmailPwd ? '隐藏密码' : '显示密码'"
                  @click="showEmailPwd = !showEmailPwd"
                >
                  <EyeOff v-if="showEmailPwd" :size="15" />
                  <Eye v-else :size="15" />
                </button>
              </div>

              <button
                type="button"
                class="btn btn-ghost btn-xs"
                :disabled="savingEmail"
                @click="handleUpdateEmail"
              >
                {{ savingEmail ? '提交中...' : profile.email ? '确认更换邮箱' : '确认绑定邮箱' }}
              </button>
            </div>
          </Transition>
        </section>

        <!-- 折叠式：自定义 AI 大模型（BYOK，AES-256-GCM 加密存储） -->
        <section class="section">
          <button
            type="button"
            class="collapse-head"
            @click="showLlmBox = !showLlmBox"
          >
            <span class="collapse-left">
              <Cpu :size="14" />
              <span>自定义 AI 大模型（BYOK）</span>
            </span>
            <span class="collapse-right">
              <span class="email-badge" :class="{ empty: !llmEnabled }">
                {{ llmEnabled ? (llmModel || '已启用') : '公共额度' }}
              </span>
              <ChevronUp v-if="showLlmBox" :size="15" />
              <ChevronDown v-else :size="15" />
            </span>
          </button>

          <Transition name="slide-up">
            <div v-if="showLlmBox" class="pwd-panel">
              <div class="pref-row">
                <span class="byok-tip">启用自带 API Key（不受全站公共配额限制）</span>
                <button
                  type="button"
                  class="switch-btn"
                  :class="{ on: llmEnabled }"
                  @click="llmEnabled = !llmEnabled"
                >
                  <span class="switch-knob" />
                </button>
              </div>

              <input
                v-model="llmBaseUrl"
                class="input"
                placeholder="OpenAI 兼容 Base URL，如 https://api.deepseek.com/v1"
              />
              <input
                v-model="llmModel"
                class="input"
                placeholder="模型名称，如 deepseek-chat / gpt-4o-mini"
              />
              <div class="pwd-input-wrap">
                <input
                  v-model="llmApiKey"
                  :type="showLlmKey ? 'text' : 'password'"
                  class="input with-eye"
                  :placeholder="llmHasKey ? `已加密保存（${llmKeyMask}），留空则沿用原 Key` : '输入你的 API Key（AES-256-GCM 加密存储）'"
                  autocomplete="off"
                />
                <button
                  type="button"
                  class="pwd-eye-btn"
                  :title="showLlmKey ? '隐藏 Key' : '显示 Key'"
                  @click="showLlmKey = !showLlmKey"
                >
                  <EyeOff v-if="showLlmKey" :size="15" />
                  <Eye v-else :size="15" />
                </button>
              </div>

              <div class="byok-actions">
                <button
                  v-if="llmHasKey"
                  type="button"
                  class="btn btn-ghost btn-xs danger-text"
                  :disabled="savingLlm"
                  @click="handleSaveLlmConfig(true)"
                >
                  清除 Key
                </button>
                <button
                  type="button"
                  class="btn btn-primary btn-xs"
                  :disabled="savingLlm"
                  @click="handleSaveLlmConfig(false)"
                >
                  {{ savingLlm ? '保存中...' : '保存 AI 配置' }}
                </button>
              </div>
            </div>
          </Transition>
        </section>

        <!-- 折叠式：修改登录密码 -->
        <section class="section">
          <button
            type="button"
            class="collapse-head"
            @click="showPasswordBox = !showPasswordBox"
          >
            <span class="collapse-left">
              <KeyRound :size="14" />
              <span>修改登录密码</span>
            </span>
            <ChevronUp v-if="showPasswordBox" :size="15" />
            <ChevronDown v-else :size="15" />
          </button>

          <Transition name="slide-up">
            <div v-if="showPasswordBox" class="pwd-panel">
              <div class="pwd-input-wrap">
                <input
                  v-model="oldPassword"
                  :type="showOldPwd ? 'text' : 'password'"
                  class="input with-eye"
                  placeholder="输入当前原密码"
                  autocomplete="current-password"
                />
                <button
                  type="button"
                  class="pwd-eye-btn"
                  :title="showOldPwd ? '隐藏密码' : '显示密码'"
                  @click="showOldPwd = !showOldPwd"
                >
                  <EyeOff v-if="showOldPwd" :size="15" />
                  <Eye v-else :size="15" />
                </button>
              </div>

              <div class="pwd-input-wrap">
                <input
                  v-model="newPassword"
                  :type="showNewPwd ? 'text' : 'password'"
                  class="input with-eye"
                  placeholder="输入新密码（至少 8 位，含字母与数字）"
                  autocomplete="new-password"
                />
                <button
                  type="button"
                  class="pwd-eye-btn"
                  :title="showNewPwd ? '隐藏密码' : '显示密码'"
                  @click="showNewPwd = !showNewPwd"
                >
                  <EyeOff v-if="showNewPwd" :size="15" />
                  <Eye v-else :size="15" />
                </button>
              </div>

              <div class="pwd-input-wrap">
                <input
                  v-model="confirmPassword"
                  :type="showConfirmPwd ? 'text' : 'password'"
                  class="input with-eye"
                  placeholder="再次确认新密码"
                  autocomplete="new-password"
                />
                <button
                  type="button"
                  class="pwd-eye-btn"
                  :title="showConfirmPwd ? '隐藏密码' : '显示密码'"
                  @click="showConfirmPwd = !showConfirmPwd"
                >
                  <EyeOff v-if="showConfirmPwd" :size="15" />
                  <Eye v-else :size="15" />
                </button>
              </div>

              <button
                type="button"
                class="btn btn-ghost btn-xs"
                :disabled="savingPassword"
                @click="handleChangePassword"
              >
                {{ savingPassword ? '提交中...' : '确认修改密码' }}
              </button>
            </div>
          </Transition>
        </section>

        <div class="modal-footer">
          <button type="button" class="btn btn-ghost" @click="emit('close')">取消</button>
          <button
            type="button"
            class="btn btn-primary"
            :disabled="savingProfile || uploadingAvatar"
            @click="handleSaveProfile"
          >
            <Check :size="15" />
            <span>{{ savingProfile ? '保存中...' : '保存个人资料' }}</span>
          </button>
        </div>
      </div>

      <!-- 模式 B：查看他人个人名片 -->
      <div v-else class="profile-body">
        <div class="card-info-list">
          <div class="card-info-row">
            <span class="card-label">在线状态</span>
            <span class="card-val" :class="{ online: profile.online }">
              {{ profile.userType === 2 ? 'AI 全天候在线' : profile.online ? '当前在线' : '离线' }}
            </span>
          </div>

          <div class="card-info-row">
            <span class="card-label">个性签名</span>
            <span class="card-val">{{ profile.signature || '这个人很低调，还没写个性签名' }}</span>
          </div>

          <div class="card-info-row">
            <span class="card-label">个人简介</span>
            <span class="card-val">{{ profile.bio || '暂无简介' }}</span>
          </div>
        </div>

        <div v-if="profile.userType !== 2" class="relation-footer">
          <!-- 非好友：支持附言发送好友申请 -->
          <div v-if="!profile.relationStatus || profile.relationStatus === 'NONE'" class="add-friend-box">
            <input
              v-model="friendRemark"
              class="input"
              maxlength="80"
              placeholder="附言（可选）：你好，我是..."
            />
            <div class="modal-footer">
              <button
                type="button"
                class="btn btn-ghost btn-xs danger-text"
                :disabled="relationBusy"
                @click="handleBlockUser"
              >
                <Ban :size="13" />
                <span>拉黑</span>
              </button>
              <button
                type="button"
                class="btn btn-primary"
                :disabled="relationBusy"
                @click="handleSendFriendReq"
              >
                <UserPlus :size="15" />
                <span>{{ relationBusy ? '发送中...' : '添加好友' }}</span>
              </button>
            </div>
          </div>

          <!-- 已是好友：发消息 / 删除好友 / 拉黑 -->
          <div v-else-if="profile.relationStatus === 'FRIEND'" class="modal-footer spread">
            <div class="left-danger-actions">
              <button
                type="button"
                class="btn btn-ghost btn-xs danger-text"
                :disabled="relationBusy"
                @click="handleDeleteFriend"
              >
                <UserMinus :size="13" />
                <span>删好友</span>
              </button>
              <button
                type="button"
                class="btn btn-ghost btn-xs danger-text"
                :disabled="relationBusy"
                @click="handleBlockUser"
              >
                <Ban :size="13" />
                <span>拉黑</span>
              </button>
            </div>
            <button type="button" class="btn btn-primary" @click="startDirectChat">
              <MessageCircle :size="15" />
              <span>发消息</span>
            </button>
          </div>

          <!-- 对方已向我发送好友申请 -->
          <div v-else-if="profile.relationStatus === 'PENDING_IN'" class="modal-footer">
            <button
              type="button"
              class="btn btn-ghost btn-xs danger-text"
              :disabled="relationBusy"
              @click="handleBlockUser"
            >
              <Ban :size="13" />
              <span>拉黑</span>
            </button>
            <button
              type="button"
              class="btn btn-primary"
              :disabled="relationBusy"
              @click="handleAcceptFriendReq"
            >
              <Check :size="15" />
              <span>同意好友申请</span>
            </button>
          </div>

          <!-- 我已发送好友申请等待对方验证 -->
          <div v-else-if="profile.relationStatus === 'PENDING_OUT'" class="modal-footer">
            <button type="button" class="btn btn-ghost" disabled>已发送好友申请，等待对方通过</button>
          </div>

          <!-- 我已拉黑对方 -->
          <div v-else-if="profile.relationStatus === 'BLOCKED'" class="modal-footer">
            <button
              type="button"
              class="btn btn-primary"
              :disabled="relationBusy"
              @click="handleUnblockUser"
            >
              <UserCheck :size="15" />
              <span>解除拉黑</span>
            </button>
          </div>

          <!-- 被对方拉黑 -->
          <div v-else-if="profile.relationStatus === 'BLOCKED_BY'" class="modal-footer">
            <span class="blocked-tip">对方已开启拒收，暂无法添加好友或私聊</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(12, 18, 36, 0.3);
  -webkit-backdrop-filter: blur(10px) saturate(145%);
  backdrop-filter: blur(10px) saturate(145%);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 120;
  padding: 16px;
}

.profile-modal {
  width: 100%;
  max-width: 472px;
  max-height: 90vh;
  display: flex;
  flex-direction: column;
  background: var(--surface-strong);
  border: 1px solid var(--glass-edge);
  border-radius: 26px;
  box-shadow: var(--shadow-md), inset 0 1px 0 rgba(255, 255, 255, 0.6);
  -webkit-backdrop-filter: blur(32px) saturate(190%);
  backdrop-filter: blur(32px) saturate(190%);
  overflow: hidden;
}

.profile-banner {
  position: relative;
  padding: 24px 20px 18px;
  background: linear-gradient(135deg, rgba(58, 107, 255, 0.22), rgba(18, 180, 140, 0.16), rgba(132, 102, 255, 0.2));
  border-bottom: 1px solid var(--border);
}

.banner-close {
  position: absolute;
  top: 14px;
  right: 14px;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  border: 1px solid var(--glass-edge);
  background: var(--surface);
  color: var(--ink-secondary);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: transform 0.3s var(--ease-spring), background 0.2s;
}

.banner-close:hover {
  background: var(--surface-strong);
  color: var(--ink);
  transform: rotate(90deg);
}

.banner-identity {
  display: flex;
  align-items: center;
  gap: 15px;
}

.avatar-editor {
  position: relative;
  cursor: pointer;
  border-radius: 22px;
  flex-shrink: 0;
}

.avatar-mask {
  position: absolute;
  inset: 0;
  border-radius: 22px;
  background: rgba(15, 23, 42, 0.56);
  color: #fff;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  font-size: 11px;
  font-weight: 600;
  opacity: 0;
  transition: opacity 0.2s;
}

.avatar-editor:hover .avatar-mask {
  opacity: 1;
}

.identity-text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.name-line {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.name-line h3 {
  font-size: 18px;
  font-weight: 800;
  letter-spacing: -0.01em;
}

.status-chip {
  font-size: 11.5px;
  font-weight: 600;
  padding: 2px 9px;
  border-radius: 999px;
  background: var(--surface-strong);
  border: 1px solid var(--glass-edge);
  color: var(--ink);
}

.handle-line {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 12px;
  color: var(--ink-secondary);
}

.copy-id-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: 1px solid var(--glass-edge);
  background: var(--surface);
  color: var(--ink-secondary);
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  cursor: pointer;
  transition: background 0.16s, color 0.16s;
}

.copy-id-btn:hover {
  background: var(--surface-strong);
  color: var(--accent);
}

.profile-body {
  padding: 16px 20px 18px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.section-label {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
  font-weight: 700;
  color: var(--ink-secondary);
}

.text-link {
  border: none;
  background: transparent;
  color: var(--accent);
  font-size: 11.5px;
  font-weight: 600;
  cursor: pointer;
}

.status-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.preset-pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: 1px solid var(--border-strong);
  background: var(--surface);
  color: var(--ink-secondary);
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 12px;
  cursor: pointer;
  transition: transform 0.28s var(--ease-spring), border-color 0.2s, background 0.2s, color 0.2s;
}

.preset-pill:hover {
  transform: translateY(-1px);
  border-color: var(--accent);
}

.preset-pill.active {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent);
  font-weight: 650;
}

.custom-status-row {
  display: flex;
  gap: 7px;
}

.presence-entry-row {
  margin-top: 8px;
  width: 100%;
  border: 1px solid var(--border);
  background: var(--rail);
  border-radius: 12px;
  padding: 9px 11px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  cursor: pointer;
  color: var(--ink);
  font-size: 12.5px;
  transition: border-color 0.2s, background 0.2s;
}

.presence-entry-row:hover {
  border-color: var(--accent);
  background: var(--hover);
}

.pe-left,
.pe-right {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.pe-ic {
  color: var(--ok);
}

.pe-ic.stealth {
  color: #7c3aed;
}

.pe-badge {
  font-size: 11px;
  font-weight: 650;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(16, 185, 129, 0.14);
  color: var(--ok);
}

.pe-badge.stealth {
  background: rgba(124, 58, 237, 0.14);
  color: #7c3aed;
}

.pe-link {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--accent);
}

.emoji-input {
  width: 56px;
  text-align: center;
  flex-shrink: 0;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.field label {
  font-size: 12px;
  font-weight: 650;
  color: var(--ink-secondary);
}

.bio-area {
  resize: vertical;
  min-height: 58px;
}

.prefs-box {
  padding: 10px 12px;
  border-radius: 14px;
  background: var(--surface);
  border: 1px solid var(--border);
  gap: 10px;
}

.pref-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  font-size: 12.5px;
}

.pref-info {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--ink);
  font-weight: 500;
}

.pref-actions {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}

.switch-btn {
  width: 38px;
  height: 22px;
  border-radius: 999px;
  border: none;
  background: var(--border-strong);
  padding: 2px;
  cursor: pointer;
  display: flex;
  align-items: center;
  transition: background 0.25s;
}

.switch-btn.on {
  background: var(--accent);
}

.switch-knob {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 2px 5px rgba(0, 0, 0, 0.2);
  transition: transform 0.32s var(--ease-spring);
}

.switch-btn.on .switch-knob {
  transform: translateX(16px);
}

.collapse-head {
  width: 100%;
  border: 1px solid var(--border);
  background: var(--surface);
  color: var(--ink);
  padding: 9px 12px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
}

.collapse-left {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.collapse-right {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.email-badge {
  max-width: 190px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11.5px;
  font-weight: 500;
  color: var(--accent);
}

.email-badge.empty {
  color: var(--ink-muted);
}

.email-code-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.flex-1 {
  flex: 1;
  min-width: 0;
}

.send-email-btn {
  flex-shrink: 0;
  min-width: 92px;
  height: 36px;
}

.dev-code-tip {
  padding: 6px 10px;
  border-radius: 9px;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 12px;
}

.pwd-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 12px;
  background: var(--surface);
  border: 1px solid var(--border);
}

.pwd-input-wrap {
  position: relative;
  display: flex;
  align-items: center;
}

.pwd-input-wrap .input {
  width: 100%;
}

.pwd-input-wrap .with-eye {
  padding-right: 36px;
}

.pwd-eye-btn {
  position: absolute;
  right: 6px;
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

.pwd-eye-btn:hover {
  color: var(--ink);
  background: var(--hover);
}

.card-info-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.card-info-row {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 10px 12px;
  border-radius: 13px;
  background: var(--surface);
  border: 1px solid var(--border);
}

.card-label {
  font-size: 11px;
  font-weight: 700;
  color: var(--ink-muted);
}

.card-val {
  font-size: 13px;
  color: var(--ink);
  word-break: break-word;
}

.card-val.online {
  color: var(--ok);
  font-weight: 650;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
  padding-top: 6px;
}

.modal-footer.spread {
  justify-content: space-between;
}

.left-danger-actions,
.byok-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
}

.byok-tip {
  font-size: 12px;
  color: var(--ink-secondary);
}

.danger-text {
  color: var(--danger);
}

.relation-footer,
.add-friend-box {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.blocked-tip {
  font-size: 12px;
  color: var(--ink-muted);
}
</style>
