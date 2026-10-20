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

/** Which case of the one-active-script Sieve policy a publish took. */
public enum SievePolicyCase {
  /** No script was active; eXo's script is now. */
  NO_ACTIVE_SCRIPT,
  /**
   * eXo's wrapper was active around a script that no longer exists; eXo's script now
   * runs alone and the wrapper is gone.
   */
  WRAPPER_REMOVED,
  /** eXo's script was active and was replaced in place. */
  EXO_ACTIVE,
  /** Another script is active through eXo's wrapper, next to eXo's script. */
  WRAPPED,
  /**
   * Another script is active and already includes eXo's script, which was replaced in
   * place; the other script stays active, unwrapped.
   */
  INCLUDED_BY_FOREIGN
}
