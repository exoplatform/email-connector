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
    nested frames and a `<meta refresh>` as well, and, with no `allow-forms`, every form
    submission, whatever the form targets. The purifier removes forms and controls all
    the same, so none renders as a dead widget; embedded documents and players are its
    job alone. The frame keeps the page's origin only so that this component can read
    its document from outside: the mail's height, its images, the anchor links. Popups
    are allowed so that a link, which the document's base targets at a new tab, opens
    one that is not sandboxed itself. The referrer policy of the document itself is the
    meta the reader writes into its head: the attribute below governs the frame's own
    request, which a `srcdoc` frame never makes.
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
import { sanitizeMailBody } from '../../js/EmailBodySanitizer.js';

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
      return sanitizeMailBody(this.emailBody || '');
    },
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
          <body${purified.bodyAttributes}>${purified.body}</body>
        </html>
      `;
    },
    onLoadIframe() {
      this.wireAnchorLinks();
      this.recalculateIframeHeight();

      setTimeout(() => this.recalculateIframeHeight(), 150);
      setTimeout(() => this.recalculateIframeHeight(), 400);
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
     * The element a fragment names, resolved the way the browser resolves one: for the
     * fragment as written, then for its percent-decoded form, the element with that id,
     * else the first `<a>` with that name; failing both, "top" (in any case) is the top
     * of the document, and so is an empty fragment.
     *
     * @param {Document} doc the frame's document
     * @param {string} fragment the part of the link after the "#"
     * @returns {Element|null} the element to bring into view, or null when the fragment
     *          names nothing
     */
    fragmentTarget(doc, fragment) {
      if (!fragment) {
        return doc.body;
      }
      let decoded = fragment;
      try {
        decoded = decodeURIComponent(fragment);
      } catch (e) {
        // A malformed escape decodes to nothing: the fragment as written is the only
        // name to try.
      }
      const candidates = decoded === fragment ? [fragment] : [fragment, decoded];
      for (const name of candidates) {
        const found = doc.getElementById(name)
          || Array.from(doc.getElementsByName(name)).find(element => element.localName === 'a');
        if (found) {
          return found;
        }
      }
      return decoded.toLowerCase() === 'top' ? doc.body : null;
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