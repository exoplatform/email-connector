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
    },
    // A new document is loading: the frame stays hidden, so nothing can be followed in
    // it, until the load handler has wired it. Between the parse and the load, the
    // sender's own targets and referrer overrides would apply to a click.
    frameDocument() {
      this.iframeVisible = false;
    },
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
    /**
     * Wire the loaded document, size the iframe to it and only then show it, so a link
     * is never reachable before its target and the referrer policy are settled. A
     * frame that navigates away from the mail it showed (a sender's script or meta
     * refresh, to another origin or to a page of the portal) is hidden as soon as the
     * mail is left, not at the next page's load, which waits for that page's own
     * resources: what it shows is not the mail. Measured again after a beat because
     * fonts and lazily loaded images can land after load and each one moves the height.
     *
     * @returns {void}
     */
    onLoadIframe() {
      const doc = this.frameContentDocument();
      if (!doc) {
        this.iframeVisible = false;
        return;
      }
      // A persisted pagehide is the whole portal page entering the back/forward cache:
      // the restored page fires no new load, so the mail must stay shown.
      doc.defaultView.addEventListener('pagehide', event => {
        if (!event.persisted) {
          this.iframeVisible = false;
        }
      });
      this.sendLinksToNewTab(doc);
      this.keepReferrerPrivate(doc);
      this.wireAnchorLinks(doc);
      this.recalculateIframeHeight();
      this.iframeVisible = true;

      setTimeout(() => this.recalculateIframeHeight(), 150);
      setTimeout(() => this.recalculateIframeHeight(), 400);
    },

    /**
     * Leave every link, image map and form of the message to the document's base, which
     * targets a new tab: the element's own `target`, or a submit button's `formtarget`,
     * would override it, and the sender's markup reaches the frame as sent, so a `_self`
     * would navigate the frame and a `_top` the portal page itself. A `mailto:` or
     * `tel:` link is not singled out: whether a browser with a web handler registered
     * for the scheme would navigate the frame to the handler's page is unverified, and
     * the new tab is safe either way.
     *
     * @param {Document} doc the frame's loaded document
     * @returns {void}
     */
    sendLinksToNewTab(doc) {
      doc.querySelectorAll('a[target], area[target], form[target]')
        .forEach(element => element.removeAttribute('target'));
      doc.querySelectorAll('[formtarget]')
        .forEach(element => element.removeAttribute('formtarget'));
    },
    /**
     * Keep the document's no-referrer policy the one in force for every link followed
     * from the message, whatever the sender's markup says: a `referrerpolicy` attribute
     * overrides it for that element, and a `meta name="referrer"` anywhere in the body
     * replaces it for the whole document. The policy is reasserted by inserting a fresh
     * meta, because the browser applies the policy when a meta is inserted and does not
     * restore the earlier one when a meta is removed.
     * <p>
     * This runs once the document has loaded, so it governs what happens from then on:
     * a sender's override on a resource the parser fetched while loading (an image) has
     * already applied, and only cleaning the body before it is served can stop that.
     *
     * @param {Document} doc the frame's loaded document
     * @returns {void}
     */
    keepReferrerPrivate(doc) {
      doc.querySelectorAll('[referrerpolicy]')
        .forEach(element => element.removeAttribute('referrerpolicy'));
      doc.querySelectorAll('meta[name="referrer" i]').forEach(meta => meta.remove());
      const meta = doc.createElement('meta');
      meta.setAttribute('name', 'referrer');
      meta.setAttribute('content', 'no-referrer');
      (doc.head || doc.documentElement).appendChild(meta);
    },
    /**
     * Keep a link to an anchor of the message ("back to top", "jump to section")
     * scrolling inside the message: the document's base sends every link to a new tab,
     * which for a fragment would be a blank one. Wired from this component through the
     * frame's document, and scrolling through the browser, which brings the target
     * into view through the frame and the drawer alike.
     *
     * @param {Document} doc the frame's loaded document
     * @returns {void}
     */
    wireAnchorLinks(doc) {
      doc.addEventListener('click', event => {
        const link = event.target && event.target.closest && event.target.closest('a[href], area[href]');
        // Trimmed as the URL parser trims it, so a fragment written " #top" is one too.
        const href = link && link.getAttribute('href').trim();
        if (!href || !href.startsWith('#')) {
          return;
        }
        event.preventDefault();
        const target = this.fragmentTarget(doc, href.slice(1));
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
     * The frame's document, when it is the mail this component served. A `srcdoc`
     * document's URL is `about:srcdoc` (followed by a fragment when a script of the
     * mail set one); any other URL means the frame navigated away, and a
     * same-origin page or `about:blank` would still be readable. `contentDocument` is
     * null for a document of another origin, where reading `contentWindow.document`
     * would throw instead.
     *
     * @returns {Document|null} the mail's document, or null when the frame shows anything else
     */
    frameContentDocument() {
      const iframe = this.$refs.iframe;
      const doc = (iframe && iframe.contentDocument) || null;
      return doc && doc.URL.split('#')[0] === 'about:srcdoc' ? doc : null;
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

      if (!this.resizeObserver) {
        this.resizeObserver = new ResizeObserver(() => this.recalculateIframeHeight());
        this.resizeObserver.observe(doc.body);
      }
    }
  }
};
</script>