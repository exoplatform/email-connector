<!--
Copyright (C) 2025 eXo Platform SAS.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program. If not, see <http://www.gnu.org/licenses/>.
-->
<template>
  <span v-if="isEmptyBody">
    {{ $t('emailConnector.mailBox.list.drawer.emptyEmail') }}</span>
  <iframe
    v-else
    ref="iframe"
    :srcdoc="sanitizedBody"
    :style="{
      width: '100%',
      border: 'none',
      height: iframeHeight + 'px',
      visibility: iframeVisible ? 'visible' : 'hidden',
      display: 'block',
    }"
    @load="onLoadIframe"
    title="email-body"></iframe>
</template>

<script>
import { foldPlainTextQuotedHistory, foldQuotedHistory } from '../../js/EmailQuotedHistoryFold.js';

/**
 * Tags that open a line box of their own. One of them anywhere in an HTML body means
 * the sender's client expressed the message's line structure in markup — so the raw
 * newlines around them are the source's own formatting, and collapsing them is what
 * a mail client is supposed to do.
 *
 * DIV is deliberately absent: it is the tag mail clients also use as a plain
 * envelope around typed text, so it is counted rather than merely detected.
 */
const LINE_STRUCTURE_TAGS = [
  'BR', 'P', 'PRE', 'HR', 'BLOCKQUOTE',
  'TABLE', 'THEAD', 'TBODY', 'TFOOT', 'TR', 'TD', 'TH', 'CAPTION', 'COL', 'COLGROUP',
  'UL', 'OL', 'LI', 'DL', 'DT', 'DD',
  'H1', 'H2', 'H3', 'H4', 'H5', 'H6',
  'ARTICLE', 'SECTION', 'ASIDE', 'HEADER', 'FOOTER', 'NAV', 'MAIN',
  'FIGURE', 'FIGCAPTION', 'FIELDSET', 'FORM', 'CENTER', 'ADDRESS',
];

/**
 * How many DIVs a body may hold and still count as "typed text in an envelope".
 * One wraps the whole message and renders as a single block; a second means the
 * sender's client is using DIVs as the message's lines.
 */
const MAX_ENVELOPE_DIVS = 1;

export default {
  data() {
    return {
      iframeHeight: 0,
      iframeVisible: false,
      resizeObserver: null
    };
  },
  props: {
    emailBody: {
      type: String,
      default: null,
    },
    // What the message said about its own body, carried from the Content-Type of the
    // part the sync took it from. Defaults to true so that anything reaching this
    // component without an answer renders as it always did, rather than as escaped text.
    htmlBody: {
      type: Boolean,
      default: true,
    },
    expandedDrawer: {
      type: Boolean,
      default: false,
    },
  },
  computed: {
    sanitizedBody() {
      return this.makeMailHtml(this.emailBody || '');
    },
    isEmptyBody() {
      if (!this.emailBody) {
        return true;
      }  
      const emailBodyText = this.emailBody.replace(/<[^>]*>/g, '').trim();
      return emailBodyText === '';
    }
  },
  watch: {
    expandedDrawer() {
      this.$nextTick(() => this.recalculateIframeHeight());
    }
  },
  methods: {
    /**
     * Wrap the (untrusted) email body into a self-contained HTML document for the
     * iframe, first folding the quoted history behind a Gmail-style "···" toggle so
     * the reader lands on the latest message and reaches the attachments row without
     * scrolling past the quoted thread. Folding degrades to the untouched body when
     * no clear quoted boundary is found.
     *
     * @param {string} html the sanitized email body HTML
     * @returns {string} the full HTML document served to the iframe srcdoc
     */
    makeMailHtml(html) {
      const baseCSS = `
        html, body {
          margin: 0 !important;
          padding: 0 !important;
          width:100%; height:auto;
          font-family:Roboto, Arial, sans-serif;
          line-height: 1 !important;
        }
        body {
          overflow: hidden; 
          padding-bottom: 2px;
        }
        body > *:last-child { 
          margin-bottom: 0 !important;
        }
        img {
          display:block; max-width:100%; height:auto;
        }
        table { border-collapse: collapse; }
        td, th { word-break: break-word; }
        p, div { margin:0; }
        a { color:#1a73e8; text-decoration:none; word-break: break-word; }
        .ec-quoted-toggle {
          display: inline-block;
          margin: 8px 0;
          line-height: 1.4;
          color: #1a73e8;
          cursor: pointer;
          user-select: none;
          font-size: 14px;
          font-weight: 500;
        }
        .ec-quoted-toggle:hover { text-decoration: underline; }
        .ec-quoted-history { margin-top: 4px; }
        /* A body whose line structure lives in its newlines keeps the only layout it
           ever had: those newlines, and its indentation. pre-wrap rather than a <br>
           pass because a mail signature, a quoted "> " block or an ASCII table also
           lean on leading spaces, which converting newlines alone would still
           collapse. Its own class rather than <pre>, whose rule below force-aligns to
           the right. */
        .ec-plain-text {
          white-space: pre-wrap;
          word-wrap: break-word;
          overflow-wrap: break-word;
          line-height: 1.4;
        }
      `;
      const responsiveCSS = `
        * { max-width: 100% !important; box-sizing: border-box !important; }
        table { width: 100% !important; height: auto !important; }
        td, th { word-break: break-word !important; }
        img { max-width: 100% !important; height: auto !important; display:block; }
        pre {
          white-space: pre-wrap !important;
          word-wrap: break-word !important;
          direction: auto !important;
          text-align: right !important;
          overflow: visible !important;
        }
        [dir="RTL"], [dir="rtl"] {
          direction: rtl !important;
          text-align: right !important;
        }
      `;
      const finalCSS = this.expandedDrawer ? baseCSS : baseCSS + responsiveCSS;
      const renderedBody = this.renderBody(html);
      return `
        <html>
          <head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>${finalCSS}</style>
          </head>
          <body>${renderedBody}</body>
        </html>
      `;
    },
    /**
     * Decide how the body reaches the iframe, from what the message said it was.
     * <p>
     * A plain-text body is escaped and shown preformatted: its newlines and its
     * indentation are the whole layout it has, and HTML would collapse both. Its quoted
     * history folds too, from the attribution line above the "&gt; " block — the markup
     * fold cannot see it, there being no markup, so such a reply used to show its whole
     * thread.
     * <p>
     * An HTML body is served as it always was, except for one shape — typed text
     * inside a lone wrapper, which the flag cannot and should not tell apart from any
     * other HTML: the part really is text/html, and the sender's client simply left the
     * message's line structure in raw newlines instead of markup. So that one stays a
     * question about the markup, and only about the markup.
     *
     * @param {string} html the raw email body
     * @returns {string} the markup to place in the iframe's body
     */
    renderBody(html) {
      if (!this.htmlBody) {
        return this.foldPlainTextHistory(html) || this.wrapPlainText(html);
      }
      if (this.isTextInWrapper(html)) {
        // Not escaped, and deliberately: this exact string is what already went into
        // the iframe for such a body. Only the whitespace rule around it changes, so
        // nothing can render here that did not render before. Folded before being
        // wrapped, so the fold sees the body's own elements and never our wrapper.
        return `<div class="ec-plain-text">${this.foldHistory(html)}</div>`;
      }
      return this.foldHistory(html);
    },
    /**
     * Collapse the quoted history behind the "See more" toggle, in the reader's
     * language.
     *
     * @param {string} html the email body to fold
     * @returns {string} the folded body, or the original when nothing was folded
     */
    foldHistory(html) {
      return foldQuotedHistory(html, this.quotedHistoryLabels());
    },
    /**
     * Collapse the quoted history of a plain-text body, where the boundary is an
     * attribution line above a run of "&gt; " lines rather than any markup.
     *
     * @param {string} text the plain-text email body
     * @returns {string|null} the folded body, or null when there is nothing to fold
     */
    foldPlainTextHistory(text) {
      return foldPlainTextQuotedHistory(text, this.quotedHistoryLabels());
    },
    /**
     * The toggle's two texts, in the reader's language.
     *
     * @returns {{show: string, hide: string}} the collapsed and expanded link texts
     */
    quotedHistoryLabels() {
      return {
        show: this.$t('emailConnector.mailBox.list.drawer.detail.showQuotedText'),
        hide: this.$t('emailConnector.mailBox.list.drawer.detail.hideQuotedText'),
      };
    },
    /**
     * Whether an HTML body is really typed text inside an envelope — the shape a reply
     * arrives in, where a lone wrapper holds a message whose only line structure is its
     * newlines.
     * <p>
     * This is the one question the server's flag cannot answer, and the reason it stays
     * here: the flag reports the part's Content-Type, which for such a body correctly
     * says text/html. What it cannot report is that the sender's client put no line
     * structure in the markup, and that is precisely what has to be known to keep the
     * message's lines.
     * <p>
     * Three things must hold together, and each one rules out a class of real HTML
     * mail: the body has newlines to save at all; it holds no tag that opens a line
     * box, which every newsletter, notification and quoted reply does; and it has at
     * most one DIV, so a client using DIVs as the message's lines is left alone.
     * <p>
     * The residual misfire is a machine-generated body that is pretty-printed across
     * several source lines yet uses no block tag whatsoever. That combination is rare
     * — generated HTML reaches for tables or paragraphs immediately — and the cost is
     * cosmetic, some source indentation becoming visible, not a mangled message.
     *
     * @param {string} html the raw email body
     * @returns {boolean} true when the newlines are the body's only line structure
     */
    isTextInWrapper(html) {
      if (!html || !html.includes('\n')) {
        return false;
      }
      try {
        const doc = new DOMParser().parseFromString(html, 'text/html');
        if (!doc || !doc.body) {
          return false;
        }
        const elements = Array.from(doc.body.querySelectorAll('*'));
        if (elements.some(el => LINE_STRUCTURE_TAGS.includes(el.tagName))) {
          return false;
        }
        if (elements.filter(el => el.tagName === 'DIV').length > MAX_ENVELOPE_DIVS) {
          return false;
        }
        return this.countTextLines(doc.body.textContent) > 1;
      } catch (e) {
        // Same rule as everywhere here: when in doubt, render as before.
        return false;
      }
    },
    /**
     * How many lines of actual text a body holds, blank ones ignored. A single line
     * means there is no line structure to preserve, and pre-wrapping it could only
     * expose the source's own indentation.
     *
     * @param {string} text the body's text content
     * @returns {number} the count of non-blank lines
     */
    countTextLines(text) {
      return (text || '').split('\n').filter(line => line.trim() !== '').length;
    },
    /**
     * Put a plain-text body into a container that keeps its line breaks.
     * <p>
     * Escaped first, and escaped by the DOM rather than by hand: the body is the
     * sender's text, so a mail whose text happens to read "&lt;script&gt;" must show
     * those characters, not become the tag. Wrapping unescaped text is how a
     * line-break fix turns into an injection.
     *
     * @param {string} text the raw plain-text email body
     * @returns {string} the escaped text wrapped in the preformatted container
     */
    wrapPlainText(text) {
      const escaper = document.createElement('div');
      escaper.textContent = text;
      return `<div class="ec-plain-text">${escaper.innerHTML}</div>`;
    },
    /**
     * Size the iframe to its content once the document is in. Measured again after a
     * beat because fonts and images land after load and each one moves the height.
     *
     * @returns {void}
     */
    onLoadIframe() {
      this.recalculateIframeHeight();

      setTimeout(() => this.recalculateIframeHeight(), 150);
      setTimeout(() => this.recalculateIframeHeight(), 400);
    },

    /**
     * Match the iframe's height to the mail it holds, so the drawer scrolls as one
     * page instead of the mail scrolling inside a fixed frame.
     *
     * @returns {void}
     */
    recalculateIframeHeight() {
      const iframe = this.$refs.iframe;
      if (!iframe) {
        return;
      }
      const doc = iframe.contentDocument || iframe.contentWindow.document;
      if (!doc || !doc.body) {
        return;
      }
      const newHeight = Math.max(doc.body.scrollHeight, doc.body.getBoundingClientRect().height);
      this.iframeHeight = newHeight;

      doc.body.style.overflow = 'hidden';
      doc.querySelectorAll('img').forEach(img => {
        if (!img.complete) {
          img.onload = () => this.recalculateIframeHeight();
        }
      });

      if (!this.iframeVisible) {
        this.iframeVisible = true;
      }

      if (!this.resizeObserver) {
        this.resizeObserver = new ResizeObserver(() => this.recalculateIframeHeight());
        this.resizeObserver.observe(doc.body);
      }
    }
  }
};
</script>