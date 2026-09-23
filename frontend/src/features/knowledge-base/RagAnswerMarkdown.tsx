import { Button } from 'antd';
import type { Link, Root, Text } from 'mdast';
import { useMemo } from 'react';
import Markdown, { defaultUrlTransform, type Components } from 'react-markdown';
import type { Node, Parent } from 'unist';
import type { Plugin } from 'unified';
import type { RagCitation } from '../../api/contracts';

const citationHrefPrefix = '#citation-';

interface RagAnswerMarkdownProps {
  answer: string;
  citations: RagCitation[];
  onCitationClick: (citation: RagCitation) => void;
}

/**
 * Renders backend Markdown without HTML execution and turns only backend-provided
 * citation identifiers in the answer into controls for the citation drawer.
 */
export function RagAnswerMarkdown({
  answer,
  citations,
  onCitationClick,
}: RagAnswerMarkdownProps) {
  const citationsById = useMemo(
    () =>
      new Map(
        citations
          .filter((citation) => citation.citationId.trim().length > 0)
          .map((citation) => [citation.citationId, citation]),
      ),
    [citations],
  );
  const citationPlugin = useMemo(
    () => createCitationPlugin(new Set(citationsById.keys())),
    [citationsById],
  );
  const components = useMemo<Components>(
    () => ({
      a: ({ href, children }) => {
        const citationId = getCitationIdFromHref(href);
        const citation = citationId ? citationsById.get(citationId) : undefined;

        if (citation) {
          return (
            <Button
              className="rag-inline-citation"
              type="link"
              size="small"
              onClick={() => onCitationClick(citation)}
            >
              {children}
            </Button>
          );
        }

        return href ? (
          <a href={href} target="_blank" rel="noreferrer">
            {children}
          </a>
        ) : (
          <span>{children}</span>
        );
      },
    }),
    [citationsById, onCitationClick],
  );

  return (
    <div className="rag-answer-markdown">
      <Markdown
        skipHtml
        remarkPlugins={[citationPlugin]}
        components={components}
        urlTransform={safeUrlTransform}
      >
        {answer}
      </Markdown>
    </div>
  );
}

function createCitationPlugin(citationIds: ReadonlySet<string>): Plugin<[], Root> {
  const ids = [...citationIds].filter(Boolean).sort((left, right) => right.length - left.length);
  const matcher =
    ids.length > 0 ? new RegExp('\\[(' + ids.map(escapeRegExp).join('|') + ')\\]', 'g') : undefined;

  return () => (tree) => {
    if (matcher) {
      replaceCitationTextNodes(tree, matcher);
    }
  };
}

function replaceCitationTextNodes(parent: Parent, matcher: RegExp) {
  parent.children = parent.children.flatMap((child) => {
    if (child.type === 'text' && 'value' in child && typeof child.value === 'string') {
      return createCitationNodes(child as Text, matcher);
    }

    if (isParent(child) && child.type !== 'link' && child.type !== 'linkReference') {
      replaceCitationTextNodes(child, matcher);
    }

    return [child];
  });
}

function createCitationNodes(text: Text, matcher: RegExp): Node[] {
  matcher.lastIndex = 0;
  const nodes: Node[] = [];
  let cursor = 0;
  let match: RegExpExecArray | null;

  while ((match = matcher.exec(text.value)) !== null) {
    const [matchedText, citationId] = match;
    if (match.index > cursor) {
      const precedingText: Text = {
        type: 'text',
        value: text.value.slice(cursor, match.index),
      };
      nodes.push(precedingText);
    }

    const citationLink: Link = {
      type: 'link',
      url: citationHrefPrefix + encodeURIComponent(citationId),
      children: [{ type: 'text', value: matchedText }],
    };
    nodes.push(citationLink);
    cursor = match.index + matchedText.length;
  }

  if (cursor === 0) {
    return [text];
  }

  if (cursor < text.value.length) {
    const trailingText: Text = { type: 'text', value: text.value.slice(cursor) };
    nodes.push(trailingText);
  }
  return nodes;
}

function isParent(node: Node): node is Parent {
  return 'children' in node && Array.isArray(node.children);
}

function getCitationIdFromHref(href: string | undefined): string | undefined {
  if (!href?.startsWith(citationHrefPrefix)) {
    return undefined;
  }

  try {
    return decodeURIComponent(href.slice(citationHrefPrefix.length));
  } catch {
    return undefined;
  }
}

function safeUrlTransform(url: string): string {
  return url.startsWith(citationHrefPrefix) ? url : defaultUrlTransform(url);
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
