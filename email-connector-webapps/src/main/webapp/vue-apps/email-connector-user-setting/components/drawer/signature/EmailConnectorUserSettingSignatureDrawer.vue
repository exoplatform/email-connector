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
          <div class="d-flex align-center flex-wrap mb-4">
            <img
              v-if="!imageMissing"
              :src="imageUrl"
              :alt="$t('UserSettings.emailConnector.signature.drawer.image.label')"
              style="max-height: 48px; max-width: 160px;"
              @error="imageMissing = true">
            <v-btn
              class="btn ms-4"
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
            <v-btn
              v-if="!logoInSignature"
              class="btn ms-2 mt-1"
              small
              @click="insertLogo">
              {{ $t('UserSettings.emailConnector.signature.drawer.image.insert') }}
            </v-btn>
          </div>
          <v-divider class="mb-4" />
          <div class="text-subtitle-1 mb-2">{{ $t('UserSettings.emailConnector.signature.drawer.text.label') }}</div>
          <rich-editor
            :key="editorKey"
            @ready="onEditorReady"
            ref="signatureEditor"
            v-model="editedHtml"
            :placeholder="$t('UserSettings.emailConnector.signature.drawer.text.placeholder')"
            ck-editor-type="emailSignature"
            :auto-grow-max-height="250"
            :tag-enabled="false"
            disable-suggester
            hide-chars-count />
        </div>
      </template>
      <template #footer>
        <div class="d-flex align-center">
          <v-btn
            :loading="saving"
            :disabled="!custom"
            class="btn"
            @click="resetToDefault">
            {{ $t('UserSettings.emailConnector.signature.drawer.resetDefault') }}
          </v-btn>
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
      :crop-options="cropOptions"
      :max-file-size="maxImageFileSize"
      :max-image-width="maxImageWidth"
      @apply="onImageCropped" />
  </div>
</template>

<script>
// The signature image's endpoint, without the version that follows it. Matched on
// this rather than on a whole URL because the version changes every time the picture
// does -- which is the entire point of it.
const SIGNATURE_IMAGE_ENDPOINT = '/user-email-setting/signature/image';
const VERSIONED_IMAGE = /\/user-email-setting\/signature\/image\?v=[^"'\s>]*/g;

export default {
  data: () => ({
    signatureDrawer: false,
    saving: false,
    resettingImage: false,
    // Bumped whenever the text is replaced programmatically. CKEditor reads its
    // starting content once and never watches the model again, so a reset that only
    // reassigns the bound value leaves the old text on screen -- the change is real
    // and invisible, which reads as "reset does nothing". Remounting is the reliable
    // way to make the editor show what was just put in it.
    editorKey: 1,
    // The signature as the server answered it on open.
    enabled: true,
    custom: false,
    customLogo: false,
    defaultHtml: '',
    // The picture's markup, so it can be put back after being deleted.
    logoHtml: '',
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
     * What the cropper is allowed to do with the picture.
     * <p>
     * The minimums are the point. ImageCropDrawer defaults its crop box to a 388px
     * minimum width, which is wider than the 350px this signature stores -- so the box
     * opened covering the whole picture and could be neither shrunk nor moved, since
     * there was nowhere smaller for it to go. These options are spread over the
     * component's own, so lowering the minimum is enough to hand the crop box back.
     * <p>
     * No aspect ratio, because a logo is whatever shape it is, and a crop area that
     * starts a little inside the picture so there is something to drag.
     *
     * @returns {object} the cropper options
     */
    cropOptions() {
      return {
        aspectRatio: NaN,
        viewMode: 1,
        minCropBoxWidth: 20,
        minCropBoxHeight: 20,
        autoCropArea: 0.9,
      };
    },
    /**
     * Whether the picture is already sitting in the signature.
     * <p>
     * Drives the insert button away when there is nothing to insert: offering to add
     * a picture that is visibly already there is the kind of button people click once
     * and then distrust. Matched on the image endpoint rather than on the whole URL,
     * because the version that follows it changes every time the picture does.
     *
     * @returns {boolean} true when the signature already shows it
     */
    logoInSignature() {
      return !!this.editedHtml && this.editedHtml.includes(SIGNATURE_IMAGE_ENDPOINT);
    },
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
          this.editorKey++;
          this.customLogo = !!signature?.customLogo;
          this.defaultHtml = signature?.defaultHtml || '';
          this.logoHtml = signature?.logoHtml || '';
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
          this.editorKey++;
          this.$root.$emit('email-signature-updated');
        })
        .catch(() => this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.signature.saveError'), 'error'))
        .finally(() => this.saving = false);
    },
    /**
     * Re-reads the signature after the picture has been replaced or reset.
     * <p>
     * Two things go stale the moment the picture changes, and both are invisible: the
     * markup the insert button holds still names the old version, and the picture
     * already sitting in the text does too. The endpoint answers with a month of
     * private caching -- deliberately, since the version makes each picture a new
     * address -- so an old address does not merely lag, it keeps serving the previous
     * image out of the browser's cache. That is why changing the picture and pressing
     * insert used to put the company logo back.
     *
     * @returns {Promise} resolves once the markup names the current picture
     */
    refreshSignatureMarkup() {
      return this.$emailConnectorCommonService.getEmailSignature()
        .then(signature => {
          this.defaultHtml = signature?.defaultHtml || '';
          this.logoHtml = signature?.logoHtml || '';
          const current = (this.logoHtml.match(VERSIONED_IMAGE) || [])[0];
          if (!current || !this.editedHtml) {
            return;
          }
          const updated = this.editedHtml.replace(VERSIONED_IMAGE, current);
          if (updated !== this.editedHtml) {
            this.editedHtml = updated;
            this.editorKey++;
          }
        })
        .catch(() => { /* the picture changed; a stale preview is not worth an alert */ });
    },
    /**
     * Refuses everything dropped into the signature.
     * <p>
     * There is nothing to drop here. The picture's size is decided in the cropper
     * where it is chosen, and its position by inserting it at the cursor -- both of
     * which do the same thing every time. Dragging was tried and taken back out: it
     * could only be started from a widget handle nobody finds, the browser's own drag
     * competed with CKEditor's and pasted the image's address into the signature as
     * "data:image/gif;base64,R0lGOD...", and each fix for that uncovered the next.
     * <p>
     * Refusing the drop outright is what keeps that text from ever appearing again --
     * a drag that does nothing is a small disappointment, a drag that writes rubbish
     * into a signature is a bug someone else has to notice.
     *
     * @returns {void}
     */
    onEditorReady() {
      const editor = this.$refs.signatureEditor?.editor;
      if (!editor || editor.signatureDropGuarded) {
        return;
      }
      editor.signatureDropGuarded = true;
      editor.on('drop', event => event.cancel());
    },
    /**
     * Puts the picture back into the signature, at the cursor.
     * <p>
     * The picture is part of the text rather than something bolted on after it, which
     * is what lets it be dragged next to the name, resized, or deleted for a signature
     * with no picture at all. Deleting it has to be undoable, though, or the only way
     * back would be resetting the whole signature -- so this drops it wherever the
     * cursor is.
     *
     * @returns {void}
     */
    insertLogo() {
      const editor = this.$refs.signatureEditor?.editor;
      if (!editor || !this.logoHtml) {
        return;
      }
      editor.insertHtml(this.logoHtml);
      editor.fire('change');
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
          return this.refreshSignatureMarkup();
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
          return this.refreshSignatureMarkup();
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
