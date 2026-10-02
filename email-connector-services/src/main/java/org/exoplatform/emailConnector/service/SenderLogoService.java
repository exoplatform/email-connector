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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.emailConnector.model.SenderLogo;
import org.exoplatform.emailConnector.storage.SenderLogoStorage;
import org.exoplatform.emailConnector.utils.SenderLogoUtils;

/**
 * A company sender's brand logo, for the avatar of a sender with no platform profile
 * (EXO-90893).
 * <p>
 * The avatar is chosen in this order: the platform user's profile picture when the
 * address belongs to one; else this logo -- the one the sender's domain publishes
 * through BIMI, else its site's icon -- when the message passed DMARC for that domain;
 * else the coloured initials. The DMARC condition is what keeps a mail spoofing a
 * brand from getting its logo, the icon included: a logo on a forged mail would lend
 * it the very trust the reader's phishing warnings take away.
 * <p>
 * The logo is fetched by the server, never by the browser, so a brand cannot learn that
 * its mail was opened, nor by whom; it is cached per domain, "no logo" included
 * ({@link SenderLogoStorage}). An administrator turns the whole feature off
 * ({@code EmailConnectorService#isSenderLogosEnabled}) for a deployment that makes no
 * outbound calls: nothing is fetched, nothing is offered, and senders keep their
 * initials.
 */
@Service
public class SenderLogoService {

  /** Where a domain's logo is served, the domain appended. */
  public static final String LOGO_PATH      = "/email-connector/rest/email-box/sender-logo/";

  /** Message code answered as a 400 for a logo asked for something that is not a domain name. */
  public static final String INVALID_DOMAIN = "emailConnector.senderLogo.invalidDomain";

  /** How long a "no logo" answer is believed, in ms: a day. */
  static final long          NONE_TTL_MS    = 24L * 60 * 60 * 1000;

  @Autowired
  private EmailConnectorService emailConnectorService;

  @Autowired
  private SenderLogoStorage     senderLogoStorage;

  /**
   * The URL of a sender's brand logo, for the avatar resolution to offer after the
   * platform profile picture and before the initials.
   * <p>
   * Null -- initials -- when the feature is off, when the message did not pass DMARC
   * for its domain, when the address has no valid domain, or when the domain is
   * already known to have no logo. Nothing is fetched here: a domain not resolved yet
   * gets its URL, and the logo is fetched when the browser asks for it.
   *
   * @param address the sender's address
   * @param dmarcPassed whether the message passed DMARC for the address's domain
   * @return the logo's URL, or null
   */
  public String logoUrlFor(String address, boolean dmarcPassed) {
    if (!dmarcPassed) {
      return null;
    }
    String domain = SenderLogoUtils.domainOfAddress(address);
    if (domain == null || !emailConnectorService.isSenderLogosEnabled()) {
      return null;
    }
    SenderLogo known = senderLogoStorage.peek(domain);
    if (known != null && !known.isPresent() && !isStale(known)) {
      return null;
    }
    return LOGO_PATH + domain;
  }

  /**
   * A domain's logo, from the cache, else fetched; a "no logo" answer older than a day
   * is fetched again.
   *
   * @param domain the domain, as the logo URL names it
   * @return the logo, or null when the domain has none or the feature is off
   * @throws IllegalArgumentException {@link #INVALID_DOMAIN} when it is not a domain
   *           name a mail could come from
   */
  public SenderLogo getLogo(String domain) {
    String normalised = SenderLogoUtils.normaliseDomain(domain);
    if (normalised == null) {
      throw new IllegalArgumentException(INVALID_DOMAIN);
    }
    if (!emailConnectorService.isSenderLogosEnabled()) {
      return null;
    }
    SenderLogo logo = senderLogoStorage.getLogo(normalised);
    if (!logo.isPresent() && isStale(logo)) {
      senderLogoStorage.evict(normalised);
      logo = senderLogoStorage.getLogo(normalised);
    }
    return logo.isPresent() ? logo : null;
  }

  /**
   * Whether a "no logo" answer is older than {@link #NONE_TTL_MS}.
   *
   * @param logo the answer
   * @return true when it should be asked again
   */
  private static boolean isStale(SenderLogo logo) {
    return System.currentTimeMillis() - logo.getResolvedAt() >= NONE_TTL_MS;
  }
}
