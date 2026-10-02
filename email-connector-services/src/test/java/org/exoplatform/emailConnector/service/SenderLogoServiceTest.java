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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.emailConnector.model.SenderLogo;
import org.exoplatform.emailConnector.storage.SenderLogoStorage;

/**
 * When a sender brand logo is offered and served (EXO-90893): DMARC, the
 * administration switch, the domain's validity, and the day a "no logo" answer is
 * believed.
 */
@ExtendWith(MockitoExtension.class)
class SenderLogoServiceTest {

  private static final String   URL = SenderLogoService.LOGO_PATH + "brand.example";

  @Mock
  private EmailConnectorService emailConnectorService;

  @Mock
  private SenderLogoStorage     senderLogoStorage;

  @InjectMocks
  private SenderLogoService     service;

  /**
   * The URL is offered for a message that passed DMARC, its domain normalised, while
   * the domain is not known to have no logo; nothing is fetched to offer it.
   */
  @Test
  void theUrlIsOfferedOnADmarcPass() {
    when(emailConnectorService.isSenderLogosEnabled()).thenReturn(true);
    assertEquals(URL, service.logoUrlFor("News@Brand.Example", true));
    when(senderLogoStorage.peek("brand.example")).thenReturn(new SenderLogo(new byte[] { 1 }, "image/png", SenderLogo.SOURCE_ICON, 1L));
    assertEquals(URL, service.logoUrlFor("news@brand.example", true));
    verify(senderLogoStorage, never()).getLogo(anyString());
  }

  /**
   * No URL without a DMARC pass, with the feature off, for an address without a valid
   * domain, or for a domain known to have no logo since less than a day; a "no logo"
   * older than that is asked again.
   */
  @Test
  void noUrlWhenThereIsNothingToShow() {
    assertNull(service.logoUrlFor("news@brand.example", false));
    verify(emailConnectorService, never()).isSenderLogosEnabled();

    when(emailConnectorService.isSenderLogosEnabled()).thenReturn(false);
    assertNull(service.logoUrlFor("news@brand.example", true), "switched off");

    when(emailConnectorService.isSenderLogosEnabled()).thenReturn(true);
    assertNull(service.logoUrlFor("news@127.0.0.1", true));
    assertNull(service.logoUrlFor("nobody", true));
    assertNull(service.logoUrlFor(null, true));

    when(senderLogoStorage.peek("brand.example")).thenReturn(SenderLogo.none(System.currentTimeMillis()));
    assertNull(service.logoUrlFor("news@brand.example", true), "known to have none");
    when(senderLogoStorage.peek("brand.example")).thenReturn(SenderLogo.none(System.currentTimeMillis() - SenderLogoService.NONE_TTL_MS));
    assertEquals(URL, service.logoUrlFor("news@brand.example", true), "a day-old none is asked again");
  }

  /**
   * The logo is served from the storage for a valid domain while the feature is on; a
   * "no logo" answer is null; a day-old one is evicted and fetched again.
   */
  @Test
  void theLogoIsServedFromTheStorage() {
    when(emailConnectorService.isSenderLogosEnabled()).thenReturn(true);
    SenderLogo logo = new SenderLogo(new byte[] { 1 }, "image/png", SenderLogo.SOURCE_ICON, 1L);
    when(senderLogoStorage.getLogo("brand.example")).thenReturn(logo);
    assertSame(logo, service.getLogo("Brand.Example"));

    when(senderLogoStorage.getLogo("plain.example")).thenReturn(SenderLogo.none(System.currentTimeMillis()));
    assertNull(service.getLogo("plain.example"));
    verify(senderLogoStorage, never()).evict("plain.example");

    when(senderLogoStorage.getLogo("stale.example")).thenReturn(SenderLogo.none(1L), logo);
    assertSame(logo, service.getLogo("stale.example"));
    verify(senderLogoStorage).evict("stale.example");
  }

  /**
   * Nothing is fetched with the feature off, and a path that is no domain is a 400's
   * message code, checked before anything else.
   */
  @Test
  void nothingIsFetchedWhenOffOrForANonDomain() {
    when(emailConnectorService.isSenderLogosEnabled()).thenReturn(false);
    assertNull(service.getLogo("brand.example"));
    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> service.getLogo("169.254.169.254"));
    assertEquals(SenderLogoService.INVALID_DOMAIN, refused.getMessage());
    assertThrows(IllegalArgumentException.class, () -> service.getLogo("localhost"));
    verify(senderLogoStorage, never()).getLogo(anyString());
  }
}
