<script setup lang="ts">
import { ref } from 'vue';
import { Bell, Clock, RefreshCw, Search, Sparkles } from 'lucide-vue-next';
import { useChatStore } from '../stores/chat';
import { formatIsoDateTime, formatTimeShort } from '../utils/format';
import { vGlow } from '../directives/glow';

const store = useChatStore();
const activeTab = ref<'metrics' | 'search'>('metrics');
const keyword = ref('');

async function runSearch() {
  await store.searchConversationHistory(keyword.value);
}
</script>

<template>
  <aside v-glow class="inspector glass">
    <div class="inspector-tabs">
      <span class="tab-indicator" :class="{ right: activeTab === 'search' }" />
      <button
        type="button"
        class="ins-tab"
        :class="{ active: activeTab === 'metrics' }"
        @click="activeTab = 'metrics'"
      >
        <Sparkles :size="14" />
        <span>AI 观测与日程</span>
      </button>
      <button
        type="button"
        class="ins-tab"
        :class="{ active: activeTab === 'search' }"
        @click="activeTab = 'search'"
      >
        <Search :size="14" />
        <span>会话搜索</span>
      </button>
    </div>

    <Transition name="tab-swap" mode="out-in">
    <!-- Tab 1: AI 观测与日程提醒 -->
    <div v-if="activeTab === 'metrics'" key="metrics" class="inspector-body">
      <div class="section-head">
        <span>Agent 执行指标</span>
        <button type="button" class="btn-icon" title="刷新观测" @click="store.loadAgentMetrics">
          <RefreshCw :size="13" />
        </button>
      </div>

      <div v-if="store.metrics" class="kpi-grid">
        <div class="kpi-card">
          <div class="kpi-label">总任务 / 完成</div>
          <div class="kpi-val tabular">
            {{ store.metrics.totalTasks }} / {{ store.metrics.doneCount }}
          </div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">平均 TTFT</div>
          <div class="kpi-val tabular">
            {{ store.metrics.avgTtftMs != null ? `${Math.round(store.metrics.avgTtftMs)}ms` : '-' }}
          </div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">平均总耗时</div>
          <div class="kpi-val tabular">
            {{ store.metrics.avgTotalMs != null ? `${Math.round(store.metrics.avgTotalMs)}ms` : '-' }}
          </div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">累计 Tokens</div>
          <div class="kpi-val tabular">
            {{ (store.metrics.totalPromptTokens || 0) + (store.metrics.totalCompletionTokens || 0) }}
          </div>
        </div>
      </div>
      <div v-else class="empty-tip">选择会话后自动加载 Agent 观测数据</div>

      <div class="section-head" style="margin-top: 16px;">
        <span>最近 Agent 任务</span>
      </div>
      <div v-if="store.metrics?.recentTasks?.length" class="list-stack">
        <div v-for="t in store.metrics.recentTasks" :key="t.id" class="info-card">
          <div class="info-row">
            <span class="mono-id">#{{ t.id.slice(-6) }}</span>
            <span class="pill" :class="`pill-${t.status}`">{{ t.status }}</span>
          </div>
          <div class="info-sub tabular">
            <span>步数 {{ t.currentStep }}</span>
            <span v-if="t.ttftMs != null">TTFT {{ t.ttftMs }}ms</span>
            <span v-if="t.totalMs != null">总计 {{ t.totalMs }}ms</span>
          </div>
        </div>
      </div>
      <div v-else class="empty-tip">暂无 Agent 执行记录</div>

      <div class="section-head" style="margin-top: 16px;">
        <span>群日程定时提醒（ZSet 延迟队列）</span>
      </div>
      <div v-if="store.metrics?.reminders?.length" class="list-stack">
        <div v-for="r in store.metrics.reminders" :key="r.id" class="info-card">
          <div class="info-row">
            <span class="reminder-title">
              <Bell :size="12" />
              <span>{{ r.title }}</span>
            </span>
            <span class="pill" :class="`pill-${r.status}`">{{ r.status }}</span>
          </div>
          <div class="info-text">{{ r.content }}</div>
          <div class="info-sub tabular">
            <Clock :size="11" />
            <span>{{ formatIsoDateTime(r.remindAt) }}</span>
          </div>
        </div>
      </div>
      <div v-else class="empty-tip">
        试试发送「@AI 15秒后提醒大家代码评审」，审批通过后将进入 Redis ZSet 延迟队列自动推送！
      </div>
    </div>

    <!-- Tab 2: 会话历史全文搜索 -->
    <div v-else key="search" class="inspector-body">
      <form class="search-form" @submit.prevent="runSearch">
        <input
          v-model="keyword"
          class="input"
          placeholder="搜索当前会话历史消息..."
        />
        <button type="submit" class="btn btn-primary btn-xs" :disabled="store.searchLoading">
          {{ store.searchLoading ? '...' : '搜索' }}
        </button>
      </form>

      <div v-if="store.searchResults.length" class="list-stack">
        <div v-for="m in store.searchResults" :key="m.id || m.seq" class="info-card">
          <div class="info-row">
            <strong>{{ m.senderNickname }}</strong>
            <span class="mono-id tabular">#{{ m.seq }} {{ formatTimeShort(m.createdAt) }}</span>
          </div>
          <div class="info-text">{{ m.content }}</div>
        </div>
      </div>
      <div v-else class="empty-tip">
        {{ keyword.trim() ? '未搜索到匹配消息' : '输入关键字检索会话内历史记录' }}
      </div>
    </div>
    </Transition>
  </aside>
</template>

<style scoped>
.inspector {
  width: 316px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  height: 100%;
  border-radius: var(--radius-panel);
  overflow: hidden;
  animation: rise-in 0.7s var(--ease-out) 0.16s both;
}

.inspector > * {
  position: relative;
  z-index: 1;
}

.inspector-tabs {
  position: relative;
  display: grid;
  grid-template-columns: 1fr 1fr;
  margin: 10px 10px 0;
  padding: 4px;
  border-radius: 14px;
  background: var(--rail);
  border: 1px solid var(--border);
}

.tab-indicator {
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

.tab-indicator.right {
  transform: translateX(100%);
}

.ins-tab {
  position: relative;
  z-index: 1;
  border: none;
  background: transparent;
  color: var(--ink-secondary);
  padding: 8px 6px;
  font-size: 12.5px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  cursor: pointer;
  transition: color 0.3s;
}

.ins-tab.active {
  color: var(--accent);
}

.inspector-body {
  flex: 1;
  overflow-y: auto;
  padding: 14px;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  font-weight: 700;
  color: var(--ink-secondary);
  margin-bottom: 8px;
}

.kpi-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.kpi-card {
  background: var(--surface);
  border: 1px solid var(--glass-edge);
  border-radius: 14px;
  padding: 10px 11px;
  box-shadow: var(--shadow-sm);
  transition: transform 0.35s var(--ease-spring), box-shadow 0.3s;
  animation: pop-in 0.5s var(--ease-spring) both;
}

.kpi-card:nth-child(2) { animation-delay: 0.05s; }
.kpi-card:nth-child(3) { animation-delay: 0.1s; }
.kpi-card:nth-child(4) { animation-delay: 0.15s; }

.kpi-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 20px rgba(34, 52, 110, 0.12);
}

.kpi-label {
  font-size: 11px;
  color: var(--ink-muted);
}

.kpi-val {
  font-size: 16px;
  font-weight: 800;
  margin-top: 2px;
}

.list-stack {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.info-card {
  background: var(--surface);
  border: 1px solid var(--glass-edge);
  border-radius: 13px;
  padding: 9px 11px;
  display: flex;
  flex-direction: column;
  gap: 5px;
  box-shadow: var(--shadow-sm);
  animation: msg-in-left 0.45s var(--ease-spring) both;
  transition: transform 0.35s var(--ease-spring);
}

.info-card:hover {
  transform: translateX(3px);
}

.info-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  font-size: 12px;
}

.reminder-title {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-weight: 700;
  color: var(--ink);
}

.mono-id {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--ink-muted);
}

.info-text {
  font-size: 12.5px;
  color: var(--ink-secondary);
  word-break: break-word;
}

.info-sub {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  color: var(--ink-muted);
}

.search-form {
  display: flex;
  gap: 6px;
  margin-bottom: 12px;
}

.empty-tip {
  font-size: 12px;
  color: var(--ink-muted);
  padding: 10px 6px;
  line-height: 1.6;
}
</style>
