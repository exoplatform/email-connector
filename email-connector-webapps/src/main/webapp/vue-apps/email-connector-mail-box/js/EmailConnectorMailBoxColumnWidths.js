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

// EXO-90575 -- the widths of the full-screen mailbox's columns, which the user drags:
// the folder column and the list. The reader takes what is left. exo-drawer lays the
// full-screen drawer out as a left pane of its drawerWidth -- the folder column and the
// list here -- and the reader beside it, so the list's divider moves the pane's edge
// and the column's divider moves the list, which keeps its own width as long as the
// reader has more than its minimum, then gives way itself and takes it back when the
// column narrows again.

// The defaults: the widths the columns had before they could be resized (EXO-90415).
export const DEFAULT_NAVIGATION_WIDTH_PX = 200;

export const DEFAULT_LIST_WIDTH_PX = 420;

// The folder column folded to an icon rail: a fixed width, no divider to drag.
export const NAVIGATION_RAIL_WIDTH_PX = 56;

// The folder column's range: a folder name and its count stay readable at the minimum.
export const NAVIGATION_MIN_WIDTH_PX = 140;

export const NAVIGATION_MAX_WIDTH_PX = 360;

// The list's minimum, its two handles included: some 320 px of rows, a phone's width,
// which the rows are laid out for.
export const LIST_MIN_WIDTH_PX = 340;

// What the reader keeps at least: a message's text stays readable, the wide ones scroll.
export const READER_MIN_WIDTH_PX = 480;

// Each handle's width. Both sit in the list's slot, so the pane's width stays the sum
// of the two columns'.
export const SEPARATOR_WIDTH_PX = 8;

// What an arrow key moves a focused handle by.
export const SEPARATOR_KEY_STEP_PX = 16;

// Where the user's widths are kept, in this browser only.
export const COLUMN_WIDTHS_STORAGE_KEY = 'emailConnector.mailBox.columnWidths';

/**
 * Brings a width within a range. A range whose maximum fell below its minimum -- a
 * window too narrow for every minimum -- gives the minimum: the columns keep theirs,
 * the reader gives way.
 *
 * @param {Number} value the width wanted, in CSS pixels
 * @param {Object} range the range, `{ min, max }`
 * @returns {Number} the width within the range
 */
export function clampWidth(value, range) {
  return Math.max(range.min, Math.min(range.max, value));
}

/**
 * The range of the folder column's width: its own bounds, and room for the list's
 * minimum and the reader's. The list's own width is not a bound: a wider column takes
 * from the reader down to its minimum, then from the list (effectiveColumnWidths), and
 * gives it back as it narrows -- so the divider is never stuck by a wide list.
 *
 * @param {Number} available the mailbox's width, in CSS pixels
 * @returns {Object} `{ min, max }`
 */
export function navigationRange(available) {
  const max = Math.min(NAVIGATION_MAX_WIDTH_PX, available - LIST_MIN_WIDTH_PX - READER_MIN_WIDTH_PX);
  return { min: NAVIGATION_MIN_WIDTH_PX, max: Math.max(NAVIGATION_MIN_WIDTH_PX, max) };
}

/**
 * The range of the list's width: its minimum, up to what leaves the reader its own.
 *
 * @param {Number} available the mailbox's width, in CSS pixels
 * @param {Number} navigationWidth the folder column's width as it is now, open or rail
 * @returns {Object} `{ min, max }`
 */
export function listRange(available, navigationWidth) {
  const max = available - navigationWidth - READER_MIN_WIDTH_PX;
  return { min: LIST_MIN_WIDTH_PX, max: Math.max(LIST_MIN_WIDTH_PX, max) };
}

/**
 * The widths the columns take in a mailbox of a given width, from the ones the user
 * chose: the folder column first, as long as the list and the reader keep their
 * minimums, then the list. A narrower window narrows the list before the column, and
 * the choice itself is kept, so a wider window gives the columns their widths back.
 *
 * @param {Object} preferred the user's widths, `{ navigation, list }`
 * @param {Number} available the mailbox's width, in CSS pixels
 * @param {Number} railWidth the folder column's width when folded to a rail, else 0
 * @returns {Object} the widths on screen, `{ navigation, list }`
 */
export function effectiveColumnWidths(preferred, available, railWidth) {
  const navigation = railWidth || clampWidth(preferred.navigation, navigationRange(available));
  const list = clampWidth(preferred.list, listRange(available, navigation));
  return { navigation, list };
}

/**
 * The width a divider's request leaves the user's choice at. A narrow window may clip
 * the choice on screen; a request at or past the bound that clips it -- a key towards
 * it, a jitter while the handle is pressed -- keeps the choice, so a wider window still
 * gives it back. Any other request is taken, within the range.
 *
 * @param {Number} chosen the user's width
 * @param {Number} width the width the divider asks for
 * @param {Object} range the column's range now, `{ min, max }`
 * @returns {Number} the user's width after the request
 */
export function resizedChoice(chosen, width, range) {
  if ((chosen > range.max && width >= range.max) || (chosen < range.min && width <= range.min)) {
    return chosen;
  }
  return clampWidth(width, range);
}

/**
 * The full-screen mailbox's width: exo-drawer's full screen fills the viewport, which
 * does not count the page's vertical scrollbar, unlike the window's innerWidth.
 *
 * @returns {Number} the width, in CSS pixels
 */
export function viewportWidth() {
  return document.documentElement?.clientWidth || window.innerWidth;
}

/**
 * A stored width, when it is one: a finite number within the column's own bounds.
 *
 * @param {*} value what the storage held
 * @param {Number} min the column's minimum
 * @param {Number} max the column's maximum
 * @param {Number} fallback the default width
 * @returns {Number} the width to start from
 */
function storedWidth(value, min, max, fallback) {
  return Number.isFinite(value) ? clampWidth(value, { min, max }) : fallback;
}

/**
 * The widths this browser remembers, else the defaults. The storage may be unavailable
 * (a private window, blocked site data) or hold anything: the defaults then.
 *
 * @returns {Object} `{ navigation, list }`
 */
export function loadColumnWidths() {
  let stored = null;
  try {
    stored = JSON.parse(window.localStorage.getItem(COLUMN_WIDTHS_STORAGE_KEY));
  } catch (e) {
    // No storage, or not ours: the defaults.
  }
  return {
    navigation: storedWidth(stored?.navigation, NAVIGATION_MIN_WIDTH_PX, NAVIGATION_MAX_WIDTH_PX, DEFAULT_NAVIGATION_WIDTH_PX),
    list: storedWidth(stored?.list, LIST_MIN_WIDTH_PX, Number.MAX_SAFE_INTEGER, DEFAULT_LIST_WIDTH_PX),
  };
}

/**
 * Remembers the widths in this browser for the next full screen.
 *
 * @param {Object} widths `{ navigation, list }`
 * @returns {void}
 */
export function saveColumnWidths(widths) {
  try {
    window.localStorage.setItem(COLUMN_WIDTHS_STORAGE_KEY, JSON.stringify({
      navigation: Math.round(widths.navigation),
      list: Math.round(widths.list),
    }));
  } catch (e) {
    // No storage: the widths hold until the page is left.
  }
}

/**
 * The width a key pressed on a focused handle asks for, or null for a key the handle
 * leaves alone. The arrows move the divider the way they point, so in a right-to-left
 * page, where the columns start on the right, Left widens and Right narrows.
 *
 * @param {KeyboardEvent} event the key press
 * @param {Number} value the column's width now
 * @param {Object} range the column's range, `{ min, max }`
 * @param {Boolean} rtl whether the page reads right to left
 * @returns {Number|null} the width asked for
 */
export function separatorKeyWidth(event, value, range, rtl) {
  if (event.altKey || event.ctrlKey || event.metaKey) {
    return null;
  }
  const towardsEnd = rtl ? -1 : 1;
  switch (event.key) {
  case 'ArrowRight':
    return value + SEPARATOR_KEY_STEP_PX * towardsEnd;
  case 'ArrowLeft':
    return value - SEPARATOR_KEY_STEP_PX * towardsEnd;
  case 'Home':
    return range.min;
  case 'End':
    return range.max;
  default:
    return null;
  }
}

/**
 * The drawer's half of the resizable columns: the widths the user chose, the ones on
 * screen for the window's width, the dividers' ranges, and what the dividers ask for.
 * Expects the drawer's `navigationRail`.
 */
export default {
  /**
   * The widths chosen, from this browser's memory, and the mailbox's width now.
   *
   * @returns {Object} the mixin's state
   */
  data() {
    return {
      // The widths the user chose, which a narrow window may narrow on screen.
      columnWidths: loadColumnWidths(),
      // The full-screen mailbox's width (viewportWidth).
      mailboxWidth: viewportWidth(),
    };
  },
  /**
   * Follows the window's width from the start.
   *
   * @returns {void}
   */
  created() {
    window.addEventListener('resize', this.onColumnsWindowResize);
  },
  /**
   * Stops following the window's width.
   *
   * @returns {void}
   */
  beforeDestroy() {
    window.removeEventListener('resize', this.onColumnsWindowResize);
  },
  computed: {
    /**
     * The columns' widths on screen: the user's, within the minimums the window leaves.
     *
     * @returns {Object} `{ navigation, list }`, in CSS pixels
     */
    shownColumnWidths() {
      return effectiveColumnWidths(this.columnWidths, this.mailboxWidth, this.navigationRail ? NAVIGATION_RAIL_WIDTH_PX : 0);
    },
    /**
     * The folder column divider's range.
     *
     * @returns {Object} `{ min, max }`
     */
    navigationColumnRange() {
      return navigationRange(this.mailboxWidth);
    },
    /**
     * The list divider's range.
     *
     * @returns {Object} `{ min, max }`
     */
    listColumnRange() {
      return listRange(this.mailboxWidth, this.shownColumnWidths.navigation);
    },
  },
  methods: {
    /**
     * Takes the folder column's width its divider asks for, within its range.
     *
     * @param {Number} width the width asked for, in CSS pixels
     * @returns {void}
     */
    resizeNavigationColumn(width) {
      this.columnWidths = { ...this.columnWidths, navigation: resizedChoice(this.columnWidths.navigation, width, this.navigationColumnRange) };
    },
    /**
     * Takes the list's width its divider asks for, within its range.
     *
     * @param {Number} width the width asked for, in CSS pixels
     * @returns {void}
     */
    resizeListColumn(width) {
      this.columnWidths = { ...this.columnWidths, list: resizedChoice(this.columnWidths.list, width, this.listColumnRange) };
    },
    /**
     * Gives the folder column its default width back (a double-click on its divider).
     *
     * @returns {void}
     */
    resetNavigationColumn() {
      this.columnWidths = { ...this.columnWidths, navigation: DEFAULT_NAVIGATION_WIDTH_PX };
      this.rememberColumnWidths();
    },
    /**
     * Gives the list its default width back (a double-click on its divider).
     *
     * @returns {void}
     */
    resetListColumn() {
      this.columnWidths = { ...this.columnWidths, list: DEFAULT_LIST_WIDTH_PX };
      this.rememberColumnWidths();
    },
    /**
     * Remembers the widths once a drag or a key has set them, not at every move.
     *
     * @returns {void}
     */
    rememberColumnWidths() {
      saveColumnWidths(this.columnWidths);
    },
    /**
     * Follows the window's width, which the columns are kept within.
     *
     * @returns {void}
     */
    onColumnsWindowResize() {
      this.mailboxWidth = viewportWidth();
    },
  },
};
