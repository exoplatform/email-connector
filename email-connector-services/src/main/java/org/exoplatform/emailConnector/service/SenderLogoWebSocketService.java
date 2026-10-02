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
package org.exoplatform.emailConnector.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.emailConnector.model.SenderLogoWebSocketMessage;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.ws.frameworks.cometd.ContinuationService;

import io.meeds.social.util.JsonUtils;

/**
 * Tells a user's open pages that a sender's avatar may now be a brand logo (EXO-90909),
 * so that the mail list and the reader switch from the initials to the logo in place.
 * <p>
 * <b>The channel is the one the mailbox already listens to</b>: app-center's badge
 * channel, {@link #COMETD_CHANNEL}, which the mail drawer subscribes every page to for
 * its unread badge ({@code APP_CENTER_BADGE_CHANNEL} in
 * {@code EmailConnectorMailBoxDrawer.vue}). Social's {@code $socialWebSocket} turns each
 * frame into a document event named by its {@code wsEventName}, so these frames reach
 * the mailbox and nothing else.
 * <p>
 * <b>A frame names, it never grants.</b> It carries a domain, or the user's own rows
 * and their senders -- never a logo URL: each URL carries a token bound to its reader
 * ({@code SenderLogoService#token}), so the page asks for its own through the
 * authenticated avatar endpoint, where every check runs again. A frame goes to the
 * users named only, each on their own connection ({@link ContinuationService#sendMessage}),
 * and only while they are connected.
 * <p>
 * Best effort: the transport not being up, or a user having left, is no incident, and
 * the page shows the logo at its next load anyway.
 */
@Service
public class SenderLogoWebSocketService {

  /**
   * The CometD channel the frames travel on: app-center's
   * {@code ApplicationBadgeWebSocketService.COMETD_CHANNEL}, which the mail drawer
   * subscribes to as {@code APP_CENTER_BADGE_CHANNEL}. Change all three together.
   */
  public static final String  COMETD_CHANNEL       = "/eXo/Application/AppCenter/Badge";

  /**
   * The event a page receives when a domain's logo was found: the webapp's
   * {@code SENDER_LOGO_FOUND_EVENT}.
   */
  public static final String  LOGO_FOUND_EVENT     = "emailConnector.senderLogo.found";

  /**
   * The event a page receives when rows of its user's mailbox got their DMARC verdict
   * and passed: the webapp's {@code SENDER_ROWS_VERIFIED_EVENT}.
   */
  public static final String  ROWS_VERIFIED_EVENT  = "emailConnector.senderLogo.rowsVerified";

  /** The key of the domain in a {@link #LOGO_FOUND_EVENT} frame. */
  public static final String  DOMAIN_KEY           = "domain";

  /** The key of the row ids in a {@link #ROWS_VERIFIED_EVENT} frame. */
  public static final String  IDS_KEY              = "ids";

  /** The key of the rows' sender addresses in a {@link #ROWS_VERIFIED_EVENT} frame. */
  public static final String  ADDRESSES_KEY        = "addresses";

  private static final Log    LOG                  = ExoLogger.getLogger(SenderLogoWebSocketService.class);

  @Autowired(required = false)
  private ContinuationService continuationService;

  /**
   * Tells the users waiting for a domain's logo that it was found: each page asks for
   * its own URL again.
   *
   * @param domain the normalised domain whose logo is now cached
   * @param usernames the users to tell, the only ones told
   */
  public void logoFound(String domain, Collection<String> usernames) {
    if (StringUtils.isBlank(domain) || usernames == null || usernames.isEmpty()) {
      return;
    }
    String frame = JsonUtils.toJsonString(new SenderLogoWebSocketMessage(LOGO_FOUND_EVENT, Map.of(DOMAIN_KEY, domain)));
    usernames.forEach(username -> send(username, frame));
  }

  /**
   * Tells a user that rows of their own mailbox passed DMARC once their verdict was
   * filled in: the list may show those rows' brand logos.
   *
   * @param username the mailbox owner, the only one told
   * @param ids the rows' ids
   * @param addresses the rows' sender addresses
   */
  public void rowsVerified(String username, List<Long> ids, Collection<String> addresses) {
    if (ids == null || ids.isEmpty()) {
      return;
    }
    String frame = JsonUtils.toJsonString(new SenderLogoWebSocketMessage(ROWS_VERIFIED_EVENT,
                                                                        Map.of(IDS_KEY,
                                                                               ids,
                                                                               ADDRESSES_KEY,
                                                                               List.copyOf(addresses))));
    send(username, frame);
  }

  /**
   * Sends a frame to one user's connected pages, if any.
   *
   * @param username the user
   * @param frame the JSON frame
   */
  private void send(String username, String frame) {
    if (continuationService == null || StringUtils.isBlank(username)) {
      return;
    }
    try {
      if (continuationService.isPresent(username)) {
        continuationService.sendMessage(username, COMETD_CHANNEL, frame);
      }
    } catch (Exception e) {
      // The transport may not be up, or the user just left: the next load shows the logo.
      LOG.debug("Could not tell user {} about a sender logo", username, e);
    }
  }
}
