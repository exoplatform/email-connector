<!--
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
-->
<template>
  <!-- The A–Z jump rail: a slim sticky strip at the list's end edge (RTL-aware
       through flex order). Letters with no bucket are dimmed and dead; "#"
       collects the non-Latin bucket and renders last, where its rows sort.
       Inline styles only — this webapp's webpack has no CSS loader. -->
  <div
    class="d-flex flex-column align-center flex-shrink-0"
    style="position: sticky; top: 4px; align-self: flex-start; width: 18px; z-index: 3;">
    <div
      v-for="letter in railLetters"
      :key="letter"
      role="button"
      tabindex="0"
      :class="letterIndex[letter] ? 'primary--text cursor-pointer' : 'text-light-color'"
      style="font-size: 10px; line-height: 13px; text-align: center; width: 100%; user-select: none;"
      @click="letterIndex[letter] && $emit('jump', letter)"
      @keydown.enter="letterIndex[letter] && $emit('jump', letter)">
      {{ letter }}
    </div>
  </div>
</template>

<script>
const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('');

export default {
  props: {
    // The ordered letter → count map; a missing letter means an empty bucket.
    letterIndex: {
      type: Object,
      default: () => ({}),
    },
  },
  computed: {
    /**
     * The rail's letters: the full alphabet, plus "#" at the end only when the
     * non-Latin bucket has rows.
     *
     * @returns {Array} the letters to render
     */
    railLetters() {
      return this.letterIndex['#'] ? ALPHABET.concat('#') : ALPHABET;
    },
  },
};
</script>
