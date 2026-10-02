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
package org.exoplatform.emailConnector.storage;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import org.exoplatform.emailConnector.model.SenderLogo;
import org.exoplatform.emailConnector.senderlogo.SenderLogoFetcher;

/**
 * The sender brand logos (EXO-90893), per domain, in the platform cache
 * ({@value #CACHE_NAME}): a domain's logo is fetched once -- concurrent loads of it
 * wait for that one fetch (single-flight) -- and served from the cache until it
 * expires. The service loads on its background pool only, and the endpoint only
 * {@link #peek}s.
 * <p>
 * "No logo" is cached too, as {@link SenderLogo#none}: the platform's cache adapter
 * never keeps a null, so an absent answer returned as null would be fetched again on
 * every request. The cache's time to live, a week, is the positive answer's; the
 * service evicts a "none" older than a day, so a brand that publishes a logo shows up
 * within a day. Both are tunable per deployment: {@code meeds.cache.<name>.ttl} and
 * {@code .max} for the cache, as for every Spring cache of the platform.
 * <p>
 * The cache is local to each server; the logos are public images, and a node that has
 * not resolved one yet resolves it once for itself, at the next read it serves.
 */
@Component
public class SenderLogoStorage {

  /** The platform cache holding the logos. */
  public static final String CACHE_NAME = "emailConnector.senderLogo";

  @Autowired
  private SenderLogoFetcher  senderLogoFetcher;

  @Autowired
  private CacheManager       cacheManager;

  /**
   * A domain's logo, from the cache, else fetched and cached.
   *
   * @param domain a normalised domain
   * @return the logo, or the "none" answer; never null
   */
  @Cacheable(cacheNames = CACHE_NAME, key = "#domain", sync = true)
  public SenderLogo getLogo(String domain) {
    return senderLogoFetcher.resolve(domain);
  }

  /**
   * A domain's cached answer, without fetching anything when there is none: what the
   * reader asks before it offers a logo URL.
   *
   * @param domain a normalised domain
   * @return the cached answer, or null when the domain was not resolved yet (or its
   *         answer expired)
   */
  public SenderLogo peek(String domain) {
    Cache cache = cacheManager.getCache(CACHE_NAME);
    return cache == null ? null : cache.get(domain, SenderLogo.class);
  }

  /**
   * Forgets a domain's cached answer, so that the next request fetches it again.
   *
   * @param domain a normalised domain
   */
  @CacheEvict(cacheNames = CACHE_NAME, key = "#domain")
  public void evict(String domain) {
    // The annotation does the work.
  }
}
