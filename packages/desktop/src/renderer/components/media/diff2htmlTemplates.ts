/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import type { TFunction } from 'i18next';

const ENTITIES: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
  // Braces too: the result is a mustache template, and `{{` in a translation would be read as a tag.
  '{': '&#123;',
  '}': '&#125;',
};

const escapeTemplateText = (text: string): string => text.replace(/[&<>"'{}]/g, (character) => ENTITIES[character]);

/**
 * diff2html writes English of its own into every diff: the tag on each file header (CHANGED, ADDED, DELETED, RENAMED)
 * and the line shown for a file without changes. The library has no language option, so these are its own templates
 * (diff2html 3.4, same markup and classes, so its stylesheet still applies) with the words in the reader's language.
 * Pass the result as `rawTemplates`, and build it again when the language changes.
 */
export function diff2htmlTemplates(t: TFunction): Record<string, string> {
  const tag = (className: string, key: string): string =>
    `<span class="d2h-tag d2h-${className} d2h-${className}-tag">${escapeTemplateText(t(key))}</span>`;
  return {
    'tag-file-changed': tag('changed', 'preview.diffTag.changed'),
    'tag-file-added': tag('added', 'preview.diffTag.added'),
    'tag-file-deleted': tag('deleted', 'preview.diffTag.deleted'),
    'tag-file-renamed': tag('moved', 'preview.diffTag.renamed'),
    'generic-empty-diff': [
      '<tr>',
      '    <td class="{{CSSLineClass.INFO}}">',
      '        <div class="{{contentClass}}">',
      `            ${escapeTemplateText(t('preview.diffEmpty'))}`,
      '        </div>',
      '    </td>',
      '</tr>',
    ].join('\n'),
  };
}
