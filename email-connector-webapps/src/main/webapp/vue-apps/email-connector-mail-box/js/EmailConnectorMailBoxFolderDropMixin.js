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

// EXO-90421 -- the full-screen folder column as a drop zone: a mail dragged from the list
// onto one of its entries is handed to the action that entry stands for (see
// folderDropAction), exactly as that action's own button hands it over. The drag itself
// is known from the drawer (dragSource): what the browser carries cannot be read before
// the drop, and a folder must say during the drag whether it takes the mail.

import { folderDropAction, hasDragPayload } from './EmailConnectorMailBoxDragAndDrop.js';

// How a folder the pointer may drop on is lit: a primary ring, inside the entry, so the
// column does not move. Inline, as the add-on has no CSS loader.
const DROP_TARGET_STYLE = {
  boxShadow: 'inset 0 0 0 2px var(--allPagesPrimaryColor, #578dc9)',
  borderRadius: '4px',
};

export default {
  props: {
    // The mail being dragged from the list, {folder, ids}; null when none is.
    dragSource: { type: Object, default: null },
  },
  data: () => ({
    // The entry the pointer is over with mail it may take, by its value.
    dropTarget: null,
  }),
  watch: {
    /**
     * Forgets the lit entry once the drag is over, however it ended.
     *
     * @param {Object} source the mail being dragged, null once the drag is over
     * @returns {void}
     */
    dragSource(source) {
      if (!source) {
        this.dropTarget = null;
      }
    },
  },
  methods: {
    /**
     * What dropping the dragged mail on an entry does: a category is assigned to it (it
     * stays where it is), a folder takes it by its own action (folderDropAction).
     *
     * @param {Object} entry the column's entry
     * @returns {Object} {event, args} to emit on the root, or null when it may not drop there
     */
    dropAction(entry) {
      const drag = this.dragSource;
      if (!drag?.ids?.length) {
        return null;
      }
      if (entry.categoryId != null) {
        return { event: 'categorize-email', args: [drag.ids, entry.categoryId, drag.folder] };
      }
      return folderDropAction(this.folders, drag, entry.folderKey);
    },
    /**
     * The drag listeners of an entry, for v-on.
     *
     * @param {Object} entry the column's entry
     * @returns {Object} the listeners by event name
     */
    dropListeners(entry) {
      return {
        dragenter: event => this.onEntryDragOver(entry, event),
        dragover: event => this.onEntryDragOver(entry, event),
        dragleave: event => this.onEntryDragLeave(entry, event),
        drop: event => this.onEntryDrop(entry, event),
      };
    },
    /**
     * Says the entry takes the mail -- by cancelling the event, which is what lets the
     * browser drop -- and lights it; says nothing for mail it may not take, or for
     * anything that is not mail of this mailbox, so the browser shows "no drop" there.
     *
     * @param {Object} entry the column's entry
     * @param {DragEvent} event the dragenter or dragover event
     * @returns {void}
     */
    onEntryDragOver(entry, event) {
      if (!hasDragPayload(event) || !this.dropAction(entry)) {
        return;
      }
      event.preventDefault();
      event.dataTransfer.dropEffect = 'move';
      this.dropTarget = entry.value;
    },
    /**
     * Puts the entry out when the pointer leaves it -- not when it only passes from the
     * entry onto its own icon or name, which fires a leave too.
     *
     * @param {Object} entry the column's entry
     * @param {DragEvent} event the dragleave event
     * @returns {void}
     */
    onEntryDragLeave(entry, event) {
      if (event.relatedTarget && event.currentTarget?.contains?.(event.relatedTarget)) {
        return;
      }
      if (this.dropTarget === entry.value) {
        this.dropTarget = null;
      }
    },
    /**
     * Hands the dropped mail to the entry's action, checked again at the drop, and ends
     * the drag: the rows it took may leave the list before their own dragend arrives.
     *
     * @param {Object} entry the column's entry
     * @param {DragEvent} event the drop event
     * @returns {void}
     */
    onEntryDrop(entry, event) {
      const action = hasDragPayload(event) && this.dropAction(entry);
      this.dropTarget = null;
      if (!action) {
        return;
      }
      event.preventDefault();
      this.$root.$emit(action.event, ...action.args);
      this.$root.$emit('email-drag-end');
    },
    /**
     * The entry's style: lit while the pointer holds mail over it that it may take.
     *
     * @param {Object} entry the column's entry
     * @returns {Object} the inline style, or null
     */
    dropStyle(entry) {
      return this.dropTarget === entry.value ? DROP_TARGET_STYLE : null;
    },
  },
};
