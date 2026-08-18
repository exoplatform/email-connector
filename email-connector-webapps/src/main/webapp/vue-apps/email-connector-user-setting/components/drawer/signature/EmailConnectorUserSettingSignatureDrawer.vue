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
  <div>
    <exo-drawer
      id="userSettingSignatureDrawer"
      ref="signatureDrawer"
      v-model="signatureDrawer"
      right
      allow-expand
      @closed="reset">
      <template #title>
        <span>{{ $t('UserSettings.emailConnector.signature.drawer.title') }}</span>
      </template>
      <template v-if="signatureDrawer" #content>
        <div class="pa-4">
          <div class="text-subtitle-1">{{ $t('UserSettings.emailConnector.signature.drawer.image.label') }}</div>
          <div class="text-caption text-sub-title mb-2">
            {{ $t('UserSettings.emailConnector.signature.drawer.image.description') }}
          </div>
          <div class="d-flex align-center mb-4">
            <img
              v-if="!imageMissing"
              :src="imageUrl"
              :alt="$t('UserSettings.emailConnector.signature.drawer.image.label')"
              style="max-height: 48px; max-width: 160px;"
              @error="imageMissing = true">
            <v-spacer />
            <v-btn
              class="btn"
              small
              @click="openImageCrop">
              {{ $t('UserSettings.emailConnector.signature.drawer.image.change') }}
            </v-btn>
            <v-btn
              v-if="customLogo"
              :loading="resettingImage"
              class="btn ms-2"
              small
              @click="resetImage">
              {{ $t('UserSettings.emailConnector.signature.drawer.image.reset') }}
            </v-btn>
          </div>
          <v-divider class="mb-4" />
          <div class="text-subtitle-1 mb-2">{{ $t('UserSettings.emailConnector.signature.drawer.text.label') }}</div>
          <rich-editor
            ref="signatureEditor"
            v-model="editedHtml"
            :placeholder="$t('UserSettings.emailConnector.signature.drawer.text.placeholder')"
            ck-editor-type="email"
            :auto-grow-max-height="250"
            :tag-enabled="false"
            disable-suggester
            hide-chars-count />
          <div class="d-flex mt-2">
            <v-btn
              v-if="custom"
              class="btn"
              small
              text
              @click="resetToDefault">
              {{ $t('UserSettings.emailConnector.signature.drawer.resetDefault') }}
            </v-btn>
          </div>
        </div>
      </template>
      <template #footer>
        <div class="d-flex">
          <v-spacer />
          <v-btn
            class="btn"
            @click="close">
            {{ $t('UserSettings.emailConnector.userSetting.drawer.cancel') }}
          </v-btn>
          <v-btn
            :loading="saving"
            class="btn btn-primary ms-5"
            @click="save">
            {{ $t('UserSettings.emailConnector.signature.drawer.save') }}
          </v-btn>
        </div>
      </template>
    </exo-drawer>
    <!-- The platform's own image cropper, borrowed whole exactly as the contact
         form borrows it: it owns the picking, the cropping and the upload, and
         hands back an upload id this drawer trades for the stored image. -->
    <image-crop-drawer
      ref="signatureImageCropDrawer"
      :drawer-title="$t('UserSettings.emailConnector.signature.drawer.image.crop.title')"
      :crop-options="{aspectRatio: NaN, viewMode: 1}"
      :max-file-size="maxImageFileSize"
      :max-image-width="maxImageWidth"
      @apply="onImageCropped" />
  </div>
</template>

<script>
export default {
  data: () => ({
    signatureDrawer: false,
    saving: false,
    resettingImage: false,
    // The signature as the server answered it on open.
    enabled: true,
    custom: false,
    customLogo: false,
    defaultHtml: '',
    // What the editor holds. Starts from the custom markup when there is one,
    // from the default otherwise -- a convenient starting point to edit, not a
    // stored copy: nothing is stored until Save.
    editedHtml: '',
    // Steps whenever the image changes, so the preview URL is never a stale
    // browser cache.
    imageVersion: Date.now(),
    imageMissing: false,
    // A signature image is displayed small; a logo of 350px and 1 MB is
    // generous for it -- the same bounds the contact photo settles on.
    maxImageWidth: 350,
    maxImageFileSize: 1024 * 1024,
  }),
  computed: {
    /**
     * The signature image as currently stored, versioned for the cache.
     *
     * @returns {string} the image URL
     */
    imageUrl() {
      return this.$emailConnectorCommonService.getSignatureImageUrl(this.imageVersion);
    },
  },
  created() {
    this.$root.$on('open-email-signature-drawer', this.open);
  },
  methods: {
    /**
     * Opens the drawer on the signature as the server holds it right now.
     *
     * @returns {void}
     */
    open() {
      this.$emailConnectorCommonService.getEmailSignature()
        .then(signature => {
          this.enabled = signature?.enabled !== false;
          this.custom = !!signature?.customHtml;
          this.customLogo = !!signature?.customLogo;
          this.defaultHtml = signature?.defaultHtml || '';
          this.editedHtml = signature?.customHtml || this.defaultHtml;
          this.imageMissing = false;
          this.imageVersion = Date.now();
          this.signatureDrawer = true;
        })
        .catch(() => this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.preferences.error'), 'error'));
    },
    /**
     * Stores what the editor holds as the caller's own signature. The enable
     * switch is not this drawer's business -- it rides along unchanged.
     *
     * @returns {void}
     */
    save() {
      this.saving = true;
      this.$emailConnectorCommonService.saveEmailSignature({
        enabled: this.enabled,
        customHtml: this.editedHtml,
      })
        .then(() => {
          this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.preferences.saved'), 'success');
          this.$root.$emit('email-signature-updated');
          this.close();
        })
        .catch(() => this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.signature.saveError'), 'error'))
        .finally(() => this.saving = false);
    },
    /**
     * Throws the custom markup away, on the user's explicit say-so: stores the
     * reset immediately (a null custom means "follow the profile again") and
     * reloads the editor with the computed default.
     *
     * @returns {void}
     */
    resetToDefault() {
      this.saving = true;
      this.$emailConnectorCommonService.saveEmailSignature({
        enabled: this.enabled,
        customHtml: null,
      })
        .then(() => this.$emailConnectorCommonService.getEmailSignature())
        .then(signature => {
          this.custom = false;
          this.defaultHtml = signature?.defaultHtml || '';
          this.editedHtml = this.defaultHtml;
          this.$root.$emit('email-signature-updated');
        })
        .catch(() => this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.signature.saveError'), 'error'))
        .finally(() => this.saving = false);
    },
    /**
     * Hands the picking and cropping to the platform's cropper.
     *
     * @returns {void}
     */
    openImageCrop() {
      this.$refs.signatureImageCropDrawer.open();
    },
    /**
     * Trades the cropper's upload for the stored signature image, then bumps
     * the preview's version so the new picture actually shows.
     *
     * @param {object} image - the cropper's result, carrying its uploadId
     * @returns {void}
     */
    onImageCropped(image) {
      if (!image?.uploadId) {
        return;
      }
      this.$emailConnectorCommonService.saveSignatureImage(image.uploadId)
        .then(() => {
          this.customLogo = true;
          this.imageMissing = false;
          this.imageVersion = Date.now();
          this.$root.$emit('email-signature-updated');
        })
        .catch(() => this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.signature.imageError'), 'error'));
    },
    /**
     * Puts the image back to the company logo.
     *
     * @returns {void}
     */
    resetImage() {
      this.resettingImage = true;
      this.$emailConnectorCommonService.resetSignatureImage()
        .then(() => {
          this.customLogo = false;
          this.imageMissing = false;
          this.imageVersion = Date.now();
          this.$root.$emit('email-signature-updated');
        })
        .catch(() => this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.signature.imageError'), 'error'))
        .finally(() => this.resettingImage = false);
    },
    /**
     * Closes the drawer.
     *
     * @returns {void}
     */
    close() {
      this.signatureDrawer = false;
    },
    /**
     * Forgets the opened state, so the next open reads fresh.
     *
     * @returns {void}
     */
    reset() {
      this.editedHtml = '';
      this.defaultHtml = '';
      this.signatureDrawer = false;
    },
  },
};
</script>
