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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.emailConnector.model.SenderLogo;
import org.exoplatform.emailConnector.storage.SenderLogoStorage;

/**
 * When a sender brand logo is offered and served (EXO-90893): DMARC, the
 * administration switch, the domain's validity, the day a "no logo" answer is
 * believed -- and that no caller's thread ever fetches: an unknown domain is resolved
 * in the background, and the endpoint serves the cache only.
 */
@ExtendWith(MockitoExtension.class)
class SenderLogoServiceTest {

  private static final String   URL  = SenderLogoService.LOGO_PATH + "brand.example";

  private static final SenderLogo LOGO = new SenderLogo(new byte[] { 1 }, "image/png", SenderLogo.SOURCE_ICON, 1L);

  @Mock
  private EmailConnectorService emailConnectorService;

  @Mock
  private SenderLogoStorage     senderLogoStorage;

  @InjectMocks
  private SenderLogoService     service;

  private final List<Runnable>  queued = new ArrayList<>();

  /**
   * Holds the background resolutions so that each test runs them when it wants.
   */
  @BeforeEach
  void holdBackgroundWork() {
    service.setWarmExecutor(queued::add);
  }

  /**
   * A cached logo is offered for a message that passed DMARC, its domain normalised;
   * nothing is fetched to offer it.
   */
  @Test
  void aCachedLogoIsOfferedOnADmarcPass() {
    when(emailConnectorService.isSenderLogosEnabled()).thenReturn(true);
    when(senderLogoStorage.peek("brand.example")).thenReturn(LOGO);
    assertEquals(URL, service.logoUrlFor("News@Brand.Example", true));
    assertEquals(List.of(), queued);
    verify(senderLogoStorage, never()).getLogo(anyString());
  }

  /**
   * An unknown domain gets no URL this time and is resolved in the background, once
   * however often it is asked meanwhile; once cached, its URL is offered.
   */
  @Test
  void anUnknownDomainIsResolvedInTheBackground() {
    when(emailConnectorService.isSenderLogosEnabled()).thenReturn(true);
    assertNull(service.logoUrlFor("news@brand.example", true));
    assertNull(service.logoUrlFor("info@brand.example", true));
    verify(senderLogoStorage, never()).getLogo(anyString());
    assertEquals(1, queued.size(), "one resolution per domain at a time");

    queued.remove(0).run();
    verify(senderLogoStorage).getLogo("brand.example");
    verify(senderLogoStorage, never()).evict(anyString());
    assertNull(service.logoUrlFor("news@brand.example", true));
    assertEquals(1, queued.size(), "a domain may be resolved again once its resolution ended");
  }

  /**
   * A domain known to have no logo gets no URL and no fetch for a day; after that its
   * answer is forgotten and resolved again, in the background.
   */
  @Test
  void aKnownNoneIsBelievedForADay() {
    when(emailConnectorService.isSenderLogosEnabled()).thenReturn(true);
    when(senderLogoStorage.peek("brand.example")).thenReturn(SenderLogo.none(System.currentTimeMillis()));
    assertNull(service.logoUrlFor("news@brand.example", true));
    assertEquals(List.of(), queued);

    when(senderLogoStorage.peek("brand.example")).thenReturn(SenderLogo.none(System.currentTimeMillis() - SenderLogoService.NONE_TTL_MS));
    assertNull(service.logoUrlFor("news@brand.example", true));
    queued.remove(0).run();
    InOrder order = inOrder(senderLogoStorage);
    order.verify(senderLogoStorage).evict("brand.example");
    order.verify(senderLogoStorage).getLogo("brand.example");
  }

  /**
   * No URL and no resolution without a DMARC pass, with the feature off, or for an
   * address without a valid domain.
   */
  @Test
  void nothingWithoutAPassTheSwitchOrADomain() {
    assertNull(service.logoUrlFor("news@brand.example", false));
    verify(emailConnectorService, never()).isSenderLogosEnabled();

    when(emailConnectorService.isSenderLogosEnabled()).thenReturn(false);
    assertNull(service.logoUrlFor("news@brand.example", true), "switched off");

    assertNull(service.logoUrlFor("news@127.0.0.1", true));
    assertNull(service.logoUrlFor("nobody", true));
    assertNull(service.logoUrlFor(null, true));
    assertEquals(List.of(), queued);
    verify(senderLogoStorage, never()).peek(anyString());
  }

  /**
   * The endpoint's read serves the cache only: a cached logo, else nothing -- never a
   * fetch, whatever the domain -- and nothing at all with the feature off; a path that
   * is no domain is a 400's message code, checked before anything else.
   */
  @Test
  void theEndpointServesTheCacheOnly() {
    when(emailConnectorService.isSenderLogosEnabled()).thenReturn(true);
    when(senderLogoStorage.peek("brand.example")).thenReturn(LOGO);
    assertSame(LOGO, service.getLogo("Brand.Example"));
    when(senderLogoStorage.peek("plain.example")).thenReturn(SenderLogo.none(1L));
    assertNull(service.getLogo("plain.example"));
    assertNull(service.getLogo("unknown.example"));

    when(emailConnectorService.isSenderLogosEnabled()).thenReturn(false);
    assertNull(service.getLogo("brand.example"));
    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> service.getLogo("169.254.169.254"));
    assertEquals(SenderLogoService.INVALID_DOMAIN, refused.getMessage());
    assertThrows(IllegalArgumentException.class, () -> service.getLogo("localhost"));

    verify(senderLogoStorage, never()).getLogo(anyString());
    verify(senderLogoStorage, never()).evict(anyString());
    assertEquals(List.of(), queued);
  }

  /**
   * A full background queue drops the domain, which is offered again at the next read.
   */
  @Test
  void aFullQueueDropsTheDomainForNow() {
    when(emailConnectorService.isSenderLogosEnabled()).thenReturn(true);
    service.setWarmExecutor(runnable -> {
      throw new java.util.concurrent.RejectedExecutionException("full");
    });
    assertNull(service.logoUrlFor("news@brand.example", true));
    service.setWarmExecutor(queued::add);
    assertNull(service.logoUrlFor("news@brand.example", true));
    assertEquals(1, queued.size(), "the dropped domain was not left marked as resolving");
  }
}
