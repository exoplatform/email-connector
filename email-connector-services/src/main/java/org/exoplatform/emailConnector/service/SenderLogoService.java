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

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.emailConnector.model.SenderLogo;
import org.exoplatform.emailConnector.storage.SenderLogoStorage;
import org.exoplatform.emailConnector.utils.SenderLogoUtils;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import jakarta.annotation.PreDestroy;

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
 * <b>No request ever waits for the internet.</b> A logo is offered only once it is in
 * the cache ({@link SenderLogoStorage}); a domain not resolved yet is handed to a small
 * background pool ({@link #WARM_THREADS} threads, {@link #WARM_QUEUE} waiting domains,
 * the rest dropped and offered again at the next read) and its sender keeps the
 * initials until then. Only a message that passed DMARC, read by its recipient, ever
 * starts a fetch; the logo endpoint serves the cache and never fetches, so no caller
 * can make the server reach a domain of its choosing.
 * <p>
 * The logo is fetched by the server, never by the browser, so a brand cannot learn that
 * its mail was opened, nor by whom. An administrator turns the whole feature off
 * ({@code EmailConnectorService#isSenderLogosEnabled}): nothing is fetched, nothing is
 * offered, and senders keep their initials.
 */
@Service
public class SenderLogoService {

  /** Where a domain's logo is served, the domain appended. */
  public static final String LOGO_PATH      = "/email-connector/rest/email-box/sender-logo/";

  /** Message code answered as a 400 for a logo asked for something that is not a domain name. */
  public static final String INVALID_DOMAIN = "emailConnector.senderLogo.invalidDomain";

  /** How long a "no logo" answer is believed, in ms: a day. */
  static final long          NONE_TTL_MS    = 24L * 60 * 60 * 1000;

  /** How many domains are resolved at once in the background. */
  static final int           WARM_THREADS   = 2;

  /** How many domains may wait for a background resolution; past it they are dropped. */
  static final int           WARM_QUEUE     = 100;

  private static final Log   LOG            = ExoLogger.getLogger(SenderLogoService.class);

  @Autowired
  private EmailConnectorService emailConnectorService;

  @Autowired
  private SenderLogoStorage     senderLogoStorage;

  private final Set<String>     warming       = ConcurrentHashMap.newKeySet();

  private final ExecutorService warmPool      = warmPool();

  private Executor              warmExecutor  = warmPool;

  /**
   * The URL of a sender's brand logo, for the avatar resolution to offer after the
   * platform profile picture and before the initials.
   * <p>
   * The URL when the feature is on, the message passed DMARC for the address's domain,
   * and the domain's logo is in the cache. A domain not resolved yet, or whose "no
   * logo" is older than a day, is resolved in the background and gets null this time;
   * null, too, for a domain known to have no logo, an address without a valid domain,
   * no DMARC pass, or the feature off. Nothing is fetched on the caller's thread.
   *
   * @param address the sender's address
   * @param dmarcPassed whether the message passed DMARC for the address's domain
   * @return the logo's URL, or null for the initials
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
    if (known != null && known.isPresent()) {
      return LOGO_PATH + domain;
    }
    if (known == null || isStale(known)) {
      warm(domain, known != null);
    }
    return null;
  }

  /**
   * A domain's logo, from the cache only: the endpoint never fetches.
   *
   * @param domain the domain, as the logo URL names it
   * @return the logo, or null when it is not cached, the domain has none, or the
   *         feature is off
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
    SenderLogo logo = senderLogoStorage.peek(normalised);
    return logo != null && logo.isPresent() ? logo : null;
  }

  /**
   * Stops the background resolutions.
   */
  @PreDestroy
  public void stop() {
    warmPool.shutdownNow();
  }

  /**
   * Replaces the background executor, for the tests.
   *
   * @param executor the executor
   */
  void setWarmExecutor(Executor executor) {
    this.warmExecutor = executor;
  }

  /**
   * Resolves a domain in the background, once at a time per domain, dropping it when
   * the pool's queue is full.
   *
   * @param domain the normalised domain
   * @param evictFirst whether a stale "no logo" answer is forgotten first
   */
  private void warm(String domain, boolean evictFirst) {
    if (!warming.add(domain)) {
      return;
    }
    try {
      warmExecutor.execute(() -> {
        try {
          if (evictFirst) {
            senderLogoStorage.evict(domain);
          }
          senderLogoStorage.getLogo(domain);
        } catch (RuntimeException e) {
          LOG.debug("The logo of a sender domain could not be resolved", e);
        } finally {
          warming.remove(domain);
        }
      });
    } catch (RejectedExecutionException e) {
      warming.remove(domain);
    }
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

  /**
   * The background pool: {@link #WARM_THREADS} daemon threads, {@link #WARM_QUEUE}
   * waiting domains, the rest refused.
   *
   * @return the pool
   */
  private static ExecutorService warmPool() {
    return new ThreadPoolExecutor(WARM_THREADS,
                                  WARM_THREADS,
                                  1,
                                  TimeUnit.MINUTES,
                                  new ArrayBlockingQueue<>(WARM_QUEUE),
                                  runnable -> {
                                    Thread thread = new Thread(runnable, "email-connector-sender-logo");
                                    thread.setDaemon(true);
                                    return thread;
                                  },
                                  new ThreadPoolExecutor.AbortPolicy());
  }
}
