/*
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
*/

/**
 * Opening a received attachment, shared by the compact chips of the mail list and
 * the rows of the mail detail and the attachments drawer, so a click means the same
 * thing everywhere.
 *
 * Three outcomes, cheapest first:
 * - an image, a sound or a video opens in the platform preview dialog straight from
 *   its URL, without copying anything into the Drive;
 * - a document OnlyOffice can render is first stored in the Drive, since the editor
 *   addresses a document by id and an attachment is only a part of a mail;
 * - anything else keeps downloading to the device, which is also what happens when
 *   the Documents add-on isn't installed and there is nothing to open a file with.
 */
export default {
  data() {
    return {
      opening: false,
    };
  },
  methods: {
    openAttachment() {
      if (this.opening || this.downloading) {
        return;
      }
      const service = this.$emailConnectorMailBoxService;
      if (service.isUrlPreviewable(this.attachment)) {
        service.previewAttachmentFromUrl(this.attachment);
        return;
      }
      if (!service.isEditorPreviewable(this.attachment)) {
        this.downloadAttachment();
        return;
      }
      this.opening = true;
      // The tab is opened now, while the click is still being handled: storing the
      // attachment first would spend the user gesture and the browser would then
      // block the pop-up, which reads as the document silently not opening even
      // though it did get stored.
      const editorTab = window.open('', '_blank');
      this.showOpeningPlaceholder(editorTab);
      service.materialiseAttachment(this.attachment)
        .then(documentId => {
          const url = service.getEditorUrl(documentId);
          if (editorTab) {
            editorTab.location = url;
          } else {
            // pop-ups are blocked outright, so open it in place rather than nowhere
            window.location.href = url;
          }
        })
        .catch(() => {
          // storing it failed, so fall back to what the attachment could always do
          if (editorTab) {
            editorTab.close();
          }
          const message = this.$t('emailConnector.mailBox.attachment.preview.error');
          this.$root.$emit('alert-message', message, 'error');
          this.downloadAttachment();
        })
        .finally(() => this.opening = false);
    },
    /**
     * Fills the freshly opened tab with a waiting state. The tab has to be opened
     * on the click itself to survive the pop-up blocker, but the document only
     * exists a few seconds later, and an empty white tab in between reads as a
     * broken window rather than as progress.
     *
     * @param {Window} editorTab the tab opened for the editor, null when blocked
     * @returns {void}
     */
    showOpeningPlaceholder(editorTab) {
      if (!editorTab) {
        return;
      }
      // the name comes from a mail, so it is not to be trusted as markup
      const name = document.createElement('div');
      name.textContent = this.attachment.name;
      const label = this.$t('emailConnector.mailBox.attachment.opening', {
        0: name.innerHTML,
      });
      editorTab.document.write(`<!doctype html>
<html><head><meta charset="utf-8"><title>${name.innerHTML}</title></head>
<body style="margin:0;height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:16px;font-family:Helvetica,Arial,sans-serif;color:#4d5466;background:#fff">
<div style="width:36px;height:36px;border:3px solid #e1e8ee;border-top-color:#476a9c;border-radius:50%;animation:s 1s linear infinite"></div>
<div>${label}</div>
<style>@keyframes s{to{transform:rotate(360deg)}}</style>
</body></html>`);
      editorTab.document.close();
    },
  },
};
