/*
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
*/

// EXO-90838 -- the mailbox drawer's advanced search: the criteria beside the search box's
// own text, the drawer that edits them, the chips that show them above the results, and
// the page address that keeps them. Mixed into the mailbox drawer, whose search it
// extends: the drawer's searchTerm, runSearch, clearSearch and filter chips (Unread,
// Favorites) are the ones used here, the two chips being the search's unread and
// starred criteria as they are the list's.

import {
  TEXT_CRITERIA,
  clearSearchFromUrl,
  emptySearchCriteria,
  hasSearchCriteria,
  writeSearchToUrl,
} from './EmailConnectorMailBoxSearchCriteria.js';

// The events between the drawer and the advanced search drawer, both in this app.
export const OPEN_ADVANCED_SEARCH_EVENT = 'open-email-advanced-search';
export const APPLY_ADVANCED_SEARCH_EVENT = 'email-advanced-search-apply';

// The user's own folders the mail server searches.
const OWN_SEARCH_FOLDERS = ['INBOX', 'SENT', 'ARCHIVE'];

// How long typing in the search box must pause before a new search while the advanced
// search's words are set: each such search reads the messages' bodies, which a server
// without a full-text index does by scanning the folder.
const BODY_SEARCH_DEBOUNCE_MS = 1200;

export default {
  data: () => ({
    // The advanced criteria, beside the search box's text: {from, to, words, after,
    // before, attachment, folder}; folder null searches the folder shown.
    searchCriteria: emptySearchCriteria(),
  }),
  computed: {
    /**
     * Whether advanced criteria narrow the search, the folder aside.
     *
     * @returns {Boolean} true when one is set
     */
    advancedSearchActive() {
      const criteria = this.searchCriteria;
      // Another folder with Unread or Favorites lit is a search too: the list cannot
      // show that folder's unread or starred mail, the server can.
      return hasSearchCriteria(criteria)
        || !!criteria.folder && criteria.folder !== this.currentFolder && (this.unreadOnly || this.favoriteOnly);
    },
    /**
     * The folder the search reads: the one the advanced search names, else the one shown.
     *
     * @returns {String} the folder key
     */
    searchFolder() {
      return this.searchCriteria.folder || this.currentFolder;
    },
    /**
     * The folders the advanced search offers: the user's own Inbox, Sent and Archive,
     * which the mail server searches, or, in a mailbox shared with the user, the folders
     * of it a search may read (isFolderSearchable).
     *
     * @returns {Array} [{key, label}]
     */
    searchFolderOptions() {
      const folders = this.currentSharedMailbox
        ? (this.availableFolders || []).map(folder => folder.key).filter(key => key?.startsWith('CUSTOM:') && this.isFolderSearchable(key))
        : OWN_SEARCH_FOLDERS.filter(key => !this.folders?.length || this.folders.some(folder => folder.key === key));
      return folders.map(key => ({ key, label: this.folderLabelOf(key) }));
    },
    /**
     * The chips above the search results: one per criterion that narrows the search,
     * the Unread and Favorites chips included while they are lit, and the folder when it
     * is not the one shown.
     *
     * @returns {Array} [{key, label}]
     */
    searchCriteriaChips() {
      const chips = [];
      const criteria = this.searchCriteria;
      TEXT_CRITERIA.filter(name => (criteria[name] || '').trim())
        .forEach(name => chips.push({ key: name, label: this.$t(`emailConnector.mailBox.search.chip.${name}`, { 0: criteria[name].trim() }) }));
      if (criteria.after) {
        chips.push({ key: 'after', label: this.$t('emailConnector.mailBox.search.chip.after', { 0: this.searchDayLabel(criteria.after) }) });
      }
      if (criteria.before) {
        chips.push({ key: 'before', label: this.$t('emailConnector.mailBox.search.chip.before', { 0: this.searchDayLabel(criteria.before) }) });
      }
      if (criteria.attachment) {
        chips.push({ key: 'attachment', label: this.$t('emailConnector.mailBox.search.chip.attachment') });
      }
      if (criteria.folder && criteria.folder !== this.currentFolder) {
        chips.push({ key: 'folder', label: this.$t('emailConnector.mailBox.search.chip.folder', { 0: this.folderLabelOf(criteria.folder) }) });
      }
      if (this.unreadOnly) {
        chips.push({ key: 'unread', label: this.$t('emailConnector.mailBox.search.chip.unread') });
      }
      if (this.favoriteOnly) {
        chips.push({ key: 'favorites', label: this.$t('emailConnector.mailBox.search.chip.favorites') });
      }
      return chips;
    },
  },
  watch: {
    /**
     * Keeps the page address's Unread in step while a search shows.
     *
     * @returns {void}
     */
    unreadOnly() {
      if (this.searchActive) {
        this.syncSearchUrl();
      }
    },
    /**
     * Keeps the page address's Favorites in step while a search shows.
     *
     * @returns {void}
     */
    favoriteOnly() {
      if (this.searchActive) {
        this.syncSearchUrl();
      }
    },
  },
  created() {
    this.$root.$on(APPLY_ADVANCED_SEARCH_EVENT, this.applyAdvancedSearch);
  },
  beforeDestroy() {
    this.$root.$off(APPLY_ADVANCED_SEARCH_EVENT, this.applyAdvancedSearch);
  },
  methods: {
    /**
     * Opens the advanced search drawer on the search as it stands.
     *
     * @returns {void}
     */
    openAdvancedSearch() {
      this.$root.$emit(OPEN_ADVANCED_SEARCH_EVENT, {
        criteria: { ...this.searchCriteria, folder: this.searchFolder },
        unread: this.unreadOnly,
        favorites: this.favoriteOnly,
        folders: this.searchFolderOptions,
        shownFolder: this.currentFolder,
      });
    },
    /**
     * Runs the search the advanced search drawer asks for: its criteria, its folder
     * (kept only when it is not the one shown) and its Unread and Favorites, which are
     * the list's chips -- toggled through the drawer's own toggles, so the list behind
     * the search agrees. Without a text or a criterion left, there is no search.
     *
     * @param {Object} search {criteria, unread, favorites}
     * @returns {void}
     */
    applyAdvancedSearch(search) {
      const criteria = { ...emptySearchCriteria(), ...(search?.criteria || {}) };
      if (criteria.folder === this.currentFolder) {
        criteria.folder = null;
      }
      this.searchCriteria = criteria;
      this.setSearchChips(!!search?.unread, !!search?.favorites);
      this.rerunSearch();
    },
    /**
     * Takes one criterion off the search -- a chip's close button -- and searches again.
     *
     * @param {String} key the criterion: from, to, words, after, before, attachment,
     *          folder, unread or favorites
     * @returns {void}
     */
    removeSearchCriterion(key) {
      if (key === 'unread') {
        this.setSearchChips(false, this.favoriteOnly);
      } else if (key === 'favorites') {
        this.setSearchChips(this.unreadOnly, false);
      } else if (Object.prototype.hasOwnProperty.call(this.searchCriteria, key)) {
        this.searchCriteria = { ...this.searchCriteria, [key]: emptySearchCriteria()[key] };
      }
      this.rerunSearch();
    },
    /**
     * Takes every advanced criterion off the search, the text kept.
     *
     * @returns {void}
     */
    clearSearchCriteria() {
      this.searchCriteria = emptySearchCriteria();
      this.setSearchChips(false, false);
      this.rerunSearch();
    },
    /**
     * Opens the search an opening asks for -- the page address's (extensions.js) or the
     * platform's search: its criteria and chips first, then its text in the search box.
     *
     * @param {Object} opening {searchTerm, searchCriteria, searchUnread, searchFavorites}
     * @returns {void}
     */
    applyOpeningSearch(opening) {
      if (!opening?.searchTerm && !opening?.searchCriteria) {
        return;
      }
      this.searchCriteria = { ...emptySearchCriteria(), ...(opening.searchCriteria || {}) };
      this.setSearchChips(!!opening.searchUnread, !!opening.searchFavorites);
      if (opening.searchTerm) {
        this.openSearchFromOutside(opening.searchTerm);
      } else {
        this.rerunSearch();
      }
    },
    /**
     * Lights or puts out the Unread and Favorites chips through the drawer's toggles.
     *
     * @param {Boolean} unread whether Unread is to be lit
     * @param {Boolean} favorites whether Favorites is to be lit
     * @returns {void}
     */
    setSearchChips(unread, favorites) {
      if (unread !== this.unreadOnly) {
        this.toggleUnreadFilter();
      }
      if (favorites !== this.favoriteOnly) {
        this.onToggleFavoriteFilter();
      }
    },
    /**
     * Searches again after a criterion changed: with the box's text or a criterion
     * left, the same search; with neither, none -- the folder's list is back.
     *
     * @returns {void}
     */
    rerunSearch() {
      if (this.searchTerm || this.advancedSearchActive) {
        this.runSearch(this.searchTerm);
      } else {
        this.clearSearch();
      }
    },
    /**
     * Follows a folder switch: the running search moves to the folder chosen, as the
     * search box's does, so the advanced search's folder is dropped and the page address
     * names the new folder. A search that only stood for the dropped folder's unread or
     * starred mail ends -- the folder's list, with its chips, shows the same thing -- and
     * the page address with it. Nothing else is ended: a search the search box is still
     * waiting to run goes on in the folder chosen.
     *
     * @returns {void}
     */
    followFolderSwitch() {
      const dropped = !!this.searchCriteria.folder;
      if (dropped) {
        this.searchCriteria = { ...this.searchCriteria, folder: null };
      }
      if (this.searchActive) {
        this.syncSearchUrl();
      } else if (dropped) {
        // A search that only stood for the other folder's unread or starred mail: ended,
        // the page address with it. With no folder dropped nothing is ended here, so a
        // search the search box is still waiting to run goes on in the folder chosen.
        this.clearSearch();
      }
    },
    /**
     * Writes the search as it stands into the page's address, or takes it out when
     * there is none.
     *
     * @returns {void}
     */
    syncSearchUrl() {
      if (this.searchActive) {
        // The folder searched is written whenever it is not the Inbox, the one shown
        // included: a reload opens the mailbox on its first folder, not on that one.
        writeSearchToUrl({
          term: this.searchTerm,
          criteria: { ...this.searchCriteria, folder: this.searchFolder === 'INBOX' ? null : this.searchFolder },
          unread: this.unreadOnly,
          favorites: this.favoriteOnly,
          mailbox: this.currentSharedMailbox?.delegationId,
        });
      } else {
        clearSearchFromUrl();
      }
    },
    /**
     * Forgets the advanced criteria and takes the search out of the page's address:
     * what clearing the search does on top of emptying its results.
     *
     * @returns {void}
     */
    resetAdvancedSearch() {
      this.searchCriteria = emptySearchCriteria();
      clearSearchFromUrl();
    },
    /**
     * How long typing in the search box must pause before a search: longer while the
     * advanced search's words are set, since each search then reads the bodies.
     *
     * @param {Number} defaultPause the search box's usual pause, in milliseconds
     * @returns {Number} the pause, in milliseconds
     */
    searchPause(defaultPause) {
      return (this.searchCriteria.words || '').trim() ? Math.max(defaultPause, BODY_SEARCH_DEBOUNCE_MS) : defaultPause;
    },
    /**
     * A day of the search, as the platform writes a day in the user's language.
     *
     * @param {String} day yyyy-MM-dd
     * @returns {String} the day, written out
     */
    searchDayLabel(day) {
      const [year, month, date] = day.split('-').map(Number);
      return this.$dateUtil.formatDateObjectToDisplay(new Date(year, month - 1, date), {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
      }, eXo.env.portal.language);
    },
  },
};
