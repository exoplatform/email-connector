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
// own text, the drawer that edits them, the chips that show them above the results, the
// page address that keeps them, and the search row above the list. An advanced search
// reads the copy of the folder kept in eXo first (the local arm); the mail server (the
// server arm) is asked only when the user does, from the results' status line, or when
// the copy holds no match. The search box's own text, without criteria, keeps its
// instant local matches merged with the mail server's answer. Mixed into the mailbox drawer, whose search it
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

// How long the server arm waits before it asks the mail server on its own -- the copy
// held no match -- while the advanced search's words are set: such a search reads the
// messages' bodies, which a server without a full-text index does by scanning the folder,
// and a new keystroke within that pause makes it unnecessary.
const BODY_SEARCH_DEBOUNCE_MS = 1200;

// How many hits the local arm asks for, as the server arm does.
const LOCAL_PAGE_SIZE = 20;

export default {
  data: () => ({
    // The advanced criteria, beside the search box's text: {from, to, words, after,
    // before, attachment, folder}; folder null searches the folder shown.
    searchCriteria: emptySearchCriteria(),
    // The search row: whether its field replaces the chips, and its text.
    searchFieldOpen: false,
    searchFieldText: '',
    // The local arm: the copy's hits, and whether they are on their way.
    searchLocalResults: [],
    searchLocalRunning: false,
    // Whether the mail server was asked for this search (always, without criteria).
    searchServerAsked: false,
    // How many server matches were examined for an attachment, when not all (0).
    searchScanned: 0,
    // The text the search box held when the advanced search was opened, carried into it.
    advancedCarriedText: '',
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
      // show that folder's unread or starred mail, a search can.
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
     * of it a search may read (isFolderSearchable) -- never one the share lets the user
     * see only as the label its folders nest under (readable false), as no other list
     * offers it either. A shared folder with no role is named by its path, so two
     * folders of one name under different parents differ; a role folder (the shared
     * Inbox, Sent...) keeps its translated name, as the folder chip names it.
     *
     * @returns {Array} [{key, label}]
     */
    searchFolderOptions() {
      if (this.currentSharedMailbox) {
        return (this.availableFolders || [])
          .filter(folder => folder.key?.startsWith('CUSTOM:') && folder.readable !== false && this.isFolderSearchable(folder.key))
          .map(folder => ({
            key: folder.key,
            // A role folder (the shared Inbox, Sent...) by its translated name; any other
            // by its path in the mailbox's namespace, as the folder column spells it.
            label: !this.$emailConnectorMailBoxService.sharedFolderRole(folder.key) && folder.path
              ? this.$emailConnectorMailBoxService.folderPath(folder, this.namespaceFolders)
              : this.folderLabelOf(folder.key),
          }));
      }
      return OWN_SEARCH_FOLDERS.filter(key => !this.folders?.length || this.folders.some(folder => folder.key === key))
        .map(key => ({ key, label: this.folderLabelOf(key) }));
    },
    /**
     * Whether the server arm can be offered: in the user's own mailbox only -- a shared
     * mailbox is only ever searched in eXo's copy of it.
     *
     * @returns {Boolean} true when the mail server can be asked
     */
    serverArmAvailable() {
      return !this.currentSharedMailbox;
    },
    /**
     * Whether the results offer to search the whole mailbox on the server: an advanced
     * search that read eXo's copy only, in the user's own mailbox, once that copy has
     * answered.
     *
     * @returns {Boolean} true when the button follows the hits
     */
    offerServerSearch() {
      return this.advancedSearchActive && this.serverArmAvailable && !this.searchServerAsked
        && !this.searchServerError && !this.searchLocalRunning;
    },
    /**
     * The search row's state, the same wherever the row is drawn.
     *
     * @returns {Object} the row's props
     */
    searchBarProps() {
      return {
        // No Important chip while a search shows, nor while its field is open: a category
        // view does not narrow a search, and the row keeps its width as the text comes.
        // Nor on the Suggestions view, whose row offers what a search's does (EXO-90882).
        importantCategory: this.searchActive || this.searchFieldOpen || this.suggestionsView ? null : this.importantCategory,
        categoryViewId: this.categoryViewId,
        favoriteOnly: this.favoriteOnly,
        unreadOnly: this.unreadOnly,
        searchable: this.canSearch,
        searchOpen: this.searchFieldOpen,
        searchText: this.searchFieldText,
        criteriaChips: this.searchCriteriaChips,
      };
    },
    /**
     * The search row's events, the same wherever the row is drawn.
     *
     * @returns {Object} the handlers, by event
     */
    searchBarListeners() {
      return {
        'toggle-important': this.toggleImportantView,
        'toggle-favorite': this.toggleSearchFavorites,
        'toggle-unread': this.toggleSearchUnread,
        'open-search': this.openSearchField,
        'close-search': this.closeSearchField,
        'search-input': this.onSearchFieldInput,
        'advanced-search': this.openAdvancedSearch,
        'remove-criterion': this.removeSearchCriterion,
        'clear-criteria': this.clearSearchCriteria,
      };
    },
    /**
     * The criteria line under the search row: one chip per criterion of the advanced
     * search, the folder when it is not the one shown, and -- while the search field
     * hides the row's own chips -- a lit Unread or Favorites, so a filter on the list or
     * the search is never unseen.
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
      if (this.searchFieldOpen && this.unreadOnly) {
        chips.push({ key: 'unread', label: this.$t('emailConnector.mailBox.search.chip.unread') });
      }
      if (this.searchFieldOpen && this.favoriteOnly) {
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
    // The server arm's own start when the copy held no match; not reactive state.
    this.serverArmTimer = null;
    this.$root.$on(APPLY_ADVANCED_SEARCH_EVENT, this.applyAdvancedSearch);
  },
  beforeDestroy() {
    window.clearTimeout(this.serverArmTimer);
    this.$root.$off(APPLY_ADVANCED_SEARCH_EVENT, this.applyAdvancedSearch);
  },
  methods: {
    /**
     * Opens the advanced search drawer on the search as it stands.
     *
     * @returns {void}
     */
    openAdvancedSearch() {
      // The text typed in the search box goes into the field it fits: an address into
      // From, anything else into "Has the words" -- unless that field is already set.
      const text = (this.searchFieldText || '').trim();
      const field = text.includes('@') ? 'from' : 'words';
      this.advancedCarriedText = text && !(this.searchCriteria[field] || '').trim() ? text : '';
      this.$root.$emit(OPEN_ADVANCED_SEARCH_EVENT, {
        criteria: { ...this.searchCriteria, folder: this.searchFolder, ...(this.advancedCarriedText ? { [field]: text } : {}) },
        folders: this.searchFolderOptions,
        shownFolder: this.currentFolder,
      });
    },
    /**
     * Runs the search the advanced search drawer asks for: its criteria and its folder
     * (kept only when it is not the one shown), with the search row's Unread and
     * Favorites as they are. Without a text or a criterion left, there is no search.
     *
     * @param {Object} search {criteria}
     * @returns {void}
     */
    applyAdvancedSearch(search) {
      const criteria = { ...emptySearchCriteria(), ...(search?.criteria || {}) };
      if (criteria.folder === this.currentFolder) {
        criteria.folder = null;
      }
      this.searchCriteria = criteria;
      if (this.advancedCarriedText) {
        // The box's text now lives in the criterion it was carried into.
        this.advancedCarriedText = '';
        this.searchFieldText = '';
        this.searchFieldOpen = false;
        window.clearTimeout(this.searchDebounceTimer);
        this.searchTerm = '';
      }
      this.rerunSearch();
    },
    /**
     * Takes one criterion off the search -- a chip's close button -- and searches again.
     *
     * @param {String} key the criterion: from, to, words, after, before, attachment,
     *          folder, or the row's unread or favorites shown on the line
     * @returns {void}
     */
    removeSearchCriterion(key) {
      if (key === 'unread' || key === 'favorites') {
        // The row's chip, put out as its own toggle does it: the search follows.
        if (key === 'unread') {
          this.toggleSearchUnread();
        } else {
          this.toggleSearchFavorites();
        }
        return;
      }
      if (Object.prototype.hasOwnProperty.call(this.searchCriteria, key)) {
        this.searchCriteria = { ...this.searchCriteria, [key]: emptySearchCriteria()[key] };
      }
      this.rerunSearch();
    },
    /**
     * Takes every chip of the criteria line off, the text kept: the advanced criteria,
     * and the row's Unread and Favorites when the line shows them.
     *
     * @returns {void}
     */
    clearSearchCriteria() {
      this.searchCriteria = emptySearchCriteria();
      if (this.searchFieldOpen) {
        this.setSearchChips(false, false);
      }
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
      window.clearTimeout(this.serverArmTimer);
      this.searchCriteria = emptySearchCriteria();
      this.searchLocalResults = [];
      this.searchLocalRunning = false;
      this.searchServerAsked = false;
      this.searchScanned = 0;
      clearSearchFromUrl();
    },
    /**
     * Runs a search's arms: with advanced criteria, the copy kept in eXo first, the mail
     * server only when the copy holds no match; without, the search box's own search, on
     * the mail server beside its instant local matches, as always.
     *
     * @returns {void}
     */
    runSearchArms() {
      window.clearTimeout(this.serverArmTimer);
      if (!this.advancedSearchActive) {
        // The criteria may just have been taken off: their copy's hits go with them.
        this.searchLocalResults = [];
        this.searchLocalRunning = false;
        this.searchServerAsked = true;
        this.runServerSearch();
        return;
      }
      this.runLocalSearch();
    },
    /**
     * The local arm: one folder of the copy kept in eXo, with every criterion. A copy
     * with no match asks the mail server on its own -- after a pause while the words are
     * set -- in the user's own mailbox; a match leaves the server to the user's link.
     *
     * @returns {void}
     */
    runLocalSearch() {
      const requestId = ++this.searchRequestId;
      const sharedMailbox = this.currentSharedMailbox;
      this.searchServerAsked = false;
      this.searchServerResults = [];
      this.searchTotalMatches = 0;
      this.searchScanned = 0;
      this.searchServerRunning = false;
      this.searchServerError = false;
      this.searchLocalRunning = true;
      this.$emailConnectorMailBoxService.searchCachedFolder(this.searchTerm, this.searchFolder, LOCAL_PAGE_SIZE, this.favoriteOnly, this.unreadOnly, this.searchCriteria)
        .then(page => {
          if (requestId !== this.searchRequestId) {
            return;
          }
          this.searchLocalResults = this.withLocalFavorites(page?.results || [], requestId);
          if (!this.searchLocalResults.length && this.serverArmAvailable) {
            const pause = this.searchPause(0);
            this.serverArmTimer = window.setTimeout(() => {
              if (requestId === this.searchRequestId) {
                this.searchWholeMailbox();
              }
            }, pause);
          }
        })
        .catch(async () => {
          if (requestId !== this.searchRequestId) {
            return;
          }
          if (sharedMailbox && (await this.leftSharedMailboxAfterFailure(sharedMailbox)
              || requestId !== this.searchRequestId)) {
            return;
          }
          this.searchLocalResults = [];
          this.searchServerError = true;
        })
        .finally(() => {
          if (requestId === this.searchRequestId) {
            this.searchLocalRunning = false;
          }
        });
    },
    /**
     * The server arm, asked by the user from the results' status line or on its own when
     * the copy held no match: the same criteria on the mail server, its hits merged with
     * the copy's (the server's answer wins for a message both hold).
     *
     * @returns {void}
     */
    searchWholeMailbox() {
      window.clearTimeout(this.serverArmTimer);
      if (!this.serverArmAvailable) {
        return;
      }
      this.searchServerAsked = true;
      this.runServerSearch(this.searchRequestId);
    },
    /**
     * The search row's Favorites chip: the list's toggle, and while a search shows, the
     * search's Favorites criterion too -- the search runs again, as when its chip is
     * closed, and the page address follows.
     *
     * @returns {void}
     */
    toggleSearchFavorites() {
      if (!this.searchActive) {
        this.onToggleFavoriteFilter();
        return;
      }
      this.setSearchChips(this.unreadOnly, !this.favoriteOnly);
      this.rerunSearch();
    },
    /**
     * The search row's Unread chip, as toggleSearchFavorites.
     *
     * @returns {void}
     */
    toggleSearchUnread() {
      if (!this.searchActive) {
        this.toggleUnreadFilter();
        return;
      }
      this.setSearchChips(!this.unreadOnly, this.favoriteOnly);
      this.rerunSearch();
    },
    /**
     * Opens the search row's field.
     *
     * @returns {void}
     */
    openSearchField() {
      this.searchFieldOpen = true;
    },
    /**
     * Closes the search row's field and empties it: back to the chips, and to the folder's
     * list unless advanced criteria still make a search.
     *
     * @returns {void}
     */
    closeSearchField() {
      this.searchFieldOpen = false;
      if (this.searchFieldText) {
        this.searchFieldText = '';
        this.onFilterUpdated('');
      }
    },
    /**
     * The search row's field changed.
     *
     * @param {String} text the field's text
     * @returns {void}
     */
    onSearchFieldInput(text) {
      this.searchFieldText = text || '';
      this.onFilterUpdated(this.searchFieldText);
    },
    /**
     * How long the server arm waits before asking the mail server on its own: longer
     * while the advanced search's words are set, since its search then reads the bodies.
     *
     * @param {Number} defaultPause the usual pause, in milliseconds
     * @returns {Number} the pause, in milliseconds
     */
    searchPause(defaultPause) {
      return (this.searchCriteria.words || '').trim() ? Math.max(defaultPause, BODY_SEARCH_DEBOUNCE_MS) : defaultPause;
    },
    /**
     * A day of the search, as the platform writes a day in the user's language.
     *
     * @param {String|Date} day yyyy-MM-dd, or a date
     * @returns {String} the day, written out
     */
    searchDayLabel(day) {
      const [year, month, date] = typeof day === 'string' ? day.split('-').map(Number) : [day.getFullYear(), day.getMonth() + 1, day.getDate()];
      return this.$dateUtil.formatDateObjectToDisplay(new Date(year, month - 1, date), {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
      }, eXo.env.portal.language);
    },
  },
};
