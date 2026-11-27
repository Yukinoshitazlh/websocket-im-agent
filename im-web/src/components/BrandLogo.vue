<script setup lang="ts">
/**
 * 脉信 PulseIM 品牌标识：渐变圆角方块 + 对话气泡 + 心电脉冲线 + 在线光点。
 * 与 public/favicon.svg 保持同一图形，便于在页面内按任意尺寸清晰渲染。
 */
withDefaults(defineProps<{ size?: number }>(), { size: 40 });
</script>

<template>
  <svg
    class="brand-logo-svg"
    :width="size"
    :height="size"
    viewBox="0 0 512 512"
    role="img"
    aria-label="脉信 PulseIM"
  >
    <defs>
      <linearGradient id="bl-bg" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0" stop-color="#3A6BFF" />
        <stop offset="1" stop-color="#7A5CFF" />
      </linearGradient>
      <linearGradient id="bl-gloss" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0" stop-color="#FFFFFF" stop-opacity="0.32" />
        <stop offset="0.55" stop-color="#FFFFFF" stop-opacity="0" />
      </linearGradient>
      <radialGradient id="bl-dot" cx="0.5" cy="0.5" r="0.5">
        <stop offset="0" stop-color="#E9FDFF" />
        <stop offset="1" stop-color="#5FE3FF" />
      </radialGradient>
    </defs>
    <rect width="512" height="512" rx="120" fill="url(#bl-bg)" />
    <rect width="512" height="512" rx="120" fill="url(#bl-gloss)" />
    <path
      d="M180 132H332A64 64 0 0 1 396 196V284A64 64 0 0 1 332 348H226L164 398V348A48 48 0 0 1 116 300V196A64 64 0 0 1 180 132Z"
      fill="#FFFFFF"
      fill-opacity="0.12"
      stroke="#FFFFFF"
      stroke-width="26"
      stroke-linejoin="round"
    />
    <path
      class="brand-pulse"
      d="M140 240H190L212 200L242 290L272 176L294 240H326"
      fill="none"
      stroke="#FFFFFF"
      stroke-width="24"
      stroke-linecap="round"
      stroke-linejoin="round"
    />
    <circle class="brand-dot" cx="352" cy="240" r="17" fill="url(#bl-dot)" />
  </svg>
</template>

<style scoped>
.brand-logo-svg {
  display: block;
  flex-shrink: 0;
}

.brand-pulse {
  stroke-dasharray: 420;
  stroke-dashoffset: 0;
  animation: brand-pulse-draw 3.2s ease-in-out infinite;
}

.brand-dot {
  transform-origin: 352px 240px;
  animation: brand-dot-blink 3.2s ease-in-out infinite;
}

@keyframes brand-pulse-draw {
  0% { stroke-dashoffset: 420; }
  35%, 100% { stroke-dashoffset: 0; }
}

@keyframes brand-dot-blink {
  0%, 30% { opacity: 0.35; transform: scale(0.7); }
  40% { opacity: 1; transform: scale(1.25); }
  55%, 100% { opacity: 1; transform: scale(1); }
}

@media (prefers-reduced-motion: reduce) {
  .brand-pulse,
  .brand-dot {
    animation: none;
  }
}
</style>
