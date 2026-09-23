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
<!-- A draggable divider between two full-screen columns (EXO-90575): a line with a grip,
     dragged with the mouse or a finger, moved by the arrow keys once focused, reset by a
     double-click. It asks for a width and the drawer decides: it never sizes anything
     itself. A press does not take the focus -- the list keeps it, and the arrow keys with
     it -- and only the keys it answers are its own. -->
<template>
  <!-- A focusable separator is a widget in WAI-ARIA 1.2 (the window splitter pattern),
       which the lint rule, reading every separator as static, does not know. -->
  <!-- eslint-disable-next-line vuejs-accessibility/no-static-element-interactions -->
  <div
    :aria-valuenow="Math.round(value)"
    :aria-valuemin="Math.round(min)"
    :aria-valuemax="Math.round(max)"
    :aria-label="label"
    :title="$t('emailConnector.mailBox.list.drawer.columnSeparator.hint')"
    :style="{ width: widthPx, minWidth: widthPx, touchAction: 'none' }"
    role="separator"
    aria-orientation="vertical"
    tabindex="0"
    class="col-resize-cursor flex-grow-0 flex-shrink-0 fill-height d-flex justify-center position-relative"
    @pointerdown="startDrag"
    @pointermove="drag"
    @pointerup="endDrag"
    @pointercancel="endDrag"
    @lostpointercapture="endDrag"
    @mousedown.prevent
    @dblclick="$emit('reset')"
    @keydown="onKeydown"
    @mouseenter="hover = true"
    @mouseleave="hover = false"
    @focus="focused = true"
    @blur="focused = false">
    <v-divider vertical />
    <div :style="gripStyle" class="position-absolute"></div>
  </div>
</template>
<script>
import { SEPARATOR_WIDTH_PX, separatorKeyWidth } from '../../js/EmailConnectorMailBoxColumnWidths.js';

export default {
  props: {
    // The width of the column this divider sizes, in CSS pixels, and its range now.
    value: {
      type: Number,
      default: 0,
    },
    min: {
      type: Number,
      default: 0,
    },
    max: {
      type: Number,
      default: 0,
    },
    // The divider's accessible name: which column it sizes.
    label: {
      type: String,
      default: null,
    },
  },
  data: () => ({
    hover: false,
    focused: false,
    // The drag under way: its pointer, where it started and the width then; null when
    // there is none.
    dragStart: null,
  }),
  computed: {
    /**
     * The handle's width, in the list's slot (SEPARATOR_WIDTH_PX).
     *
     * @returns {String} the width, in CSS pixels
     */
    widthPx() {
      return `${SEPARATOR_WIDTH_PX}px`;
    },
    /**
     * The grip, in the platform's primary color while the divider is pointed at, held
     * or focused, a muted grey otherwise, so the handle is visible without shouting.
     *
     * @returns {Object} the grip's inline style
     */
    gripStyle() {
      const active = this.hover || this.focused || !!this.dragStart;
      return {
        top: '50%',
        left: '50%',
        width: '4px',
        height: '32px',
        borderRadius: '2px',
        transform: 'translate(-50%, -50%)',
        pointerEvents: 'none',
        background: active ? 'var(--allPagesPrimaryColor, #578dc9)' : 'var(--allPagesGreyColorLighten1, #707070)',
        opacity: active ? 1 : 0.4,
      };
    },
  },
  methods: {
    /**
     * Starts a drag from the main button or a touch, and keeps the pointer's moves on
     * the divider (pointer capture) however fast it leaves it.
     *
     * @param {PointerEvent} event the press
     * @returns {void}
     */
    startDrag(event) {
      if (event.button !== 0 || this.dragStart) {
        return;
      }
      this.dragStart = { pointerId: event.pointerId, x: event.clientX, value: this.value, moved: false };
      try {
        event.currentTarget.setPointerCapture(event.pointerId);
      } catch (e) {
        // No capture (an old browser): the drag follows the pointer while it is over the divider.
      }
    },
    /**
     * Asks for the width the pointer points at: the width at the start moved by how
     * far the pointer went -- mirrored in a right-to-left page, whose columns start on
     * the right. The drawer keeps it within the column's range, so the drag stops at
     * a minimum and goes on from where the pointer is when it comes back.
     *
     * @param {PointerEvent} event the move
     * @returns {void}
     */
    drag(event) {
      if (!this.dragStart || event.pointerId !== this.dragStart.pointerId) {
        return;
      }
      const moved = (event.clientX - this.dragStart.x) * (this.$vuetify.rtl ? -1 : 1);
      this.dragStart.moved = true;
      this.$emit('resize', this.dragStart.value + moved);
    },
    /**
     * Ends the drag, however it ends, and lets the drawer remember the widths when the
     * pointer moved -- even back to where it started: the drawer's choice may have
     * changed while the width on screen did not (a window clipping it).
     *
     * @param {PointerEvent} event the release, cancel or loss of capture
     * @returns {void}
     */
    endDrag(event) {
      if (!this.dragStart || event.pointerId !== this.dragStart.pointerId) {
        return;
      }
      const moved = this.dragStart.moved;
      this.dragStart = null;
      if (moved) {
        this.$emit('commit');
      }
    },
    /**
     * Left and Right move the divider by a step, Home and End to the column's minimum
     * and maximum. Every other key -- Up and Down that walk the list above all -- goes
     * on untouched.
     *
     * @param {KeyboardEvent} event the key press
     * @returns {void}
     */
    onKeydown(event) {
      const width = separatorKeyWidth(event, this.value, { min: this.min, max: this.max }, this.$vuetify.rtl);
      if (width === null) {
        return;
      }
      event.preventDefault();
      event.stopPropagation();
      this.$emit('resize', width);
      this.$emit('commit');
    },
  },
};
</script>
