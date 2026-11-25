import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import './style.css';

// Chromium 内核支持 backdrop-filter: url(#svg)，开启 SVG 位移折射液态透镜；其他浏览器自动退化为磨砂玻璃
if (/Chrome\//.test(navigator.userAgent) && !/Firefox\//.test(navigator.userAgent)) {
  document.documentElement.setAttribute('data-refract', '');
}

const app = createApp(App);
app.use(createPinia());
app.mount('#app');
