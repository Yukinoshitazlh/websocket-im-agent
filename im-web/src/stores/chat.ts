import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import type {
  AgentMetrics,
  AgentStreamState,
  ApprovalCardState,
  AuthConfig,
  AuthResponse,
  CaptchaResp,
  ChatMessage,
  Conversation,
  ConversationMember,
  FileMeta,
  ForwardBundleMeta,
  FriendRequestItem,
  GroupJoinRequestItem,
  InviteMembersResult,
  PinnedMessage,
  PresenceMode,
  PresenceSettings,
  ReactionGroup,
  SendEmailCodeResp,
  UserLlmConfig,
  UserProfile,
  WsPacket,
  WsTicketResp
} from '../types/im';
import { apiRequest, setUnauthorizedHandler, uploadFileWithInstantCheck } from '../utils/api';
import { cacheConversationMessages, clearCachedMessages, loadCachedMessages } from '../utils/db';
import {
  isDesktopNotificationSupported,
  requestDesktopNotificationPermission,
  showDesktopNotification
} from '../utils/notification';
import { playMessageChime } from '../utils/sound';

export interface ToastItem {
  id: number;
  text: string;
  type: 'info' | 'success' | 'error';
}

export interface UnreadDigestState {
  conversationId: string;
  afterSeq: number;
  count: number;
}

function isLocalDevHost(): boolean {
  if (typeof window === 'undefined') return true;
  const host = window.location.hostname;
  return host === 'localhost' || host === '127.0.0.1';
}

function resolveDefaultWsUrl(): string {
  const configured = (import.meta as any).env?.VITE_WS_URL as string | undefined;
  if (configured && configured.trim()) {
    return configured.trim();
  }
  if (typeof window !== 'undefined' && !isLocalDevHost()) {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${protocol}//${window.location.host}/ws`;
  }
  return 'ws://127.0.0.1:9001/ws';
}

export const useChatStore = defineStore('chat', () => {
  const token = ref<string>(sessionStorage.getItem('im_token') || '');
  const currentUser = ref<UserProfile | null>(
    JSON.parse(sessionStorage.getItem('im_user') || 'null')
  );
  const wsUrl = ref<string>(
    isLocalDevHost()
      ? sessionStorage.getItem('im_ws_url') || resolveDefaultWsUrl()
      : resolveDefaultWsUrl()
  );
  const authConfig = ref<AuthConfig>({
    requireCaptcha: true,
    requireEmailCode: true,
    inviteCodeRequired: false,
    allowDemoQuickLogin: isLocalDevHost(),
    showDemoMode: isLocalDevHost()
  });
  const theme = ref<'light' | 'dark'>((localStorage.getItem('im_theme') as 'light' | 'dark') || 'light');
  const demoMode = ref<boolean>(isLocalDevHost() && localStorage.getItem('im_demo_mode') === '1');
  const soundEnabled = ref<boolean>(localStorage.getItem('im_sound') !== '0');
  const desktopNotifyEnabled = ref<boolean>(localStorage.getItem('im_desktop_notify') === '1');
  const navTab = ref<'chats' | 'contacts'>('chats');
  const friends = ref<UserProfile[]>([]);
  const blockedUsers = ref<UserProfile[]>([]);
  const friendRequests = ref<FriendRequestItem[]>([]);
  const userLlmConfig = ref<UserLlmConfig | null>(null);
  const presenceSettings = ref<PresenceSettings>({
    mode: 'ALL',
    exceptUserIds: [],
    onlyUserIds: []
  });
  const showPresenceModal = ref<boolean>(false);
  const viewingProfile = ref<UserProfile | null>(null);

  const wsState = ref<'disconnected' | 'connecting' | 'connected'>('disconnected');
  const wsStatusText = ref<string>('未连接');
  const wsRttMs = ref<number | null>(null);
  const manualOffline = ref<boolean>(false);
  const authNotice = ref<string>('');

  const conversations = ref<Conversation[]>([]);
  const activeConvId = ref<string | null>(null);
  const activeMembers = ref<ConversationMember[]>([]);
  const activePins = ref<PinnedMessage[]>([]);
  const activeJoinRequests = ref<GroupJoinRequestItem[]>([]);
  const messages = ref<ChatMessage[]>([]);
  const hasMoreHistory = ref<boolean>(true);
  const loadingOlder = ref<boolean>(false);

  const drafts = ref<Record<string, string>>({});
  const replyTarget = ref<ChatMessage | null>(null);
  const typingHint = ref<string>('');
  const agentStreams = ref<Record<string, AgentStreamState>>({});
  const approvalCards = ref<ApprovalCardState[]>([]);
  const metrics = ref<AgentMetrics | null>(null);
  const searchResults = ref<ChatMessage[]>([]);
  const searchLoading = ref<boolean>(false);

  // 飞书 & 微信高阶协作状态
  const unreadDigestBanner = ref<UnreadDigestState | null>(null);
  const unreadStartSeq = ref<number | null>(null);
  const mentionJumpSeq = ref<number | null>(null);
  const highlightedSeq = ref<number | null>(null);
  const multiSelectMode = ref<boolean>(false);
  const selectedMsgIds = ref<string[]>([]);
  const viewingBundle = ref<ForwardBundleMeta | null>(null);
  const readDetailMsg = ref<ChatMessage | null>(null);
  const summarizing = ref<boolean>(false);

  const syncNotice = ref<string>('');
  const unreadBelowCount = ref<number>(0);
  const toasts = ref<ToastItem[]>([]);
  const lightboxUrl = ref<string | null>(null);

  let ws: WebSocket | null = null;
  let heartbeatTimer: number | null = null;
  let reconnectTimer: number | null = null;
  let typingClearTimer: number | null = null;
  let highlightTimer: number | null = null;
  let lastPingSentAt = 0;
  let lastTypingSentAt = 0;
  let reconnectAttempt = 0;
  let hasConnectedOnce = false;
  let changingPassword = false;
  let toastSeq = 1;
  let hiddenUnreadCount = 0;

  const activeConversation = computed(() =>
    conversations.value.find((c) => c.id === activeConvId.value) || null
  );

  const pendingFriendRequestCount = computed(
    () => friendRequests.value.filter((r) => r.direction === 'INCOMING' && r.status === 'PENDING').length
  );

  const activeStreamsList = computed(() =>
    Object.values(agentStreams.value).filter((s) => s.conversationId === activeConvId.value)
  );

  const activeApprovalCards = computed(() =>
    approvalCards.value.filter((c) => c.conversationId === activeConvId.value)
  );

  const maxLoadedSeq = computed(() =>
    messages.value.reduce((max, m) => (m.seq > max ? m.seq : max), 0)
  );

  const minLoadedSeq = computed(() => {
    const seqs = messages.value.map((m) => m.seq).filter((s) => s > 0);
    return seqs.length > 0 ? Math.min(...seqs) : 0;
  });

  function draftStorageKey(): string {
    return `im_drafts_${currentUser.value?.id || 'anon'}`;
  }

  function loadDraftsFromStorage() {
    try {
      drafts.value = JSON.parse(localStorage.getItem(draftStorageKey()) || '{}');
    } catch {
      drafts.value = {};
    }
  }

  function setDraft(convId: string, text: string) {
    if (!convId) return;
    if (text.trim()) {
      drafts.value[convId] = text;
    } else {
      delete drafts.value[convId];
    }
    try {
      localStorage.setItem(draftStorageKey(), JSON.stringify(drafts.value));
    } catch {
      // ignore storage quota errors
    }
  }

  function getDraft(convId: string): string {
    return drafts.value[convId] || '';
  }

  function applyTheme(next: 'light' | 'dark') {
    theme.value = next;
    localStorage.setItem('im_theme', next);
    document.documentElement.setAttribute('data-theme', next);
  }

  function toggleTheme() {
    applyTheme(theme.value === 'light' ? 'dark' : 'light');
  }

  function toggleDemoMode() {
    if (!authConfig.value.showDemoMode) {
      demoMode.value = false;
      localStorage.removeItem('im_demo_mode');
      return;
    }
    demoMode.value = !demoMode.value;
    localStorage.setItem('im_demo_mode', demoMode.value ? '1' : '0');
    pushToast(
      demoMode.value
        ? '已开启面试演练模式：显示双网关、断网模拟、#seq、幂等测试与 AI 观测'
        : '已切回日常纯净模式'
    );
  }

  async function loadAuthConfig() {
    try {
      const cfg = await apiRequest<AuthConfig>('/api/auth/config', '');
      authConfig.value = cfg;
      if (!cfg.showDemoMode) {
        demoMode.value = false;
        localStorage.removeItem('im_demo_mode');
      }
    } catch {
      // keep safe defaults
    }
  }

  async function fetchCaptcha(): Promise<CaptchaResp> {
    return apiRequest<CaptchaResp>('/api/auth/captcha', '');
  }

  async function sendEmailCode(
    email: string,
    captchaId?: string,
    captchaCode?: string
  ): Promise<SendEmailCodeResp> {
    return apiRequest<SendEmailCodeResp>('/api/auth/email-code', '', {
      method: 'POST',
      body: JSON.stringify({ email, captchaId, captchaCode })
    });
  }

  function toggleSound() {
    soundEnabled.value = !soundEnabled.value;
    localStorage.setItem('im_sound', soundEnabled.value ? '1' : '0');
    if (soundEnabled.value) {
      playMessageChime();
    }
  }

  function setNavTab(tab: 'chats' | 'contacts') {
    navTab.value = tab;
    if (tab === 'contacts') {
      loadContactsData();
    }
  }

  async function toggleDesktopNotify() {
    if (!desktopNotifyEnabled.value) {
      if (!isDesktopNotificationSupported()) {
        pushToast('当前浏览器不支持桌面系统通知', 'error');
        return;
      }
      const perm = await requestDesktopNotificationPermission();
      if (perm !== 'granted') {
        pushToast('未获得浏览器通知权限，请在地址栏右侧允许通知', 'error');
        desktopNotifyEnabled.value = false;
        localStorage.setItem('im_desktop_notify', '0');
        return;
      }
      desktopNotifyEnabled.value = true;
      localStorage.setItem('im_desktop_notify', '1');
      pushToast('已开启浏览器桌面通知（网页切后台时自动提醒）', 'success');
    } else {
      desktopNotifyEnabled.value = false;
      localStorage.setItem('im_desktop_notify', '0');
      pushToast('已关闭浏览器桌面通知');
    }
  }

  async function sendPasswordResetCode(
    email: string,
    captchaId?: string,
    captchaCode?: string
  ): Promise<SendEmailCodeResp> {
    return apiRequest<SendEmailCodeResp>('/api/auth/password-reset/code', '', {
      method: 'POST',
      body: JSON.stringify({ email, captchaId, captchaCode })
    });
  }

  async function resetPasswordByEmail(payload: {
    email: string;
    emailCode: string;
    newPassword: string;
  }) {
    await apiRequest<void>('/api/auth/password-reset/confirm', '', {
      method: 'POST',
      body: JSON.stringify(payload)
    });
  }

  async function loadFriends(keyword = ''): Promise<UserProfile[]> {
    if (!token.value) return [];
    const qs = keyword.trim() ? `?keyword=${encodeURIComponent(keyword.trim())}` : '';
    const list = await apiRequest<UserProfile[]>(`/api/friends${qs}`, token.value);
    if (!keyword.trim()) {
      friends.value = list;
    }
    return list;
  }

  async function loadContactsData() {
    if (!token.value) return;
    try {
      const [fList, rList, bList] = await Promise.all([
        apiRequest<UserProfile[]>('/api/friends', token.value),
        apiRequest<FriendRequestItem[]>('/api/friends/requests', token.value),
        apiRequest<UserProfile[]>('/api/friends/blocked', token.value)
      ]);
      friends.value = fList;
      friendRequests.value = rList;
      blockedUsers.value = bList;
    } catch {
      // ignore silent contacts load error
    }
  }

  async function sendFriendRequest(targetUserId: string, remark = '') {
    const res = await apiRequest<FriendRequestItem>('/api/friends/requests', token.value, {
      method: 'POST',
      body: JSON.stringify({ targetUserId, remark })
    });
    await loadContactsData();
    if (viewingProfile.value && String(viewingProfile.value.id) === String(targetUserId)) {
      await openProfile(targetUserId);
    }
    pushToast(res.status === 'ACCEPTED' ? '双方互相申请，已成为好友！' : '好友申请已发送，等待对方同意', 'success');
  }

  async function acceptFriendRequest(requestId: string) {
    const conv = await apiRequest<Conversation>(
      `/api/friends/requests/${encodeURIComponent(requestId)}/accept`,
      token.value,
      { method: 'POST' }
    );
    await Promise.all([loadContactsData(), loadConversations()]);
    pushToast('已通过好友申请，现在可以开始聊天了', 'success');
    if (conv?.id) {
      navTab.value = 'chats';
      await selectConversation(conv.id);
    }
  }

  async function rejectFriendRequest(requestId: string) {
    await apiRequest<void>(`/api/friends/requests/${encodeURIComponent(requestId)}/reject`, token.value, {
      method: 'POST'
    });
    await loadContactsData();
    pushToast('已拒绝该好友申请');
  }

  async function deleteFriend(friendId: string) {
    await apiRequest<void>(`/api/friends/${encodeURIComponent(friendId)}`, token.value, {
      method: 'DELETE'
    });
    await loadContactsData();
    if (viewingProfile.value && String(viewingProfile.value.id) === String(friendId)) {
      await openProfile(friendId);
    }
    pushToast('已解除好友关系');
  }

  async function blockUser(targetUserId: string) {
    await apiRequest<void>(`/api/friends/${encodeURIComponent(targetUserId)}/block`, token.value, {
      method: 'POST'
    });
    await loadContactsData();
    if (viewingProfile.value && String(viewingProfile.value.id) === String(targetUserId)) {
      await openProfile(targetUserId);
    }
    pushToast('已加入黑名单，将拒收对方私聊与好友申请');
  }

  async function unblockUser(targetUserId: string) {
    await apiRequest<void>(`/api/friends/${encodeURIComponent(targetUserId)}/block`, token.value, {
      method: 'DELETE'
    });
    await loadContactsData();
    if (viewingProfile.value && String(viewingProfile.value.id) === String(targetUserId)) {
      await openProfile(targetUserId);
    }
    pushToast('已移出黑名单', 'success');
  }

  async function loadMyLlmConfig(): Promise<UserLlmConfig | null> {
    if (!token.value) return null;
    try {
      userLlmConfig.value = await apiRequest<UserLlmConfig>('/api/users/me/llm-config', token.value);
      return userLlmConfig.value;
    } catch {
      return null;
    }
  }

  async function saveMyLlmConfig(payload: {
    enabled?: boolean;
    baseUrl?: string;
    model?: string;
    apiKey?: string;
    clearApiKey?: boolean;
  }) {
    userLlmConfig.value = await apiRequest<UserLlmConfig>('/api/users/me/llm-config', token.value, {
      method: 'PUT',
      body: JSON.stringify(payload)
    });
    pushToast(
      payload.clearApiKey
        ? '已清除自定义 API Key，恢复使用公共 AI 服务'
        : userLlmConfig.value.enabled
          ? '自定义 AI 大模型（BYOK）已保存并启用'
          : 'AI 配置已保存（当前使用公共 AI 服务）',
      'success'
    );
    return userLlmConfig.value;
  }

  async function refreshMyProfile() {
    if (!token.value) return;
    try {
      const fresh = await apiRequest<UserProfile>('/api/users/me', token.value);
      currentUser.value = fresh;
      sessionStorage.setItem('im_user', JSON.stringify(fresh));
      if (viewingProfile.value && String(viewingProfile.value.id) === String(fresh.id)) {
        viewingProfile.value = fresh;
      }
    } catch {
      // ignore silent refresh failure
    }
  }

  async function openProfile(userId?: string) {
    if (!currentUser.value) return;
    const isMe = !userId || String(userId) === String(currentUser.value.id);
    if (isMe) {
      viewingProfile.value = { ...currentUser.value };
      refreshMyProfile();
      return;
    }
    try {
      viewingProfile.value = await apiRequest<UserProfile>(
        `/api/users/${encodeURIComponent(userId)}`,
        token.value
      );
    } catch (e: any) {
      pushToast(e.message || '加载用户资料失败', 'error');
    }
  }

  async function uploadAvatarFile(file: File): Promise<string> {
    if (file.size > 10 * 1024 * 1024) {
      throw new Error('头像图片不能超过 10MB');
    }
    const uploaded = await uploadFileWithInstantCheck(file, token.value);
    return uploaded.url;
  }

  async function updateMyProfile(payload: {
    nickname: string;
    avatar: string;
    signature: string;
    bio: string;
    statusEmoji: string;
    statusText: string;
  }) {
    const updated = await apiRequest<UserProfile>('/api/users/me', token.value, {
      method: 'PUT',
      body: JSON.stringify(payload)
    });
    currentUser.value = updated;
    sessionStorage.setItem('im_user', JSON.stringify(updated));
    if (viewingProfile.value && String(viewingProfile.value.id) === String(updated.id)) {
      viewingProfile.value = updated;
    }
    // 立刻刷新当前会话里自己已发消息的头像和昵称
    for (const m of messages.value) {
      if (String(m.senderId) === String(updated.id)) {
        m.senderNickname = updated.nickname;
        m.senderAvatar = updated.avatar;
      }
    }
    if (activeConvId.value) {
      loadActiveMembers(activeConvId.value);
    }
    loadConversations();
    pushToast('个人资料已保存', 'success');
  }

  async function sendBindEmailCode(email: string): Promise<SendEmailCodeResp> {
    return apiRequest<SendEmailCodeResp>('/api/users/me/email/code', token.value, {
      method: 'POST',
      body: JSON.stringify({ email })
    });
  }

  async function updateMyEmail(payload: {
    email: string;
    emailCode?: string;
    currentPassword: string;
  }) {
    const updated = await apiRequest<UserProfile>('/api/users/me/email', token.value, {
      method: 'PUT',
      body: JSON.stringify(payload)
    });
    currentUser.value = updated;
    sessionStorage.setItem('im_user', JSON.stringify(updated));
    if (viewingProfile.value && String(viewingProfile.value.id) === String(updated.id)) {
      viewingProfile.value = updated;
    }
    pushToast('绑定邮箱已更新', 'success');
  }

  async function changeMyPassword(oldPassword: string, newPassword: string) {
    changingPassword = true;
    try {
      const refreshed = await apiRequest<AuthResponse>('/api/users/me/password', token.value, {
        method: 'PUT',
        body: JSON.stringify({ oldPassword, newPassword })
      });
      if (refreshed?.token) {
        token.value = refreshed.token;
        currentUser.value = refreshed.user;
        sessionStorage.setItem('im_token', refreshed.token);
        sessionStorage.setItem('im_user', JSON.stringify(refreshed.user));
        // 用新 Token 重新签发一次性票据建立安全连接，旧端已被服务端强制下线
        clearTimers();
        const prev = ws;
        ws = null;
        prev?.close();
        manualOffline.value = false;
        reconnectAttempt = 0;
        connectWs();
      }
      pushToast('登录密码已更新，其他设备旧会话已自动失效', 'success');
    } finally {
      window.setTimeout(() => {
        changingPassword = false;
      }, 1500);
    }
  }

  function pushToast(text: string, type: 'info' | 'success' | 'error' = 'info') {
    const id = toastSeq++;
    toasts.value.push({ id, text, type });
    window.setTimeout(() => {
      toasts.value = toasts.value.filter((t) => t.id !== id);
    }, 3200);
  }

  function flashHighlightSeq(seq: number) {
    highlightedSeq.value = seq;
    if (highlightTimer) window.clearTimeout(highlightTimer);
    highlightTimer = window.setTimeout(() => {
      highlightedSeq.value = null;
    }, 2200);
  }

  function updateDocumentTitle() {
    if (!document.hidden) {
      hiddenUnreadCount = 0;
    }
    document.title =
      hiddenUnreadCount > 0
        ? `(${hiddenUnreadCount}) 新消息 - 脉信`
        : '脉信 PulseIM — 即时通信与 AI 协同工作台';
  }

  if (typeof document !== 'undefined') {
    applyTheme(theme.value);
    document.addEventListener('visibilitychange', () => {
      if (!document.hidden) {
        hiddenUnreadCount = 0;
        updateDocumentTitle();
      }
    });
  }

  async function login(payload: {
    account: string;
    password: string;
    captchaId?: string;
    captchaCode?: string;
  }) {
    const data = await apiRequest<AuthResponse>('/api/auth/login', '', {
      method: 'POST',
      body: JSON.stringify({
        account: payload.account,
        username: payload.account,
        password: payload.password,
        captchaId: payload.captchaId,
        captchaCode: payload.captchaCode
      })
    });
    setAuthSession(data);
  }

  async function register(payload: {
    email: string;
    emailCode?: string;
    username?: string;
    password: string;
    nickname?: string;
    captchaId?: string;
    captchaCode?: string;
    inviteCode?: string;
  }) {
    const data = await apiRequest<AuthResponse>('/api/auth/register', '', {
      method: 'POST',
      body: JSON.stringify(payload)
    });
    setAuthSession(data);
  }

  function setAuthSession(data: AuthResponse) {
    authNotice.value = '';
    manualOffline.value = false;
    reconnectAttempt = 0;
    hasConnectedOnce = false;
    token.value = data.token;
    currentUser.value = data.user;
    sessionStorage.setItem('im_token', data.token);
    sessionStorage.setItem('im_user', JSON.stringify(data.user));
    loadDraftsFromStorage();
    loadMyPresence();
    loadConversations();
    loadContactsData();
    connectWs();
  }

  function logout(reason?: string) {
    manualOffline.value = true;
    clearTimers();
    const currentSocket = ws;
    ws = null;
    currentSocket?.close();
    sessionStorage.clear();
    token.value = '';
    currentUser.value = null;
    navTab.value = 'chats';
    friends.value = [];
    blockedUsers.value = [];
    friendRequests.value = [];
    userLlmConfig.value = null;
    wsState.value = 'disconnected';
    wsStatusText.value = '未连接';
    wsRttMs.value = null;
    conversations.value = [];
    messages.value = [];
    activeConvId.value = null;
    activeMembers.value = [];
    activePins.value = [];
    activeJoinRequests.value = [];
    exitMultiSelect();
    if (reason) {
      authNotice.value = reason;
      pushToast(reason, 'error');
    } else {
      authNotice.value = '';
    }
  }

  setUnauthorizedHandler((message) => {
    if (token.value) {
      logout(message || '你的账号已在其他设备或页面登录，当前会话已下线');
    }
  });

  function clearTimers() {
    if (heartbeatTimer) window.clearInterval(heartbeatTimer);
    if (reconnectTimer) window.clearTimeout(reconnectTimer);
    if (typingClearTimer) window.clearTimeout(typingClearTimer);
    if (highlightTimer) window.clearTimeout(highlightTimer);
    heartbeatTimer = null;
    reconnectTimer = null;
    typingClearTimer = null;
    highlightTimer = null;
  }

  function switchGateway(url: string) {
    wsUrl.value = url;
    sessionStorage.setItem('im_ws_url', url);
    manualOffline.value = false;
    reconnectAttempt = 0;
    clearTimers();
    ws?.close();
    connectWs();
    pushToast('已切换网关：' + (url.includes('9002') ? 'gateway-2 (:9002)' : 'gateway-1 (:9001)'));
  }

  function toggleManualOffline() {
    if (!manualOffline.value) {
      manualOffline.value = true;
      clearTimers();
      ws?.close();
      wsState.value = 'disconnected';
      wsStatusText.value = '已手动断网';
      wsRttMs.value = null;
      pushToast('已模拟断网，对方发消息后点「恢复联网」即可验证增量同步');
    } else {
      manualOffline.value = false;
      reconnectAttempt = 0;
      connectWs();
    }
  }

  function formatWsLabel(url: string): string {
    if (url.includes('9002')) return 'gateway-2 :9002';
    if (url.includes('9001')) return 'gateway-1 :9001';
    return '云端安全网关';
  }

  async function connectWs() {
    if (!token.value || manualOffline.value) return;
    const label = formatWsLabel(wsUrl.value);
    wsState.value = 'connecting';
    wsStatusText.value = `连接中 (${label})`;

    let ticketParam: string;
    try {
      const resp = await apiRequest<WsTicketResp>('/api/auth/ws-ticket', token.value, {
        method: 'POST'
      });
      ticketParam = `ticket=${encodeURIComponent(resp.ticket)}`;
    } catch (e: any) {
      const msg = String(e?.message || '');
      if (msg.includes('Token') || msg.includes('失效') || msg.includes('登录') || msg.includes('禁用')) {
        pushToast(msg || '登录状态已失效，请重新登录', 'error');
        logout();
        return;
      }
      const delaySec = Math.min(15, Math.pow(2, reconnectAttempt));
      reconnectAttempt++;
      wsState.value = 'connecting';
      wsStatusText.value = `${delaySec}s 后重连`;
      reconnectTimer = window.setTimeout(connectWs, delaySec * 1000);
      return;
    }

    if (!token.value || manualOffline.value) return;
    const separator = wsUrl.value.includes('?') ? '&' : '?';
    const socket = new WebSocket(`${wsUrl.value}${separator}${ticketParam}`);
    ws = socket;

    socket.onopen = async () => {
      if (ws !== socket) return;
      const isReconnect = hasConnectedOnce;
      hasConnectedOnce = true;
      reconnectAttempt = 0;
      wsState.value = 'connected';
      wsStatusText.value = `在线 (${label})`;

      sendPing();
      if (heartbeatTimer) window.clearInterval(heartbeatTimer);
      heartbeatTimer = window.setInterval(sendPing, 20000);

      if (isReconnect) {
        await syncActiveAfterReconnect();
        await loadConversations();
        if (activeConvId.value) {
          loadActiveMembers(activeConvId.value);
          loadActivePins(activeConvId.value);
        }
      }
    };

    socket.onclose = (event) => {
      if (ws !== socket) return;
      if (heartbeatTimer) window.clearInterval(heartbeatTimer);
      wsRttMs.value = null;
      if (manualOffline.value) return;

      // 4001 表示被同账号新登录或新页面顶下线：直接退出到登录页，绝不重连死循环
      if (event.code === 4001) {
        logout('你的账号已在其他设备或页面登录，当前会话已下线');
        return;
      }

      const delaySec = Math.min(15, Math.pow(2, reconnectAttempt));
      reconnectAttempt++;
      wsState.value = 'connecting';
      wsStatusText.value = `${delaySec}s 后重连`;
      reconnectTimer = window.setTimeout(connectWs, delaySec * 1000);
    };

    socket.onmessage = (event) => {
      try {
        const pkt = JSON.parse(event.data) as WsPacket<any>;
        handleWsPacket(pkt);
      } catch (e) {
        console.error('解析下行帧失败:', e);
      }
    };
  }

  function sendPing() {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    lastPingSentAt = performance.now();
    ws.send(
      JSON.stringify({
        type: 'PING',
        requestId: 'hb-' + Date.now(),
        timestamp: Date.now(),
        data: {}
      })
    );
  }

  function handleWsPacket(pkt: WsPacket<any>) {
    switch (pkt.type) {
      case 'PONG':
        if (lastPingSentAt > 0) {
          wsRttMs.value = Math.max(1, Math.round(performance.now() - lastPingSentAt));
        }
        break;
      case 'SERVER_ACK':
        onServerAck(pkt.data);
        break;
      case 'CHAT_PUSH':
        onChatPush(pkt.data);
        break;
      case 'MSG_RECALL':
        onMsgRecall(pkt.data);
        break;
      case 'TYPING':
        onTypingSignal(pkt.data);
        break;
      case 'READ_RECEIPT':
        onReadReceipt(pkt.data);
        break;
      case 'MSG_REACTION':
        onMsgReaction(pkt.data);
        break;
      case 'MSG_PIN':
        onMsgPin(pkt.data);
        break;
      case 'CONV_UPDATE':
        onConvUpdate(pkt.data);
        break;
      case 'AGENT_STREAM':
        onAgentStream(pkt.data);
        break;
      case 'AGENT_APPROVAL_CARD':
        onAgentApprovalCard(pkt.data);
        break;
      case 'FRIEND_EVENT':
        onFriendEvent(pkt.data);
        break;
      case 'FORCE_LOGOUT':
        if (changingPassword && pkt.data?.reason === 'PASSWORD_CHANGED') {
          break;
        }
        logout(pkt.data?.message || '你的账号已在其他设备或页面登录，当前会话已下线');
        break;
      case 'ERROR':
        pushToast(pkt.data?.message || '操作失败', 'error');
        break;
    }
  }

  async function loadConversations() {
    if (!token.value) return;
    try {
      conversations.value = await apiRequest<Conversation[]>('/api/conversations', token.value);
    } catch (e) {
      console.error('加载会话失败:', e);
    }
  }

  async function loadActiveMembers(convId: string) {
    if (!token.value) return;
    try {
      const list = await apiRequest<ConversationMember[]>(
        `/api/conversations/${encodeURIComponent(convId)}/members`,
        token.value
      );
      if (activeConvId.value === convId) {
        activeMembers.value = list;
      }
    } catch (e) {
      console.error('加载成员列表失败:', e);
    }
  }

  async function loadActivePins(convId: string) {
    if (!token.value) return;
    try {
      const list = await apiRequest<PinnedMessage[]>(
        `/api/conversations/${encodeURIComponent(convId)}/pins`,
        token.value
      );
      if (activeConvId.value === convId) {
        activePins.value = list;
      }
    } catch (e) {
      console.error('加载置顶消息失败:', e);
    }
  }

  async function searchUsers(keyword: string): Promise<UserProfile[]> {
    return apiRequest<UserProfile[]>(
      `/api/users/search?keyword=${encodeURIComponent(keyword)}`,
      token.value
    );
  }

  async function startDirect(targetUserId: string) {
    const conv = await apiRequest<Conversation>('/api/conversations/direct', token.value, {
      method: 'POST',
      body: JSON.stringify({ targetUserId })
    });
    await loadConversations();
    await selectConversation(conv.id);
  }

  async function createGroup(title: string, memberIds: string[]) {
    const conv = await apiRequest<Conversation>('/api/conversations/group', token.value, {
      method: 'POST',
      body: JSON.stringify({ title, memberIds })
    });
    await loadConversations();
    await selectConversation(conv.id);
    pushToast('群聊创建成功', 'success');
  }

  async function updateConvPreferences(
    convId: string,
    prefs: {
      pinned?: boolean;
      muted?: boolean;
      groupRemark?: string;
      groupNickname?: string;
      showMemberNickname?: boolean;
    }
  ) {
    await apiRequest<void>(`/api/conversations/${encodeURIComponent(convId)}/preferences`, token.value, {
      method: 'POST',
      body: JSON.stringify(prefs)
    });
    await loadConversations();
    if (prefs.groupNickname !== undefined && activeConvId.value === convId) {
      await loadActiveMembers(convId);
    }
    if (prefs.pinned !== undefined) {
      pushToast(prefs.pinned ? '已置顶会话' : '已取消置顶', 'success');
    } else if (prefs.muted !== undefined) {
      pushToast(prefs.muted ? '已开启消息免打扰' : '已关闭消息免打扰', 'success');
    } else if (prefs.groupRemark !== undefined) {
      pushToast(prefs.groupRemark.trim() ? '群聊备注已保存（仅自己可见）' : '已清空群聊备注', 'success');
    } else if (prefs.groupNickname !== undefined) {
      pushToast(prefs.groupNickname.trim() ? '本群昵称已更新' : '已恢复默认昵称', 'success');
    } else if (prefs.showMemberNickname !== undefined) {
      pushToast(prefs.showMemberNickname ? '已开启显示群成员昵称' : '已隐藏群成员昵称');
    }
  }

  async function updateGroupAnnouncement(convId: string, announcement: string) {
    await apiRequest<void>(`/api/conversations/${encodeURIComponent(convId)}/announcement`, token.value, {
      method: 'PUT',
      body: JSON.stringify({ announcement })
    });
    await loadConversations();
    pushToast(announcement.trim() ? '群公告已发布并通知全群成员' : '群公告已清空', 'success');
  }

  async function updateGroupSettings(convId: string, settings: { joinApprovalRequired?: boolean }) {
    await apiRequest<void>(`/api/conversations/${encodeURIComponent(convId)}/settings`, token.value, {
      method: 'PUT',
      body: JSON.stringify(settings)
    });
    await loadConversations();
    if (settings.joinApprovalRequired !== undefined) {
      pushToast(
        settings.joinApprovalRequired
          ? '已开启「进群需要群主/群管理员确认」'
          : '已关闭进群确认',
        'success'
      );
    }
  }

  async function loadActiveJoinRequests(convId: string) {
    if (!token.value) return;
    try {
      const list = await apiRequest<GroupJoinRequestItem[]>(
        `/api/conversations/${encodeURIComponent(convId)}/join-requests`,
        token.value
      );
      if (activeConvId.value === convId) {
        activeJoinRequests.value = list;
      }
    } catch {
      if (activeConvId.value === convId) {
        activeJoinRequests.value = [];
      }
    }
  }

  async function approveJoinRequest(convId: string, requestId: string) {
    await apiRequest<void>(
      `/api/conversations/${encodeURIComponent(convId)}/join-requests/${encodeURIComponent(requestId)}/approve`,
      token.value,
      { method: 'POST' }
    );
    await Promise.all([loadActiveJoinRequests(convId), loadActiveMembers(convId), loadConversations()]);
    pushToast('已同意入群申请', 'success');
  }

  async function rejectJoinRequest(convId: string, requestId: string) {
    await apiRequest<void>(
      `/api/conversations/${encodeURIComponent(convId)}/join-requests/${encodeURIComponent(requestId)}/reject`,
      token.value,
      { method: 'POST' }
    );
    await Promise.all([loadActiveJoinRequests(convId), loadConversations()]);
    pushToast('已拒绝入群申请');
  }

  const presenceSummaryText = computed(() => {
    const p = presenceSettings.value;
    if (p.mode === 'NONE') return '全员隐身';
    if (p.mode === 'EXCEPT') {
      return p.exceptUserIds.length > 0 ? `不给 ${p.exceptUserIds.length} 人看` : '不给谁看';
    }
    if (p.mode === 'ONLY') {
      return p.onlyUserIds.length > 0 ? `仅 ${p.onlyUserIds.length} 人可见` : '部分可见';
    }
    return '在线可见';
  });

  function isStealthForConv(convId: string): boolean {
    if (presenceSettings.value.mode === 'NONE') return true;
    const conv = conversations.value.find((c) => c.id === convId);
    if (conv && typeof conv.stealthActive === 'boolean') {
      return conv.stealthActive;
    }
    if (conv && conv.type === 1 && conv.peerUserId) {
      if (presenceSettings.value.mode === 'EXCEPT') {
        return presenceSettings.value.exceptUserIds.includes(String(conv.peerUserId));
      }
      if (presenceSettings.value.mode === 'ONLY') {
        return !presenceSettings.value.onlyUserIds.includes(String(conv.peerUserId));
      }
    }
    return false;
  }

  async function loadMyPresence(): Promise<PresenceSettings | null> {
    if (!token.value) return null;
    try {
      const cfg = await apiRequest<PresenceSettings>('/api/users/me/presence', token.value);
      presenceSettings.value = {
        mode: cfg.mode || 'ALL',
        exceptUserIds: (cfg.exceptUserIds || []).map(String),
        onlyUserIds: (cfg.onlyUserIds || []).map(String)
      };
      return presenceSettings.value;
    } catch {
      return null;
    }
  }

  async function saveMyPresence(payload: {
    mode: PresenceMode;
    exceptUserIds?: string[];
    onlyUserIds?: string[];
  }): Promise<PresenceSettings> {
    const saved = await apiRequest<PresenceSettings>('/api/users/me/presence', token.value, {
      method: 'PUT',
      body: JSON.stringify(payload)
    });
    presenceSettings.value = {
      mode: saved.mode || 'ALL',
      exceptUserIds: (saved.exceptUserIds || []).map(String),
      onlyUserIds: (saved.onlyUserIds || []).map(String)
    };
    await loadConversations();
    const label =
      saved.mode === 'NONE'
        ? '已切换为「全员隐身」，查看消息不会触发已读'
        : saved.mode === 'EXCEPT'
          ? `已保存「不给谁看」（已屏蔽 ${saved.exceptUserIds.length} 人）`
          : saved.mode === 'ONLY'
            ? `已保存「部分可见」（仅 ${saved.onlyUserIds.length} 人可见在线）`
            : '已开启在线状态（所有人可见）';
    pushToast(label, 'success');
    return presenceSettings.value;
  }

  async function markConvAsReadManual(convId: string) {
    try {
      const wasStealth = isStealthForConv(convId);
      await apiRequest<void>(`/api/conversations/${encodeURIComponent(convId)}/read`, token.value, {
        method: 'POST'
      });
      await loadConversations();
      pushToast(wasStealth ? '已手动标为已读（对方已收到已读状态）' : '已标为已读', 'success');
    } catch (e: any) {
      pushToast(e.message || '标为已读失败', 'error');
    }
  }

  async function markConvAsUnreadManual(convId: string) {
    try {
      await apiRequest<void>(`/api/conversations/${encodeURIComponent(convId)}/unread`, token.value, {
        method: 'POST'
      });
      await loadConversations();
      pushToast('已标为未读');
    } catch (e: any) {
      pushToast(e.message || '标为未读失败', 'error');
    }
  }

  async function deleteConversationForMe(convId: string) {
    try {
      await apiRequest<void>(`/api/conversations/${encodeURIComponent(convId)}`, token.value, {
        method: 'DELETE'
      });
      if (currentUser.value) {
        await clearCachedMessages(currentUser.value.id, convId);
      }
      setDraft(convId, '');
      if (activeConvId.value === convId) {
        activeConvId.value = null;
        messages.value = [];
        activeMembers.value = [];
        activePins.value = [];
        activeJoinRequests.value = [];
        replyTarget.value = null;
        unreadDigestBanner.value = null;
        mentionJumpSeq.value = null;
      }
      conversations.value = conversations.value.filter((c) => c.id !== convId);
      await loadConversations();
      pushToast('已删除该聊天');
    } catch (e: any) {
      pushToast(e.message || '删除聊天失败', 'error');
    }
  }

  async function inviteGroupMembers(convId: string, memberIds: string[]) {
    const res = await apiRequest<InviteMembersResult>(
      `/api/conversations/${encodeURIComponent(convId)}/members`,
      token.value,
      {
        method: 'POST',
        body: JSON.stringify({ memberIds })
      }
    );
    await loadActiveMembers(convId);
    await loadConversations();
    if (res?.pendingApprovalCount > 0 && res.joinedCount === 0) {
      pushToast(res.message || '已提交入群申请，等待群主或群管理员确认', 'info');
    } else {
      pushToast(res?.message || '已邀请新成员入群', 'success');
    }
    return res;
  }

  async function kickGroupMember(convId: string, targetUserId: string) {
    await apiRequest<void>(
      `/api/conversations/${encodeURIComponent(convId)}/members/${encodeURIComponent(targetUserId)}`,
      token.value,
      { method: 'DELETE' }
    );
    await loadActiveMembers(convId);
    await loadConversations();
    pushToast('已移出该群成员');
  }

  async function leaveGroup(convId: string) {
    await apiRequest<void>(`/api/conversations/${encodeURIComponent(convId)}/leave`, token.value, {
      method: 'POST'
    });
    if (activeConvId.value === convId) {
      activeConvId.value = null;
      messages.value = [];
      activeMembers.value = [];
      activePins.value = [];
    }
    await loadConversations();
    pushToast('已退出群聊');
  }

  async function renameGroup(convId: string, title: string) {
    await apiRequest<void>(`/api/conversations/${encodeURIComponent(convId)}/title`, token.value, {
      method: 'PUT',
      body: JSON.stringify({ title })
    });
    await loadConversations();
    pushToast('群名称已更新', 'success');
  }

  async function selectConversation(convId: string) {
    const targetConv = conversations.value.find((c) => c.id === convId);

    // 捕获进入会话前的未读快照（满 3 条弹出飞书式 AI 一键省流总结胶囊）与「以下是新消息」分割水位
    if (targetConv && targetConv.unreadCount > 0) {
      unreadStartSeq.value = targetConv.lastReadSeq || 0;
    } else {
      unreadStartSeq.value = null;
    }
    if (targetConv && targetConv.unreadCount >= 3) {
      unreadDigestBanner.value = {
        conversationId: convId,
        afterSeq: targetConv.lastReadSeq || 0,
        count: targetConv.unreadCount
      };
    } else {
      unreadDigestBanner.value = null;
    }
    mentionJumpSeq.value = targetConv?.mentionSeq || null;

    activeConvId.value = convId;
    replyTarget.value = null;
    typingHint.value = '';
    unreadBelowCount.value = 0;
    hasMoreHistory.value = true;
    searchResults.value = [];
    syncNotice.value = '';
    activeMembers.value = [];
    activePins.value = [];
    activeJoinRequests.value = [];
    exitMultiSelect();

    // 并行拉取成员（含在线绿点与已读水位）和置顶消息
    loadActiveMembers(convId);
    loadActivePins(convId);

    // 1. IndexedDB 本地缓存秒开
    if (currentUser.value) {
      const cached = await loadCachedMessages(currentUser.value.id, convId);
      if (activeConvId.value === convId && cached.length > 0) {
        messages.value = cached;
      } else {
        messages.value = [];
      }
    }

    // 2. 拉取服务端最新 30 条权威历史并回填 IndexedDB
    try {
      const remote = await apiRequest<ChatMessage[]>(
        `/api/messages/history?conversationId=${encodeURIComponent(convId)}&limit=30`,
        token.value
      );
      if (activeConvId.value !== convId) return;
      messages.value = remote;
      hasMoreHistory.value = remote.length >= 30;
      if (currentUser.value) {
        cacheConversationMessages(currentUser.value.id, convId, remote);
      }
      if (!isStealthForConv(convId)) {
        if (maxLoadedSeq.value > 0) {
          sendMsgAck(convId, maxLoadedSeq.value);
        } else if (targetConv && targetConv.unreadCount > 0) {
          await apiRequest<void>(`/api/conversations/${encodeURIComponent(convId)}/read`, token.value, {
            method: 'POST'
          }).catch(() => {});
        }
      }
      window.setTimeout(loadConversations, 150);
      loadAgentMetrics();
    } catch (e: any) {
      pushToast('加载消息失败：' + e.message, 'error');
    }
  }

  async function loadOlderMessages(): Promise<number> {
    if (!activeConvId.value || loadingOlder.value || !hasMoreHistory.value) return 0;
    const cursor = minLoadedSeq.value;
    if (cursor <= 1) {
      hasMoreHistory.value = false;
      return 0;
    }
    loadingOlder.value = true;
    try {
      const convId = activeConvId.value;
      const older = await apiRequest<ChatMessage[]>(
        `/api/messages/history?conversationId=${encodeURIComponent(convId)}&beforeSeq=${cursor}&limit=30`,
        token.value
      );
      if (activeConvId.value !== convId) return 0;
      if (older.length < 30) {
        hasMoreHistory.value = false;
      }
      if (older.length > 0) {
        const existingSeqs = new Set(messages.value.map((m) => m.seq));
        const uniqueOlder = older.filter((m) => !existingSeqs.has(m.seq));
        messages.value = [...uniqueOlder, ...messages.value];
        if (currentUser.value) {
          cacheConversationMessages(currentUser.value.id, convId, messages.value);
        }
        return uniqueOlder.length;
      }
      return 0;
    } catch (e: any) {
      pushToast('加载更早历史失败：' + e.message, 'error');
      return 0;
    } finally {
      loadingOlder.value = false;
    }
  }

  async function syncActiveAfterReconnect() {
    if (!activeConvId.value) return;
    const convId = activeConvId.value;
    const afterSeq = maxLoadedSeq.value;
    try {
      const missed = await apiRequest<ChatMessage[]>(
        `/api/messages/sync?conversationId=${encodeURIComponent(convId)}&afterSeq=${afterSeq}&limit=100`,
        token.value
      );
      let added = 0;
      for (const m of missed) {
        if (mergeOrAppendMessage(m)) added++;
      }
      if (added > 0) {
        sendMsgAck(convId, maxLoadedSeq.value);
        syncNotice.value = `重连已补齐 ${added} 条离线消息`;
        pushToast(`断线重连成功，自动增量补齐 ${added} 条消息`, 'success');
      } else {
        syncNotice.value = `已同步至最新 #${afterSeq}`;
      }
    } catch (e) {
      console.error('重连增量同步失败:', e);
    }
  }

  function sendMsgAck(convId: string, seq: number) {
    if (!convId || seq <= 0) return;
    if (isStealthForConv(convId)) return;
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(
        JSON.stringify({
          type: 'MSG_ACK',
          requestId: 'ack-' + Date.now(),
          timestamp: Date.now(),
          data: { conversationId: convId, seq }
        })
      );
    } else if (token.value) {
      apiRequest(`/api/messages/ack?conversationId=${encodeURIComponent(convId)}&seq=${seq}`, token.value, {
        method: 'POST'
      }).catch(() => {});
    }
  }

  function sendTypingSignal() {
    if (!activeConvId.value || !ws || ws.readyState !== WebSocket.OPEN) return;
    const now = Date.now();
    if (now - lastTypingSentAt < 2000) return;
    lastTypingSentAt = now;
    ws.send(
      JSON.stringify({
        type: 'TYPING',
        requestId: 'typ-' + now,
        timestamp: now,
        data: { conversationId: activeConvId.value, typing: true }
      })
    );
  }

  function sendChatMessage(
    content: string,
    options?: {
      conversationId?: string;
      msgType?: string;
      extra?: string;
      atUserIds?: string[];
      duplicateTest?: boolean;
    }
  ) {
    const targetConvId = options?.conversationId || activeConvId.value;
    if (!targetConvId || !currentUser.value) {
      pushToast('请先选择一个会话', 'error');
      return false;
    }
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      pushToast('WebSocket 未连接，请先恢复联网', 'error');
      return false;
    }

    const msgType = options?.msgType || 'TEXT';
    const clientMsgId = 'c-' + Date.now() + '-' + Math.random().toString(36).slice(2, 7);
    const mentionAgent = msgType === 'TEXT' && content.toLowerCase().includes('@ai');
    const isCurrentConv = targetConvId === activeConvId.value;
    const reply = isCurrentConv ? replyTarget.value : null;

    if (isCurrentConv) {
      const optimistic: ChatMessage = {
        conversationId: targetConvId,
        senderId: currentUser.value.id,
        senderNickname: currentUser.value.nickname,
        senderAvatar: currentUser.value.avatar || '',
        clientMsgId,
        seq: 0,
        msgType,
        content,
        replyToMsgId: reply?.id || null,
        replyToSender: reply?.senderNickname || null,
        replyToContent: reply ? (reply.msgType === 'IMAGE' ? '[图片]' : reply.content.slice(0, 50)) : null,
        extra: options?.extra || null,
        atUserIds: options?.atUserIds || [],
        reactions: [],
        status: 1,
        createdAt: Date.now(),
        localStatus: 'sending'
      };
      messages.value.push(optimistic);
    }

    const pkt = {
      type: 'CHAT_SEND',
      requestId: clientMsgId,
      timestamp: Date.now(),
      data: {
        conversationId: targetConvId,
        clientMsgId,
        msgType,
        content,
        replyToMsgId: reply?.id || null,
        extra: options?.extra || null,
        atUserIds: options?.atUserIds || [],
        mentionAgent
      }
    };
    ws.send(JSON.stringify(pkt));
    if (options?.duplicateTest) {
      ws.send(JSON.stringify(pkt));
      pushToast('已使用同一 clientMsgId 连发 2 次，验证服务端双重幂等去重');
    }
    if (isCurrentConv) {
      replyTarget.value = null;
      setDraft(targetConvId, '');
    }
    return true;
  }

  async function sendFileMessage(file: File) {
    if (!activeConvId.value) {
      pushToast('请先选择一个会话再发送图片或文件', 'error');
      return;
    }
    if (file.size > 20 * 1024 * 1024) {
      pushToast('单个文件不能超过 20MB', 'error');
      return;
    }
    try {
      const uploaded = await uploadFileWithInstantCheck(file, token.value);
      const isImage = uploaded.contentType.startsWith('image/');
      const meta: FileMeta = {
        url: uploaded.url,
        fileName: uploaded.fileName,
        fileSize: uploaded.fileSize,
        contentType: uploaded.contentType,
        storageType: uploaded.storageType,
        instantHit: uploaded.instantHit
      };
      sendChatMessage(isImage ? uploaded.url : uploaded.fileName, {
        msgType: isImage ? 'IMAGE' : 'FILE',
        extra: JSON.stringify(meta)
      });
      if (uploaded.instantHit) {
        pushToast(`命中 SHA-256 秒传（${uploaded.storageType}），零流量极速发送`, 'success');
      } else {
        pushToast(`上传成功（存储引擎：${uploaded.storageType}）`, 'success');
      }
    } catch (e: any) {
      pushToast('上传失败：' + e.message, 'error');
    }
  }

  async function recallMessage(msgId: string) {
    try {
      await apiRequest<void>(`/api/messages/${encodeURIComponent(msgId)}/recall`, token.value, {
        method: 'POST'
      });
      pushToast('消息已撤回');
    } catch (e: any) {
      pushToast(e.message || '撤回失败', 'error');
    }
  }

  async function toggleReaction(msgId: string, emoji: string) {
    try {
      const updated = await apiRequest<ReactionGroup[]>(
        `/api/messages/${encodeURIComponent(msgId)}/reactions`,
        token.value,
        {
          method: 'POST',
          body: JSON.stringify({ emoji })
        }
      );
      const target = messages.value.find((m) => String(m.id) === String(msgId));
      if (target) {
        target.reactions = updated;
      }
    } catch (e: any) {
      pushToast(e.message || '表态失败', 'error');
    }
  }

  async function pinMessage(msgId: string) {
    if (!activeConvId.value) return;
    try {
      await apiRequest<PinnedMessage>(
        `/api/conversations/${encodeURIComponent(activeConvId.value)}/pins/${encodeURIComponent(msgId)}`,
        token.value,
        { method: 'POST' }
      );
      await loadActivePins(activeConvId.value);
      pushToast('已置顶到会话公告栏', 'success');
    } catch (e: any) {
      pushToast(e.message || '置顶失败', 'error');
    }
  }

  async function unpinMessage(msgId: string) {
    if (!activeConvId.value) return;
    try {
      await apiRequest<void>(
        `/api/conversations/${encodeURIComponent(activeConvId.value)}/pins/${encodeURIComponent(msgId)}`,
        token.value,
        { method: 'DELETE' }
      );
      activePins.value = activePins.value.filter((p) => p.msgId !== msgId);
      pushToast('已取消置顶');
    } catch (e: any) {
      pushToast(e.message || '取消置顶失败', 'error');
    }
  }

  // 多选模式（合并转发 + AI 提炼）
  function enterMultiSelect(initialMsgId?: string) {
    multiSelectMode.value = true;
    selectedMsgIds.value = initialMsgId ? [initialMsgId] : [];
  }

  function toggleSelectMsg(msgId: string) {
    if (selectedMsgIds.value.includes(msgId)) {
      selectedMsgIds.value = selectedMsgIds.value.filter((id) => id !== msgId);
    } else {
      selectedMsgIds.value = [...selectedMsgIds.value, msgId];
    }
  }

  function exitMultiSelect() {
    multiSelectMode.value = false;
    selectedMsgIds.value = [];
  }

  function sendForwardBundle(targetConvId: string) {
    const picked = messages.value
      .filter((m) => m.id && selectedMsgIds.value.includes(m.id))
      .sort((a, b) => a.seq - b.seq);
    if (picked.length === 0) {
      pushToast('请至少勾选一条消息', 'error');
      return false;
    }
    const convTitle = activeConversation.value?.title || '聊天';
    const bundleTitle = `${convTitle}的聊天记录`;
    const meta: ForwardBundleMeta = {
      title: bundleTitle,
      items: picked.map((m) => ({
        senderNickname: m.senderNickname,
        msgType: m.msgType,
        content:
          m.msgType === 'IMAGE'
            ? '[图片]'
            : m.msgType === 'FILE'
              ? `[文件] ${m.content}`
              : m.content.slice(0, 300),
        createdAt: m.createdAt
      }))
    };
    const ok = sendChatMessage(bundleTitle, {
      conversationId: targetConvId,
      msgType: 'FORWARD_BUNDLE',
      extra: JSON.stringify(meta)
    });
    if (ok) {
      exitMultiSelect();
      pushToast(`已合并转发 ${picked.length} 条消息`, 'success');
    }
    return ok;
  }

  async function triggerAiSummary(
    mode: 'UNREAD' | 'SELECTED',
    opts?: { afterSeq?: number; msgIds?: string[] }
  ) {
    if (!activeConvId.value) return;
    summarizing.value = true;
    try {
      await apiRequest<string>('/api/agent/summarize', token.value, {
        method: 'POST',
        body: JSON.stringify({
          conversationId: activeConvId.value,
          mode,
          afterSeq: opts?.afterSeq ?? null,
          msgIds: opts?.msgIds ?? null
        })
      });
      if (mode === 'UNREAD') {
        unreadDigestBanner.value = null;
        pushToast('AI 正在为你省流总结未读消息...', 'success');
      } else {
        exitMultiSelect();
        pushToast('已将选中消息交给 AI 提炼纪要...', 'success');
      }
    } catch (e: any) {
      pushToast(e.message || '触发 AI 总结失败', 'error');
    } finally {
      summarizing.value = false;
    }
  }

  function cancelAgentTask(taskId: string, convId: string) {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    if (agentStreams.value[taskId]) {
      agentStreams.value[taskId].stopping = true;
    }
    ws.send(
      JSON.stringify({
        type: 'AGENT_CANCEL',
        requestId: 'cancel-' + Date.now(),
        timestamp: Date.now(),
        data: { taskId, conversationId: convId }
      })
    );
  }

  function submitApproval(card: ApprovalCardState, approved: boolean) {
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      pushToast('WebSocket 未连接', 'error');
      return;
    }
    card.decided = approved ? 'approved' : 'rejected';
    ws.send(
      JSON.stringify({
        type: 'AGENT_APPROVAL',
        requestId: 'appr-' + Date.now(),
        timestamp: Date.now(),
        data: {
          taskId: card.taskId,
          approvalId: card.approvalId,
          approved,
          comment: approved ? 'Vue 工作台审批通过' : 'Vue 工作台审批拒绝'
        }
      })
    );
  }

  async function loadAgentMetrics() {
    if (!activeConvId.value || !token.value) return;
    try {
      metrics.value = await apiRequest<AgentMetrics>(
        `/api/agent/metrics?conversationId=${encodeURIComponent(activeConvId.value)}`,
        token.value
      );
    } catch (e) {
      console.error('加载观测数据失败:', e);
    }
  }

  async function searchConversationHistory(keyword: string) {
    if (!activeConvId.value || !keyword.trim()) {
      searchResults.value = [];
      return;
    }
    searchLoading.value = true;
    try {
      searchResults.value = await apiRequest<ChatMessage[]>(
        `/api/messages/search?conversationId=${encodeURIComponent(activeConvId.value)}&keyword=${encodeURIComponent(keyword.trim())}&limit=30`,
        token.value
      );
    } catch (e: any) {
      pushToast('搜索历史失败：' + e.message, 'error');
    } finally {
      searchLoading.value = false;
    }
  }

  function onServerAck(ack: { clientMsgId: string; msgId: string; conversationId: string; seq: number }) {
    const target = messages.value.find((m) => m.clientMsgId === ack.clientMsgId);
    if (target) {
      target.id = String(ack.msgId);
      target.seq = ack.seq;
      target.localStatus = 'sent';
    }
    if (String(ack.conversationId) === activeConvId.value) {
      syncNotice.value = `最新确认 #${ack.seq}`;
      if (currentUser.value) {
        cacheConversationMessages(currentUser.value.id, activeConvId.value, messages.value);
      }
    }
  }

  function onChatPush(raw: any) {
    const incoming: ChatMessage = {
      id: String(raw.msgId),
      conversationId: String(raw.conversationId),
      senderId: String(raw.senderId),
      senderNickname: raw.senderNickname || '用户',
      senderAvatar: raw.senderAvatar || '',
      clientMsgId: raw.clientMsgId,
      seq: Number(raw.seq),
      msgType: raw.msgType || 'TEXT',
      content: raw.content || '',
      replyToMsgId: raw.replyToMsgId ? String(raw.replyToMsgId) : null,
      replyToSender: raw.replyToSender || null,
      replyToContent: raw.replyToContent || null,
      extra: raw.extra || null,
      atUserIds: Array.isArray(raw.atUserIds) ? raw.atUserIds.map(String) : [],
      reactions: [],
      status: Number(raw.status || 1),
      createdAt: Number(raw.createdAt || Date.now()),
      localStatus: 'sent'
    };

    const conv = conversations.value.find((c) => c.id === incoming.conversationId);
    if (incoming.senderId !== currentUser.value?.id && !conv?.muted && incoming.msgType !== 'SYSTEM') {
      if (soundEnabled.value) {
        playMessageChime();
      }
      if (document.hidden) {
        hiddenUnreadCount++;
        updateDocumentTitle();
        if (desktopNotifyEnabled.value) {
          const bodyPreview =
            incoming.msgType === 'IMAGE'
              ? '[图片]'
              : incoming.msgType === 'FILE'
                ? `[文件] ${incoming.content}`
                : incoming.msgType === 'FORWARD_BUNDLE'
                  ? '[合并转发聊天记录]'
                  : incoming.content.slice(0, 80);
          showDesktopNotification({
            title: conv?.type === 2 ? `${conv.title} · ${incoming.senderNickname}` : incoming.senderNickname,
            body: bodyPreview,
            tag: `conv-${incoming.conversationId}`,
            icon: incoming.senderAvatar || undefined,
            onClick: () => {
              navTab.value = 'chats';
              selectConversation(incoming.conversationId);
            }
          });
        }
      }
    }

    if (incoming.conversationId === activeConvId.value) {
      const added = mergeOrAppendMessage(incoming);
      sendMsgAck(activeConvId.value, incoming.seq);
      if (added && incoming.senderId !== currentUser.value?.id) {
        unreadBelowCount.value++;
      }
      if (incoming.msgType === 'REMINDER') {
        pushToast('收到一条到点群日程提醒！', 'success');
        loadAgentMetrics();
      }
      if (currentUser.value) {
        cacheConversationMessages(currentUser.value.id, activeConvId.value, messages.value);
      }
      window.setTimeout(loadConversations, 150);
    } else {
      loadConversations();
    }
  }

  function mergeOrAppendMessage(msg: ChatMessage): boolean {
    if (msg.clientMsgId) {
      const byClient = messages.value.find((m) => m.clientMsgId === msg.clientMsgId);
      if (byClient) {
        Object.assign(byClient, msg, { localStatus: 'sent' });
        return false;
      }
    }
    if (msg.seq > 0) {
      const bySeq = messages.value.find((m) => m.seq === msg.seq);
      if (bySeq) {
        Object.assign(bySeq, msg, { localStatus: 'sent' });
        return false;
      }
    }
    messages.value.push(msg);
    messages.value.sort((a, b) => {
      if (a.seq > 0 && b.seq > 0) return a.seq - b.seq;
      return a.createdAt - b.createdAt;
    });
    return true;
  }

  function onMsgRecall(data: { conversationId: string; msgId: string; operatorNickname: string }) {
    if (String(data.conversationId) === activeConvId.value) {
      const target = messages.value.find((m) => String(m.id) === String(data.msgId));
      if (target) {
        target.status = 2;
        target.content = `${data.operatorNickname || '对方'} 撤回了一条消息`;
      }
      if (currentUser.value) {
        cacheConversationMessages(currentUser.value.id, activeConvId.value, messages.value);
      }
    }
    loadConversations();
  }

  function onTypingSignal(data: { conversationId: string; nickname: string; typing: boolean }) {
    if (String(data.conversationId) !== activeConvId.value) return;
    if (data.typing) {
      typingHint.value = `${data.nickname} 正在输入...`;
      if (typingClearTimer) window.clearTimeout(typingClearTimer);
      typingClearTimer = window.setTimeout(() => {
        typingHint.value = '';
      }, 3200);
    } else {
      typingHint.value = '';
    }
  }

  function onReadReceipt(data: { conversationId: string; userId: string; lastReadSeq: number }) {
    if (String(data.conversationId) !== activeConvId.value) return;
    const member = activeMembers.value.find((m) => String(m.userId) === String(data.userId));
    if (member) {
      member.lastReadSeq = Math.max(member.lastReadSeq || 0, Number(data.lastReadSeq || 0));
      member.online = true;
    }
  }

  function onMsgReaction(data: {
    conversationId: string;
    msgId: string;
    userId: string;
    nickname: string;
    emoji: string;
    added: boolean;
  }) {
    if (String(data.conversationId) !== activeConvId.value) return;
    const msg = messages.value.find((m) => String(m.id) === String(data.msgId));
    if (!msg) return;
    if (!msg.reactions) msg.reactions = [];
    const isMe = String(data.userId) === String(currentUser.value?.id);
    let group = msg.reactions.find((g) => g.emoji === data.emoji);

    if (data.added) {
      if (!group) {
        group = { emoji: data.emoji, count: 0, reactedByMe: false, users: [] };
        msg.reactions.push(group);
      }
      if (!group.users.includes(data.nickname)) {
        group.users.push(data.nickname);
        group.count = group.users.length;
      }
      if (isMe) group.reactedByMe = true;
    } else if (group) {
      group.users = group.users.filter((u) => u !== data.nickname);
      group.count = group.users.length;
      if (isMe) group.reactedByMe = false;
      if (group.count <= 0) {
        msg.reactions = msg.reactions.filter((g) => g.emoji !== data.emoji);
      }
    }
  }

  function onMsgPin(data: { conversationId: string; pinned: boolean; pinnedByNickname?: string }) {
    if (String(data.conversationId) === activeConvId.value) {
      loadActivePins(activeConvId.value);
    }
  }

  function onConvUpdate(data: { conversationId: string; eventType?: string }) {
    loadConversations();
    if (String(data.conversationId) === activeConvId.value) {
      loadActiveMembers(activeConvId.value);
      if (
        data.eventType === 'JOIN_REQUEST_CREATED' ||
        data.eventType === 'JOIN_REQUEST_REVIEWED' ||
        data.eventType === 'MEMBERS_CHANGED'
      ) {
        loadActiveJoinRequests(activeConvId.value);
      }
    }
  }

  function onAgentStream(data: any) {
    const convId = String(data.conversationId);
    const taskId = String(data.taskId);
    if (convId !== activeConvId.value) {
      if (data.stage === 'done' || data.stage === 'aborted') {
        loadConversations();
      }
      return;
    }

    let state = agentStreams.value[taskId];
    if (!state) {
      state = {
        taskId,
        conversationId: convId,
        stage: data.stage,
        toolName: data.toolName,
        text: '',
        createdAt: Date.now()
      };
      agentStreams.value[taskId] = state;
    }

    state.stage = data.stage;
    if (data.toolName) state.toolName = data.toolName;
    if (data.stage === 'delta' && data.delta) {
      state.text += data.delta;
    } else if (data.stage === 'done' || data.stage === 'aborted') {
      const finalMsg: ChatMessage = {
        id: data.msgId ? String(data.msgId) : undefined,
        conversationId: convId,
        senderId: '10000',
        senderNickname: 'AI 助手',
        clientMsgId: 'agent-task-' + taskId,
        seq: Number(data.seq || 0),
        msgType: 'AGENT_TEXT',
        content: state.text || (data.stage === 'aborted' ? '（生成已由用户主动中止）' : ''),
        reactions: [],
        status: 1,
        createdAt: state.createdAt,
        localStatus: data.stage === 'aborted' ? 'aborted' : 'sent'
      };
      delete agentStreams.value[taskId];
      if (finalMsg.content) {
        mergeOrAppendMessage(finalMsg);
      }
      if (finalMsg.seq > 0) {
        sendMsgAck(convId, finalMsg.seq);
      }
      if (currentUser.value) {
        cacheConversationMessages(currentUser.value.id, convId, messages.value);
      }
      window.setTimeout(() => {
        loadConversations();
        loadAgentMetrics();
      }, 200);
    } else if (data.stage === 'error') {
      state.errorMessage = data.errorMessage || 'AI 处理失败';
      window.setTimeout(loadAgentMetrics, 200);
    }
  }

  function onAgentApprovalCard(card: any) {
    const convId = String(card.conversationId);
    const taskId = String(card.taskId);
    if (convId !== activeConvId.value) {
      loadConversations();
      return;
    }
    if (agentStreams.value[taskId]) {
      delete agentStreams.value[taskId];
    }
    approvalCards.value.push({
      taskId,
      approvalId: String(card.approvalId),
      conversationId: convId,
      actionName: card.actionName,
      summary: card.summary,
      expireAt: Number(card.expireAt)
    });
    loadAgentMetrics();
  }

  function onFriendEvent(data: {
    eventType?: string;
    fromUserId?: string;
    fromNickname?: string;
    remark?: string;
  }) {
    loadContactsData();
    if (data?.eventType === 'REQUEST_RECEIVED') {
      const text = `${data.fromNickname || '新朋友'} 请求添加你为好友${data.remark ? '：' + data.remark : ''}`;
      pushToast(text, 'info');
      if (document.hidden && desktopNotifyEnabled.value) {
        showDesktopNotification({
          title: '新的好友申请 - 脉信',
          body: text,
          tag: 'friend-request',
          onClick: () => setNavTab('contacts')
        });
      }
    } else if (data?.eventType === 'REQUEST_ACCEPTED') {
      loadConversations();
      pushToast(`${data.fromNickname || '对方'} 已同意你的好友申请`, 'success');
    }
  }

  loadAuthConfig();

  if (token.value && currentUser.value) {
    loadDraftsFromStorage();
    refreshMyProfile();
    loadMyPresence();
    loadConversations();
    loadContactsData();
    connectWs();
  }

  return {
    token,
    currentUser,
    wsUrl,
    authConfig,
    theme,
    demoMode,
    soundEnabled,
    desktopNotifyEnabled,
    navTab,
    friends,
    blockedUsers,
    friendRequests,
    pendingFriendRequestCount,
    userLlmConfig,
    presenceSettings,
    presenceSummaryText,
    showPresenceModal,
    viewingProfile,
    wsState,
    wsStatusText,
    wsRttMs,
    manualOffline,
    authNotice,
    conversations,
    activeConvId,
    activeConversation,
    activeMembers,
    activePins,
    activeJoinRequests,
    messages,
    hasMoreHistory,
    loadingOlder,
    drafts,
    replyTarget,
    typingHint,
    activeStreamsList,
    activeApprovalCards,
    metrics,
    searchResults,
    searchLoading,
    unreadDigestBanner,
    unreadStartSeq,
    mentionJumpSeq,
    highlightedSeq,
    multiSelectMode,
    selectedMsgIds,
    viewingBundle,
    readDetailMsg,
    summarizing,
    syncNotice,
    unreadBelowCount,
    toasts,
    lightboxUrl,
    toggleTheme,
    toggleDemoMode,
    toggleSound,
    toggleDesktopNotify,
    setNavTab,
    loadAuthConfig,
    fetchCaptcha,
    sendEmailCode,
    sendPasswordResetCode,
    resetPasswordByEmail,
    loadFriends,
    loadContactsData,
    sendFriendRequest,
    acceptFriendRequest,
    rejectFriendRequest,
    deleteFriend,
    blockUser,
    unblockUser,
    loadMyLlmConfig,
    saveMyLlmConfig,
    loadMyPresence,
    saveMyPresence,
    isStealthForConv,
    openProfile,
    uploadAvatarFile,
    updateMyProfile,
    sendBindEmailCode,
    updateMyEmail,
    changeMyPassword,
    pushToast,
    flashHighlightSeq,
    setDraft,
    getDraft,
    login,
    register,
    logout,
    switchGateway,
    toggleManualOffline,
    loadConversations,
    loadActiveMembers,
    loadActivePins,
    searchUsers,
    startDirect,
    createGroup,
    updateConvPreferences,
    updateGroupAnnouncement,
    updateGroupSettings,
    loadActiveJoinRequests,
    approveJoinRequest,
    rejectJoinRequest,
    markConvAsReadManual,
    markConvAsUnreadManual,
    deleteConversationForMe,
    inviteGroupMembers,
    kickGroupMember,
    leaveGroup,
    renameGroup,
    selectConversation,
    loadOlderMessages,
    sendTypingSignal,
    sendChatMessage,
    sendFileMessage,
    recallMessage,
    toggleReaction,
    pinMessage,
    unpinMessage,
    enterMultiSelect,
    toggleSelectMsg,
    exitMultiSelect,
    sendForwardBundle,
    triggerAiSummary,
    cancelAgentTask,
    submitApproval,
    loadAgentMetrics,
    searchConversationHistory
  };
});
