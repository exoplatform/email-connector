/*
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
 */

// EXO-90421 -- the mailbox drawer's half of dragging mail onto the folder column: it
// holds what is being dragged (the column must know it during the drag, and the
// browser only reveals the payload at the drop), forgets it however the drag ends, and
// performs the one drop that is not an existing event -- assigning a category. The
// folder drops are the existing move, delete and spam events, handled where the
// buttons' are.

export default {
  data: () => ({
    // The mail being dragged from the full-screen list, {folder, ids}; null when none is.
    emailDrag: null,
  }),
  watch: {
    /**
     * Forgets the drag when the drawer leaves full screen: the column it was headed for
     * is gone.
     *
     * @param {Boolean} expanded whether the drawer is full screen
     * @returns {void}
     */
    expanded(expanded) {
      if (!expanded) {
        this.endEmailDrag();
      }
    },
  },
  created() {
    this.onEmailDragStart = payload => this.emailDrag = payload || null;
    this.$root.$on('email-drag-start', this.onEmailDragStart);
    this.$root.$on('email-drag-end', this.endEmailDrag);
    this.$root.$on('categorize-email', this.categorizeEmails);
    // A drag the page never hears the end of (the window left mid-drag) ends here.
    window.addEventListener('blur', this.endEmailDrag);
  },
  beforeDestroy() {
    this.$root.$off('email-drag-start', this.onEmailDragStart);
    this.$root.$off('email-drag-end', this.endEmailDrag);
    this.$root.$off('categorize-email', this.categorizeEmails);
    window.removeEventListener('blur', this.endEmailDrag);
  },
  methods: {
    /**
     * Forgets the mail being dragged.
     *
     * @returns {void}
     */
    endEmailDrag() {
      this.emailDrag = null;
    },
    /**
     * Assigns a category to mail dropped on it: the category bar's assignment, addressed
     * by the UIDs AND the folder they are numbered in (a UID resolved in another folder
     * is another message, EXO-90416). The mail stays where it is; its rows take the
     * category at once, and a short toast says it was done -- the drop has no other
     * visible effect. A refused request says so, the way the other actions do.
     *
     * @param {Array<Number>} mailRemoteIds the IMAP UIDs, within `folder`
     * @param {Number} categoryId the category
     * @param {String} folder the folder the UIDs are numbered in
     * @returns {Promise} resolved once the server has answered
     */
    categorizeEmails(mailRemoteIds, categoryId, folder) {
      const category = (this.emailCategories || []).find(candidate => candidate.id === categoryId);
      return this.$emailConnectorMailBoxService.linkEmailsToCategory(mailRemoteIds, categoryId, folder)
        .then(() => {
          this.onCategoriesUpdated({ mailRemoteIds, categoryId, assign: true, folder });
          document.dispatchEvent(new CustomEvent('alert-message', {detail: {
            alertType: 'success',
            alertMessage: this.$t('emailConnector.mailBox.list.drawer.categorize.email.success', { 0: category?.name || '' }),
          }}));
        }, () => this.alertOnActionFailures(mailRemoteIds.length, 'categorize'));
    },
  },
};
