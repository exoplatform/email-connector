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
  <div v-if="active" class="emailAttachments mx-4 mt-2">
    <!-- Native fallback: only when the Documents add-on (and its picker) is absent.
         The composer then owns the whole flow with its own file input. -->
    <div v-if="!documentsDeployed" class="d-flex align-center">
      <v-btn
        small
        text
        class="px-2"
        :aria-label="$t('emailConnector.mailBox.newEmail.drawer.attach.tooltip')"
        @click="$refs.nativeFileInput.click()">
        <v-icon size="18" class="me-2">fa-paperclip</v-icon>
        {{ $t('emailConnector.mailBox.newEmail.drawer.attach.label') }}
      </v-btn>
      <input
        ref="nativeFileInput"
        type="file"
        multiple
        class="d-none"
        :aria-label="$t('emailConnector.mailBox.newEmail.drawer.attach.label')"
        @change="onNativeFilesSelected">
    </div>
    <!-- Hybrid preview: the first few files show as inline chips right in the
         composer; the "view all" link opens the shared list drawer for the full
         set (Documents deployed). We render our OWN chips — not the picker's
         uploaded list — so nothing flashes then auto-closes (the old flicker). -->
    <div v-if="items.length" class="d-flex flex-wrap mt-1">
      <div
        v-for="(attachment, index) in visibleItems"
        :key="attachment.key"
        class="emailAttachmentChip d-flex align-center border-color rounded pa-1 pe-2 me-2 mb-2">
        <email-box-sync-loader
          v-if="attachment.uploading"
          :icon-size="20"
          :loader-width="3"
          style="flex: 0 0 auto;" />
        <v-icon
          v-else
          size="20"
          :color="getIconColor(attachment.mimeType)">
          {{ getIconClass(attachment.mimeType) }}
        </v-icon>
        <span class="text-truncate ms-2 caption" style="max-width: 160px;">{{ attachment.name }}</span>
        <span class="text-light-color caption ms-2">{{ humanFileSize(attachment.size) }}</span>
        <v-btn
          icon
          x-small
          class="ms-1"
          :aria-label="$t('emailConnector.mailBox.newEmail.drawer.attach.remove')"
          @click="removeAttachment(index)">
          <v-icon size="14">fa-times</v-icon>
        </v-btn>
      </div>
    </div>
    <v-btn
      v-if="documentsDeployed && items.length"
      text
      small
      color="primary"
      class="px-2 mt-1"
      @click="openListDrawer">
      <v-icon size="16" class="pe-1">fa-paperclip</v-icon>
      <span class="text-decoration-underline text-none">
        {{ $t('emailConnector.mailBox.newEmail.drawer.attach.viewAll', { 0: items.length }) }}
      </span>
    </v-btn>
  </div>
</template>

<script>
// Folder at the root of the user's Personal Documents where mail attachments land.
// Two spellings on purpose: the platform stores a folder under a lower-cased JCR
// node name while keeping the capitalised title for display. The attachments
// service resolves the destination as a JCR path verbatim, so it has to be given
// the node name — passing the title makes creating a document fail with
// "Can't find path: .../Private/Mail Attachments" even when the folder is there.
const ATTACHMENTS_FOLDER_TITLE = 'Mail Attachments';

const ATTACHMENTS_FOLDER_PATH = ATTACHMENTS_FOLDER_TITLE.toLowerCase();

export default {
  props: {
    value: {
      type: Array,
      default: () => [],
    },
    active: {
      type: Boolean,
      default: false,
    },
    // Hands a freshly uploaded file to whoever owns the draft, and answers with the
    // attachment as stored. Injected rather than called directly here because the
    // draft session — its local id, its revision, the queue that serialises its saves
    // — belongs to the composer; this component owns a list of chips and an upload.
    persist: {
      type: Function,
      default: null,
    },
    // The counterpart, for a file already stored on the draft.
    unpersist: {
      type: Function,
      default: null,
    },
  },
  data() {
    return {
      // Local source of truth. Relying on the v-model prop round-trip would drop
      // files when several attachment-added events fire synchronously (each read of
      // the not-yet-updated prop would overwrite the previous entry).
      items: [],
      // The array this component last emitted. Everything else arriving through the
      // v-model prop is the parent REPLACING the list — resuming a draft with the
      // files it was stored with, or clearing the composer — and has to be adopted.
      // Comparing by reference is what tells the two apart: without it, either the
      // resumed files never appear (the prop is ignored) or files added by two
      // synchronous events overwrite each other (the prop is trusted blindly, which
      // is the flicker the local list was introduced to fix).
      lastEmitted: null,
      chipKey: 0,
      // How many files preview as inline chips before the rest fold into "view all".
      maxInlineChips: 5,
    };
  },
  computed: {
    // Cap the inline chips; slice(0, n) keeps original indices for removeAttachment.
    visibleItems() {
      return this.items.slice(0, this.maxInlineChips);
    },
    documentsDeployed() {
      return extensionRegistry.loadExtensions('RichEditor', 'ckeditor-extensions').some(ext => ext.id === 'attachFile')
        || !!Vue.prototype.$attachmentService;
    },
    maxFileSizeBytes() {
      const mb = eXo.env.portal.maxFileSize;
      return mb ? mb * 1024 * 1024 : 0;
    },
  },
  watch: {
    value(newValue) {
      if (newValue === this.lastEmitted) {
        return;
      }
      this.items = Array.isArray(newValue) ? newValue.slice() : [];
    },
  },
  created() {
    this.items = Array.isArray(this.value) ? this.value.slice() : [];
    document.addEventListener('open-email-attachments', this.openPicker);
    document.addEventListener('attachment-added', this.onAttachmentAdded);
    document.addEventListener('attachment-removed', this.onAttachmentRemoved);
  },
  beforeDestroy() {
    document.removeEventListener('open-email-attachments', this.openPicker);
    document.removeEventListener('attachment-added', this.onAttachmentAdded);
    document.removeEventListener('attachment-removed', this.onAttachmentRemoved);
  },
  methods: {
    // Opens the reused Documents picker drawer. Only reachable when Documents is
    // deployed (the CKEditor paperclip dispatches open-email-attachments). The
    // picker is used as-is (the user closes it via its own Done button), so no
    // programmatic auto-close is needed — that was the source of the flicker.
    openPicker() {
      if (!this.active) {
        return;
      }
      // Make sure the destination folder exists before the picker opens, so the
      // very first attachment doesn't land in a folder that has yet to be created.
      this.ensureAttachmentsFolder().finally(() => {
        document.dispatchEvent(new CustomEvent('open-attachments-app-drawer', {
          detail: {
            entityType: '',
            entityId: '',
            attachToEntity: false,
            sourceApp: 'emailConnector',
            defaultFolder: ATTACHMENTS_FOLDER_PATH,
            attachments: [],
            spaceId: null,
          },
        }));
      });
    },
    // Creates the mail attachments folder at the root of the user's Personal
    // Documents when it isn't there yet, using the capitalised title so the Drive
    // shows a clean label (the platform lower-cases the node name underneath). Never rejects: an existing folder answers
    // with a conflict, and any other failure must not stop the user from attaching
    // a file, since only the create-a-document path depends on it.
    ensureAttachmentsFolder() {
      const ownerId = eXo.env.portal.userIdentityId;
      if (!ownerId) {
        return Promise.resolve();
      }
      const params = new URLSearchParams({
        ownerId,
        folderPath: '',
        name: ATTACHMENTS_FOLDER_TITLE,
      });
      return fetch(`${eXo.env.portal.context}/${eXo.env.portal.rest}/v1/documents/folder?${params}`, {
        credentials: 'include',
        method: 'POST',
      }).catch(() => null);
    },
    // Opens the shared Documents list drawer to review/remove the composed mail's
    // attachments. A clone is passed so the drawer mutates its own copy; removals
    // flow back to us through the attachment-removed event.
    openListDrawer() {
      document.dispatchEvent(new CustomEvent('open-attachments-list-drawer', {
        detail: {
          entityType: '',
          entityId: '',
          attachToEntity: false,
          sourceApp: 'emailConnector',
          attachments: this.items.map(this.toListDrawerAttachment),
          spaceId: null,
        },
      }));
    },
    // Clone for the shared list drawer, minus the picker's drive metadata.
    // AttachmentItem flags a file as "from another drive" by comparing
    // attachment.fileDrive.title to its current-drive prop — and the list drawer
    // never passes current-drive, so any file carrying fileDrive shows an info
    // icon reading "available for all members once you post it": activity-stream
    // wording that is simply wrong for an email. Activity avoids it only because
    // it re-fetches clean documents. Dropping fileDrive/space does the same for us.
    toListDrawerAttachment(item) {
      const clone = JSON.parse(JSON.stringify(item));
      delete clone.fileDrive;
      delete clone.space;
      return clone;
    },
    // A file was picked/uploaded through the Documents drawer. The drawer stores it
    // as a platform document (uploadId cleared), so we normalize it to a commons
    // upload id by downloading its bytes and re-uploading them.
    onAttachmentAdded(event) {
      if (!this.active || !event.detail || !event.detail.attachment) {
        return;
      }
      this.addFromDriveDocument(event.detail.attachment);
    },
    onAttachmentRemoved(event) {
      if (!this.active || !event.detail) {
        return;
      }
      const removed = event.detail;
      const index = this.items.findIndex(a => a.id && (a.id === removed.id));
      if (index >= 0) {
        this.removeAttachment(index);
      }
    },
    async addFromDriveDocument(doc) {
      const entry = this.pushPending(doc.title || doc.name, doc.mimetype || doc.mimeType, doc.size, doc);
      try {
        const downloadUrl = doc.downloadUrl
          || `${eXo.env.portal.context}/${eXo.env.portal.rest}/v1/documents/content/${doc.id}`;
        const response = await fetch(downloadUrl, { credentials: 'include' });
        if (!response.ok) {
          throw new Error(`Could not download the selected document (${response.status})`);
        }
        const blob = await response.blob();
        const file = new File([blob], entry.name, { type: entry.mimeType || blob.type });
        await this.uploadFile(file, entry);
      } catch (e) {
        this.failPending(entry);
      }
    },
    onNativeFilesSelected(event) {
      const files = Array.from(event.target.files || []);
      event.target.value = '';
      files.forEach(file => {
        if (this.maxFileSizeBytes && file.size > this.maxFileSizeBytes) {
          this.$root.$emit('alert-message', this.$t('emailConnector.mailBox.newEmail.drawer.attach.maxSize.error', {
            0: eXo.env.portal.maxFileSize,
          }), 'error');
          return;
        }
        const entry = this.pushPending(file.name, file.type, file.size, null);
        this.uploadFile(file, entry).catch(() => this.failPending(entry));
      });
    },
    // Uploads a File to the commons upload service and, as soon as it has an upload
    // id, hands it to the draft — which copies the bytes into the platform's file
    // store and answers with the attachment as stored.
    //
    // The chip stays in "uploading" until BOTH have happened, which is deliberate: it
    // is what the Send button reads to stay disabled, and a file that has reached the
    // commons upload but not yet the draft is exactly as unsendable as one still going
    // up. The user is told a file is on its way, and it is.
    async uploadFile(file, entry) {
      const uploadId = this.$uploadService.generateRandomId();
      const resolvedId = await this.$uploadService.upload(file, uploadId);
      if (!resolvedId) {
        throw new Error('Upload failed');
      }
      entry.uploadId = resolvedId;
      if (this.persist) {
        const stored = await this.persist(entry);
        if (stored) {
          // From here the file belongs to the draft, not to this session. Its own id
          // is how it is downloaded and removed; the upload id is dropped because the
          // server has consumed the upload, and leaving it would put the same file on
          // the message twice at send time.
          entry.id = stored.id;
          entry.uploadId = null;
          entry.stored = true;
          entry.size = stored.size || entry.size;
        }
      }
      entry.uploading = false;
      this.sync();
    },
    // Builds an entry. For drive documents the raw doc object is spread in so the
    // shared list drawer can render it (id/title/mimetype/acl); our normalized
    // fields (name/mimeType/size/uploadId) drive the send payload and native chips.
    pushPending(name, mimeType, size, doc) {
      const entry = {
        ...(doc || {}),
        key: `att-${this.chipKey++}`,
        id: (doc && doc.id) || null,
        name: name || 'file',
        title: name || 'file',
        mimeType: mimeType || '',
        mimetype: mimeType || '',
        size: size || 0,
        uploadId: null,
        uploading: true,
      };
      this.items.push(entry);
      this.sync();
      return entry;
    },
    failPending(entry) {
      // The upload may have succeeded and only the handover to the draft failed, in
      // which case a temporary file is sitting there belonging to nothing. Released
      // here rather than left to expire, since the chip it backed is about to go.
      if (entry && entry.uploadId) {
        this.$uploadService.deleteUpload(entry.uploadId);
      }
      const index = this.items.indexOf(entry);
      if (index >= 0) {
        this.items.splice(index, 1);
        this.sync();
      }
      this.$root.$emit('alert-message', this.$t('emailConnector.mailBox.newEmail.drawer.attach.error'), 'error');
    },
    // Takes a file off the composed mail. Where it goes depends on where it got to: a
    // file already on the draft is removed through the draft (and its bytes recorded
    // for a later sweep), a file that only ever reached the commons upload is released
    // there. The chip goes either way and immediately — the user's action is not worth
    // making them watch a round trip, and a removal that fails server-side leaves a
    // file on a draft they will see again the next time they open it, which is
    // recoverable; a chip that lingers under a click is not.
    removeAttachment(index) {
      const attachment = this.items[index];
      if (attachment && attachment.stored && this.unpersist) {
        this.unpersist(attachment).catch(() => {
          this.$root.$emit('alert-message', this.$t('emailConnector.mailBox.newEmail.drawer.attach.error'), 'error');
        });
      } else if (attachment && attachment.uploadId) {
        this.$uploadService.deleteUpload(attachment.uploadId);
      }
      this.items.splice(index, 1);
      this.sync();
    },
    // Mirror the local list to the parent's v-model (used to build the send payload).
    // The emitted array is remembered so the watcher above can tell our own echo from
    // the parent genuinely replacing the list.
    sync() {
      this.lastEmitted = this.items.slice();
      this.$emit('input', this.lastEmitted);
    },
    getIconClass(mimeType) {
      return this.$emailConnectorMailBoxService.getAttachmentIcon(mimeType || '').class;
    },
    getIconColor(mimeType) {
      return this.$emailConnectorMailBoxService.getAttachmentIcon(mimeType || '').color;
    },
    humanFileSize(size) {
      if (!size) {
        return '';
      }
      const units = ['B', 'KB', 'MB', 'GB'];
      let value = size;
      let unitIndex = 0;
      while (value >= 1024 && unitIndex < units.length - 1) {
        value /= 1024;
        unitIndex++;
      }
      return `${value.toFixed(unitIndex === 0 ? 0 : 1)} ${units[unitIndex]}`;
    },
  },
};
</script>
