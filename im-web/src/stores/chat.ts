import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import type {
  AgentMetrics,
  AgentStreamState,
  ApprovalCardState,
  AuthResponse,
  ChatMessage,
  Conversation,
  ConversationMember,
  FileMeta,
  ForwardBundleMeta,
  PinnedMessage,
  ReactionGroup,
  UserProfile,
  WsPacket
} from '../types/im';
import { apiRequest, uploadFileWithInstantCheck } from '../utils/api';
import { cacheConversationMessages, loadCachedMessages } from '../utils/db';

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

export const useChatStore = defineStore('chat', () => {
  const token = ref<string>(sessionStorage.getItem('im_token') || '');
  const currentUser = ref<UserProfile | null>(
    JSON.parse(sessionStorage.getItem('im_user') || 'null')
  );
  const wsUrl = ref<string>(sessionStorage.getItem('im_ws_url') || 'ws://127.0.0.1:9001/ws');
  const theme = ref<'light' | 'dark'>((localStorage.getItem('im_theme') as 'light' | 'dark') || 'light');
  const demoMode = ref<boolean>(localStorage.getItem('im_demo_mode') === '1');

  const wsState = ref<'disconnected' | 'connecting' | 'connected'>('disconnected');
  const wsStatusText = ref<string>('未连接');
  const wsRttMs = ref<number | null>(null);
  const manualOffline = ref<boolean>(false);

  const conversations = ref<Conversation[]>([]);
  const activeConvId = ref<string | null>(null);
  const activeMembers = ref<ConversationMember[]>([]);
  const activePins = ref<PinnedMessage[]>([]);
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
  let toastSeq = 1;
  let hiddenUnreadCount = 0;

  const activeConversation = computed(() =>
    conversations.value.find((c) => c.id === activeConvId.value) || null
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
    demoMode.value = !demoMode.value;
    localStorage.setItem('im_demo_mode', demoMode.value ? '1' : '0');
    pushToast(
      demoMode.value
        ? '已开启面试演练模式：显示双网关切换、断网模拟、#seq 与幂等重发'
        : '已切回日常纯净模式'
    );
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
        ? `(${hiddenUnreadCount}) 新消息 - PulseIM`
        : 'PulseIM — 分布式即时通信与 AI 协同工作台';
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

  async function login(username: string, password: string) {
    const data = await apiRequest<AuthResponse>('/api/auth/login', '', {
      method: 'POST',
      body: JSON.stringify({ username, password })
    });
    setAuthSession(data);
  }

  async function register(username: string, password: string, nickname: string) {
    const data = await apiRequest<AuthResponse>('/api/auth/register', '', {
      method: 'POST',
      body: JSON.stringify({ username, password, nickname })
    });
    setAuthSession(data);
  }

  function setAuthSession(data: AuthResponse) {
    token.value = data.token;
    currentUser.value = data.user;
    sessionStorage.setItem('im_token', data.token);
    sessionStorage.setItem('im_user', JSON.stringify(data.user));
    loadDraftsFromStorage();
    loadConversations();
    connectWs();
  }

  function logout() {
    manualOffline.value = true;
    clearTimers();
    ws?.close();
    sessionStorage.clear();
    token.value = '';
    currentUser.value = null;
    conversations.value = [];
    messages.value = [];
    activeConvId.value = null;
    activeMembers.value = [];
    activePins.value = [];
    exitMultiSelect();
  }

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

  function connectWs() {
    if (!token.value || manualOffline.value) return;
    const label = wsUrl.value.includes('9002') ? 'gateway-2 :9002' : 'gateway-1 :9001';
    wsState.value = 'connecting';
    wsStatusText.value = `连接中 (${label})`;

    const socket = new WebSocket(`${wsUrl.value}?token=${encodeURIComponent(token.value)}`);
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

    socket.onclose = () => {
      if (ws !== socket) return;
      if (heartbeatTimer) window.clearInterval(heartbeatTimer);
      wsRttMs.value = null;
      if (manualOffline.value) return;

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
    prefs: { pinned?: boolean; muted?: boolean }
  ) {
    await apiRequest<void>(`/api/conversations/${encodeURIComponent(convId)}/preferences`, token.value, {
      method: 'POST',
      body: JSON.stringify(prefs)
    });
    await loadConversations();
    if (prefs.pinned !== undefined) {
      pushToast(prefs.pinned ? '已置顶会话' : '已取消置顶', 'success');
    } else if (prefs.muted !== undefined) {
      pushToast(prefs.muted ? '已开启消息免打扰' : '已关闭消息免打扰', 'success');
    }
  }

  async function markConvAsReadManual(convId: string) {
    const conv = conversations.value.find((c) => c.id === convId);
    if (!conv || conv.currentSeq <= 0) return;
    sendMsgAck(convId, conv.currentSeq);
    window.setTimeout(loadConversations, 150);
    pushToast('已标为已读');
  }

  async function inviteGroupMembers(convId: string, memberIds: string[]) {
    await apiRequest<void>(`/api/conversations/${encodeURIComponent(convId)}/members`, token.value, {
      method: 'POST',
      body: JSON.stringify({ memberIds })
    });
    await loadActiveMembers(convId);
    await loadConversations();
    pushToast('已邀请新成员入群', 'success');
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
      if (maxLoadedSeq.value > 0) {
        sendMsgAck(convId, maxLoadedSeq.value);
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
    if (document.hidden && incoming.senderId !== currentUser.value?.id && !conv?.muted) {
      hiddenUnreadCount++;
      updateDocumentTitle();
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

  function onConvUpdate(data: { conversationId: string }) {
    loadConversations();
    if (String(data.conversationId) === activeConvId.value) {
      loadActiveMembers(activeConvId.value);
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

  if (token.value && currentUser.value) {
    loadDraftsFromStorage();
    loadConversations();
    connectWs();
  }

  return {
    token,
    currentUser,
    wsUrl,
    theme,
    demoMode,
    wsState,
    wsStatusText,
    wsRttMs,
    manualOffline,
    conversations,
    activeConvId,
    activeConversation,
    activeMembers,
    activePins,
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
    markConvAsReadManual,
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
