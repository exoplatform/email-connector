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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import org.exoplatform.emailConnector.model.SenderLogo;
import org.exoplatform.emailConnector.senderlogo.SenderLogoFetcher;

/**
 * The sender logo cache (EXO-90893) against a real cache manager, not a mock: a key
 * drift would make every lookup a fetch and every eviction a no-op, which only a real
 * cache shows.
 */
@SpringJUnitConfig(SenderLogoStorageTest.CacheConfiguration.class)
class SenderLogoStorageTest {

  @MockitoBean
  private SenderLogoFetcher senderLogoFetcher;

  @Autowired
  private SenderLogoStorage senderLogoStorage;

  @Autowired
  private CacheManager      cacheManager;

  /**
   * A cache manager and the storage, nothing else.
   */
  @Configuration
  @EnableCaching
  static class CacheConfiguration {

    /**
     * An in-memory cache manager standing for the platform's.
     *
     * @return the manager
     */
    @Bean
    CacheManager cacheManager() {
      return new ConcurrentMapCacheManager();
    }

    /**
     * The storage under test.
     *
     * @return the storage
     */
    @Bean
    SenderLogoStorage senderLogoStorage() {
      return new SenderLogoStorage();
    }
  }

  /**
   * Empties the cache between scenarios.
   */
  @BeforeEach
  void clearCache() {
    cacheManager.getCache(SenderLogoStorage.CACHE_NAME).clear();
  }

  /**
   * A domain is fetched once, then served from the cache, per domain.
   */
  @Test
  void aLogoIsFetchedOncePerDomain() {
    SenderLogo logo = new SenderLogo(new byte[] { 1 }, "image/png", SenderLogo.SOURCE_ICON, 1L);
    when(senderLogoFetcher.resolve("brand.example")).thenReturn(logo);
    when(senderLogoFetcher.resolve("other.example")).thenReturn(SenderLogo.none(1L));

    assertSame(logo, senderLogoStorage.getLogo("brand.example"));
    assertSame(logo, senderLogoStorage.getLogo("brand.example"));
    senderLogoStorage.getLogo("other.example");

    verify(senderLogoFetcher, times(1)).resolve("brand.example");
    verify(senderLogoFetcher, times(1)).resolve("other.example");
  }

  /**
   * "No logo" is cached too: a domain without one is not fetched on every request.
   */
  @Test
  void noLogoIsCachedToo() {
    when(senderLogoFetcher.resolve("plain.example")).thenReturn(SenderLogo.none(1L));

    assertFalse(senderLogoStorage.getLogo("plain.example").isPresent());
    assertFalse(senderLogoStorage.getLogo("plain.example").isPresent());

    verify(senderLogoFetcher, times(1)).resolve("plain.example");
  }

  /**
   * A peek reads the cache without fetching; an eviction makes the next request fetch
   * again.
   */
  @Test
  void peekReadsAndEvictionForgets() {
    SenderLogo none = SenderLogo.none(1L);
    when(senderLogoFetcher.resolve("brand.example")).thenReturn(none);

    assertNull(senderLogoStorage.peek("brand.example"));
    senderLogoStorage.getLogo("brand.example");
    assertEquals(none, senderLogoStorage.peek("brand.example"));

    clearInvocations(senderLogoFetcher);
    senderLogoStorage.evict("brand.example");
    assertNull(senderLogoStorage.peek("brand.example"));
    senderLogoStorage.getLogo("brand.example");
    verify(senderLogoFetcher, times(1)).resolve("brand.example");
  }
}
