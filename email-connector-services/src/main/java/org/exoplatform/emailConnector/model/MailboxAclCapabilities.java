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
package org.exoplatform.emailConnector.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * What one connected mail server can do about sharing, probed live rather than
 * assumed -- and probed by <b>attempting the command</b>, never by reading the
 * CAPABILITY advertisement: both servers this feature was tested against (BlueMind,
 * Stalwart v0.11.8) answered MYRIGHTS, GETACL and, where they let it through, SETACL
 * while advertising no {@code ACL} at all, and Stalwart served its Other Users prefix
 * while advertising no {@code NAMESPACE} (delegation plan, sections 3.2, 13.B.9,
 * 13.C). The two advertisement bits are kept as hints for the log and the interface;
 * nothing is gated on them.
 * <p>
 * The three engine traits describe what differs between servers from the user's
 * standpoint (plan, section 13.F), so the lifecycle can act on them without knowing
 * the engine: whether the server itself tells the owner about a rights change,
 * whether it grants per folder or per mailbox, and whether the grantee must accept
 * on the server before the share is visible to them.
 *
 * @param supported whether shares can be read and written at all on this session
 * @param aclAdvertised whether CAPABILITY listed {@code ACL} (RFC 4314) -- a hint; a
 *          server that answers the commands without it is supported all the same
 * @param namespaceAdvertised whether CAPABILITY listed {@code NAMESPACE} (RFC 2342)
 *          -- a hint; discovery falls back to the session's own LIST without it
 * @param grantGranularity the unit a grant is written on
 * @param serverNotifiesOwner whether the server e-mails the mailbox owner on every
 *          rights change of its own accord (BlueMind does, four such mails were
 *          observed; Stalwart does not). Any owner-facing notification eXo adds around
 *          grant or revoke must be gated on this being false, or a BlueMind owner is
 *          told twice for one act (plan, section 5.1)
 * @param subscriptionRequired whether the grantee must perform a server-side
 *          acceptance before the share is visible to them (BlueMind: the share made in
 *          the owner's interface did not appear to the delegate until he accepted it
 *          in his own -- plan, section 13.B.14; Stalwart: none, the folder was in the
 *          delegate's LIST immediately). When true, accepting in eXo also calls the
 *          engine's subscribe hook; when false, "Accept" is purely eXo-side
 * @param reasonCode the message code saying why sharing is unsupported, null when it is
 * @param sendModes the shapes of writing in the owner's name the server accepts from a
 *          delegate, as the engine knows them (EXO-90582): declared by the administrator
 *          on an IMAP engine ({@link SendMode#declaredFor(Long)}), read from the server's
 *          own verbs on a vendor engine that has them. Empty where nothing is declared,
 *          which hides the owner's control. Never null, never {@link SendMode#NONE}
 * @param sendModeOnServer whether the engine writes the owner's consent on the server
 *          too ({@link org.exoplatform.emailConnector.service.acl.MailboxAclEngine#grantSendMode});
 *          false on an IMAP engine, where eXo alone holds and enforces it -- an ACL
 *          letter cannot say "send"
 */
public record MailboxAclCapabilities(boolean supported,
                                     boolean aclAdvertised,
                                     boolean namespaceAdvertised,
                                     GrantGranularity grantGranularity,
                                     boolean serverNotifiesOwner,
                                     boolean subscriptionRequired,
                                     String reasonCode,
                                     Set<SendMode> sendModes,
                                     boolean sendModeOnServer) {

  /**
   * Normalises the declared shapes: never null, never {@link SendMode#NONE}, and a copy
   * nobody can change afterwards.
   *
   * @param supported whether shares can be read and written at all
   * @param aclAdvertised whether CAPABILITY listed ACL
   * @param namespaceAdvertised whether CAPABILITY listed NAMESPACE
   * @param grantGranularity the unit a grant is written on
   * @param serverNotifiesOwner whether the server notifies the owner itself
   * @param subscriptionRequired whether the grantee must accept on the server
   * @param reasonCode why sharing is unsupported, null when it is
   * @param sendModes the shapes of writing in the owner's name the server accepts
   * @param sendModeOnServer whether the engine writes the consent on the server
   */
  public MailboxAclCapabilities {
    Set<SendMode> modes = EnumSet.noneOf(SendMode.class);
    if (sendModes != null) {
      sendModes.stream().filter(mode -> mode != null && mode != SendMode.NONE).forEach(modes::add);
    }
    sendModes = Collections.unmodifiableSet(modes);
  }

  /**
   * The capabilities of a server that accepts no writing in the owner's name -- every
   * caller written before EXO-90582.
   *
   * @param supported whether shares can be read and written at all
   * @param aclAdvertised whether CAPABILITY listed ACL
   * @param namespaceAdvertised whether CAPABILITY listed NAMESPACE
   * @param grantGranularity the unit a grant is written on
   * @param serverNotifiesOwner whether the server notifies the owner itself
   * @param subscriptionRequired whether the grantee must accept on the server
   * @param reasonCode why sharing is unsupported, null when it is
   */
  public MailboxAclCapabilities(boolean supported,
                                boolean aclAdvertised,
                                boolean namespaceAdvertised,
                                GrantGranularity grantGranularity,
                                boolean serverNotifiesOwner,
                                boolean subscriptionRequired,
                                String reasonCode) {
    this(supported,
         aclAdvertised,
         namespaceAdvertised,
         grantGranularity,
         serverNotifiesOwner,
         subscriptionRequired,
         reasonCode,
         Set.of(),
         false);
  }

  /**
   * A server on which sharing is not available.
   *
   * @param reasonCode why
   * @return the capabilities
   */
  public static MailboxAclCapabilities unsupported(String reasonCode) {
    return new MailboxAclCapabilities(false, false, false, GrantGranularity.NONE, false, false, reasonCode);
  }

  /**
   * A plain RFC 4314 server: grants per folder, no owner notification of its own, no
   * acceptance step on the server -- what Stalwart, Cyrus and Dovecot's ACL plugin
   * are.
   *
   * @param aclAdvertised whether CAPABILITY listed ACL
   * @param namespaceAdvertised whether CAPABILITY listed NAMESPACE
   * @return the capabilities, supported
   */
  public static MailboxAclCapabilities imap(boolean aclAdvertised, boolean namespaceAdvertised) {
    return imap(aclAdvertised, namespaceAdvertised, Set.of());
  }

  /**
   * {@link #imap(boolean, boolean)} with the shapes of writing in the owner's name the
   * administrator declared for the connector (EXO-90582); eXo alone holds the consent on
   * such a server.
   *
   * @param aclAdvertised whether CAPABILITY listed ACL
   * @param namespaceAdvertised whether CAPABILITY listed NAMESPACE
   * @param sendModes the declared shapes
   * @return the capabilities, supported
   */
  public static MailboxAclCapabilities imap(boolean aclAdvertised, boolean namespaceAdvertised, Set<SendMode> sendModes) {
    return new MailboxAclCapabilities(true,
                                      aclAdvertised,
                                      namespaceAdvertised,
                                      GrantGranularity.FOLDER,
                                      false,
                                      false,
                                      null,
                                      sendModes,
                                      false);
  }
}
