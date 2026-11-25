export interface ApiResult<T> {
  code: number;
  message: string;
  data: T;
}

export interface UserProfile {
  id: string;
  username: string;
  nickname: string;
  avatar: string;
  userType: number;
}

export interface AuthResponse {
  token: string;
  user: UserProfile;
}

export interface Conversation {
  id: string;
  type: number; // 1=单聊, 2=群聊
  title: string;
  currentSeq: number;
  lastReadSeq: number;
  unreadCount: number;
  peerUserId?: string | null;
  peerOnline?: boolean | null;
  lastMsgPreview: string;
  lastMsgTime?: number | null;
}

export interface FileMeta {
  url: string;
  fileName: string;
  fileSize: number;
  contentType: string;
  storageType?: string;
  instantHit?: boolean;
}

export interface ChatMessage {
  id?: string;
  conversationId: string;
  senderId: string;
  senderNickname: string;
  clientMsgId?: string;
  seq: number;
  msgType: 'TEXT' | 'IMAGE' | 'FILE' | 'AGENT_TEXT' | 'REMINDER' | 'SYSTEM' | string;
  content: string;
  replyToMsgId?: string | null;
  replyToSender?: string | null;
  replyToContent?: string | null;
  extra?: string | null;
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
  | 'AGENT_STREAM'
  | 'AGENT_CANCEL'
  | 'AGENT_APPROVAL_CARD'
  | 'AGENT_APPROVAL'
  | 'ERROR';

export interface WsPacket<T = unknown> {
  type: WsPacketType;
  requestId?: string;
  timestamp?: number;
  data: T;
}
