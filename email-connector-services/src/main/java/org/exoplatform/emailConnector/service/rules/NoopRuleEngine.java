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
package org.exoplatform.emailConnector.service.rules;

import org.springframework.stereotype.Service;

import org.exoplatform.emailConnector.exception.ServerRuleUnsupportedException;
import org.exoplatform.emailConnector.model.ServerRuleCapabilities;
import org.exoplatform.emailConnector.model.ServerRuleCapabilities.VocabularySource;
import org.exoplatform.emailConnector.model.ServerVacation;
import org.exoplatform.emailConnector.model.VacationSetting;
import org.exoplatform.emailConnector.service.acl.MailboxAclSession;

/**
 * The engine of a preset with no server-rule engine configured, or whose server offers
 * none eXo speaks (Gmail, Exchange, Office 365: the automatic reply is a setting of the
 * provider's own interface). It says so in {@link #probe} without opening anything on
 * the caller's session, reads nothing, and refuses every write with the same code, so
 * the interface can point to the provider instead of showing a failure.
 */
@Service
public class NoopRuleEngine implements ServerRuleEngine {

  /** The engine name a preset selects, and the default. */
  public static final String NAME   = "none";

  /** Why nothing is supported: no engine for this connector. */
  public static final String REASON = "emailConnector.absence.unsupported.provider";

  /**
   * The engine's name.
   *
   * @return {@value #NAME}
   */
  @Override
  public String getName() {
    return NAME;
  }

  /**
   * Nothing is supported; the session is never opened.
   *
   * @param session unused
   * @return unsupported, with the provider reason
   */
  @Override
  public ServerRuleCapabilities probe(MailboxAclSession session) {
    return ServerRuleCapabilities.unsupported(REASON, VocabularySource.NONE);
  }

  /**
   * Nothing to read.
   *
   * @param session unused
   * @return {@link ServerVacation#none()}
   */
  @Override
  public ServerVacation readVacation(MailboxAclSession session) {
    return ServerVacation.none();
  }

  /**
   * Refused.
   *
   * @param session unused
   * @param vacation unused
   * @param days unused
   * @param expectedScriptHash unused
   * @return never
   * @throws ServerRuleUnsupportedException always
   */
  @Override
  public ServerVacation writeVacation(MailboxAclSession session,
                                      VacationSetting vacation,
                                      int days,
                                      String expectedScriptHash) throws ServerRuleUnsupportedException {
    throw new ServerRuleUnsupportedException(ServerRuleUnsupportedException.VACATION_UNSUPPORTED);
  }
}
