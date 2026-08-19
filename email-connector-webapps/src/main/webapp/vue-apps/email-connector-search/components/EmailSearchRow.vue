<!--
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
-->
<!--
  One mail in the platform's search results.

  The skeleton is the one the other connectors use (see the Documents card): the same
  list item, the same icon gutter, the same title and subtitle classes. Results from every connector are interleaved in a single list, so a row
  that sets its own sizes lands on a different grid from the row above it -- titles
  at another size, text starting a few pixels off -- and the list reads as broken
  rather than as one answer to one question.
-->
<template>
  <v-hover v-slot="{ hover }">
    <v-card
      flat
      class="pa-0"
      @click.stop.prevent="openEmail">
      <v-list class="pa-0" :class="hover && 'light-grey-background-color no-border-radius' || ''">
        <v-list-item>
          <!-- 26 rather than the 32 the file rows use: an envelope is a much wider
               glyph than a file, so at an equal size it reads as a bigger icon. The
               gutter is unchanged, so the titles still line up. -->
          <v-list-item-icon class="ms-1 me-3">
            <v-icon
              size="26"
              :color="iconColor"
              class="mt-2">
              {{ iconClass }}
            </v-icon>
          </v-list-item-icon>
          <v-list-item-content>
            <v-list-item-title class="d-flex flex-row full-width align-center">
              <!-- The same title as every other row, unread or not: the file rows
                   never bold theirs, and a bolder title here would break the rhythm
                   of a list the user reads as one answer. Unread is already said by
                   the sealed envelope. -->
              <h1
                class="flex-grow-1 title primary--text pt-1 mb-0 ps-0 my-auto align-center text-start text-truncate"
                :aria-label="subject">
                {{ subject }}
              </h1>
              <!-- Favoriting from the results, like the actions on the other
                   connectors' rows: revealed on hover, and clickable.

                   It fades rather than leaving the layout — taking it out would let
                   the title grow into its place as the pointer arrives, and a row
                   that resizes under the cursor is what made the mailbox list
                   flicker. The click is stopped here so that favoriting a result
                   does not also open it. -->
              <v-btn
                v-if="canFavorite"
                icon
                x-small
                class="ms-2 flex-grow-0 flex-shrink-0"
                :style="{opacity: hover ? 1 : 0, pointerEvents: hover ? 'auto' : 'none'}"
                :title="favoriteLabel"
                @click.stop.prevent="toggleFavorite">
                <v-icon size="14" :class="favorite ? 'amber--text text--darken-1' : 'icon-default-color'">
                  {{ favorite ? 'fas fa-star' : 'far fa-star' }}
                </v-icon>
              </v-btn>
              <!-- Like the star, and like the actions on the other connectors'
                   rows: shown on hover, and fading rather than leaving the layout so
                   the title does not grow into its place under the pointer. -->
              <v-chip
                v-if="folderLabel"
                x-small
                class="ms-2 flex-grow-0 flex-shrink-0"
                :style="{opacity: hover ? 1 : 0}">
                {{ folderLabel }}
              </v-chip>
            </v-list-item-title>
            <v-list-item-subtitle class="d-flex flex-column">
              <span class="d-flex flex-row align-center mx-auto full-width">
                <span class="text-subtitle text-truncate">{{ senderName }}</span>
                <span class="d-flex flex-row align-center flex-grow-0 flex-shrink-0">
                  <v-icon
                    size="3"
                    class="icon-default-color mx-3">fas fa-circle</v-icon>
                  <v-icon
                    size="12"
                    class="icon-default-color">fas fa-calendar-alt</v-icon>
                </span>
                <date-format class="ms-1 my-auto" :value="result.receivedDate" />
              </span>
              <!-- The message around what was searched for, like the file rows quote
                   the document. Only a hit read from the local cache carries one: a
                   hit found on the mail server is envelope-only, and fetching each
                   body to quote it would cost a round-trip per result. -->
              <div
                v-if="excerptHtml"
                class="pt-2 text-wrap text-body-2 text-color text-break text-truncate-2"
                v-sanitized-html="excerptHtml"></div>
            </v-list-item-subtitle>
          </v-list-item-content>
        </v-list-item>
      </v-list>
    </v-card>
  </v-hover>
</template>
<script>
const HTML_ENTITIES = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  '\'': '&#39;',
};

export default {
  props: {
    result: {
      type: Object,
      default: () => ({}),
    },
    term: {
      type: String,
      default: () => null,
    },
  },
  data: () => ({
    // The row owns the flag once the user touches it: the search response it was
    // built from is a snapshot, and re-fetching the whole search to reflect one
    // click would be absurd.
    toggled: null,
  }),
  computed: {
    favorite() {
      return this.toggled === null ? !!this.result?.starred : this.toggled;
    },
    excerptHtml() {
      const excerpt = this.result?.excerpt;
      if (!excerpt) {
        return '';
      }
      // Escaped first, then the searched words are wrapped: the excerpt is a piece of
      // somebody's mail, so it reaches the page as text and never as markup.
      const escaped = excerpt.replace(/[&<>"']/g, character => HTML_ENTITIES[character]);
      const searched = (this.term || this.result?.term || '').trim();
      if (!searched) {
        return escaped;
      }
      const pattern = new RegExp(String.raw`(${searched.replace(/[.*+?^${}()|[\]\\]/g, String.raw`\$&`)})`, 'gi');
      return escaped.replace(pattern, '<strong>$1</strong>');
    },
    favoriteLabel() {
      return this.favorite && this.$t('emailConnector.mailBox.list.drawer.detail.removeFavorite.label')
        || this.$t('emailConnector.mailBox.list.drawer.detail.addFavorite.label');
    },
    canFavorite() {
      // The flag is pushed through the INBOX, so only inbox mail can be favorited
      // from here -- the same rule the mailbox itself applies. A Sent or Archive hit
      // simply offers nothing.
      return !this.result?.folder || this.result.folder === 'INBOX';
    },
    unread() {
      return this.result && !this.result.read;
    },
    iconClass() {
      // A sealed envelope for unread, an opened one otherwise: the same distinction
      // the mailbox makes, readable at a glance in a mixed list.
      return this.unread ? 'fas fa-envelope' : 'far fa-envelope-open';
    },
    iconColor() {
      return this.unread ? 'primary' : '#707070';
    },
    subject() {
      return this.result?.subject?.trim?.() || this.$t('emailConnector.mailBox.list.drawer.noSubject');
    },
    senderName() {
      return this.result?.sender?.name || this.result?.sender?.address || '';
    },
    folderLabel() {
      // The inbox is where mail normally is, so only the other folders are worth a
      // chip: seeing "Sent" explains why a hit reads as something the user wrote.
      if (this.result?.folder === 'SENT') {
        return this.$t('emailConnector.mailBox.list.drawer.folder.sent');
      }
      return this.result?.folder === 'ARCHIVE' ? this.$t('emailConnector.mailBox.list.drawer.folder.archive') : '';
    },
  },
  methods: {
    /**
     * Favorites or unfavorites the message from the results.
     *
     * The star is lit before the server answers and put back if it refuses, because
     * this is the mail server's own \Flagged flag: it is what the user will see in
     * their webmail and on their phone, so a star left lit that the server rejected
     * would be a lie, not a delay.
     *
     * @returns {void}
     */
    toggleFavorite() {
      const wanted = !this.favorite;
      this.toggled = wanted;
      fetch(`/email-connector/rest/email-box/starred?starred=${wanted}`, {
        headers: {'Content-Type': 'application/json'},
        credentials: 'include',
        method: 'PATCH',
        body: JSON.stringify([this.result?.mailRemoteId]),
      }).then(response => {
        if (!response?.ok) {
          throw new Error('The favorite could not be updated');
        }
        return response.json();
      }).then(result => {
        // The endpoint answers {"failedUpdates": n}, not a bare number. Reading the
        // object as one made every success look like a refusal: the flag was written
        // on the server and the star put straight back here.
        if ((result?.failedUpdates ?? 0) > 0) {
          throw new Error('The mail server refused the favorite');
        }
      }).catch(() => {
        this.toggled = !wanted;
        const errorKey = wanted && 'emailConnector.mailBox.list.drawer.addFavorite.email.error'
          || 'emailConnector.mailBox.list.drawer.removeFavorite.email.error';
        document.dispatchEvent(new CustomEvent('alert-message', {detail: {
          alertType: 'error',
          alertMessage: this.$t(errorKey, {0: 1}),
        }}));
      });
    },
    openEmail() {
      // The mailbox owns the reader, wherever the user is standing. Requiring the
      // module first covers the page that has not loaded it yet; it is a no-op once
      // it has.
      //
      // The folder and the cached flag travel with the id, and both matter: a UID is
      // only unique within its folder, and a hit the server found may not be held
      // locally at all -- the mailbox pulls that one in before opening it. Sending
      // the id alone opened an empty reader for every hit that was not a cached
      // inbox message.
      window.require(['SHARED/emailConnectorQuickActionExtension'], () =>
        document.dispatchEvent(new CustomEvent('open-email-box-mail', {
          detail: {
            mailRemoteId: this.result?.mailRemoteId,
            folder: this.result?.folder,
            cached: this.result?.cached,
          },
        })));
    },
  },
};
</script>
