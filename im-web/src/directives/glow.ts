import type { Directive } from 'vue';

interface GlowState {
  move: (e: PointerEvent) => void;
  enter: () => void;
  leave: () => void;
  raf: number;
}

const stateMap = new WeakMap<HTMLElement, GlowState>();

/**
 * v-glow：液态玻璃镜面光斑跟随指针。
 * 通过 rAF 节流写入 --mx / --my CSS 变量，样式层用 radial-gradient 渲染，零重排。
 */
export const vGlow: Directive<HTMLElement> = {
  mounted(el) {
    const state: GlowState = {
      raf: 0,
      move: (e: PointerEvent) => {
        if (state.raf) return;
        const { clientX, clientY } = e;
        state.raf = requestAnimationFrame(() => {
          state.raf = 0;
          const rect = el.getBoundingClientRect();
          el.style.setProperty('--mx', `${clientX - rect.left}px`);
          el.style.setProperty('--my', `${clientY - rect.top}px`);
        });
      },
      enter: () => el.classList.add('glow-on'),
      leave: () => el.classList.remove('glow-on')
    };
    el.addEventListener('pointermove', state.move, { passive: true });
    el.addEventListener('pointerenter', state.enter);
    el.addEventListener('pointerleave', state.leave);
    stateMap.set(el, state);
  },
  unmounted(el) {
    const state = stateMap.get(el);
    if (!state) return;
    if (state.raf) cancelAnimationFrame(state.raf);
    el.removeEventListener('pointermove', state.move);
    el.removeEventListener('pointerenter', state.enter);
    el.removeEventListener('pointerleave', state.leave);
    stateMap.delete(el);
  }
};
