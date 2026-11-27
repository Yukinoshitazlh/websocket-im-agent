/**
 * 零依赖中英文首字母分组与拼音自然排序工具（基于浏览器原生 Intl.Collator zh-Hans-CN）
 */

const zhCollator = new Intl.Collator('zh-Hans-CN', {
  sensitivity: 'base',
  numeric: true
});

// CLDR zh-Hans-CN 拼音排序下每个声母分组的首边界字
const PINYIN_ANCHORS: ReadonlyArray<readonly [string, string]> = [
  ['A', '吖'],
  ['B', '八'],
  ['C', '嚓'],
  ['D', '咑'],
  ['E', '妸'],
  ['F', '发'],
  ['G', '旮'],
  ['H', '哈'],
  ['J', '丌'],
  ['K', '咔'],
  ['L', '拉'],
  ['M', '妈'],
  ['N', '拿'],
  ['O', '噢'],
  ['P', '趴'],
  ['Q', '七'],
  ['R', '呥'],
  ['S', '撒'],
  ['T', '他'],
  ['W', '屲'],
  ['X', '夕'],
  ['Y', '丫'],
  ['Z', '帀']
];

// 常见多音姓氏优先按姓氏读音归类
const SURNAME_OVERRIDES: Record<string, string> = {
  曾: 'Z',
  仇: 'Q',
  单: 'S',
  解: 'X',
  查: 'Z',
  区: 'O',
  朴: 'P',
  翟: 'Z',
  任: 'R',
  乐: 'Y'
};

/**
 * 提取昵称的首字母分组键（A-Z，非中英文字符统一归为 '#'）
 */
export function getInitialLetter(raw: string | null | undefined): string {
  const text = (raw || '').trim();
  if (!text) return '#';

  const first = Array.from(text)[0] || '';
  // 全角英文字母转半角
  const code = first.charCodeAt(0);
  const normalized =
    code >= 0xff21 && code <= 0xff3a
      ? String.fromCharCode(code - 0xfee0)
      : code >= 0xff41 && code <= 0xff5a
        ? String.fromCharCode(code - 0xfee0)
        : first;

  if (/^[A-Za-z]$/.test(normalized)) {
    return normalized.toUpperCase();
  }

  if (SURNAME_OVERRIDES[first]) {
    return SURNAME_OVERRIDES[first];
  }

  // CJK 统一表意文字（含扩展区常见汉字）
  if (/[\u4e00-\u9fff]/.test(first)) {
    let letter = '#';
    for (const [key, anchor] of PINYIN_ANCHORS) {
      if (zhCollator.compare(first, anchor) >= 0) {
        letter = key;
      } else {
        break;
      }
    }
    return letter;
  }

  return '#';
}

export interface AlphaGroup<T> {
  letter: string;
  items: T[];
}

/**
 * 按首字母 A-Z（# 置底）分组，并在组内按中文拼音 + 英文自然序排列
 */
export function groupByInitial<T>(
  items: T[],
  getName: (item: T) => string
): AlphaGroup<T>[] {
  const buckets = new Map<string, T[]>();

  for (const item of items) {
    const letter = getInitialLetter(getName(item));
    const list = buckets.get(letter);
    if (list) {
      list.push(item);
    } else {
      buckets.set(letter, [item]);
    }
  }

  const keys = Array.from(buckets.keys()).sort((a, b) => {
    if (a === '#') return 1;
    if (b === '#') return -1;
    return a.localeCompare(b);
  });

  return keys.map((letter) => ({
    letter,
    items: (buckets.get(letter) || []).sort((a, b) =>
      zhCollator.compare(getName(a) || '', getName(b) || '')
    )
  }));
}
