#!/usr/bin/env node
/**
 * 脉信 PulseIM 真实 WebSocket 并发长连接与端到端吞吐压测脚本（Node.js 24 原生实现，零第三方依赖）
 *
 * 特性（已适配 v2.0 一次性 WS 票据 + v2.1 好友关系校验）：
 *   1. 每次运行自动生成独立批次的临时压测账号（tmp_lt_<runId>_*）并初始化配对好友关系；
 *   2. 使用本地 .env 中的 JWT_SECRET 签发令牌，并走真实 POST /api/auth/ws-ticket 获取 30s 一次性握手票据；
 *   3. 压测结束、发生异常或按 Ctrl+C 中断时，自动在事务内彻底清理本批次全部临时账号、会话、消息与好友关系，库内零残留。
 *
 * 用法示例：
 *   node scripts/load-test.mjs --users=60 --msgs=3
 *   node scripts/load-test.mjs --users=200 --msgs=5 --ws=ws://127.0.0.1:9001/ws,ws://127.0.0.1:9002/ws
 *
 * 可选参数：
 *   --users=60         模拟并发连接数（自动向上取偶，默认 60）
 *   --msgs=3           每个连接发送的消息数（默认 3）
 *   --interval=150     每轮发送间隔毫秒数（默认 150）
 *   --api=...          im-server HTTP 地址（默认 http://127.0.0.1:8080）
 *   --ws=...           im-gateway WS 地址，多个用逗号分隔（默认 ws://127.0.0.1:9001/ws）
 *   --env=...          指定 .env 路径（默认项目根目录 .env）
 *   --mysql=...        指定 mysql 客户端可执行文件路径（默认自动探测 PATH 与 Windows MySQL 8）
 *   --keep             保留本次临时账号用于排障（默认跑完自动清理）
 *
 * 提示：大吞吐压测若希望测系统极限而非单用户限流，可在启动 im-server 时设置 RATE_LIMIT_ENABLED=false
 */

import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, '..');

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
const KEEP_TEMP_DATA = args.keep === 'true';

// 合法的 BCrypt 占位哈希（临时账号直接签发 JWT，不会用于真实密码登录且跑完即删）
const DUMMY_BCRYPT_HASH = '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy';

function loadEnvConfig() {
    const envPath = args.env ? path.resolve(args.env) : path.join(REPO_ROOT, '.env');
    const fileEnv = {};
    if (fs.existsSync(envPath)) {
        const content = fs.readFileSync(envPath, 'utf8');
        for (const rawLine of content.split(/\r?\n/)) {
            const line = rawLine.trim();
            if (!line || line.startsWith('#')) continue;
            const eq = line.indexOf('=');
            if (eq <= 0) continue;
            const key = line.slice(0, eq).trim();
            let val = line.slice(eq + 1).trim();
            if ((val.startsWith('"') && val.endsWith('"')) || (val.startsWith("'") && val.endsWith("'"))) {
                val = val.slice(1, -1);
            }
            fileEnv[key] = val;
        }
    }
    const pick = (k, fallback) => process.env[k] || fileEnv[k] || fallback;
    return {
        mysqlHost: args['mysql-host'] || pick('MYSQL_HOST', '127.0.0.1'),
        mysqlPort: args['mysql-port'] || pick('MYSQL_PORT', '3306'),
        mysqlDatabase: args['mysql-db'] || pick('MYSQL_DATABASE', 'im_agent_chat'),
        mysqlUser: args['mysql-user'] || pick('MYSQL_USER', 'root'),
        mysqlPassword: args['mysql-password'] || pick('MYSQL_PASSWORD', ''),
        jwtSecret: args['jwt-secret'] || pick('JWT_SECRET', 'im-agent-local-dev-jwt-secret-key-32bytes!')
    };
}

function resolveMysqlBin() {
    if (args.mysql) {
        if (!fs.existsSync(args.mysql)) {
            throw new Error(`指定的 mysql 路径不存在: ${args.mysql}`);
        }
        return args.mysql;
    }
    try {
        execFileSync('mysql', ['--version'], { stdio: 'ignore' });
        return 'mysql';
    } catch {}

    const candidates = [
        'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe',
        'C:\\Program Files\\MySQL\\MySQL Server 8.4\\bin\\mysql.exe',
        'C:\\Program Files\\MySQL\\MySQL Server 9.0\\bin\\mysql.exe'
    ];
    for (const c of candidates) {
        if (fs.existsSync(c)) return c;
    }
    throw new Error('未找到 mysql 客户端，请将 mysql 加入 PATH 或通过 --mysql=<路径> 指定');
}

function execMysql(cfg, mysqlBin, sql) {
    const cliArgs = [
        '--default-character-set=utf8mb4',
        '--batch',
        '--raw',
        '-h', String(cfg.mysqlHost),
        '-P', String(cfg.mysqlPort),
        '-u', String(cfg.mysqlUser),
        String(cfg.mysqlDatabase)
    ];
    const env = { ...process.env };
    if (cfg.mysqlPassword) {
        env.MYSQL_PWD = cfg.mysqlPassword;
    }
    return execFileSync(mysqlBin, cliArgs, {
        input: sql,
        encoding: 'utf8',
        env,
        stdio: ['pipe', 'pipe', 'pipe']
    });
}

function base64Url(input) {
    return Buffer.from(input).toString('base64url');
}

/**
 * 与 im-common JwtTokenUtil (JJWT Keys.hmacShaKeyFor) 完全对齐的原生 JWT 签发
 */
function signJwt(secret, userId, username, tokenVersion = 1, expireSeconds = 3600) {
    const secretBuf = Buffer.from(secret, 'utf8');
    if (secretBuf.length < 32) {
        throw new Error('JWT_SECRET 长度不足 32 字节，请检查 .env 配置');
    }
    const alg = secretBuf.length < 48 ? 'HS256' : secretBuf.length < 64 ? 'HS384' : 'HS512';
    const hashAlg = alg === 'HS256' ? 'sha256' : alg === 'HS384' ? 'sha384' : 'sha512';
    const nowSec = Math.floor(Date.now() / 1000);
    const header = base64Url(JSON.stringify({ alg, typ: 'JWT' }));
    const payload = base64Url(JSON.stringify({
        sub: String(userId),
        username,
        tv: tokenVersion,
        iat: nowSec,
        exp: nowSec + expireSeconds
    }));
    const signingInput = `${header}.${payload}`;
    const signature = crypto.createHmac(hashAlg, secretBuf).update(signingInput).digest('base64url');
    return `${signingInput}.${signature}`;
}

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

async function apiJson(reqPath, token, body, method) {
    const headers = { 'Content-Type': 'application/json' };
    if (token) headers['Authorization'] = 'Bearer ' + token;
    const res = await fetch(API_BASE + reqPath, {
        method: method || (body !== undefined ? 'POST' : 'GET'),
        headers,
        body: body !== undefined ? JSON.stringify(body) : undefined
    });
    const text = await res.text();
    let json = null;
    try { json = JSON.parse(text); } catch {}
    return { status: res.status, json, text };
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

function buildTempUsers(runId, count, jwtSecret) {
    // 15 位纯数字 ID（远小于 JS Number.MAX_SAFE_INTEGER，且与雪花 ID/种子 ID 零冲突）
    const baseId = BigInt(`89${String(Date.now()).slice(-9)}0000`);
    const users = [];
    for (let i = 0; i < count; i++) {
        const userId = String(baseId + BigInt(i));
        const username = `tmp_lt_${runId}_${i}`;
        const nickname = `临时压测${i}`;
        const token = signJwt(jwtSecret, userId, username, 1);
        users.push({ index: i, userId, username, nickname, token });
    }
    return users;
}

function provisionTempUsersInDb(cfg, mysqlBin, users) {
    const hasFriendship = execMysql(cfg, mysqlBin, "SHOW TABLES LIKE 'im_friendship';").trim().includes('im_friendship');

    const sqlParts = ['START TRANSACTION;'];
    const chunkSize = 250;

    for (let i = 0; i < users.length; i += chunkSize) {
        const chunk = users.slice(i, i + chunkSize);
        const values = chunk.map(u =>
            `(${u.userId}, '${u.username}', '${DUMMY_BCRYPT_HASH}', '${u.nickname}', 1, 1)`
        ).join(',\n');
        sqlParts.push(`INSERT INTO im_user (id, username, password_hash, nickname, user_type, status) VALUES\n${values};`);
    }

    if (hasFriendship) {
        const pairValues = [];
        for (let i = 0; i < users.length; i += 2) {
            const a = users[i];
            const b = users[i + 1];
            pairValues.push(`(${a.userId}, ${b.userId}, 1)`, `(${b.userId}, ${a.userId}, 1)`);
        }
        for (let i = 0; i < pairValues.length; i += 500) {
            sqlParts.push(`INSERT IGNORE INTO im_friendship (user_id, friend_id, status) VALUES\n${pairValues.slice(i, i + 500).join(',\n')};`);
        }
    }

    sqlParts.push('COMMIT;');
    execMysql(cfg, mysqlBin, sqlParts.join('\n'));
}

function cleanupTempUsersInDb(cfg, mysqlBin, runId) {
    const likePattern = `tmp\\_lt\\_${runId}\\_%`;
    const hasFriendship = execMysql(cfg, mysqlBin, "SHOW TABLES LIKE 'im_friendship';").trim().includes('im_friendship');
    const hasFriendRequest = execMysql(cfg, mysqlBin, "SHOW TABLES LIKE 'im_friend_request';").trim().includes('im_friend_request');
    const hasLlmConfig = execMysql(cfg, mysqlBin, "SHOW TABLES LIKE 'im_user_llm_config';").trim().includes('im_user_llm_config');

    const sql = `
DROP TEMPORARY TABLE IF EXISTS tmp_lt_u;
DROP TEMPORARY TABLE IF EXISTS tmp_lt_keep;
DROP TEMPORARY TABLE IF EXISTS tmp_lt_conv;
DROP TEMPORARY TABLE IF EXISTS tmp_lt_msg;

CREATE TEMPORARY TABLE tmp_lt_u (id BIGINT PRIMARY KEY)
SELECT id FROM im_user WHERE username LIKE '${likePattern}';

CREATE TEMPORARY TABLE tmp_lt_keep (id BIGINT PRIMARY KEY)
SELECT u.id FROM im_user u
LEFT JOIN tmp_lt_u b ON b.id = u.id
WHERE b.id IS NULL AND u.user_type <> 2;

CREATE TEMPORARY TABLE tmp_lt_conv (id BIGINT PRIMARY KEY)
SELECT m.conversation_id AS id
FROM im_conversation_member m
LEFT JOIN tmp_lt_keep k ON k.id = m.user_id
GROUP BY m.conversation_id
HAVING COUNT(k.id) = 0
   AND SUM(m.user_id IN (SELECT id FROM tmp_lt_u)) > 0;

CREATE TEMPORARY TABLE tmp_lt_msg (id BIGINT PRIMARY KEY)
SELECT msg.id FROM im_message msg
JOIN tmp_lt_conv c ON c.id = msg.conversation_id;

SELECT
  (SELECT COUNT(*) FROM tmp_lt_u) AS users_deleted,
  (SELECT COUNT(*) FROM tmp_lt_conv) AS convs_deleted,
  (SELECT COUNT(*) FROM tmp_lt_msg) AS msgs_deleted;

START TRANSACTION;
DELETE r FROM im_message_reaction r JOIN tmp_lt_conv c ON c.id = r.conversation_id;
DELETE p FROM im_conversation_pin p JOIN tmp_lt_conv c ON c.id = p.conversation_id;
DELETE g FROM im_group_reminder g JOIN tmp_lt_conv c ON c.id = g.conversation_id;
DELETE a FROM im_agent_approval a JOIN tmp_lt_conv c ON c.id = a.conversation_id;
DELETE t FROM im_agent_task t JOIN tmp_lt_conv c ON c.id = t.conversation_id;
DELETE msg FROM im_message msg JOIN tmp_lt_msg x ON x.id = msg.id;
DELETE m FROM im_conversation_member m JOIN tmp_lt_conv c ON c.id = m.conversation_id;
DELETE cv FROM im_conversation cv JOIN tmp_lt_conv c ON c.id = cv.id;

DELETE r FROM im_message_reaction r JOIN tmp_lt_u b ON b.id = r.user_id;
DELETE m FROM im_conversation_member m JOIN tmp_lt_u b ON b.id = m.user_id;
${hasFriendship ? 'DELETE f FROM im_friendship f JOIN tmp_lt_u b ON b.id = f.user_id;\nDELETE f FROM im_friendship f JOIN tmp_lt_u b ON b.id = f.friend_id;' : ''}
${hasFriendRequest ? 'DELETE fr FROM im_friend_request fr JOIN tmp_lt_u b ON b.id = fr.from_user_id;\nDELETE fr FROM im_friend_request fr JOIN tmp_lt_u b ON b.id = fr.to_user_id;' : ''}
${hasLlmConfig ? 'DELETE l FROM im_user_llm_config l JOIN tmp_lt_u b ON b.id = l.user_id;' : ''}
DELETE u FROM im_user u JOIN tmp_lt_u b ON b.id = u.id;
COMMIT;

SELECT COUNT(*) AS remaining FROM im_user WHERE username LIKE '${likePattern}';
`;
    const output = execMysql(cfg, mysqlBin, sql).trim().split(/\r?\n/);
    // 输出格式：header1, row1(users\tconvs\tmsgs), header2, row2(remaining)
    const counts = (output[1] || '0\t0\t0').split('\t');
    const remaining = output[3] ?? '?';
    return {
        users: Number(counts[0] || 0),
        convs: Number(counts[1] || 0),
        msgs: Number(counts[2] || 0),
        remaining: Number(remaining)
    };
}

async function resolveWsConnectUrl(endpoint, user) {
    const res = await apiJson('/api/auth/ws-ticket', user.token, undefined, 'POST');
    if (res.status === 200 && res.json?.code === 200 && res.json?.data?.ticket) {
        return `${endpoint}?ticket=${encodeURIComponent(res.json.data.ticket)}`;
    }
    if (res.status === 404) {
        return `${endpoint}?token=${encodeURIComponent(user.token)}`;
    }
    throw new Error(`获取 WS 一次性票据失败 (HTTP ${res.status}): ${res.json?.message || res.text}`);
}

async function main() {
    const cfg = loadEnvConfig();
    const mysqlBin = resolveMysqlBin();
    const runId = `${Date.now().toString(36)}${crypto.randomBytes(2).toString('hex')}`;

    console.log('============================================================');
    console.log('脉信 PulseIM WebSocket 临时账号并发压测启动');
    console.log(`批次 ID: tmp_lt_${runId}_* | 并发连接: ${EVEN_USERS} | 每连接发消息: ${MSGS_PER_USER}`);
    console.log(`API: ${API_BASE} | 网关: ${WS_ENDPOINTS.join(', ')}`);
    console.log(`跑完自动清理: ${KEEP_TEMP_DATA ? '已关闭 (--keep)' : '开启（零残留）'}`);
    console.log('============================================================');

    let provisioned = false;
    let cleanedUp = false;
    let activeClients = [];

    const closeAllSockets = () => {
        for (const c of activeClients) {
            try { c.ws.close(); } catch {}
        }
        activeClients = [];
    };

    const runCleanup = () => {
        if (!provisioned || cleanedUp || KEEP_TEMP_DATA) return;
        cleanedUp = true;
        try {
            const stat = cleanupTempUsersInDb(cfg, mysqlBin, runId);
            console.log(`[清理] 已自动删除临时压测数据: 账号 ${stat.users} 个, 会话 ${stat.convs} 个, 消息 ${stat.msgs} 条 (本批次剩余: ${stat.remaining})`);
        } catch (err) {
            console.error('[清理失败] 请手动执行 scripts/cleanup-bench-users.sql:', err.message);
        }
    };

    const onInterrupt = (signal) => {
        console.log(`\n收到 ${signal} 中断信号，正在关闭连接并清理临时压测账号...`);
        closeAllSockets();
        runCleanup();
        process.exit(130);
    };
    process.once('SIGINT', onInterrupt);
    process.once('SIGTERM', onInterrupt);

    try {
        // 1. 批量创建临时账号 + 配对好友关系 + 建立真实单聊会话
        const tPrepStart = performance.now();
        const users = buildTempUsers(runId, EVEN_USERS, cfg.jwtSecret);
        provisionTempUsersInDb(cfg, mysqlBin, users);
        provisioned = true;

        const pairIndices = Array.from({ length: EVEN_USERS / 2 }, (_, i) => i);
        await runPool(pairIndices, 20, async (pairIdx) => {
            const a = users[pairIdx * 2];
            const b = users[pairIdx * 2 + 1];
            const res = await apiJson('/api/conversations/direct', a.token, { targetUserId: b.userId });
            if (res.status !== 200 || res.json?.code !== 200) {
                throw new Error(`创建配对会话失败 (HTTP ${res.status}): ${res.json?.message || res.text}`);
            }
            a.convId = String(res.json.data.id);
            b.convId = String(res.json.data.id);
            a.peerId = b.userId;
            b.peerId = a.userId;
        });
        console.log(`[1/3] 临时账号与配对会话准备完成，耗时 ${((performance.now() - tPrepStart) / 1000).toFixed(2)}s`);

        // 2. 获取一次性 WS 票据并并发建立 WebSocket 长连接
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

        const sockets = await runPool(users, 50, async (u, idx) => {
            const endpoint = WS_ENDPOINTS[idx % WS_ENDPOINTS.length];
            let wsUrl;
            try {
                wsUrl = await resolveWsConnectUrl(endpoint, u);
            } catch (err) {
                wsFailed++;
                if (wsFailed <= 3) console.error(`  票据签发失败: ${err.message}`);
                return null;
            }

            return new Promise((resolve) => {
                const t0 = performance.now();
                const ws = new WebSocket(wsUrl);
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
            });
        });

        activeClients = sockets.filter(Boolean);
        const hsStats = summarize(handshakeLatencies);
        console.log(`[2/3] 长连接建连完成: 成功 ${wsSuccess}/${EVEN_USERS} (失败 ${wsFailed}), 建连 P50=${hsStats.p50}ms, P99=${hsStats.p99}ms`);

        if (activeClients.length === 0) {
            throw new Error('没有建立任何有效连接，请确认 ServerApplication(8080) 和 GatewayApplication(9001) 已启动。');
        }

        // 3. 并发发送消息并统计 ACK 与跨用户端到端延迟
        const expectedTotal = activeClients.length * MSGS_PER_USER;
        const tBenchStart = performance.now();

        for (let round = 0; round < MSGS_PER_USER; round++) {
            for (const client of activeClients) {
                const clientMsgId = `lt-${runId}-${client.user.index}-${round}-${Math.random().toString(36).slice(2, 6)}`;
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
    } finally {
        closeAllSockets();
        // 给网关短暂缓冲处理断连帧，避免清理后仍有在途写库
        await new Promise(r => setTimeout(r, 200));
        runCleanup();
    }
}

main()
    .then(() => process.exit(0))
    .catch(err => {
        console.error('压测执行出错:', err.message || err);
        process.exit(1);
    });
