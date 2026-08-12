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
 * What can be done to one contact, shared by the contact card and by the list
 * row's own menu.
 *
 * The two surfaces offer the same actions, and an action is more than its REST
 * call: publishing has three outcomes worth three different sentences, and
 * deleting says "Delete" or "Remove from list" depending on how the contact
 * came to exist. Copied into a second component, those rules drift — one of
 * them keeps saying "Delete" for a collected row, or stops offering the undo.
 * So they live here once, and each caller only decides what its own surface
 * does afterwards (close the card, leave the row where it is).
 *
 * Every method takes the contact rather than reading it off the component: the
 * card holds one in its data, a row holds one as a prop, and a shared handler
 * that assumed either would only fit one of them.
 */
export default {
  data() {
    return {
      publishing: false,
      deleting: false,
      // A bulk call is in flight. Its own flag rather than a shared one: a
      // selection's actions are several, and greying the wrong one out is the
      // bug this avoids.
      bulkWorking: false,
    };
  },
  methods: {
    /**
     * Opens the mail composer addressed to this contact — their main address,
     * which is the one the list files them under.
     *
     * @param {object} contact - the contact to write to
     * @returns {void}
     */
    composeToContact(contact) {
      this.$emailConnectorContactsService.composeTo({
        name: contact.displayName || '',
        address: contact.primaryEmail,
      });
    },
    /**
     * Opens the composer with this contact's vCard already attached and nobody
     * addressed — sharing the person is this click, choosing who gets them is
     * the user's next one.
     *
     * @param {object} contact - the contact to share
     * @returns {void}
     */
    sendContactByEmail(contact) {
      this.$emailConnectorContactsService.sendByEmail(contact);
    },
    /**
     * Hands this contact's card to the chat, which asks the user which
     * conversation it goes into.
     *
     * @param {object} contact - the contact to share
     * @returns {void}
     */
    sendContactByChat(contact) {
      this.$emailConnectorContactsService.sendByChat(contact)
        .catch(() => this.notifyContactAction('error', 'emailConnector.contacts.detail.sendByChat.error'));
    },
    /**
     * Publishes one contact into the user's own address book — creates only,
     * server-guarded, so nothing already on the server can be overwritten.
     * <p>
     * This is the single publish, not the reviewed bulk queue: one row, one
     * decision, one answer to report.
     *
     * @param {object} contact - the contact to publish
     * @returns {Promise<object>} the contact re-read as an address-book row, or
     *          null when it was queued instead or the publish was refused —
     *          the user has been told which in every case
     */
    publishContactCard(contact) {
      this.publishing = true;
      return this.$emailConnectorContactsService.publishContact(contact.id)
        .then(published => {
          if (published?.queued) {
            // The server was away, so the publish waits in the queue and goes
            // out after the next successful sync. The contact is unchanged --
            // still local, still here -- so nothing re-renders; only the words
            // change: "will be saved", never an error asking to retry what no
            // longer needs retrying.
            this.notifyContactAction('info', 'emailConnector.contacts.detail.publish.queued');
            return null;
          }
          this.$root.$emit('email-contacts-refresh');
          this.notifyContactAction('success', 'emailConnector.contacts.detail.publish.success');
          return published;
        })
        .catch(error => {
          // 409 is the server refusing to create over an existing entry - the
          // one refusal worth its own words, because the fix (sync, then look
          // again) is different from "try later".
          const key = error?.status === 409
            && 'emailConnector.contacts.detail.publish.exists'
            || 'emailConnector.contacts.detail.publish.error';
          this.notifyContactAction('error', key);
          return null;
        })
        .finally(() => this.publishing = false);
    },
    /**
     * Deletes or suppresses the contact, per its source, and announces a
     * suppression so the app can offer its undo.
     *
     * @param {object} contact - the contact to remove
     * @returns {Promise<boolean>} true when the contact is gone from the list
     */
    removeContactCard(contact) {
      this.deleting = true;
      return this.$emailConnectorContactsService.deleteContact(contact.id)
        .then(result => {
          if (result.suppressed) {
            this.$root.$emit('email-contact-suppressed', result.id);
          }
          this.$root.$emit('email-contacts-refresh');
          return true;
        })
        .catch(() => {
          this.notifyContactAction('error', 'emailConnector.contacts.delete.error');
          return false;
        })
        .finally(() => this.deleting = false);
    },
    /**
     * Writes to everybody in a selection at once — the composer opens with all
     * of them as recipients.
     *
     * @param {Array} contacts - the contacts to write to
     * @returns {void}
     */
    composeToContacts(contacts) {
      this.$emailConnectorContactsService.composeToMany(contacts.map(contact => ({
        name: contact.displayName || '',
        address: contact.primaryEmail,
      })));
    },
    /**
     * Shares a selection by email: the composer opens with the people attached
     * and nobody addressed, exactly as sharing one contact does.
     * <p>
     * One contact takes the single-card path rather than the multi-card one, so
     * sharing the row you happened to tick sends the very file its own menu
     * would have sent, named after the person rather than "1 contacts.vcf".
     *
     * @param {Array} contacts - the contacts to share
     * @returns {void}
     */
    sendContactsByEmail(contacts) {
      if (contacts.length === 1) {
        this.sendContactByEmail(contacts[0]);
        return;
      }
      this.$emailConnectorContactsService.sendManyByEmail(contacts);
    },
    /**
     * Shares a selection into a chat conversation, as one file the chat asks a
     * destination for. One contact takes the single-card path, for the same
     * reason as by email.
     *
     * @param {Array} contacts - the contacts to share
     * @returns {void}
     */
    sendContactsByChat(contacts) {
      if (contacts.length === 1) {
        this.sendContactByChat(contacts[0]);
        return;
      }
      this.$emailConnectorContactsService.sendManyByChat(contacts)
        .catch(() => this.notifyContactAction('error', 'emailConnector.contacts.select.sendByChat.error'));
    },
    /**
     * Stars or unstars a whole selection.
     * <p>
     * Reported by the numbers rather than by a thrown failure: a hundred rows
     * are a hundred calls, and "eleven could not be starred" is the only honest
     * sentence when eleven of them refused.
     *
     * @param {Array} contacts - the contacts to star or unstar
     * @param {boolean} favorite - true to star, false to unstar
     * @returns {Promise<void>} resolves once every call has been answered
     */
    setContactsFavorite(contacts, favorite) {
      this.bulkWorking = true;
      return this.$emailConnectorContactsService.setFavorites(contacts.map(contact => contact.id), favorite)
        .then(outcome => {
          if (outcome.failed) {
            this.notifyContactAction('error', favorite ? 'emailConnector.contacts.select.favorite.error'
              : 'emailConnector.contacts.select.unfavorite.error');
          } else {
            this.notifyContactCount(favorite ? 'emailConnector.contacts.select.favorite.done'
              : 'emailConnector.contacts.select.unfavorite.done', outcome.done);
          }
          this.$root.$emit('email-contacts-refresh');
        })
        .finally(() => this.bulkWorking = false);
    },
    /**
     * Deletes or suppresses a whole selection, per each row's own source — the
     * server decides that per contact, exactly as it does for one.
     * <p>
     * No per-row undo is offered here, unlike the single delete: a selection of
     * fifty collected rows would raise fifty undo toasts, each about a contact
     * the user can no longer point at. The confirmation named the count before
     * anything happened, which is where this action's safety lives.
     *
     * @param {Array} contacts - the contacts to remove
     * @returns {Promise} resolves once every call has been answered
     */
    removeContactCards(contacts) {
      if (contacts.length === 1) {
        // One row takes the single delete, undo included: a suppression the
        // user can still point at is worth offering back.
        return this.removeContactCard(contacts[0]);
      }
      this.deleting = true;
      return this.$emailConnectorContactsService.deleteContacts(contacts.map(contact => contact.id))
        .then(outcome => {
          if (outcome.failed) {
            this.notifyContactAction('error', 'emailConnector.contacts.select.delete.error');
          } else {
            this.notifyContactCount('emailConnector.contacts.select.delete.done', outcome.done);
          }
          this.$root.$emit('email-contacts-refresh');
        })
        .finally(() => this.deleting = false);
    },
    /**
     * The honest label of the delete action. A manual or directory-linked
     * contact deletes for real, both existing by an explicit act; a collected
     * one is only removed from the list, because the next mail from that person
     * would bring it straight back.
     *
     * @param {object} contact - the contact the action would remove
     * @returns {string} the localized label
     */
    contactDeleteLabel(contact) {
      return (contact?.source === 'MANUAL' || contact?.source === 'DIRECTORY')
        && this.$t('emailConnector.contacts.detail.delete')
        || this.$t('emailConnector.contacts.detail.remove');
    },
    /**
     * Says how an action went, in the toast every other outcome in this app
     * uses. On the document rather than on the component, so the message
     * survives the surface that raised it — a row menu unmounts the moment the
     * pointer leaves, well before a slow publish answers.
     *
     * @param {string} alertType - success, info or error
     * @param {string} messageCode - the bundle key of the sentence
     * @returns {void}
     */
    notifyContactAction(alertType, messageCode) {
      document.dispatchEvent(new CustomEvent('alert-message', {detail: {alertType, alertMessage: this.$t(messageCode)}}));
    },
    /**
     * Says how a bulk action went, with the number it acted on — which is the
     * only part of "done" a selection's outcome can be checked against.
     *
     * @param {string} messageCode - the bundle key, taking the count as {0}
     * @param {number} count - how many contacts were acted on
     * @returns {void}
     */
    notifyContactCount(messageCode, count) {
      document.dispatchEvent(new CustomEvent('alert-message', {detail: {
        alertType: 'success',
        alertMessage: this.$t(messageCode, [count]),
      }}));
    },
  },
};
