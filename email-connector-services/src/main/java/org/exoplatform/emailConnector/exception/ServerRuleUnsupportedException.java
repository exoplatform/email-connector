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
package org.exoplatform.emailConnector.exception;

/**
 * The rules engine of this connector cannot do what was asked: no engine is configured
 * for it, its server lacks the extension, or the verb belongs to a feature this engine
 * does not implement yet. Never an {@link UnsupportedOperationException}: the caller
 * answers the user with the code, and the REST layer answers 400 with it, since the
 * interface only offers what the probe answered as supported.
 */
public class ServerRuleUnsupportedException extends Exception {

  private static final long  serialVersionUID     = 1L;

  /** The automatic reply cannot be managed on this connector. */
  public static final String VACATION_UNSUPPORTED = "emailConnector.absence.unsupported";

  /** Server rules cannot be managed by this engine. */
  public static final String RULES_UNSUPPORTED    = "emailConnector.rules.unsupported";

  /**
   * An unsupported request.
   *
   * @param code one of the codes of this class, the exception's message
   */
  public ServerRuleUnsupportedException(String code) {
    super(code);
  }
}
