import type { ChatMessage } from '../types/im';

const DB_NAME = 'pulse_im_cache_v1';
const STORE_NAME = 'messages';

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        const store = db.createObjectStore(STORE_NAME, { keyPath: 'cacheKey' });
        store.createIndex('by_conv', 'conversationId', { unique: false });
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

export async function cacheConversationMessages(
  userId: string,
  conversationId: string,
  messages: ChatMessage[]
): Promise<void> {
  try {
    const db = await openDb();
    const tx = db.transaction(STORE_NAME, 'readwrite');
    const store = tx.objectStore(STORE_NAME);
    const valid = messages.filter((m) => m.seq > 0).slice(-120);
    for (const msg of valid) {
      store.put({
        ...msg,
        cacheKey: `${userId}:${conversationId}:${msg.seq}`
      });
    }
  } catch {
    // 浏览器隐私模式禁用 IndexedDB 时静默降级
  }
}

export async function loadCachedMessages(
  userId: string,
  conversationId: string
): Promise<ChatMessage[]> {
  try {
    const db = await openDb();
    return await new Promise((resolve) => {
      const tx = db.transaction(STORE_NAME, 'readonly');
      const index = tx.objectStore(STORE_NAME).index('by_conv');
      const req = index.getAll(IDBKeyRange.only(conversationId));
      req.onsuccess = () => {
        const prefix = `${userId}:${conversationId}:`;
        const rows = (req.result || [])
          .filter((item: any) => typeof item.cacheKey === 'string' && item.cacheKey.startsWith(prefix))
          .sort((a: ChatMessage, b: ChatMessage) => a.seq - b.seq);
        resolve(rows);
      };
      req.onerror = () => resolve([]);
    });
  } catch {
    return [];
  }
}
