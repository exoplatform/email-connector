/**
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.constant;

/** Where an engine's element answers come from. */
public enum ServerRuleVocabularySource {
  /** A fixed model, the same on every server of that family. */
  FIXED,
  /** Read from the server per connector, e.g. the Sieve {@code SIEVE} capability line. */
  DYNAMIC,
  /** No engine: nothing is supported. */
  NONE
}
