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
  <!--
    The message is somebody else's markup, so the frame it is shown in runs no script:
    no `allow-scripts`, never together with `allow-same-origin`, since a frame with
    both can lift its own sandbox. That stops inline handlers, `javascript:` URLs,
    nested frames and a `<meta refresh>` as well; forms, embedded documents and players
    are the purifier's job, the sandbox alone would let a form out through a popup. The
    frame keeps the page's origin only so that this component can read its document
    from outside: the mail's height, its images, the quoted-history toggle, the anchor
    links. Popups are allowed so that a link, which the document's base targets at a
    new tab, opens one that is not sandboxed itself. The referrer policy of the
    document itself is the meta the reader writes into its head: the attribute below
    governs the frame's own request, which a `srcdoc` frame never makes.
  -->
  <iframe
    v-else
    ref="iframe"
    :srcdoc="frameDocument"
    sandbox="allow-same-origin allow-popups allow-popups-to-escape-sandbox"
    referrerpolicy="no-referrer"
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
import { HISTORY_ID, TOGGLE_ID, TOGGLE_OPEN_CLASS, foldPlainTextQuotedHistory, foldQuotedHistory } from '../../js/EmailQuotedHistoryFold.js';
import { sanitizeMailBody } from '../../js/EmailBodySanitizer.js';

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
    /**
     * The received body, purified when it is HTML: every tag and attribute outside the
     * allow-list gone, the sender's style sheets set apart for the frame's head. A
     * plain-text body is not markup and is escaped where it is rendered instead.
     *
     * @returns {{styles: string, htmlAttributes: string, bodyAttributes: string, body: string}}
     *          the sender's style sheets, document attributes and the body
     */
    sanitizedBody() {
      const body = this.emailBody || '';
      return this.htmlBody ? sanitizeMailBody(body) : { styles: '', htmlAttributes: '', bodyAttributes: '', body };
    },
    /**
     * The whole document the frame shows: the reader's style, the sender's style
     * sheets, then the purified body with its quoted history folded.
     *
     * @returns {string} the frame's document
     */
    frameDocument() {
      return this.makeMailHtml(this.sanitizedBody);
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
     * Wrap the purified email body into a self-contained HTML document for the
     * iframe, first folding the quoted history behind a Gmail-style "···" toggle so
     * the reader lands on the latest message and reaches the attachments row without
     * scrolling past the quoted thread. Folding degrades to the untouched body when
     * no clear quoted boundary is found.
     * <p>
     * The document carries no script: the frame would refuse to run one, and the
     * toggle is wired from this component once the frame has loaded. Its base targets
     * every link at a new tab, so a click never navigates the frame itself, and its
     * referrer meta keeps the page's address out of every request the message makes
     * (an image, an imported style sheet): the policy of a `srcdoc` document is the
     * one its own head declares. The sender's document and body attributes go onto the
     * reader's tags, so a right-to-left mail or a dark one keeps its direction and its
     * background.
     *
     * @param {{styles: string, htmlAttributes: string, bodyAttributes: string, body: string}} purified
     *          the sender's style sheets, document attributes and purified body (for a
     *          plain-text body, no styles, no attributes and the raw text)
     * @returns {string} the full HTML document served to the iframe srcdoc
     */
    makeMailHtml(purified) {
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
      const renderedBody = this.renderBody(purified.body);
      const senderCSS = purified.styles ? `<style>${purified.styles}</style>` : '';
      return `
        <html${purified.htmlAttributes}>
          <head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="referrer" content="no-referrer">
            <base target="_blank">
            <style>${finalCSS}</style>
            ${senderCSS}
          </head>
          <body${purified.bodyAttributes}>${renderedBody}</body>
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
     * An HTML body, already purified, is served as it is, except for one shape — typed
     * text inside a lone wrapper, which the flag cannot and should not tell apart from
     * any other HTML: the part really is text/html, and the sender's client simply left
     * the message's line structure in raw newlines instead of markup. So that one stays
     * a question about the markup, and only about the markup.
     *
     * @param {string} html the email body: purified markup, or the raw text of a
     *          plain-text body
     * @returns {string} the markup to place in the iframe's body
     */
    renderBody(html) {
      if (!this.htmlBody) {
        return this.foldPlainTextHistory(html) || this.wrapPlainText(html);
      }
      if (this.isTextInWrapper(html)) {
        // Not escaped: it is markup the purifier has already cleaned, and only the
        // whitespace rule around it changes. Folded before being wrapped, so the fold
        // sees the body's own elements and never our wrapper.
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
      this.wireQuotedHistoryToggle();
      this.wireAnchorLinks();
      this.recalculateIframeHeight();

      setTimeout(() => this.recalculateIframeHeight(), 150);
      setTimeout(() => this.recalculateIframeHeight(), 400);
    },

    /**
     * Give the quoted-history toggle its behaviour, from outside the frame: the frame
     * allows no script, so the fold left a plain element in the document and this
     * component attaches the click and the keyboard handlers through the frame's
     * document, which the sandbox lets it read. Nothing to do when the body had no
     * history to fold.
     *
     * @returns {void}
     */
    wireQuotedHistoryToggle() {
      const doc = this.frameContentDocument();
      const toggle = doc && doc.getElementById(TOGGLE_ID);
      const history = doc && doc.getElementById(HISTORY_ID);
      // The fold's own elements, never the body: the ids are looked up in document
      // order, and the body comes first.
      if (!toggle || !history || toggle === doc.body || history === doc.body) {
        return;
      }
      const labels = this.quotedHistoryLabels();
      const set = open => {
        history.style.display = open ? 'block' : 'none';
        toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
        toggle.setAttribute('title', open ? labels.hide : labels.show);
        toggle.textContent = open ? labels.hide : labels.show;
        toggle.className = open ? `ec-quoted-toggle ${TOGGLE_OPEN_CLASS}` : 'ec-quoted-toggle';
        this.recalculateIframeHeight();
      };
      const flip = () => set(toggle.getAttribute('aria-expanded') !== 'true');
      toggle.addEventListener('click', flip);
      toggle.addEventListener('keydown', event => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          flip();
        }
      });
    },
    /**
     * Keep a link to an anchor of the message ("back to top", "jump to section")
     * scrolling inside the message: the document's base sends every link to a new tab,
     * which for a fragment would be a blank one. Wired from this component for the same
     * reason as the toggle, and scrolling through the browser, which brings the target
     * into view through the frame and the drawer alike.
     *
     * @returns {void}
     */
    wireAnchorLinks() {
      const doc = this.frameContentDocument();
      if (!doc) {
        return;
      }
      doc.addEventListener('click', event => {
        const link = event.target && event.target.closest && event.target.closest('a[href^="#"]');
        if (!link) {
          return;
        }
        event.preventDefault();
        const target = this.fragmentTarget(doc, link.getAttribute('href').slice(1));
        if (target) {
          target.scrollIntoView();
        }
      });
    },
    /**
     * The element a fragment names, resolved the way the browser resolves one: the
     * fragment as written first, then percent-decoded; an empty fragment or "top" is
     * the top of the document.
     *
     * @param {Document} doc the frame's document
     * @param {string} fragment the part of the link after the "#"
     * @returns {Element|null} the element to bring into view
     */
    fragmentTarget(doc, fragment) {
      if (!fragment || fragment.toLowerCase() === 'top') {
        return doc.body;
      }
      const candidates = [fragment];
      try {
        const decoded = decodeURIComponent(fragment);
        if (decoded !== fragment) {
          candidates.push(decoded);
        }
      } catch (e) {
        // A malformed escape is not an address of anything: the fragment as written is
        // the only name left to try.
      }
      for (const name of candidates) {
        const found = doc.getElementById(name) || doc.getElementsByName(name)[0];
        if (found) {
          return found;
        }
      }
      return null;
    },
    /**
     * The frame's document, when the frame is there and has one.
     *
     * @returns {Document|null} the document the frame shows
     */
    frameContentDocument() {
      const iframe = this.$refs.iframe;
      if (!iframe) {
        return null;
      }
      return iframe.contentDocument || (iframe.contentWindow && iframe.contentWindow.document) || null;
    },
    /**
     * Match the iframe's height to the mail it holds, so the drawer scrolls as one
     * page instead of the mail scrolling inside a fixed frame.
     *
     * @returns {void}
     */
    recalculateIframeHeight() {
      const doc = this.frameContentDocument();
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