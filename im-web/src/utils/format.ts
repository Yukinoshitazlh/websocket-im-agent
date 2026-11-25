import type { FileMeta, ForwardBundleMeta } from '../types/im';

const AVATAR_PALETTE = [
  '#2457F5',
  '#0D7A5F',
  '#7C3AED',
  '#C2410C',
  '#0369A1',
  '#BE185D',
  '#4338CA',
  '#0F766E'
];

export function avatarColor(seed: string): string {
  let hash = 0;
  for (let i = 0; i < seed.length; i++) {
    hash = (hash * 31 + seed.charCodeAt(i)) >>> 0;
  }
  return AVATAR_PALETTE[hash % AVATAR_PALETTE.length];
}

export function avatarInitial(name: string): string {
  const trimmed = (name || '?').trim();
  return trimmed ? trimmed.slice(0, 1).toUpperCase() : '?';
}

export function formatTimeShort(ts?: number | null): string {
  if (!ts) return '';
  const d = new Date(ts);
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${hh}:${mm}`;
}

export function formatDateDivider(ts: number): string {
  const d = new Date(ts);
  const now = new Date();
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const yesterdayStart = todayStart - 86_400_000;
  if (ts >= todayStart) {
    return `${hh}:${mm}`;
  }
  if (ts >= yesterdayStart) {
    return `昨天 ${hh}:${mm}`;
  }
  const month = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${month}-${day} ${hh}:${mm}`;
}

export function formatFileSize(bytes?: number | null): string {
  if (!bytes || bytes <= 0) return '0 B';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}

export function parseFileMeta(extra?: string | null): FileMeta | null {
  if (!extra) return null;
  try {
    const parsed = JSON.parse(extra);
    if (parsed && typeof parsed.url === 'string') {
      return parsed as FileMeta;
    }
  } catch {
    // ignore invalid json
  }
  return null;
}

export function parseForwardBundle(extra?: string | null): ForwardBundleMeta | null {
  if (!extra) return null;
  try {
    const parsed = JSON.parse(extra);
    if (parsed && Array.isArray(parsed.items)) {
      return parsed as ForwardBundleMeta;
    }
  } catch {
    // ignore invalid json
  }
  return null;
}

export function formatIsoDateTime(iso?: string | null): string {
  if (!iso) return '-';
  return iso.replace('T', ' ').slice(0, 19);
}
