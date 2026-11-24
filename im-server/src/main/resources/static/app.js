const API_BASE = '';
const AI_USER_ID = '10000';
const AVATAR_COLORS = ['#2457F5', '#0D7A5F', '#7C3AED', '#D97706', '#DB2777', '#0284C7', '#4F46E5'];

let wsUrl = sessionStorage.getItem('im_ws_url') || 'ws://127.0.0.1:9001/ws';
let authMode = 'login';
let token = sessionStorage.getItem('im_token') || '';
let currentUser = JSON.parse(sessionStorage.getItem('im_user') || 'null');

let ws = null;
let heartbeatTimer = null;
let reconnectTimer = null;
let reconnectAttempt = 0;
let manualOffline = false;
let hasConnectedOnce = false;

let activeConvId = null;
let inspectorOpen = true;
const convMaxSeqMap = new Map();
const renderedClientMsgIds = new Map();
const renderedSeqSet = new Set();
const activeAgentStreams = new Map();

function showToast(msg, type = '') {
    const box = document.getElementById('toastContainer');
    const el = document.createElement('div');
    el.className = 'toast ' + type;
    el.innerText = msg;
    box.appendChild(el);
    setTimeout(() => el.remove(), 3200);
}

function avatarColor(seed) {
    let hash = 0;
    const str = String(seed || 'u');
    for (let i = 0; i < str.length; i++) {
        hash = ((hash << 5) - hash) + str.charCodeAt(i);
    }
    return AVATAR_COLORS[Math.abs(hash) % AVATAR_COLORS.length];
}

function initials(name) {
    const s = String(name || '?').trim();
    return s.slice(0, 1).toUpperCase();
}

function switchTab(mode) {
    authMode = mode;
    document.getElementById('tabLogin').classList.toggle('active', mode === 'login');
    document.getElementById('tabReg').classList.toggle('active', mode === 'register');
    document.getElementById('nicknameField').style.display = mode === 'register' ? 'block' : 'none';
    document.getElementById('authSubmitBtn').innerText = mode === 'login' ? '登录工作台' : '创建账号并进入';
    document.getElementById('authErr').innerText = '';
}

async function api(path, options = {}) {
    const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
    if (token) headers['Authorization'] = 'Bearer ' + token;
    const res = await fetch(API_BASE + path, { ...options, headers });
    const json = await res.json();
    if (json.code !== 200) throw new Error(json.message || '请求失败');
    return json.data;
}

async function submitAuth(event) {
    if (event) event.preventDefault();
    const username = document.getElementById('username').value.trim();
    const password = document.getElementById('password').value;
    const nickname = document.getElementById('nickname').value.trim();
    try {
        const data = authMode === 'login'
            ? await api('/api/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) })
            : await api('/api/auth/register', { method: 'POST', body: JSON.stringify({ username, password, nickname }) });
        token = data.token;
        currentUser = data.user;
        sessionStorage.setItem('im_token', token);
        sessionStorage.setItem('im_user', JSON.stringify(currentUser));
        enterWorkspace();
    } catch (e) {
        document.getElementById('authErr').innerText = e.message;
    }
}

function logout() {
    manualOffline = true;
    clearTimeout(reconnectTimer);
    clearInterval(heartbeatTimer);
    if (ws) ws.close();
    sessionStorage.clear();
    location.reload();
}

function enterWorkspace() {
    document.getElementById('authScreen').style.display = 'none';
    document.getElementById('workspace').classList.add('visible');

    const av = document.getElementById('meAvatar');
    av.innerText = initials(currentUser.nickname);
    av.style.background = avatarColor(currentUser.id);
    document.getElementById('meNickname').innerText = currentUser.nickname;
    document.getElementById('gwSelect').value = wsUrl;

    loadConversations();
    connectWs();
}

function setWsUi(state, text) {
    const dot = document.getElementById('wsDot');
    dot.className = 'status-dot' + (state ? ' ' + state : '');
    document.getElementById('wsText').innerText = text;
}

function switchGateway() {
    wsUrl = document.getElementById('gwSelect').value;
    sessionStorage.setItem('im_ws_url', wsUrl);
    manualOffline = false;
    document.getElementById('toggleNetBtn').innerText = '模拟断网';
    clearTimeout(reconnectTimer);
    reconnectAttempt = 0;
    if (ws) ws.close();
    connectWs();
    showToast('已切换网关节点：' + (wsUrl.includes('9002') ? 'gateway-2 (:9002)' : 'gateway-1 (:9001)'));
}

function toggleManualOffline() {
    const btn = document.getElementById('toggleNetBtn');
    if (!manualOffline) {
        manualOffline = true;
        clearTimeout(reconnectTimer);
        clearInterval(heartbeatTimer);
        if (ws) ws.close();
        setWsUi('', '已手动断网');
        btn.innerText = '恢复联网';
        showToast('已断开长连接，可让对方发消息后点「恢复联网」体验增量补齐');
    } else {
        manualOffline = false;
        btn.innerText = '模拟断网';
        reconnectAttempt = 0;
        connectWs();
    }
}

function toggleInspector() {
    inspectorOpen = !inspectorOpen;
    document.getElementById('inspectorPane').classList.toggle('hidden', !inspectorOpen);
    if (inspectorOpen && activeConvId) {
        loadAgentMetrics();
    }
}

function connectWs() {
    if (manualOffline) return;
    const portLabel = wsUrl.includes('9002') ? 'gateway-2 :9002' : 'gateway-1 :9001';
    setWsUi('reconnecting', `连接中 (${portLabel})`);
    ws = new WebSocket(`${wsUrl}?token=${encodeURIComponent(token)}`);

    ws.onopen = async () => {
        const isReconnect = hasConnectedOnce;
        hasConnectedOnce = true;
        reconnectAttempt = 0;
        setWsUi('on', `在线 (${portLabel})`);
        clearInterval(heartbeatTimer);
        heartbeatTimer = setInterval(() => {
            if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send(JSON.stringify({ type: 'PING', requestId: 'hb-' + Date.now(), timestamp: Date.now(), data: {} }));
            }
        }, 25000);

        if (isReconnect) {
            await syncActiveConversationAfterReconnect();
            loadConversations();
        }
    };

    ws.onclose = () => {
        clearInterval(heartbeatTimer);
        if (manualOffline) return;
        const delaySec = Math.min(15, Math.pow(2, reconnectAttempt));
        reconnectAttempt++;
        setWsUi('reconnecting', `${delaySec}s 后自动重连`);
        clearTimeout(reconnectTimer);
        reconnectTimer = setTimeout(connectWs, delaySec * 1000);
    };

    ws.onmessage = (event) => {
        const pkt = JSON.parse(event.data);
        if (pkt.type === 'CHAT_PUSH') {
            onChatPush(pkt.data);
        } else if (pkt.type === 'SERVER_ACK') {
            onServerAck(pkt.data);
        } else if (pkt.type === 'AGENT_STREAM') {
            onAgentStream(pkt.data);
        } else if (pkt.type === 'AGENT_APPROVAL_CARD') {
            onAgentApprovalCard(pkt.data);
        } else if (pkt.type === 'ERROR') {
            showToast(pkt.data.message || '服务处理失败', 'error');
        }
    };
}

async function syncActiveConversationAfterReconnect() {
    if (!activeConvId) return;
    const afterSeq = convMaxSeqMap.get(activeConvId) || 0;
    try {
        const missed = await api(`/api/messages/sync?conversationId=${encodeURIComponent(activeConvId)}&afterSeq=${afterSeq}&limit=100`);
        let added = 0;
        missed.forEach(m => {
            if (appendOrMergeMessage(m)) added++;
        });
        if (added > 0) {
            const maxSeq = convMaxSeqMap.get(activeConvId) || afterSeq;
            sendMsgAck(activeConvId, maxSeq);
            document.getElementById('chatSub').innerText = `重连后已增量同步 ${added} 条消息，当前序号 #${maxSeq}`;
            showToast(`断线重连成功，自动补齐 ${added} 条离线消息`, 'success');
            scrollToBottom();
        } else {
            document.getElementById('chatSub').innerText = `重连成功，消息已是最新 (#${afterSeq})`;
        }
    } catch (e) {
        console.error('重连增量同步失败:', e);
    }
}

function sendMsgAck(convId, seq) {
    if (!convId || !seq || seq <= 0) return;
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({
            type: 'MSG_ACK',
            requestId: 'ack-' + Date.now(),
            timestamp: Date.now(),
            data: { conversationId: String(convId), seq }
        }));
    } else {
        api(`/api/messages/ack?conversationId=${encodeURIComponent(convId)}&seq=${seq}`, { method: 'POST' }).catch(() => {});
    }
}

function fillPrompt(text, autoSend = false) {
    const input = document.getElementById('msgInput');
    input.value = text;
    autoResizeInput();
    input.focus();
    if (autoSend) {
        sendMessage(false);
    }
}

function autoResizeInput() {
    const input = document.getElementById('msgInput');
    input.style.height = 'auto';
    input.style.height = Math.min(120, Math.max(42, input.scrollHeight)) + 'px';
}

function onComposerKeydown(event) {
    if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        sendMessage(false);
    }
}

function onAgentStream(data) {
    if (String(data.conversationId) !== String(activeConvId)) {
        if (data.stage === 'done' || data.stage === 'aborted') {
            loadConversations();
        }
        return;
    }
    const taskId = String(data.taskId);
    let holder = activeAgentStreams.get(taskId);
    if (!holder) {
        holder = createAgentStreamBubble(taskId, data.conversationId);
        activeAgentStreams.set(taskId, holder);
    }

    if (data.stage === 'thinking') {
        holder.statusEl.innerText = '正在分析上下文...';
    } else if (data.stage === 'tool_call') {
        holder.statusEl.innerText = `正在调用工具：${data.toolName || ''}`;
    } else if (data.stage === 'delta') {
        holder.statusEl.innerText = '正在流式生成';
        holder.buffer += (data.delta || '');
        holder.textEl.innerText = holder.buffer;
    } else if (data.stage === 'done' || data.stage === 'aborted') {
        holder.stopBtn.style.display = 'none';
        holder.statusEl.innerText = data.stage === 'aborted' ? '已主动中止' : '生成完成';
        if (data.seq) {
            renderedSeqSet.add(data.seq);
            const prev = convMaxSeqMap.get(activeConvId) || 0;
            if (data.seq > prev) convMaxSeqMap.set(activeConvId, data.seq);
            sendMsgAck(activeConvId, data.seq);
            holder.ackEl.className = 'msg-foot ' + (data.stage === 'aborted' ? 'aborted' : 'ok');
            holder.ackEl.innerText = (data.stage === 'aborted' ? '已中止 ' : '已落库 ') + `#${data.seq}`;
        }
        activeAgentStreams.delete(taskId);
        setTimeout(() => {
            loadConversations();
            if (inspectorOpen) loadAgentMetrics();
        }, 200);
    } else if (data.stage === 'error') {
        holder.stopBtn.style.display = 'none';
        holder.statusEl.innerText = '执行异常';
        holder.textEl.innerText = data.errorMessage || 'AI 助手处理异常';
        activeAgentStreams.delete(taskId);
        if (inspectorOpen) loadAgentMetrics();
    }
    scrollToBottom();
}

function createAgentStreamBubble(taskId, convId) {
    const empty = document.getElementById('emptyStage');
    if (empty) empty.remove();

    const rowEl = document.createElement('div');
    rowEl.className = 'msg-row ai';
    const timeStr = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    rowEl.innerHTML = `
        <div class="avatar avatar-sm avatar-ai">AI</div>
        <div class="msg-content-col">
            <div class="msg-meta"><span class="ai-pill">AI 助手</span><span>${timeStr}</span></div>
            <div class="msg-bubble">
                <div class="stream-bar">
                    <span class="js-status">正在分析上下文...</span>
                    <button class="btn btn-danger btn-xs js-stop">停止生成</button>
                </div>
                <div class="js-text"></div>
            </div>
            <div class="msg-foot">流式推送中</div>
        </div>
    `;
    const statusEl = rowEl.querySelector('.js-status');
    const textEl = rowEl.querySelector('.js-text');
    const stopBtn = rowEl.querySelector('.js-stop');
    const ackEl = rowEl.querySelector('.msg-foot');

    stopBtn.onclick = () => {
        if (!ws || ws.readyState !== WebSocket.OPEN) return;
        stopBtn.disabled = true;
        stopBtn.innerText = '中止中...';
        ws.send(JSON.stringify({
            type: 'AGENT_CANCEL',
            requestId: 'cancel-' + Date.now(),
            timestamp: Date.now(),
            data: { taskId: String(taskId), conversationId: String(convId) }
        }));
    };

    document.getElementById('msgList').appendChild(rowEl);
    return { rowEl, statusEl, textEl, stopBtn, ackEl, buffer: '' };
}

function onAgentApprovalCard(card) {
    if (String(card.conversationId) !== String(activeConvId)) {
        loadConversations();
        return;
    }
    const taskId = String(card.taskId);
    const holder = activeAgentStreams.get(taskId);
    if (holder) {
        holder.statusEl.innerText = '已挂起，等待成员审批确认';
        holder.stopBtn.style.display = 'none';
    }

    const el = document.createElement('div');
    el.className = 'approval-card';
    const expireStr = new Date(card.expireAt).toLocaleTimeString();
    el.innerHTML = `
        <div class="approval-head">写操作审批确认（Human-in-the-Loop）</div>
        <div class="approval-desc">${escapeHtml(card.summary)}</div>
        <div class="approval-meta">工具名称：${escapeHtml(card.actionName)} | 有效期至 ${expireStr}</div>
        <div class="approval-btns">
            <button class="btn btn-success btn-xs js-approve">同意执行</button>
            <button class="btn btn-danger btn-xs js-reject">拒绝</button>
            <span class="approval-state js-res"></span>
        </div>
    `;
    const approveBtn = el.querySelector('.js-approve');
    const rejectBtn = el.querySelector('.js-reject');
    const resEl = el.querySelector('.js-res');

    const sendDecision = (approved) => {
        if (!ws || ws.readyState !== WebSocket.OPEN) return showToast('WebSocket 未连接', 'error');
        approveBtn.disabled = true;
        rejectBtn.disabled = true;
        resEl.innerText = approved ? '已同意，正在唤醒 Agent 执行...' : '已拒绝该操作';
        ws.send(JSON.stringify({
            type: 'AGENT_APPROVAL',
            requestId: 'appr-' + Date.now(),
            timestamp: Date.now(),
            data: {
                taskId: String(card.taskId),
                approvalId: String(card.approvalId),
                approved,
                comment: approved ? '工作台确认同意' : '工作台拒绝'
            }
        }));
    };
    approveBtn.onclick = () => sendDecision(true);
    rejectBtn.onclick = () => sendDecision(false);

    document.getElementById('msgList').appendChild(el);
    scrollToBottom();
    if (inspectorOpen) loadAgentMetrics();
}

async function searchUsers() {
    try {
        const kw = document.getElementById('searchInput').value.trim();
        const users = await api('/api/users/search?keyword=' + encodeURIComponent(kw));
        const box = document.getElementById('userResults');
        box.style.display = 'block';
        box.innerHTML = users.length === 0
            ? '<div class="search-item" style="color:var(--ink-muted)">未找到匹配用户</div>'
            : users.map(u => `
                <div class="search-item">
                    <span><strong>${escapeHtml(u.nickname)}</strong> <span style="color:var(--ink-muted)">@${escapeHtml(u.username)}</span></span>
                    <button class="btn btn-primary btn-xs" onclick="startDirect('${u.id}')">发起单聊</button>
                </div>
            `).join('');
    } catch (e) {
        showToast('搜索失败：' + e.message, 'error');
    }
}

async function startDirect(targetUserId) {
    try {
        const conv = await api('/api/conversations/direct', { method: 'POST', body: JSON.stringify({ targetUserId: String(targetUserId) }) });
        document.getElementById('userResults').style.display = 'none';
        await loadConversations();
        selectConversation(String(conv.id), conv.title);
    } catch (e) {
        showToast('发起单聊失败：' + e.message, 'error');
    }
}

async function openGroupModal() {
    try {
        const users = await api('/api/users/search?keyword=');
        const listEl = document.getElementById('groupMemberList');
        if (users.length === 0) return showToast('还没有其他注册用户，请先在另一标签页注册新账号', 'error');
        listEl.innerHTML = users.map(u => `
            <label class="member-opt">
                <input type="checkbox" value="${u.id}" />
                <span>${escapeHtml(u.nickname)} (@${escapeHtml(u.username)})</span>
            </label>
        `).join('');
        document.getElementById('groupModal').classList.add('show');
    } catch (e) {
        showToast('加载用户失败：' + e.message, 'error');
    }
}

function closeGroupModal() {
    document.getElementById('groupModal').classList.remove('show');
}

async function submitCreateGroup() {
    try {
        const title = document.getElementById('groupTitleInput').value.trim();
        if (!title) return showToast('请输入群名称', 'error');
        const memberIds = Array.from(document.querySelectorAll('#groupMemberList input[type="checkbox"]:checked')).map(el => el.value);
        if (memberIds.length === 0) return showToast('请至少勾选一名群成员', 'error');
        const conv = await api('/api/conversations/group', { method: 'POST', body: JSON.stringify({ title, memberIds }) });
        closeGroupModal();
        await loadConversations();
        selectConversation(String(conv.id), conv.title);
        showToast('群聊创建成功', 'success');
    } catch (e) {
        showToast('建群失败：' + e.message, 'error');
    }
}

async function loadConversations() {
    try {
        const list = await api('/api/conversations');
        const container = document.getElementById('convList');
        if (list.length === 0) {
            container.innerHTML = '<div class="muted-empty" style="text-align:center;padding:24px 12px;">搜索上方用户发起单聊，或点击「建群」开始协作</div>';
            return;
        }
        container.innerHTML = list.map(c => {
            const unread = Number(c.unreadCount || 0);
            const rightEl = unread > 0
                ? `<span class="unread-badge">${unread > 99 ? '99+' : unread}</span>`
                : `<span class="seq-tag">#${c.currentSeq || 0}</span>`;
            const isGroup = c.type === 2;
            return `
                <div class="conv-item ${String(c.id) === String(activeConvId) ? 'active' : ''}" onclick="selectConversation('${c.id}', '${escapeHtml(c.title).replace(/'/g, "\\'")}')">
                    <div class="avatar avatar-sm" style="background:${avatarColor(c.id)}">${isGroup ? '群' : initials(c.title)}</div>
                    <div class="conv-body">
                        <div class="conv-top">
                            <span class="conv-name">${isGroup ? '<span class="conv-tag">群</span>' : ''}${escapeHtml(c.title)}</span>
                            ${rightEl}
                        </div>
                        <div class="conv-preview">${escapeHtml(c.lastMsgPreview || '暂无消息')}</div>
                    </div>
                </div>
            `;
        }).join('');
    } catch (e) {
        console.error('加载会话列表失败:', e);
    }
}

async function selectConversation(convId, title) {
    try {
        activeConvId = String(convId);
        document.getElementById('chatTitle').innerText = title;
        const history = await api(`/api/messages/history?conversationId=${encodeURIComponent(activeConvId)}&limit=50`);
        const msgList = document.getElementById('msgList');
        msgList.innerHTML = '';
        renderedClientMsgIds.clear();
        renderedSeqSet.clear();
        activeAgentStreams.clear();

        let maxSeq = 0;
        if (history.length === 0) {
            msgList.innerHTML = `<div class="empty-stage" id="emptyStage"><h3>会话已就绪</h3><p>发送第一条消息，或点击下方快捷指令体验 @AI 群聊总结与人机协同审批。</p></div>`;
        } else {
            history.forEach(m => {
                appendOrMergeMessage(m);
                if (m.seq > maxSeq) maxSeq = m.seq;
            });
        }
        convMaxSeqMap.set(activeConvId, maxSeq);
        if (maxSeq > 0) {
            sendMsgAck(activeConvId, maxSeq);
            document.getElementById('chatSub').innerText = `会话 ID ${activeConvId} | 已同步至 #${maxSeq}`;
        } else {
            document.getElementById('chatSub').innerText = `会话 ID ${activeConvId}`;
        }
        scrollToBottom();
        setTimeout(loadConversations, 150);
        if (inspectorOpen) loadAgentMetrics();
    } catch (e) {
        showToast('加载消息历史失败：' + e.message, 'error');
    }
}

async function loadAgentMetrics() {
    if (!activeConvId) return;
    try {
        const m = await api(`/api/agent/metrics?conversationId=${encodeURIComponent(activeConvId)}`);
        document.getElementById('statTotalTasks').innerText = m.totalTasks;
        document.getElementById('statAvgTtft').innerText = m.avgTtftMs != null ? `${m.avgTtftMs} ms` : '-';
        document.getElementById('statAvgTotal').innerText = m.avgTotalMs != null ? `${m.avgTotalMs} ms` : '-';
        document.getElementById('statTokens').innerText = (m.totalPromptTokens + m.totalCompletionTokens);

        const taskListEl = document.getElementById('recentTaskList');
        if (!m.recentTasks || m.recentTasks.length === 0) {
            taskListEl.innerHTML = '<div class="muted-empty">本会话暂无 @AI 执行记录</div>';
        } else {
            taskListEl.innerHTML = m.recentTasks.slice(0, 6).map(t => `
                <div class="list-card">
                    <div class="list-card-top">
                        <span style="font-weight:600;">任务 #${String(t.id).slice(-6)}</span>
                        <span class="pill pill-${escapeHtml(t.status)}">${escapeHtml(t.status)}</span>
                    </div>
                    <div style="color:var(--ink-muted);font-size:11.5px;">
                        TTFT: ${t.ttftMs != null ? t.ttftMs + 'ms' : '-'} | 总耗时: ${t.totalMs != null ? t.totalMs + 'ms' : '-'} | 步数: ${t.currentStep || 0}
                    </div>
                </div>
            `).join('');
        }

        const remListEl = document.getElementById('reminderList');
        if (!m.reminders || m.reminders.length === 0) {
            remListEl.innerHTML = '<div class="muted-empty">暂无审批创建的群提醒</div>';
        } else {
            remListEl.innerHTML = m.reminders.map(r => `
                <div class="list-card">
                    <div class="list-card-top">
                        <strong>${escapeHtml(r.title)}</strong>
                        <span class="pill pill-DONE">${escapeHtml(r.status)}</span>
                    </div>
                    <div style="color:var(--ink-secondary);margin-bottom:3px;">${escapeHtml(r.content)}</div>
                    <div style="color:var(--ink-muted);font-size:11.5px;">计划触发：${escapeHtml(String(r.remindAt || '').replace('T', ' '))}</div>
                </div>
            `).join('');
        }
    } catch (e) {
        console.error('加载 Agent 观测面板失败:', e);
    }
}

function sendMessage(duplicateTest) {
    if (!activeConvId) return showToast('请先在左侧选择或发起一个会话', 'error');
    const input = document.getElementById('msgInput');
    const content = input.value.trim() || (duplicateTest ? '幂等重发验证消息' : '');
    if (!content) return;
    if (!ws || ws.readyState !== WebSocket.OPEN) return showToast('WebSocket 未连接，请先恢复联网', 'error');

    const empty = document.getElementById('emptyStage');
    if (empty) empty.remove();

    const clientMsgId = 'c-' + Date.now() + '-' + Math.random().toString(36).slice(2, 7);
    const mentionAgent = content.toLowerCase().includes('@ai');
    const pkt = {
        type: 'CHAT_SEND',
        requestId: clientMsgId,
        timestamp: Date.now(),
        data: { conversationId: String(activeConvId), clientMsgId, msgType: 'TEXT', content, mentionAgent }
    };

    renderOptimisticMessage({
        clientMsgId,
        senderId: currentUser.id,
        senderNickname: currentUser.nickname,
        content,
        createdAt: Date.now()
    });
    scrollToBottom();

    ws.send(JSON.stringify(pkt));
    if (duplicateTest) {
        ws.send(JSON.stringify(pkt));
        showToast('已用同一 clientMsgId 连发 2 次，验证服务端双重幂等去重');
    }
    input.value = '';
    autoResizeInput();
}

function renderOptimisticMessage(msg) {
    const el = document.createElement('div');
    el.className = 'msg-row mine';
    el.dataset.clientMsgId = msg.clientMsgId;
    const timeStr = new Date(msg.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    el.innerHTML = `
        <div class="avatar avatar-sm" style="background:${avatarColor(msg.senderId)}">${initials(msg.senderNickname)}</div>
        <div class="msg-content-col">
            <div class="msg-meta"><span>${timeStr}</span><span>${escapeHtml(msg.senderNickname)}</span></div>
            <div class="msg-bubble">${escapeHtml(msg.content)}</div>
            <div class="msg-foot pending">发送中...</div>
        </div>
    `;
    document.getElementById('msgList').appendChild(el);
    renderedClientMsgIds.set(msg.clientMsgId, el);
}

function onServerAck(ack) {
    const el = renderedClientMsgIds.get(ack.clientMsgId);
    if (el) {
        const foot = el.querySelector('.msg-foot');
        foot.className = 'msg-foot ok';
        foot.innerText = `已送达 #${ack.seq}`;
        renderedSeqSet.add(ack.seq);
    }
    if (String(ack.conversationId) === String(activeConvId)) {
        const prev = convMaxSeqMap.get(activeConvId) || 0;
        if (ack.seq > prev) convMaxSeqMap.set(activeConvId, ack.seq);
        document.getElementById('chatSub').innerText = `会话 ID ${activeConvId} | 最新确认 #${ack.seq}`;
    }
}

function onChatPush(msg) {
    if (String(msg.conversationId) === String(activeConvId)) {
        appendOrMergeMessage(msg);
        const prev = convMaxSeqMap.get(activeConvId) || 0;
        if (msg.seq > prev) convMaxSeqMap.set(activeConvId, msg.seq);
        sendMsgAck(activeConvId, msg.seq);
        scrollToBottom();
        setTimeout(loadConversations, 150);
    } else {
        loadConversations();
    }
}

function appendOrMergeMessage(msg) {
    const empty = document.getElementById('emptyStage');
    if (empty) empty.remove();

    if (msg.clientMsgId && renderedClientMsgIds.has(msg.clientMsgId)) {
        const existingEl = renderedClientMsgIds.get(msg.clientMsgId);
        const foot = existingEl.querySelector('.msg-foot');
        foot.className = 'msg-foot ok';
        foot.innerText = `已送达 #${msg.seq}`;
        if (msg.seq) renderedSeqSet.add(msg.seq);
        return false;
    }
    if (msg.seq && renderedSeqSet.has(msg.seq)) {
        return false;
    }

    const isMine = String(msg.senderId) === String(currentUser.id);
    const isAi = String(msg.senderId) === AI_USER_ID || msg.msgType === 'AGENT_TEXT';
    const el = document.createElement('div');
    el.className = 'msg-row ' + (isMine ? 'mine' : (isAi ? 'ai' : ''));
    const timeStr = new Date(msg.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });

    const avatarHtml = isAi
        ? `<div class="avatar avatar-sm avatar-ai">AI</div>`
        : `<div class="avatar avatar-sm" style="background:${avatarColor(msg.senderId)}">${initials(msg.senderNickname)}</div>`;
    const metaHtml = isAi
        ? `<span class="ai-pill">AI 助手</span><span>${timeStr}</span>`
        : (isMine ? `<span>${timeStr}</span><span>${escapeHtml(msg.senderNickname)}</span>` : `<span>${escapeHtml(msg.senderNickname)}</span><span>${timeStr}</span>`);

    el.innerHTML = `
        ${avatarHtml}
        <div class="msg-content-col">
            <div class="msg-meta">${metaHtml}</div>
            <div class="msg-bubble">${escapeHtml(msg.content)}</div>
            <div class="msg-foot ${isMine ? 'ok' : ''}">${isMine ? '已送达 ' : ''}#${msg.seq}</div>
        </div>
    `;
    document.getElementById('msgList').appendChild(el);
    if (msg.clientMsgId) renderedClientMsgIds.set(msg.clientMsgId, el);
    if (msg.seq) renderedSeqSet.add(msg.seq);
    if (String(msg.conversationId) === String(activeConvId)) {
        const prev = convMaxSeqMap.get(activeConvId) || 0;
        if (msg.seq > prev) convMaxSeqMap.set(activeConvId, msg.seq);
    }
    return true;
}

function scrollToBottom() {
    const msgList = document.getElementById('msgList');
    msgList.scrollTop = msgList.scrollHeight;
}

function escapeHtml(str) {
    return String(str).replace(/[&<>"']/g, m => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[m]));
}

if (token && currentUser) enterWorkspace();
