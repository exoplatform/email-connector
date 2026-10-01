<!--
Copyright (C) 2026 eXo Platform SAS.

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
  <!-- "Import mail..." (EXO-90846): .eml files, a .zip of them or an .mbox, picked or
       dropped, uploaded through the platform's upload service, then added to the folder
       on the mail server in the background. The drawer follows the run and shows its
       report; the end notification tells the same when the drawer was closed. File names
       are shown as text only, and nothing of the files is ever rendered. -->
  <exo-drawer
    id="emailImportDrawer"
    ref="emailImportDrawer"
    v-model="drawer"
    :loading="busy"
    right
    @closed="close">
    <template #title>
      <span>{{ $t('emailConnector.mailBox.import.drawer.title') }}</span>
    </template>
    <template v-if="drawer" #content>
      <div class="pa-4">
        <div class="mb-4">
          {{ $t('emailConnector.mailBox.import.drawer.into', { 0: folderLabel }) }}
        </div>
        <template v-if="!running && !report">
          <div
            :class="dragging ? 'primary--text' : 'text-sub-title'"
            :style="dropZoneStyle"
            class="d-flex flex-column align-center justify-center pa-6 mb-2 border-radius text-center clickable"
            role="button"
            tabindex="0"
            @click="chooseFiles"
            @keydown.enter="chooseFiles"
            @dragenter.prevent="dragging = true"
            @dragover.prevent="dragging = true"
            @dragleave.prevent="dragging = false"
            @drop.prevent="onDrop">
            <v-icon size="32" class="mb-2 icon-default-color">fa-file-import</v-icon>
            <span>{{ $t('emailConnector.mailBox.import.drawer.dropHere') }}</span>
          </div>
          <input
            ref="fileInput"
            :aria-label="$t('emailConnector.mailBox.import.drawer.dropHere')"
            type="file"
            multiple
            accept=".eml,.zip,.mbox,message/rfc822,application/zip,application/mbox"
            class="d-none"
            @change="onFilesChosen">
          <div class="caption text-sub-title mb-4">{{ limitsText }}</div>
          <v-list v-if="files.length" dense>
            <v-list-item
              v-for="(file, index) in files"
              :key="`${file.name}-${index}`"
              class="px-0">
              <v-icon size="16" class="me-2 icon-default-color">fa-file</v-icon>
              <span class="text-truncate">{{ file.name }}</span>
              <span class="ms-auto ps-2 caption text-sub-title text-no-wrap">{{ formatSize(file.size) }}</span>
              <v-btn
                :title="$t('emailConnector.mailBox.import.drawer.remove')"
                icon
                small
                @click="files.splice(index, 1)">
                <v-icon size="14" class="icon-default-color">fa-times</v-icon>
              </v-btn>
            </v-list-item>
          </v-list>
          <div v-if="errorMessage" class="error--text mt-2">{{ errorMessage }}</div>
        </template>
        <template v-else-if="running">
          <div class="mb-2">{{ $t('emailConnector.mailBox.import.drawer.running') }}</div>
          <!-- Progress is counted file by file: inside the first file it is not known yet,
               and the counts below move instead. -->
          <v-progress-linear
            :value="progress"
            :indeterminate="!progress"
            color="primary"
            height="6"
            rounded />
          <div class="caption text-sub-title mt-2">{{ countsText(state) }}</div>
        </template>
        <email-connector-mail-box-import-report v-else :state="report" />
      </div>
    </template>
    <template #footer>
      <div class="d-flex">
        <v-spacer />
        <v-btn class="btn me-2" @click="$refs.emailImportDrawer.close()">
          {{ $t('emailConnector.mailBox.import.drawer.close') }}
        </v-btn>
        <v-btn
          v-if="!running && !report"
          :disabled="!files.length || busy"
          :loading="uploading"
          class="btn btn-primary"
          @click="startImport">
          {{ $t('emailConnector.mailBox.import.drawer.import') }}
        </v-btn>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
import { IMPORT_FINISHED_EVENT, OPEN_IMPORT_DRAWER_EVENT, getImportStatus, startImport } from '../../js/EmailConnectorMailTransfer.js';

// How often the drawer asks how the run goes.
const POLL_MS = 1500;

// The server re-reads the folder a moment after the run; the list is reloaded once more
// after this delay so the imported mail shows without waiting for the next check.
const LATE_REFRESH_MS = 6000;

export default {
  data() {
    return {
      drawer: false,
      folder: 'INBOX',
      folderLabel: '',
      files: [],
      state: null,
      report: null,
      uploading: false,
      dragging: false,
      errorMessage: null,
      pollTimeout: null,
    };
  },
  computed: {
    /**
     * @returns {Boolean} whether a run goes on the server
     */
    running() {
      return this.state?.status === 'IN_PROGRESS';
    },
    /**
     * @returns {Boolean} whether the drawer's bar under its title moves
     */
    busy() {
      return this.uploading || this.running;
    },
    /**
     * @returns {Number} how far the run read its files, in percent
     */
    progress() {
      const total = this.state?.totalBytes || 0;
      return total ? Math.min(100, Math.round(100 * (this.state.processedBytes || 0) / total)) : 0;
    },
    /**
     * The limits, as the server states them on every answer.
     *
     * @returns {String} the sentence
     */
    limitsText() {
      const limits = this.state || {};
      return this.$t('emailConnector.mailBox.import.drawer.limits', {
        0: limits.maxFiles || '-',
        1: this.formatSize(limits.maxTotalBytes || 0),
        2: limits.maxMails || '-',
        3: this.formatSize(limits.maxMailBytes || 0),
      });
    },
    /**
     * @returns {Object} the drop zone's dashed frame
     */
    dropZoneStyle() {
      return { border: '2px dashed currentColor', minHeight: '120px' };
    },
  },
  created() {
    this.$root.$on(OPEN_IMPORT_DRAWER_EVENT, this.open);
  },
  beforeDestroy() {
    this.$root.$off(OPEN_IMPORT_DRAWER_EVENT, this.open);
    window.clearTimeout(this.pollTimeout);
  },
  methods: {
    /**
     * Opens the drawer on a folder, with files already dropped when there are some, and
     * follows a run already going.
     *
     * @param {Object} detail {folder, label, files}
     * @returns {void}
     */
    open(detail) {
      this.folder = detail?.folder || 'INBOX';
      this.folderLabel = detail?.label || this.folder;
      this.files = Array.from(detail?.files || []);
      this.report = null;
      this.errorMessage = null;
      this.drawer = true;
      this.$refs.emailImportDrawer.open();
      getImportStatus().then(state => {
        this.state = state;
        if (this.running) {
          this.schedulePoll();
        }
      }).catch(() => this.state = null);
    },
    /**
     * @returns {void}
     */
    chooseFiles() {
      this.$refs.fileInput?.click();
    },
    /**
     * Adds the files picked in the browser's dialog.
     *
     * @param {Event} event the change event
     * @returns {void}
     */
    onFilesChosen(event) {
      this.files.push(...Array.from(event.target.files || []));
      event.target.value = '';
    },
    /**
     * Adds the files dropped on the drop zone.
     *
     * @param {DragEvent} event the drop
     * @returns {void}
     */
    onDrop(event) {
      this.dragging = false;
      this.files.push(...Array.from(event.dataTransfer?.files || []));
    },
    /**
     * Uploads the files one by one through the platform's upload service, then starts
     * the run. A file over the platform's upload limit is refused before it is sent;
     * every other limit is the server's to apply, and its refusal is told in words.
     *
     * @returns {Promise<void>} settled once the run started or was refused
     */
    async startImport() {
      this.errorMessage = null;
      const maxBytes = (eXo.env.portal.maxFileSize || 0) * 1024 * 1024;
      if (maxBytes && this.files.some(file => file.size > maxBytes)) {
        this.errorMessage = this.$t('emailConnector.mailBox.import.error.fileTooLarge', { 0: eXo.env.portal.maxFileSize });
        return;
      }
      this.uploading = true;
      const uploadIds = [];
      try {
        // One upload after the other: a large mbox is the platform's upload servlet's
        // whole work for a while, and fifty at once would only queue there.
        await this.files.reduce((previous, file) => previous.then(async () => {
          const uploadId = await this.$uploadService.upload(file, this.$uploadService.generateRandomId());
          if (!uploadId) {
            throw new Error('Upload failed');
          }
          uploadIds.push(uploadId);
        }), Promise.resolve());
        this.state = await startImport(this.folder, uploadIds);
        this.files = [];
        this.schedulePoll();
      } catch (error) {
        uploadIds.forEach(uploadId => this.$uploadService.deleteUpload(uploadId));
        this.errorMessage = this.$t(this.errorKey(error));
      } finally {
        this.uploading = false;
      }
    },
    /**
     * @returns {void}
     */
    schedulePoll() {
      window.clearTimeout(this.pollTimeout);
      this.pollTimeout = window.setTimeout(() => this.poll(), POLL_MS);
    },
    /**
     * Asks how the run goes; once it ended, shows the report and reloads the list.
     *
     * @returns {void}
     */
    poll() {
      getImportStatus().then(state => {
        this.state = state;
        if (this.running) {
          this.schedulePoll();
          return;
        }
        this.report = state;
        this.$root.$emit(IMPORT_FINISHED_EVENT, { folder: state.folder });
        this.$root.$emit('refresh-email-box');
        window.setTimeout(() => this.$root.$emit('refresh-email-box'), LATE_REFRESH_MS);
      }).catch(() => this.schedulePoll());
    },
    /**
     * The message key of a refused start, by the server's code or its status.
     *
     * @param {Error} error the error: {status, code}
     * @returns {String} the key
     */
    errorKey(error) {
      if (error?.status === 409) {
        return 'emailConnector.import.alreadyRunning';
      }
      if (error?.status === 403 || error?.status === 410) {
        return 'emailConnector.mailBox.import.error.forbidden';
      }
      if (error?.status === 400 && error.code && this.$te(error.code)) {
        return error.code;
      }
      return 'emailConnector.mailBox.import.error.failed';
    },
    /**
     * The three counts of a state, as a sentence.
     *
     * @param {Object} state the state
     * @returns {String} the sentence
     */
    countsText(state) {
      return this.$t('emailConnector.mailBox.import.counts', {
        0: state?.added || 0,
        1: state?.skipped || 0,
        2: state?.refused || 0,
      });
    },
    /**
     * A byte count in the unit a person reads it in.
     *
     * @param {Number} bytes the count
     * @returns {String} e.g. "512 KB", "25 MB"
     */
    formatSize(bytes) {
      if (bytes < 1024 * 1024) {
        return `${Math.round(bytes / 1024)} KB`;
      }
      return `${Math.round(bytes / (1024 * 1024))} MB`;
    },
    /**
     * Stops following the run -- it goes on on the server, and its end is notified --
     * and forgets the files.
     *
     * @returns {void}
     */
    close() {
      window.clearTimeout(this.pollTimeout);
      this.drawer = false;
      this.files = [];
      this.state = null;
      this.report = null;
      this.errorMessage = null;
    },
  },
};
</script>
