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
 * A connector's engine cannot be switched: its users still have on the mail server what
 * eXo set through the current engine, and the engine switched to could neither read nor
 * remove it. The counts say what, so that the administrator knows what to have removed
 * first.
 */
public class EngineInUseException extends Exception {

  private static final long  serialVersionUID = 1L;

  /** The server rules engine is in use: automatic replies, forwards or server rules. */
  public static final String RULES_IN_USE     = "emailConnector.engines.rulesInUse";

  /** The mailbox sharing engine is in use: shares made from eXo. */
  public static final String ACL_IN_USE       = "emailConnector.engines.aclInUse";

  /** The automatic replies eXo set that are on. */
  private final long         replies;

  /** The forwards eXo set that are on. */
  private final long         forwards;

  /** The users with server rules eXo wrote that are on. */
  private final long         rules;

  /** The shares made from eXo whose access is on the server. */
  private final long         shares;

  /**
   * A refusal.
   *
   * @param code {@value #RULES_IN_USE} or {@value #ACL_IN_USE}, the exception's message
   * @param replies the automatic replies eXo set that are on
   * @param forwards the forwards eXo set that are on
   * @param rules the users with server rules eXo wrote that are on
   * @param shares the shares made from eXo whose access is on the server
   */
  public EngineInUseException(String code, long replies, long forwards, long rules, long shares) {
    super(code);
    this.replies = replies;
    this.forwards = forwards;
    this.rules = rules;
    this.shares = shares;
  }

  /**
   * @return the automatic replies eXo set that are on
   */
  public long getReplies() {
    return replies;
  }

  /**
   * @return the forwards eXo set that are on
   */
  public long getForwards() {
    return forwards;
  }

  /**
   * @return the users with server rules eXo wrote that are on
   */
  public long getRules() {
    return rules;
  }

  /**
   * @return the shares made from eXo whose access is on the server
   */
  public long getShares() {
    return shares;
  }
}
