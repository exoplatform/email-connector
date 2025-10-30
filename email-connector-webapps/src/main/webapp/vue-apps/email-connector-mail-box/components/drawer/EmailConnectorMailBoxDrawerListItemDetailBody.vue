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
  <iframe
    ref="iframe"
    :srcdoc="sanitizedContent"
    :style="{
      width: '100%',
      border: 'none',
      overflow: 'hidden',
      transition: 'height 0.2s ease',
      height: iframeHeight + 'px'
    }"
    @load="onLoadIframe"
    title="email-body"
  ></iframe>
</template>

<script>
export default {
  data: () => ({
    iframeHeight: 400,
  }),
  props: {
    emailContent: {
      type: String,
      default: null,
    },
    expandedDrawer: {
      type: Boolean,
      default: false,
    },
  },
  computed: {
    sanitizedContent() {
      return this.makeMailHtml(this.emailContent || '');
    }
  },
  watch: {
    expandedDrawer() {
      this.$nextTick(() => this.recalculateIframeHeight());
    }
  },
  methods: {
    makeMailHtml(html) {
      const baseCSS = `
        html, body {
          margin:0; padding:0;
          width:100%; height:auto;
          overflow:hidden;
          font-family:Roboto, Arial, sans-serif;
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
        img { max-width: 100% !important; height: auto !important; display:block; }
      `;
      const finalCSS = this.expandedDrawer ? baseCSS : baseCSS + responsiveCSS;
      return `
        <html>
          <head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>${finalCSS}</style>
          </head>
          <body>${html}</body>
        </html>
      `;
    },
    onLoadIframe() {
      this.recalculateIframeHeight();
    },
    recalculateIframeHeight() {
      const iframe = this.$refs.iframe;
      if (!iframe) {
        return;
      }
      try {
        const doc = iframe.contentDocument || iframe.contentWindow.document;
        setTimeout(() => {
          const newHeight = Math.max(
            doc.body.scrollHeight,
            doc.documentElement.scrollHeight
          );
          this.iframeHeight = newHeight;
        }, 100);
      } catch {
        this.iframeHeight = 400;
      }
    }
  }
};
</script>