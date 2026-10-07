import type { RagCitationOptions } from '../types/ragCitation';

interface MarkdownNode {
  type: string;
  value?: string;
  url?: string;
  title?: string;
  identifier?: string;
  children?: MarkdownNode[];
  position?: { start: { offset?: number }; end: { offset?: number } };
}

export function evidenceAnchor(scope: string, evidenceId: string): string {
  return `rag-evidence-${scope}-${evidenceId}`;
}

/** 只绑定正文中的已知编号，保留代码、显式链接和未知编号。 */
export function remarkRagCitations(options: RagCitationOptions) {
  const known = new Set(options.evidenceIds);
  return (tree: MarkdownNode, file: { value: unknown }) => {
    const source = String(file.value);
    const definitions = new Set<string>();
    const collect = (node: MarkdownNode) => {
      if (node.type === 'definition' && node.identifier) definitions.add(node.identifier.toLowerCase());
      node.children?.forEach(collect);
    };
    collect(tree);
    const visit = (node: MarkdownNode) => {
      if (['code', 'inlineCode', 'link', 'linkReference', 'definition', 'html'].includes(node.type)) return;
      if (!node.children) return;
      node.children = node.children.flatMap(child => {
        // CommonMark 会把相邻的 [E1][E2] 当作完整引用链接；没有定义时还原为正文标记。
        if (child.type === 'linkReference' && !definitions.has(child.identifier?.toLowerCase() || '')) {
          const raw = source.slice(child.position?.start.offset, child.position?.end.offset);
          if (/^(?:\[E[0-9]+\]\s*){2,}$/.test(raw)) child = { ...child, type: 'text', value: raw };
        }
        if (child.type !== 'text' || !child.value) {
          visit(child);
          return [child];
        }
        // Markdown 解析会移除转义符；含转义标记的文本节点保守保持原样。
        const raw = source.slice(child.position?.start.offset, child.position?.end.offset);
        if (/\\\[E[0-9]+\]/.test(raw)) return [child];
        const parts: MarkdownNode[] = [];
        let offset = 0;
        for (const match of child.value.matchAll(/\[(E[0-9]+)\]/g)) {
          if (!known.has(match[1])) continue;
          const start = match.index!;
          if (start > offset) parts.push({ type: 'text', value: child.value.slice(offset, start) });
          parts.push({
            type: 'link', url: `#${evidenceAnchor(options.scope, match[1])}`,
            title: `查看来源 ${match[1]}`,
            children: [{ type: 'text', value: match[0] }],
          });
          offset = start + match[0].length;
        }
        if (offset === 0) return [child];
        if (offset < child.value.length) parts.push({ type: 'text', value: child.value.slice(offset) });
        return parts;
      });
    };
    visit(tree);
  };
}
