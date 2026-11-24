#!/usr/bin/env node
/**
 * PulseIM 真实 WebSocket 并发长连接与端到端吞吐压测脚本（基于 Node.js 24 原生 WebSocket，零第三方依赖）
 *
 * 用法示例：
 *   node scripts/load-test.mjs --users=100 --msgs=5
 *   node scripts/load-test.mjs --users=400 --msgs=3 --ws=ws://127.0.0.1:9001/ws,ws://127.0.0.1:9002/ws
 *
 * 提示：大吞吐压测时若希望测系统极限而非单用户限流，可在启动 im-server 时设置环境变量 RATE_LIMIT_ENABLED=false
 */

const args = Object.fromEntries(
    process.argv.slice(2)
        .filter(a => a.startsWith('--'))
        .map(a => {
            const idx = a.indexOf('=');
            return idx > 0 ? [a.slice(2, idx), a.slice(idx + 1)] : [a.slice(2), 'true'];
        })
);

const USERS = Math.max(2, Number(args.users || 60));
const EVEN_USERS = USERS % 2 === 0 ? USERS : USERS + 1;
const MSGS_PER_USER = Math.max(1, Number(args.msgs || 3));
const API_BASE = (args.api || 'http://127.0.0.1:8080').replace(/\/+$/, '');
const WS_ENDPOINTS = (args.ws || 'ws://127.0.0.1:9001/ws').split(',').map(s => s.trim()).filter(Boolean);
const SEND_INTERVAL_MS = Math.max(0, Number(args.interval || 150));

function percentile(sorted, p) {
    if (sorted.length === 0) return 0;
    const idx = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1);
    return sorted[Math.max(0, idx)];
}

function summarize(arr) {
    if (arr.length === 0) return { avg: 0, p50: 0, p95: 0, p99: 0, max: 0 };
    const sorted = [...arr].sort((a, b) => a - b);
    const sum = sorted.reduce((acc, v) => acc + v, 0);
    return {
        avg: Number((sum / sorted.length).toFixed(2)),
        p50: Number(percentile(sorted, 50).toFixed(2)),
        p95: Number(percentile(sorted, 95).toFixed(2)),
        p99: Number(percentile(sorted, 99).toFixed(2)),
        max: Number(sorted[sorted.length - 1].toFixed(2))
    };
}

async function apiJson(path, token, body) {
    const headers = { 'Content-Type': 'application/json' };
    if (token) headers['Authorization'] = 'Bearer ' + token;
    const res = await fetch(API_BASE + path, {
        method: body ? 'POST' : 'GET',
        headers,
        body: body ? JSON.stringify(body) : undefined
    });
    return res.json();
}

async function ensureBenchUser(index) {
    const username = `bench_u_${index}`;
    const password = 'bench_password_123';
    let json = await apiJson('/api/auth/login', null, { username, password });
    if (json.code !== 200) {
        json = await apiJson('/api/auth/register', null, { username, password, nickname: `压测用户${index}` });
    }
    if (json.code !== 200) {
        throw new Error(`初始化压测账号 ${username} 失败: ${json.message}`);
    }
    return { index, userId: String(json.data.user.id), token: json.data.token };
}

async function runPool(items, concurrency, fn) {
    const results = new Array(items.length);
    let cursor = 0;
    async function worker() {
        while (cursor < items.length) {
            const i = cursor++;
            results[i] = await fn(items[i], i);
        }
    }
    await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, worker));
    return results;
}

async function main() {
    console.log('============================================================');
    console.log(`PulseIM WebSocket 并发压测启动`);
    console.log(`模拟并发连接数: ${EVEN_USERS} | 每连接发消息: ${MSGS_PER_USER} | 网关: ${WS_ENDPOINTS.join(', ')}`);
    console.log('============================================================');

    const tPrepStart = performance.now();
    const indices = Array.from({ length: EVEN_USERS }, (_, i) => i);
    const users = await runPool(indices, 20, ensureBenchUser);

    // 两两配对建立单聊会话，形成真实跨用户推送链路
    const pairIndices = Array.from({ length: EVEN_USERS / 2 }, (_, i) => i);
    await runPool(pairIndices, 20, async (pairIdx) => {
        const a = users[pairIdx * 2];
        const b = users[pairIdx * 2 + 1];
        const res = await apiJson('/api/conversations/direct', a.token, { targetUserId: b.userId });
        if (res.code !== 200) throw new Error(`创建配对会话失败: ${res.message}`);
        a.convId = String(res.data.id);
        b.convId = String(res.data.id);
        a.peerId = b.userId;
        b.peerId = a.userId;
    });
    console.log(`[1/3] 账号与配对会话准备完成，耗时 ${((performance.now() - tPrepStart) / 1000).toFixed(2)}s`);

    // 2. 并发建立 WebSocket 长连接
    const handshakeLatencies = [];
    const ackLatencies = [];
    const e2eLatencies = [];
    const sendStartMap = new Map();
    const receivedByPeerSet = new Set();
    let wsSuccess = 0;
    let wsFailed = 0;
    let ackCount = 0;
    let rateLimitedCount = 0;
    let errorCount = 0;

    const sockets = await runPool(users, 50, (u, idx) => new Promise((resolve) => {
        const endpoint = WS_ENDPOINTS[idx % WS_ENDPOINTS.length];
        const t0 = performance.now();
        const ws = new WebSocket(`${endpoint}?token=${encodeURIComponent(u.token)}`);
        let settled = false;

        const timer = setTimeout(() => {
            if (!settled) {
                settled = true;
                wsFailed++;
                try { ws.close(); } catch {}
                resolve(null);
            }
        }, 10000);

        ws.addEventListener('open', () => {
            if (!settled) {
                settled = true;
                clearTimeout(timer);
                wsSuccess++;
                handshakeLatencies.push(performance.now() - t0);
                resolve({ user: u, ws });
            }
        });

        ws.addEventListener('error', () => {
            if (!settled) {
                settled = true;
                clearTimeout(timer);
                wsFailed++;
                resolve(null);
            }
        });

        ws.addEventListener('message', (evt) => {
            try {
                const pkt = JSON.parse(evt.data);
                if (pkt.type === 'SERVER_ACK' && pkt.data?.clientMsgId) {
                    const sentAt = sendStartMap.get(pkt.data.clientMsgId);
                    if (sentAt !== undefined) {
                        ackLatencies.push(performance.now() - sentAt);
                    }
                    ackCount++;
                } else if (pkt.type === 'CHAT_PUSH' && pkt.data?.clientMsgId) {
                    if (String(pkt.data.senderId) !== u.userId && !receivedByPeerSet.has(pkt.data.clientMsgId)) {
                        receivedByPeerSet.add(pkt.data.clientMsgId);
                        const sentAt = sendStartMap.get(pkt.data.clientMsgId);
                        if (sentAt !== undefined) {
                            e2eLatencies.push(performance.now() - sentAt);
                        }
                    }
                } else if (pkt.type === 'ERROR') {
                    if (pkt.data?.code === 4290 || pkt.data?.code === 4291) {
                        rateLimitedCount++;
                    } else {
                        errorCount++;
                    }
                }
            } catch {}
        });
    }));

    const activeClients = sockets.filter(Boolean);
    const hsStats = summarize(handshakeLatencies);
    console.log(`[2/3] 长连接建连完成: 成功 ${wsSuccess}/${EVEN_USERS} (失败 ${wsFailed}), 建连 P50=${hsStats.p50}ms, P99=${hsStats.p99}ms`);

    if (activeClients.length === 0) {
        console.error('没有建立任何有效连接，请确认 ServerApplication(8080) 和 GatewayApplication(9001) 已启动。');
        process.exit(1);
    }

    // 3. 并发发送消息并统计 ACK 与跨用户端到端延迟
    const expectedTotal = activeClients.length * MSGS_PER_USER;
    const tBenchStart = performance.now();

    for (let round = 0; round < MSGS_PER_USER; round++) {
        for (const client of activeClients) {
            const clientMsgId = `bench-${Date.now()}-${client.user.index}-${round}-${Math.random().toString(36).slice(2, 6)}`;
            sendStartMap.set(clientMsgId, performance.now());
            client.ws.send(JSON.stringify({
                type: 'CHAT_SEND',
                requestId: clientMsgId,
                timestamp: Date.now(),
                data: {
                    conversationId: client.user.convId,
                    clientMsgId,
                    msgType: 'TEXT',
                    content: `load-test msg round=${round} from=${client.user.index}`,
                    mentionAgent: false
                }
            }));
        }
        if (round < MSGS_PER_USER - 1 && SEND_INTERVAL_MS > 0) {
            await new Promise(r => setTimeout(r, SEND_INTERVAL_MS));
        }
    }

    // 等待全部 ACK 与对端推送到达（最长等待 15s）
    const waitDeadline = performance.now() + 15000;
    while (performance.now() < waitDeadline) {
        if (ackCount + rateLimitedCount + errorCount >= expectedTotal && receivedByPeerSet.size >= ackCount) {
            break;
        }
        await new Promise(r => setTimeout(r, 100));
    }

    const totalSec = (performance.now() - tBenchStart) / 1000;
    const ackStats = summarize(ackLatencies);
    const e2eStats = summarize(e2eLatencies);
    const qps = (ackCount / Math.max(0.001, totalSec)).toFixed(1);
    const deliveryRate = ackCount > 0 ? ((receivedByPeerSet.size / ackCount) * 100).toFixed(2) : '0.00';

    console.log('\n[3/3] 压测结果汇总（可直接作为简历与压测报告素材）：');
    console.log('------------------------------------------------------------');
    console.log(`并发在线连接数        : ${wsSuccess} / ${EVEN_USERS}`);
    console.log(`总发送请求数          : ${expectedTotal} (成功落库 ACK: ${ackCount}, 限流拦截: ${rateLimitedCount}, 异常: ${errorCount})`);
    console.log(`对端实时送达率        : ${receivedByPeerSet.size}/${ackCount} (${deliveryRate}%)`);
    console.log(`实测落库+推送吞吐     : ${qps} msg/s (总耗时 ${totalSec.toFixed(2)}s)`);
    console.log(`握手建连延迟 (ms)     : avg=${hsStats.avg} | P50=${hsStats.p50} | P95=${hsStats.p95} | P99=${hsStats.p99}`);
    console.log(`SERVER_ACK 延迟 (ms)  : avg=${ackStats.avg} | P50=${ackStats.p50} | P95=${ackStats.p95} | P99=${ackStats.p99}`);
    console.log(`端到端跨用户延迟 (ms) : avg=${e2eStats.avg} | P50=${e2eStats.p50} | P95=${e2eStats.p95} | P99=${e2eStats.p99}`);
    console.log('------------------------------------------------------------');

    activeClients.forEach(c => {
        try { c.ws.close(); } catch {}
    });
    process.exit(0);
}

main().catch(err => {
    console.error('压测执行出错:', err);
    process.exit(1);
});
