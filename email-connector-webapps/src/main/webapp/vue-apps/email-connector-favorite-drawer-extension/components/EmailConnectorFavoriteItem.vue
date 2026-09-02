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
<template>
  <v-list-item
    v-if="email"
    @keydown.enter="open"
    @click="open">
    <v-list-item-icon class="me-3 my-auto">
      <v-card
        :min-width="iconWidth"
        class="d-flex justify-center no-border-radius"
        color="transparent"
        flat>
        <v-icon :size="iconSize" color="primary">fa-envelope</v-icon>
      </v-card>
    </v-list-item-icon>
    <v-list-item-content>
      <v-list-item-title :class="['text-truncate', {'font-weight-bold': unread}]">{{ subject }}</v-list-item-title>
      <v-list-item-subtitle v-if="expanded" class="d-flex align-center full-width overflow-hidden pt-2px">
        <span class="flex-grow-0 flex-shrink-1 text-truncate">{{ senderName }}</span>
        <v-icon class="flex-grow-0 flex-shrink-0 mx-2" size="2">fa-circle</v-icon>
        <date-format class="flex-grow-0 flex-shrink-0" :value="receivedDate" />
      </v-list-item-subtitle>
    </v-list-item-content>
    <v-list-item-action>
      <favorite-button
        :id="id"
        :favorite="isFavorite"
        type="email"
        type-label="email"
        @removed="removed"
        @remove-error="removeError" />
    </v-list-item-action>
  </v-list-item>
</template>
<script>
export default {
  props: {
    id: {
      type: String,
      default: () => null,
    },
    clickCallback: {
      type: Function,
      default: null,
    },
    expanded: {
      type: Boolean,
      default: false,
    },
  },
  data: () => ({
    email: null,
    // A row of the favorites drawer is a favorite by construction: the drawer
    // listed it from the favorites store, so the button starts lit without a
    // second read to ask what the drawer already knows.
    isFavorite: true,
  }),
  computed: {
    iconWidth() {
      return this.expanded ? 40 : 30;
    },
    iconSize() {
      return this.expanded ? 24 : 18;
    },
    subject() {
      return this.email?.subject?.trim?.() || this.$t('UITopBarFavoritesPortlet.email.noSubject');
    },
    senderName() {
      return this.email?.sender?.name || this.email?.sender?.email || '';
    },
    receivedDate() {
      return this.email?.receivedDate;
    },
    unread() {
      return this.email && !this.email.read;
    },
  },
  created() {
    // Favorites are keyed by the mail's technical id, which is the one identifier
    // the rest of this app never uses — everything else addresses a message by its
    // IMAP UID — hence the dedicated read.
    fetch(`/email-connector/rest/email-box/favorites/${this.id}`, {
      method: 'GET',
      credentials: 'include',
    }).then(response => {
      if (!response?.ok) {
        throw new Error('Favorited email cannot be read');
      }
      return response.json();
    }).then(email => this.email = email)
      .catch(() => {
        // The mail is gone from the mailbox (deleted, or aged out of the cached
        // window between two syncs). Telling the drawer drops the entry instead of
        // leaving a row that opens onto nothing.
        this.$root.$emit('favorite-removed', 'email', this.id);
      });
  },
  methods: {
    /**
     * Opens the mail in the mailbox's own reader, and records the favorite as
     * accessed when a real click (not a keyboard Enter) brought the user there.
     *
     * @param {Event} event the row's click or keydown
     * @returns {void}
     */
    open(event) {
      // Vuetify re-emits an Enter pressed anywhere inside the row as the row's own
      // click, so a keyboard user unfavoriting from the star would also open the
      // mail; the star's mouse clicks never reach here, it stops them itself.
      if (event?.target?.closest?.('.v-list-item__action')) {
        return;
      }
      if (event?.which === 1 || event?.which === 2) {
        this.clickCallback?.('email', this.id);
      }
      // The mailbox owns the reader, so it is asked to open the conversation rather
      // than this drawer rendering a second, lesser copy of it.
      window.require(['SHARED/emailConnectorQuickActionExtension'], () =>
        document.dispatchEvent(new CustomEvent('open-email-box-mail', {
          detail: {mailRemoteId: this.email?.mailRemoteId},
        })));
    },
    /**
     * The star has removed the favorite from the platform's store: drop the row,
     * then clear the flag the favorite was mirroring.
     *
     * The favorite of a mail is only a mirror of the mail server's own \Flagged
     * flag, recomputed from it at every sync — so a removal that stopped at the
     * favorites store would be undone within minutes, the row quietly back in the
     * drawer. The flag is therefore cleared too, through the endpoint the mailbox
     * star uses — called directly, as the read above is: the mailbox service that
     * wraps it lives in the mailbox bundle, which this extension deliberately
     * does not load on every page. When the server refuses the message, that
     * endpoint reverts the row and reconciles the favorites itself; when it
     * cannot reach the server at all it answers before reconciling — so the
     * favorite is put back from here in both cases (a no-op in the first) and
     * the drawer re-read to show it.
     *
     * The drop below destroys this row before the server answers, and a
     * destroyed component has no translator any more: what the toasts will say
     * is resolved first, while there is still one to ask.
     *
     * @returns {void}
     */
    removed() {
      const removedMessage = this.$t('Favorite.tooltip.SuccessfullyDeletedFavorite');
      const errorMessage = this.$t('Favorite.tooltip.ErrorDeletingFavorite', {0: this.$t('UITopBarFavoritesPortlet.email.label')});
      this.isFavorite = false;
      this.$root.$emit('favorite-removed', 'email', this.id);
      fetch('/email-connector/rest/email-box/starred?starred=false', {
        method: 'PATCH',
        credentials: 'include',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify([this.email.mailRemoteId]),
      }).then(response => {
        if (!response?.ok) {
          throw new Error('Favorited email cannot be unstarred');
        }
        return response.json();
      }).then(result => {
        if (result?.failedUpdates) {
          throw new Error('The mail server refused to unstar the email');
        }
        // A mailbox drawer already open holds its own copy of the flag and hears
        // only its own root; the document is the one bus the two apps share.
        document.dispatchEvent(new CustomEvent('email-favorite-status-changed', {
          detail: {
            mailRemoteIds: [this.email.mailRemoteId],
            favorite: false,
          },
        }));
        this.displayAlert(removedMessage);
      }).catch(() => this.$favoriteService.addFavorite('email', this.id)
        .catch(() => null)
        .finally(() => {
          this.displayAlert(errorMessage, 'error');
          this.$root.$emit('refresh-favorite-list');
        }));
    },
    /**
     * Tells the user the favorite could not be removed — the button's own
     * failure, on a row still alive.
     *
     * @returns {void}
     */
    removeError() {
      this.displayAlert(this.$t('Favorite.tooltip.ErrorDeletingFavorite', {0: this.$t('UITopBarFavoritesPortlet.email.label')}), 'error');
    },
    /**
     * Shows a platform toast: the alert component lives in another Vue root, so
     * it is reached through the document rather than this app's root.
     *
     * @param {string} message the text to show
     * @param {string} type 'success' (default) or 'error'
     * @returns {void}
     */
    displayAlert(message, type) {
      document.dispatchEvent(new CustomEvent('notification-alert', {detail: {
        message,
        type: type || 'success',
      }}));
    },
  },
};
</script>
