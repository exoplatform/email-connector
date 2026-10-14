/**
 * Copyright (C) 2026 eXo Platform SAS
 *
 *  This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.exception;

/**
 * The mail server would not, or could not, do what a delegation operation asked of its
 * access-control list: no ACL capability, an owner without the right to administer
 * their own mailbox, a SETACL or DELETEACL the server answered NO to, a mailbox that
 * could not be reached. Typed so the REST layer answers a fixed code and never the
 * server's own text -- that text routinely names paths and users, and belongs in a
 * DEBUG log, not a response body.
 */
public class MailboxAclException extends RuntimeException {

  private static final long  serialVersionUID        = 1L;

  /**
   * The server refused the ACL command the probe attempted (a {@code BAD} or {@code NO}
   * to MYRIGHTS): no RFC 4314 on this session. Not the capability advertisement -- a
   * server that answers the command without advertising it is supported.
   */
  public static final String UNSUPPORTED             = "emailConnector.delegation.unsupported";

  /** The connector's preset is configured without an ACL engine (Gmail, Exchange...). */
  public static final String UNSUPPORTED_PROVIDER    = "emailConnector.delegation.unsupported.provider";

  /** The connection is not an IMAP one, so no ACL command exists for it. */
  public static final String NOT_IMAP                = "emailConnector.delegation.unsupported.notImap";

  /**
   * MYRIGHTS on the owner's own INBOX does not carry {@code a}: this server keeps ACL
   * administration to itself (or to its own interface), and eXo cannot write a grant.
   */
  public static final String OWNER_CANNOT_ADMINISTER = "emailConnector.delegation.ownerCannotAdminister";

  /** The server answered NO (or failed) to an ACL command. */
  public static final String SERVER_REFUSED          = "emailConnector.delegation.serverRefused";

  /** The mailbox could not be connected to with the caller's own session. */
  public static final String UNREACHABLE             = "emailConnector.delegation.unreachable";

  /** The intersection of the preset with the owner's own rights left nothing to grant. */
  public static final String NOTHING_TO_GRANT        = "emailConnector.delegation.nothingToGrant";

  private final String       detail;

  /**
   * @param code the message code the interface translates (one of the constants)
   * @param detail the server's or the library's own text, for the DEBUG log only
   */
  public MailboxAclException(String code, String detail) {
    super(code);
    this.detail = detail;
  }

  /**
   * @param code the message code the interface translates (one of the constants)
   * @param cause what the mail library threw
   */
  public MailboxAclException(String code, Throwable cause) {
    super(code, cause);
    this.detail = cause == null ? null : cause.getMessage();
  }

  /**
   * The message code, which is all a response body carries.
   *
   * @return the code
   */
  public String getCode() {
    return getMessage();
  }

  /**
   * The server's own words, never sent to a client.
   *
   * @return the detail, possibly null
   */
  public String getDetail() {
    return detail;
  }
}
