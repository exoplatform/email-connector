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
import { foldQuotedHistory } from '../../js/EmailQuotedHistoryFold.js';

/**
 * Tags that open a line box of their own. One of them anywhere in a body means the
 * sender's client expressed the message's line structure in markup — so the raw
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
        /* A body that carried no markup keeps the only layout it ever had: its own
           newlines and indentation. pre-wrap rather than a <br> pass because a mail
           signature, a quoted "> " block or an ASCII table also lean on leading
           spaces, which converting newlines alone would still collapse. Its own
           class rather than <pre>, whose rule below force-aligns to the right. */
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
     * Decide how the body reaches the iframe. Three shapes, because mail comes in
     * three and they want opposite whitespace handling:
     * <p>
     * 1. no markup at all — the sender's newlines are the whole layout, so the text
     *    is escaped and shown preformatted;<br>
     * 2. markup that draws no lines of its own (typed text in an envelope, the shape
     *    a reply arrives in) — same treatment, but the markup is kept, not escaped;<br>
     * 3. real HTML — untouched, and folded as before.
     * <p>
     * Folding is skipped for 1 and 2. It looks for a gmail_quote, a blockquote or an
     * element holding the "On … wrote:" line; a body of those shapes has none, so it
     * never found a boundary in one and returned it unchanged. Left to run over the
     * wrapper it would now find exactly one element, the wrapper, and fold from it —
     * hiding the whole message. Plain-text quoting (the leading "&gt; ") is therefore
     * still not folded: it never was, and keeping the line breaks does not change it.
     *
     * @param {string} html the raw email body
     * @returns {string} the markup to place in the iframe's body
     */
    renderBody(html) {
      if (this.isPlainTextBody(html)) {
        return this.wrapPlainText(html);
      }
      if (this.isTextInWrapper(html)) {
        // Not escaped, and deliberately: this exact string is what already went into
        // the iframe for such a body. Only the whitespace rule around it changes, so
        // nothing can render here that did not render before.
        return `<div class="ec-plain-text">${html}</div>`;
      }
      return foldQuotedHistory(html, {
        show: this.$t('emailConnector.mailBox.list.drawer.detail.showQuotedText'),
        hide: this.$t('emailConnector.mailBox.list.drawer.detail.hideQuotedText'),
      });
    },
    /**
     * Whether a body that *does* carry markup is really typed text inside an
     * envelope — the shape a reply arrives in, where a lone wrapper holds a message
     * whose only line structure is its newlines.
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
     * Whether the body arrived as plain text, i.e. carries no HTML the reader is
     * meant to see rendered.
     * <p>
     * Keyed on whether parsing the body yields a *known* HTML element, not on
     * whether it contains a "&lt;". Plain text mentioning "a &lt; b" never opens a
     * tag at all (the parser needs a letter right after the "&lt;"), and the common
     * "&lt;someone@example.com&gt;" does tokenize as a tag but as one no browser
     * recognises — so both stay plain, which is the whole point. Conversely a mail
     * with a single &lt;br&gt; is markup and is left alone.
     * <p>
     * A plain-text mail where somebody typed "&lt;b&gt;" by hand is read as HTML
     * here. That is the same call every mail client makes, and the safe direction to
     * be wrong in: it renders as it did before this fix rather than as escaped noise.
     *
     * @param {string} html the raw email body
     * @returns {boolean} true when the body should be shown as preformatted text
     */
    isPlainTextBody(html) {
      if (!html) {
        return false;
      }
      try {
        const doc = new DOMParser().parseFromString(html, 'text/html');
        if (!doc || !doc.body) {
          return false;
        }
        return !Array.from(doc.body.querySelectorAll('*')).some(el => this.isKnownHtmlElement(el.tagName));
      } catch (e) {
        // Unparseable is not a reason to mangle the mail: fall back to the previous
        // behaviour and let it through as HTML.
        return false;
      }
    },
    /**
     * Whether a tag name is one the browser actually implements.
     * <p>
     * An unrecognised name yields an HTMLUnknownElement, which is exactly how we tell
     * a real tag from the angle brackets around an email address.
     *
     * @param {string} tagName the parsed element's tag name
     * @returns {boolean} true when the browser has a real element for it
     */
    isKnownHtmlElement(tagName) {
      try {
        return !(document.createElement(tagName) instanceof HTMLUnknownElement);
      } catch (e) {
        // createElement rejects names it considers invalid; nothing the browser
        // refuses to build is markup we should honour.
        return false;
      }
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