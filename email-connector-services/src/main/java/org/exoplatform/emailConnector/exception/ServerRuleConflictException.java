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
 * eXo declined to publish on the mail server because doing so would replace, rewrite or
 * silently break something another client put there. Nothing was written. Not an error
 * of the caller's input and not a failure of the server: the REST layer answers 409
 * with the code as message, and the script name so the interface can say which client
 * manages it.
 */
public class ServerRuleConflictException extends Exception {

  private static final long  serialVersionUID  = 1L;

  /**
   * Another script is active and the server cannot include it (no RFC 6609
   * {@code include}): publishing would replace it.
   */
  public static final String SERVER_CONFLICT   = "emailConnector.absence.serverConflict";

  /**
   * Another active script already carries a {@code vacation}; RFC 5230 §4.6 allows one
   * per run, so both replies would fail at delivery.
   */
  public static final String MANAGED_ELSEWHERE = "emailConnector.absence.managedElsewhere";

  /** eXo's own wrapper was edited by another client and no longer reads as eXo's. */
  public static final String MODIFIED_OUTSIDE  = "emailConnector.absence.modifiedOutside";

  /**
   * eXo's own script no longer reads as eXo's: nothing eXo writes may replace it, not even
   * on "Re-publish", since what it holds could not be written back. Repaired or deleted in
   * the mail client.
   */
  public static final String UNREADABLE        = "emailConnector.absence.unreadable";

  /** The script the conflict is about. */
  private final String       scriptName;

  /**
   * A conflict about a script.
   *
   * @param code one of the codes of this class
   * @param scriptName the script the conflict is about
   */
  public ServerRuleConflictException(String code, String scriptName) {
    super(code);
    this.scriptName = scriptName;
  }

  /**
   * The script the conflict is about, to name it to the user.
   *
   * @return the name as the server stores it
   */
  public String getScriptName() {
    return scriptName;
  }
}
