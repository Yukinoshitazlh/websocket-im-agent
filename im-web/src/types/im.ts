export interface ApiResult<T> {
  code: number;
  message: string;
  data: T;
}

export interface UserProfile {
  id: string;
  username: string;
  email?: string;
  nickname: string;
  avatar: string;
  signature?: string;
  bio?: string;
  statusEmoji?: string;
  statusText?: string;
  online?: boolean | null;
  userType: number;
  relationStatus?: 'SELF' | 'FRIEND' | 'PENDING_OUT' | 'PENDING_IN' | 'BLOCKED' | 'BLOCKED_BY' | 'NONE';
  pendingRequestId?: string | null;
}

export interface FriendRequestItem {
  id: string;
  direction: 'INCOMING' | 'OUTGOING';
  peerUserId: string;
  peerUsername: string;
  peerNickname: string;
  peerAvatar: string;
  peerSignature?: string;
  remark: string;
  status: 'PENDING' | 'ACCEPTED' | 'REJECTED';
  createdAt: number;
}

export interface UserLlmConfig {
  enabled: boolean;
  baseUrl: string;
  model: string;
  apiKeyMask: string;
  hasApiKey: boolean;
}

export interface AuthResponse {
  token: string;
  user: UserProfile;
}

export interface AuthConfig {
  requireCaptcha: boolean;
  requireEmailCode: boolean;
  inviteCodeRequired: boolean;
  allowDemoQuickLogin: boolean;
  showDemoMode: boolean;
}

export interface CaptchaResp {
  captchaId: string;
  svgDataUri: string;
  expireSeconds: number;
}

export interface SendEmailCodeResp {
  cooldownSeconds: number;
  expireSeconds: number;
  devCodeHint?: string | null;
}

export interface WsTicketResp {
  ticket: string;
  expireSeconds: number;
}

export interface Conversation {
  id: string;
  type: number; // 1=单聊, 2=群聊
  title: string;
  ownerId?: string | null;
  currentSeq: number;
  lastReadSeq: number;
  unreadCount: number;
  mentionSeq?: number | null;
  pinned?: boolean;
  muted?: boolean;
  memberCount?: number;
  peerUserId?: string | null;
  peerAvatar?: string | null;
  peerOnline?: boolean | null;
  peerStatusEmoji?: string | null;
  peerStatusText?: string | null;
  lastMsgPreview: string;
  lastMsgTime?: number | null;
}

export interface ConversationMember {
  userId: string;
  username: string;
  nickname: string;
  avatar?: string;
  signature?: string;
  statusEmoji?: string;
  statusText?: string;
  role: number; // 1=普通成员, 2=管理员, 3=群主
  online: boolean;
  lastReadSeq: number;
}

export interface ReactionGroup {
  emoji: string;
  count: number;
  reactedByMe: boolean;
  users: string[];
}

export interface PinnedMessage {
  msgId: string;
  conversationId: string;
  seq: number;
  senderNickname: string;
  msgType: string;
  preview: string;
  pinnedByNickname: string;
  pinnedAt: number;
}

export interface FileMeta {
  url: string;
  fileName: string;
  fileSize: number;
  contentType: string;
  storageType?: string;
  instantHit?: boolean;
}

export interface ForwardBundleItem {
  senderNickname: string;
  msgType: string;
  content: string;
  createdAt: number;
}

export interface ForwardBundleMeta {
  title: string;
  items: ForwardBundleItem[];
}

export interface ChatMessage {
  id?: string;
  conversationId: string;
  senderId: string;
  senderNickname: string;
  senderAvatar?: string;
  clientMsgId?: string;
  seq: number;
  msgType:
    | 'TEXT'
    | 'IMAGE'
    | 'FILE'
    | 'FORWARD_BUNDLE'
    | 'AGENT_TEXT'
    | 'REMINDER'
    | 'SYSTEM'
    | string;
  content: string;
  replyToMsgId?: string | null;
  replyToSender?: string | null;
  replyToContent?: string | null;
  extra?: string | null;
  atUserIds?: string[];
  reactions?: ReactionGroup[];
  status: number; // 1=正常, 2=已撤回
  createdAt: number;
  localStatus?: 'sending' | 'sent' | 'aborted';
}

export interface AgentStreamState {
  taskId: string;
  conversationId: string;
  stage: 'thinking' | 'tool_call' | 'delta' | 'done' | 'aborted' | 'error';
  toolName?: string;
  text: string;
  seq?: number;
  errorMessage?: string;
  stopping?: boolean;
  createdAt: number;
}

export interface ApprovalCardState {
  taskId: string;
  approvalId: string;
  conversationId: string;
  actionName: string;
  summary: string;
  expireAt: number;
  decided?: 'approved' | 'rejected';
}

export interface AgentTaskItem {
  id: string;
  status: string;
  currentStep: number;
  ttftMs?: number | null;
  totalMs?: number | null;
  promptTokens?: number;
  completionTokens?: number;
  createdAt?: string;
}

export interface GroupReminderItem {
  id: string;
  title: string;
  content: string;
  remindAt: string;
  status: string;
}

export interface AgentMetrics {
  totalTasks: number;
  doneCount: number;
  abortedCount: number;
  waitingApprovalCount: number;
  failedCount: number;
  avgTtftMs?: number | null;
  avgTotalMs?: number | null;
  totalPromptTokens: number;
  totalCompletionTokens: number;
  recentTasks: AgentTaskItem[];
  reminders: GroupReminderItem[];
}

export interface FileUploadResp {
  id: string;
  sha256: string;
  fileName: string;
  contentType: string;
  fileSize: number;
  storageType: string;
  url: string;
  instantHit: boolean;
}

export type WsPacketType =
  | 'PING'
  | 'PONG'
  | 'CHAT_SEND'
  | 'SERVER_ACK'
  | 'CHAT_PUSH'
  | 'MSG_ACK'
  | 'MSG_RECALL'
  | 'TYPING'
  | 'READ_RECEIPT'
  | 'MSG_REACTION'
  | 'MSG_PIN'
  | 'CONV_UPDATE'
  | 'AGENT_STREAM'
  | 'AGENT_CANCEL'
  | 'AGENT_APPROVAL_CARD'
  | 'AGENT_APPROVAL'
  | 'FRIEND_EVENT'
  | 'FORCE_LOGOUT'
  | 'ERROR';

export interface WsPacket<T = unknown> {
  type: WsPacketType;
  requestId?: string;
  timestamp?: number;
  data: T;
}
