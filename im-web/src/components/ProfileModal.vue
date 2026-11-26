<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  Camera,
  Check,
  ChevronDown,
  ChevronUp,
  Copy,
  FlaskConical,
  KeyRound,
  MessageCircle,
  Moon,
  RotateCcw,
  Sun,
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
const showPasswordBox = ref(false);
const oldPassword = ref('');
const newPassword = ref('');
const confirmPassword = ref('');
const savingPassword = ref(false);

const avatarInputRef = ref<HTMLInputElement | null>(null);

watch(
  () => props.profile,
  (p) => {
    formNickname.value = p.nickname || '';
    formAvatar.value = p.avatar || '';
    formSignature.value = p.signature || '';
    formBio.value = p.bio || '';
    formStatusEmoji.value = p.statusEmoji || '';
    formStatusText.value = p.statusText || '';
    showPasswordBox.value = false;
    oldPassword.value = '';
    newPassword.value = '';
    confirmPassword.value = '';
  },
  { immediate: true }
);

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

async function handleChangePassword() {
  if (!oldPassword.value || !newPassword.value) {
    store.pushToast('请填写原密码与新密码', 'error');
    return;
  }
  if (newPassword.value.length < 6) {
    store.pushToast('新密码长度至少为 6 位', 'error');
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

async function startDirectChat() {
  try {
    await store.startDirect(props.profile.id);
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
              <input v-model="oldPassword" type="password" class="input" placeholder="输入当前原密码" />
              <input v-model="newPassword" type="password" class="input" placeholder="输入新密码（至少 8 位，含字母与数字）" />
              <input v-model="confirmPassword" type="password" class="input" placeholder="再次确认新密码" />
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

        <div v-if="profile.userType !== 2" class="modal-footer">
          <button type="button" class="btn btn-ghost" @click="emit('close')">关闭</button>
          <button type="button" class="btn btn-primary" @click="startDirectChat">
            <MessageCircle :size="15" />
            <span>发消息</span>
          </button>
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

.pwd-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 12px;
  background: var(--surface);
  border: 1px solid var(--border);
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
  gap: 8px;
  padding-top: 6px;
}
</style>
