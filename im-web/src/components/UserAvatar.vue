<script setup lang="ts">
import { ref, watch } from 'vue';
import { Sparkles, Users } from 'lucide-vue-next';
import { avatarColor, avatarInitial } from '../utils/format';

const props = withDefaults(
  defineProps<{
    name: string;
    seed?: string;
    src?: string | null;
    size?: 'xs' | 'sm' | 'md' | 'lg' | 'xl';
    isAi?: boolean;
    isGroup?: boolean;
    online?: boolean | null;
    clickable?: boolean;
  }>(),
  {
    size: 'md',
    isAi: false,
    isGroup: false,
    online: null,
    clickable: false
  }
);

const imgFailed = ref(false);

watch(
  () => props.src,
  () => {
    imgFailed.value = false;
  }
);
</script>

<template>
  <span
    class="u-avatar"
    :class="[`sz-${size}`, { 'is-ai': isAi, 'is-clickable': clickable }]"
    :style="
      !isAi && (!src || imgFailed)
        ? { background: isGroup ? 'linear-gradient(135deg, #109E7A, #0B7A69)' : avatarColor(seed || name) }
        : undefined
    "
  >
    <Sparkles v-if="isAi" :size="size === 'xs' ? 12 : size === 'sm' ? 14 : 18" />
    <img
      v-else-if="src && !imgFailed"
      :src="src"
      :alt="name"
      class="u-avatar-img"
      @error="imgFailed = true"
    />
    <Users v-else-if="isGroup" :size="size === 'sm' ? 14 : 16" />
    <span v-else>{{ avatarInitial(name) }}</span>

    <span v-if="online" class="online-dot" title="当前在线" />
  </span>
</template>

<style scoped>
.u-avatar {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-weight: 700;
  flex-shrink: 0;
  user-select: none;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.35), 0 3px 10px rgba(20, 30, 70, 0.16);
  transition: transform 0.3s var(--ease-spring), box-shadow 0.25s;
}

.u-avatar.is-clickable {
  cursor: pointer;
}

.u-avatar.is-clickable:hover {
  transform: scale(1.06);
  box-shadow: 0 6px 18px rgba(47, 91, 255, 0.28), 0 0 0 2px var(--glass-edge);
}

.u-avatar.is-ai {
  background: linear-gradient(135deg, #12B48C, #0B7F9E);
}

.sz-xs {
  width: 28px;
  height: 28px;
  border-radius: 9px;
  font-size: 11px;
}

.sz-sm {
  width: 32px;
  height: 32px;
  border-radius: 10px;
  font-size: 12px;
}

.sz-md {
  width: 36px;
  height: 36px;
  border-radius: 11px;
  font-size: 14px;
}

.sz-lg {
  width: 42px;
  height: 42px;
  border-radius: 13px;
  font-size: 15px;
}

.sz-xl {
  width: 74px;
  height: 74px;
  border-radius: 22px;
  font-size: 28px;
}

.u-avatar-img {
  width: 100%;
  height: 100%;
  border-radius: inherit;
  object-fit: cover;
  display: block;
}
</style>
