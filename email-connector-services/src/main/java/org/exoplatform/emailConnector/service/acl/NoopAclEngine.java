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
package org.exoplatform.emailConnector.service.acl;

import java.util.List;

import org.springframework.stereotype.Service;

import org.exoplatform.emailConnector.exception.MailboxAclException;
import org.exoplatform.emailConnector.model.DelegationPreset;
import org.exoplatform.emailConnector.model.MailboxAce;
import org.exoplatform.emailConnector.model.MailboxAclCapabilities;
import org.exoplatform.emailConnector.model.MailboxRights;
import org.exoplatform.emailConnector.model.SharedMailbox;

/**
 * The engine of a preset whose server has no reachable ACL (Gmail, Exchange, Office
 * 365: delegation is a setting of the provider's own interface). It says so in
 * {@link #probe} -- without opening anything on the caller's session -- and refuses
 * everything else with the same code, so the interface can show the provider's link
 * instead of a failure.
 */
@Service
public class NoopAclEngine implements MailboxAclEngine {

  /** The engine name a preset selects. */
  public static final String NAME = "none";

  /**
   * @return {@value #NAME}
   */
  @Override
  public String getName() {
    return NAME;
  }

  /**
   * @param session the caller's session, never opened
   * @return unsupported, with the provider reason
   */
  @Override
  public MailboxAclCapabilities probe(MailboxAclSession session) {
    return MailboxAclCapabilities.unsupported(MailboxAclException.UNSUPPORTED_PROVIDER);
  }

  /**
   * @param session unused
   * @param mailbox unused
   * @return never
   * @throws MailboxAclException always
   */
  @Override
  public MailboxRights myRights(MailboxAclSession session, String mailbox) {
    throw unsupported();
  }

  /**
   * @param session unused
   * @param mailbox unused
   * @return never
   * @throws MailboxAclException always
   */
  @Override
  public List<MailboxAce> listAcl(MailboxAclSession session, String mailbox) {
    throw unsupported();
  }

  /**
   * @param session unused
   * @param mailbox unused
   * @param identifier unused
   * @param preset unused
   * @param ownerRights unused
   * @return never
   * @throws MailboxAclException always
   */
  @Override
  public MailboxAce grant(MailboxAclSession session,
                          String mailbox,
                          String identifier,
                          DelegationPreset preset,
                          MailboxRights ownerRights) {
    throw unsupported();
  }

  /**
   * @param session unused
   * @param mailbox unused
   * @param identifier unused
   * @throws MailboxAclException always
   */
  @Override
  public void revoke(MailboxAclSession session, String mailbox, String identifier) {
    throw unsupported();
  }

  /**
   * @param session unused
   * @return never
   * @throws MailboxAclException always
   */
  @Override
  public List<SharedMailbox> listSharedMailboxes(MailboxAclSession session) {
    throw unsupported();
  }

  /**
   * @param session unused
   * @param ownerIdentifier unused
   * @return never
   * @throws MailboxAclException always
   */
  @Override
  public SharedMailbox findSharedMailbox(MailboxAclSession session, String ownerIdentifier) {
    throw unsupported();
  }

  /**
   * @return the one refusal this engine gives
   */
  private MailboxAclException unsupported() {
    return new MailboxAclException(MailboxAclException.UNSUPPORTED_PROVIDER, "no ACL engine for this preset");
  }
}
