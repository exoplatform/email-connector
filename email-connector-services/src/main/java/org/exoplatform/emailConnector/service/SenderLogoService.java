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

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.emailConnector.model.SenderLogo;
import org.exoplatform.emailConnector.storage.SenderLogoStorage;
import org.exoplatform.emailConnector.utils.EmailContactUtils;
import org.exoplatform.emailConnector.utils.EmailSecurityUtils;
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
 * initials until then. Only a stored message that passed DMARC -- opened in the
 * reader, or listed on a row in view of its recipient -- ever starts a fetch; the logo
 * endpoint serves the cache and never fetches, so no caller
 * can make the server reach a domain of its choosing.
 * <p>
 * <b>A logo URL is its reader's.</b> It carries a token binding the domain to the user
 * it was offered to (an HMAC under one key for the whole platform, drawn on first use
 * and kept as a global setting, so that every node of a cluster checks the same token),
 * and the endpoint serves it to that user only: whether this server holds a domain's
 * logo -- that is, whether someone here read genuine mail from it this week -- is never
 * answered to anyone else. A node that has not resolved the domain yet answers no logo,
 * and the avatar falls back to its other picture until that node resolves it.
 * <p>
 * The logo is fetched by the server, never by the browser, so a brand cannot learn that
 * its mail was opened, nor by whom. An administrator turns the whole feature off
 * ({@code EmailConnectorService#isSenderLogosEnabled}): nothing is fetched, nothing is
 * offered, and senders keep their initials.
 */
@Service
public class SenderLogoService {

  /**
   * Where a domain's logo is served, the domain appended. The webapp keeps a copy,
   * {@code SENDER_LOGO_PATH} in {@code EmailConnectorSenderAvatars.js}, which tells a
   * brand logo from a person's photo so that the list shows a logo on verified rows
   * only: change both together.
   */
  public static final String LOGO_PATH      = "/email-connector/rest/email-box/sender-logo/";

  /** The query parameter carrying a logo URL's token. */
  public static final String TOKEN_PARAMETER = "t";

  /** Message code answered as a 400 for a logo asked for something that is not a domain name. */
  public static final String INVALID_DOMAIN = "emailConnector.senderLogo.invalidDomain";

  /** How long a "no logo" answer is believed, in ms: a day. */
  static final long          NONE_TTL_MS    = 24L * 60 * 60 * 1000;

  /** How many domains are resolved at once in the background. */
  static final int           WARM_THREADS   = 2;

  /** How many domains may wait for a background resolution; past it they are dropped. */
  static final int           WARM_QUEUE     = 100;

  /** The global setting holding the URL tokens' key, base64. */
  static final String         TOKEN_KEY_SETTING = "senderLogoTokenKey";

  private static final Log   LOG            = ExoLogger.getLogger(SenderLogoService.class);

  private static final String TOKEN_ALGORITHM = "HmacSHA256";

  /** The length of the URL tokens' key, in bytes. */
  private static final int    TOKEN_KEY_BYTES = 32;

  /** How many bytes of the HMAC a token keeps: 128 bits. */
  private static final int    TOKEN_BYTES     = 16;

  @Autowired
  private EmailConnectorService emailConnectorService;

  @Autowired
  private SenderLogoStorage     senderLogoStorage;

  @Autowired
  private SettingService        settingService;

  private final Set<String>     warming       = ConcurrentHashMap.newKeySet();

  private final ExecutorService warmPool      = warmPool();

  private final int             epoch         = new SecureRandom().nextInt();

  private final AtomicLong      resolutions   = new AtomicLong();

  private Executor              warmExecutor  = warmPool;

  /**
   * The URL of a sender's brand logo, for the avatar resolution to offer after the
   * platform profile picture and before the initials.
   * <p>
   * The URL when the feature is on, the message passed DMARC for the address's domain,
   * and the domain's logo is in the cache. A domain not resolved yet, or whose "no
   * logo" is older than a day, is resolved in the background and gets null this time;
   * null, too, for a domain known to have no logo, an address without a valid domain,
   * no DMARC pass, a free mail provider's domain, the feature off, or no mail server
   * named as trusted ({@code EmailSecurityUtils#TRUSTED_AUTHSERV_IDS_PROPERTY}: a pass
   * stored before it was unset is not believed either). Nothing is fetched on the
   * caller's thread.
   *
   * @param address the sender's address
   * @param dmarcPassed whether the message passed DMARC for the address's domain
   * @param username the user the URL is offered to, the only one it is served to
   * @return the logo's URL, or null for the initials
   */
  public String logoUrlFor(String address, boolean dmarcPassed, String username) {
    if (!dmarcPassed || StringUtils.isBlank(username) || !trustConfigured()) {
      return null;
    }
    String domain = brandDomainOf(address);
    if (domain == null || !emailConnectorService.isSenderLogosEnabled()) {
      return null;
    }
    SenderLogo known = senderLogoStorage.peek(domain);
    if (known != null && known.isPresent()) {
      return LOGO_PATH + domain + "?" + TOKEN_PARAMETER + "=" + token(domain, username);
    }
    if (known == null || isStale(known)) {
      warm(domain, known != null);
    }
    return null;
  }

  /**
   * Whether an address could be offered a logo at all -- a mail server named as
   * trusted for its sender checks, the feature on, a valid domain, not one known to
   * have none -- for a caller to skip the costlier checks of {@link #logoUrlFor} when it
   * could not.
   *
   * @param address the sender's address
   * @return false when no logo can be offered for it now
   */
  public boolean mayOffer(String address) {
    if (!trustConfigured()) {
      return false;
    }
    String domain = brandDomainOf(address);
    if (domain == null || !emailConnectorService.isSenderLogosEnabled()) {
      return false;
    }
    SenderLogo known = senderLogoStorage.peek(domain);
    return known == null || known.isPresent() || isStale(known);
  }

  /**
   * A domain's logo, from the cache only -- the endpoint never fetches -- for the user
   * its URL was offered to.
   *
   * @param domain the domain, as the logo URL names it
   * @param token the URL's token
   * @param username the user asking
   * @return the logo, or null when the token is not this user's for this domain, it is
   *         not cached, the domain has none, or the feature is off
   * @throws IllegalArgumentException {@link #INVALID_DOMAIN} when it is not a domain
   *           name a mail could come from
   */
  public SenderLogo getLogo(String domain, String token, String username) {
    String normalised = SenderLogoUtils.normaliseDomain(domain);
    if (normalised == null) {
      throw new IllegalArgumentException(INVALID_DOMAIN);
    }
    if (StringUtils.isBlank(token) || StringUtils.isBlank(username)
        || !MessageDigest.isEqual(token(normalised, username).getBytes(StandardCharsets.US_ASCII),
                                  token.getBytes(StandardCharsets.US_ASCII))) {
      return null;
    }
    if (!emailConnectorService.isSenderLogosEnabled()) {
      return null;
    }
    SenderLogo logo = senderLogoStorage.peek(normalised);
    return logo != null && logo.isPresent() ? logo : null;
  }

  /**
   * A value that changes whenever a logo offer could: at each server start (the cache
   * starts empty), when the switch or the trusted mail servers change, and each time a
   * domain's resolution finds a logo. The reader's cache validators fold it in, so a
   * copy cached before a logo was resolved, or carrying a URL a restart left without
   * its logo, is not confirmed as current.
   *
   * @return the fingerprint
   */
  public int offerFingerprint() {
    if (!trustConfigured()) {
      return 0;
    }
    return Objects.hash(epoch, emailConnectorService.isSenderLogosEnabled(), EmailSecurityUtils.trustedAuthservIds(), resolutions.get());
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
          if (!emailConnectorService.isSenderLogosEnabled()) {
            return;
          }
          if (evictFirst) {
            senderLogoStorage.evict(domain);
          }
          if (senderLogoStorage.getLogo(domain).isPresent()) {
            // Only a logo changes what a reader is offered; a "none" leaves it null.
            resolutions.incrementAndGet();
          }
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
   * Whether the deployment names the mail servers whose DMARC verdict is believed:
   * without one, no logo is ever offered, and nothing is looked up for one.
   *
   * @return true when at least one is named
   */
  private static boolean trustConfigured() {
    return !EmailSecurityUtils.trustedAuthservIds().isEmpty();
  }

  /**
   * The domain whose brand an address's mail may show: a valid domain that is not a
   * free mail provider's -- a person writing from gmail.com is not Google.
   *
   * @param address the sender's address
   * @return the normalised domain, or null when the address has no brand to show
   */
  private static String brandDomainOf(String address) {
    String domain = SenderLogoUtils.domainOfAddress(address);
    return domain == null || EmailContactUtils.isFreemailDomain(domain) ? null : domain;
  }

  /**
   * The token binding a domain's logo URL to a user: the first {@link #TOKEN_BYTES}
   * bytes of an HMAC of both, URL-safe.
   *
   * @param domain the normalised domain
   * @param username the user
   * @return the token
   */
  String token(String domain, String username) {
    try {
      Mac mac = Mac.getInstance(TOKEN_ALGORITHM);
      mac.init(new SecretKeySpec(tokenKey(), TOKEN_ALGORITHM));
      byte[] digest = mac.doFinal((domain + "\n" + username).getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(digest, TOKEN_BYTES));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("No " + TOKEN_ALGORITHM + " on this JVM", e);
    }
  }

  /**
   * The key of the URL tokens, one for the whole platform: read from the global
   * setting {@value #TOKEN_KEY_SETTING}, and drawn and stored there by the first node
   * that finds none. It is read again on every use, never kept in memory, so that two
   * nodes drawing one at the same moment converge on the one stored last.
   *
   * @return the key's bytes
   */
  private byte[] tokenKey() {
    byte[] key = storedTokenKey();
    if (key == null) {
      byte[] drawn = new byte[TOKEN_KEY_BYTES];
      new SecureRandom().nextBytes(drawn);
      settingService.set(Context.GLOBAL,
                         EmailConnectorService.EMAIL_CONNECTOR_SCOPE,
                         TOKEN_KEY_SETTING,
                         SettingValue.create(Base64.getEncoder().encodeToString(drawn)));
      key = storedTokenKey();
    }
    return key == null ? new byte[TOKEN_KEY_BYTES] : key;
  }

  /**
   * The key stored in the global setting.
   *
   * @return its bytes, or null when none is stored or it cannot be read
   */
  private byte[] storedTokenKey() {
    SettingValue<?> value = settingService.get(Context.GLOBAL, EmailConnectorService.EMAIL_CONNECTOR_SCOPE, TOKEN_KEY_SETTING);
    if (value == null || value.getValue() == null) {
      return null; // NOSONAR null is "none stored"
    }
    try {
      byte[] key = Base64.getDecoder().decode(value.getValue().toString());
      return key.length == TOKEN_KEY_BYTES ? key : null;
    } catch (IllegalArgumentException e) {
      return null; // NOSONAR an unreadable key is drawn anew
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
