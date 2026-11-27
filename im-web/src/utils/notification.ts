/**
 * 浏览器系统级桌面通知封装（Notification API）
 * - 支持权限检测与按需申请；
 * - 仅在页面切后台/失焦时提醒，点击通知自动聚焦浏览器窗口并执行跳转回调。
 */
export function isDesktopNotificationSupported(): boolean {
  return typeof window !== 'undefined' && 'Notification' in window;
}

export function getDesktopNotificationPermission(): NotificationPermission | 'unsupported' {
  if (!isDesktopNotificationSupported()) return 'unsupported';
  return window.Notification.permission;
}

export async function requestDesktopNotificationPermission(): Promise<NotificationPermission | 'unsupported'> {
  if (!isDesktopNotificationSupported()) return 'unsupported';
  if (window.Notification.permission === 'granted') return 'granted';
  try {
    return await window.Notification.requestPermission();
  } catch {
    return window.Notification.permission;
  }
}

export function showDesktopNotification(options: {
  title: string;
  body: string;
  tag?: string;
  icon?: string;
  onClick?: () => void;
}) {
  if (!isDesktopNotificationSupported()) return;
  if (window.Notification.permission !== 'granted') return;
  try {
    const notice = new window.Notification(options.title, {
      body: options.body,
      tag: options.tag,
      icon: options.icon || undefined
    });
    notice.onclick = () => {
      window.focus();
      options.onClick?.();
      notice.close();
    };
    window.setTimeout(() => notice.close(), 6000);
  } catch {
    // 忽略部分浏览器在非 HTTPS 下的通知限制
  }
}
