// UI 自动化画布节点分类与字段定义。
// 本项目方向是「UI 自动化」（不是视频），节点语义对应桌面/网页自动化操作步骤：
//   启动程序 → 点击 → 输入 → 等待 → 条件判断 → 截图校验 → 结束
// 每种分类对应一组浮窗编辑字段（按类型不同，浮窗内容不同）。

export type NodeCategory =
  | 'launch'
  | 'click'
  | 'input'
  | 'wait'
  | 'condition'
  | 'screenshot'
  | 'end';

export type FieldType = 'text' | 'textarea' | 'number' | 'select' | 'checkbox';

export interface FieldDef {
  /** 写入 node.data.meta 的键 */
  key: string;
  /** i18n key（canvasScene.fields.*） */
  labelKey: string;
  type: FieldType;
  /** select 类型选项 */
  options?: { value: string; labelKey: string }[];
  /** 占位符 i18n key */
  placeholderKey?: string;
}

export interface CategoryDef {
  category: NodeCategory;
  /** i18n key（canvasScene.categories.*） */
  labelKey: string;
  /** 节点标题区图标（emoji，沿用现有 SVG 节点风格） */
  icon: string;
  /** 对应 React Flow 节点形状（process/decision/io/start/end） */
  rfType: string;
  fields: FieldDef[];
}

export const CATEGORY_DEFS: Record<NodeCategory, CategoryDef> = {
  launch: {
    category: 'launch',
    labelKey: 'canvasScene.categories.launch',
    icon: '▶',
    rfType: 'start',
    fields: [
      { key: 'appName', labelKey: 'canvasScene.fields.appName', type: 'text', placeholderKey: 'canvasScene.placeholders.appName' },
      { key: 'args', labelKey: 'canvasScene.fields.args', type: 'text', placeholderKey: 'canvasScene.placeholders.args' },
    ],
  },
  click: {
    category: 'click',
    labelKey: 'canvasScene.categories.click',
    icon: '⚡',
    rfType: 'process',
    fields: [
      { key: 'selector', labelKey: 'canvasScene.fields.selector', type: 'text', placeholderKey: 'canvasScene.placeholders.selector' },
      {
        key: 'button', labelKey: 'canvasScene.fields.button', type: 'select',
        options: [
          { value: 'left', labelKey: 'canvasScene.fields.buttonLeft' },
          { value: 'right', labelKey: 'canvasScene.fields.buttonRight' },
          { value: 'double', labelKey: 'canvasScene.fields.buttonDouble' },
        ],
      },
      { key: 'occurrence', labelKey: 'canvasScene.fields.occurrence', type: 'number', placeholderKey: 'canvasScene.placeholders.occurrence' },
      { key: 'description', labelKey: 'canvasScene.fields.description', type: 'text', placeholderKey: 'canvasScene.placeholders.description' },
    ],
  },
  input: {
    category: 'input',
    labelKey: 'canvasScene.categories.input',
    icon: '⌨',
    rfType: 'process',
    fields: [
      { key: 'selector', labelKey: 'canvasScene.fields.selector', type: 'text', placeholderKey: 'canvasScene.placeholders.selector' },
      { key: 'value', labelKey: 'canvasScene.fields.value', type: 'textarea', placeholderKey: 'canvasScene.placeholders.value' },
      { key: 'clear', labelKey: 'canvasScene.fields.clear', type: 'checkbox' },
      { key: 'description', labelKey: 'canvasScene.fields.description', type: 'text', placeholderKey: 'canvasScene.placeholders.description' },
    ],
  },
  wait: {
    category: 'wait',
    labelKey: 'canvasScene.categories.wait',
    icon: '⏱',
    rfType: 'process',
    fields: [
      { key: 'waitMs', labelKey: 'canvasScene.fields.waitMs', type: 'number', placeholderKey: 'canvasScene.placeholders.waitMs' },
      {
        key: 'strategy', labelKey: 'canvasScene.fields.strategy', type: 'select',
        options: [
          { value: 'fixed', labelKey: 'canvasScene.fields.strategyFixed' },
          { value: 'element', labelKey: 'canvasScene.fields.strategyElement' },
          { value: 'text', labelKey: 'canvasScene.fields.strategyText' },
        ],
      },
      { key: 'target', labelKey: 'canvasScene.fields.target', type: 'text', placeholderKey: 'canvasScene.placeholders.target' },
    ],
  },
  condition: {
    category: 'condition',
    labelKey: 'canvasScene.categories.condition',
    icon: '◆',
    rfType: 'decision',
    fields: [
      {
        key: 'kind', labelKey: 'canvasScene.fields.kind', type: 'select',
        options: [
          { value: 'element', labelKey: 'canvasScene.fields.kindElement' },
          { value: 'text', labelKey: 'canvasScene.fields.kindText' },
          { value: 'ocr', labelKey: 'canvasScene.fields.kindOcr' },
        ],
      },
      { key: 'expect', labelKey: 'canvasScene.fields.expect', type: 'text', placeholderKey: 'canvasScene.placeholders.expect' },
      { key: 'elseLabel', labelKey: 'canvasScene.fields.elseLabel', type: 'text', placeholderKey: 'canvasScene.placeholders.elseLabel' },
    ],
  },
  screenshot: {
    category: 'screenshot',
    labelKey: 'canvasScene.categories.screenshot',
    icon: '📸',
    rfType: 'io',
    fields: [
      { key: 'label', labelKey: 'canvasScene.fields.label', type: 'text', placeholderKey: 'canvasScene.placeholders.label' },
      { key: 'verify', labelKey: 'canvasScene.fields.verify', type: 'checkbox' },
      { key: 'expectText', labelKey: 'canvasScene.fields.expectText', type: 'text', placeholderKey: 'canvasScene.placeholders.expectText' },
    ],
  },
  end: {
    category: 'end',
    labelKey: 'canvasScene.categories.end',
    icon: '■',
    rfType: 'end',
    fields: [],
  },
};

const ACTION_TO_CATEGORY: Array<[RegExp, NodeCategory]> = [
  [/click/i, 'click'],
  [/type|input|fill/i, 'input'],
  [/wait/i, 'wait'],
  [/screenshot|ocr|vlm|read/i, 'screenshot'],
];

/** 从节点推断其 UI 自动化分类（优先 meta.category，其次 action，再次 RF 类型）。 */
export function deriveCategory(node: any): NodeCategory {
  const meta = node?.data?.meta || {};
  if (meta.category && CATEGORY_DEFS[meta.category as NodeCategory]) {
    return meta.category as NodeCategory;
  }
  const action: string = node?.data?.action || '';
  for (const [re, cat] of ACTION_TO_CATEGORY) {
    if (re.test(action)) return cat;
  }
  switch (node?.type) {
    case 'start':
      return 'launch';
    case 'decision':
      return 'condition';
    case 'end':
      return 'end';
    case 'io':
      return 'screenshot';
    default:
      return 'click';
  }
}
