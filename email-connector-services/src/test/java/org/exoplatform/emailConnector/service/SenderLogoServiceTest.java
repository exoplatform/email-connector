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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.emailConnector.model.SenderLogo;
import org.exoplatform.emailConnector.storage.SenderLogoStorage;
import org.exoplatform.emailConnector.utils.EmailSecurityUtils;

/**
 * When a sender brand logo is offered and served (EXO-90893): DMARC, the
 * administration switch, the domain's validity, the day a "no logo" answer is
 * believed -- and that no caller's thread ever fetches: an unknown domain is resolved
 * in the background, and the endpoint serves the cache only.
 */
@ExtendWith(MockitoExtension.class)
class SenderLogoServiceTest {

  private static final String   USER = "rita";

  private static final SenderLogo LOGO = new SenderLogo(new byte[] { 1 }, "image/png", SenderLogo.SOURCE_ICON, 1L);

  @Mock
  private EmailConnectorService emailConnectorService;

  @Mock
  private SenderLogoStorage     senderLogoStorage;

  @Mock
  private SettingService        settingService;

  /** The global settings, as the platform would keep them for every node. */
  private final Map<String, String> settings = new HashMap<>();

  @InjectMocks
  private SenderLogoService     service;

  private final List<Runnable>  queued = new ArrayList<>();

  /**
   * Holds the background resolutions so that each test runs them when it wants, and
   * names a trusted mail server, without which nothing is ever offered.
   */
  @BeforeEach
  void holdBackgroundWork() {
    service.setWarmExecutor(queued::add);
    keepSettingsIn(settingService);
    System.setProperty(EmailSecurityUtils.TRUSTED_AUTHSERV_IDS_PROPERTY, "mx.example.com");
  }

  /**
   * Forgets the trusted mail server.
   */
  @AfterEach
  void forgetTrust() {
    System.clearProperty(EmailSecurityUtils.TRUSTED_AUTHSERV_IDS_PROPERTY);
  }

  /**
   * With no mail server named as trusted, nothing is offered and nothing is looked up,
   * even for a pass stored before the property was unset.
   */
  @Test
  void nothingWithoutATrustedMailServer() {
    System.clearProperty(EmailSecurityUtils.TRUSTED_AUTHSERV_IDS_PROPERTY);
    assertNull(service.logoUrlFor("news@brand.example", true, USER));
    assertEquals(false, service.mayOffer("news@brand.example"));
    verify(senderLogoStorage, never()).peek(anyString());
    verify(emailConnectorService, never()).isSenderLogosEnabled();
    assertEquals(List.of(), queued);
  }

  /**
   * A cached logo is offered for a message that passed DMARC, its domain normalised;
   * nothing is fetched to offer it.
   */
  @Test
  void aCachedLogoIsOfferedOnADmarcPass() {
    when(emailConnectorService.isSenderLogosEnabled()).thenReturn(true);
    when(senderLogoStorage.peek("brand.example")).thenReturn(LOGO);
    assertEquals(SenderLogoService.LOGO_PATH + "brand.example?t=" + service.token("brand.example", USER),
                 service.logoUrlFor("News@Brand.Example", true, USER));
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
    assertNull(service.logoUrlFor("news@brand.example", true, USER));
    assertNull(service.logoUrlFor("info@brand.example", true, USER));
    verify(senderLogoStorage, never()).getLogo(anyString());
    assertEquals(1, queued.size(), "one resolution per domain at a time");

    when(senderLogoStorage.getLogo("brand.example")).thenReturn(SenderLogo.none(System.currentTimeMillis()));
    queued.remove(0).run();
    verify(senderLogoStorage).getLogo("brand.example");
    verify(senderLogoStorage, never()).evict(anyString());
    assertNull(service.logoUrlFor("news@brand.example", true, USER));
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
    assertNull(service.logoUrlFor("news@brand.example", true, USER));
    assertEquals(List.of(), queued);

    when(senderLogoStorage.peek("brand.example")).thenReturn(SenderLogo.none(System.currentTimeMillis() - SenderLogoService.NONE_TTL_MS));
    assertNull(service.logoUrlFor("news@brand.example", true, USER));
    when(senderLogoStorage.getLogo("brand.example")).thenReturn(SenderLogo.none(System.currentTimeMillis()));
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
    assertNull(service.logoUrlFor("news@brand.example", false, USER));
    verify(emailConnectorService, never()).isSenderLogosEnabled();

    when(emailConnectorService.isSenderLogosEnabled()).thenReturn(false);
    assertNull(service.logoUrlFor("news@brand.example", true, USER), "switched off");

    assertNull(service.logoUrlFor("news@127.0.0.1", true, USER));
    assertNull(service.logoUrlFor("nobody", true, USER));
    assertNull(service.logoUrlFor(null, true, USER));
    assertEquals(List.of(), queued);
    verify(senderLogoStorage, never()).peek(anyString());
  }

  /**
   * The endpoint's read serves the cache only -- a cached logo, else nothing, never a
   * fetch -- to the user its URL was offered to: another user, a forged or missing
   * token, or another domain's token gets nothing; nothing at all with the feature off;
   * a path that is no domain is a 400's message code, checked before anything else.
   */
  @Test
  void theEndpointServesTheCacheToItsReaderOnly() {
    when(emailConnectorService.isSenderLogosEnabled()).thenReturn(true);
    when(senderLogoStorage.peek("brand.example")).thenReturn(LOGO);
    String token = service.token("brand.example", USER);
    assertSame(LOGO, service.getLogo("Brand.Example", token, USER));
    assertNull(service.getLogo("brand.example", token, "mallory"), "another user");
    assertNull(service.getLogo("brand.example", service.token("other.example", USER), USER), "another domain's token");
    assertNull(service.getLogo("brand.example", "forged", USER));
    assertNull(service.getLogo("brand.example", null, USER));
    assertNull(service.getLogo("brand.example", token, null));
    when(senderLogoStorage.peek("plain.example")).thenReturn(SenderLogo.none(1L));
    assertNull(service.getLogo("plain.example", service.token("plain.example", USER), USER));
    assertNull(service.getLogo("unknown.example", service.token("unknown.example", USER), USER));

    when(emailConnectorService.isSenderLogosEnabled()).thenReturn(false);
    assertNull(service.getLogo("brand.example", token, USER));
    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                                                    () -> service.getLogo("169.254.169.254", token, USER));
    assertEquals(SenderLogoService.INVALID_DOMAIN, refused.getMessage());
    assertThrows(IllegalArgumentException.class, () -> service.getLogo("localhost", token, USER));

    verify(senderLogoStorage, never()).getLogo(anyString());
    verify(senderLogoStorage, never()).evict(anyString());
    assertEquals(List.of(), queued);
  }

  /**
   * A free mail provider is no brand: its senders get no logo, and nothing is fetched.
   * {@code mayOffer} answers what {@code logoUrlFor} could offer without asking more.
   */
  @Test
  void aFreeMailProviderIsNoBrand() {
    when(emailConnectorService.isSenderLogosEnabled()).thenReturn(true);
    assertNull(service.logoUrlFor("someone@gmail.com", true, USER));
    assertEquals(false, service.mayOffer("someone@gmail.com"));
    assertEquals(List.of(), queued);

    assertEquals(true, service.mayOffer("news@brand.example"), "unknown yet");
    when(senderLogoStorage.peek("brand.example")).thenReturn(SenderLogo.none(System.currentTimeMillis()));
    assertEquals(false, service.mayOffer("news@brand.example"), "known to have none");
    when(senderLogoStorage.peek("brand.example")).thenReturn(LOGO);
    assertEquals(true, service.mayOffer("news@brand.example"));
    assertNull(service.logoUrlFor("news@brand.example", true, " "), "no user, no URL");
    when(emailConnectorService.isSenderLogosEnabled()).thenReturn(false);
    assertEquals(false, service.mayOffer("news@brand.example"));
  }

  /**
   * A domain queued before the administrator switched the feature off is not fetched
   * after it.
   */
  @Test
  void aQueuedDomainIsNotFetchedOnceSwitchedOff() {
    when(emailConnectorService.isSenderLogosEnabled()).thenReturn(true);
    assertNull(service.logoUrlFor("news@brand.example", true, USER));
    when(emailConnectorService.isSenderLogosEnabled()).thenReturn(false);
    queued.remove(0).run();
    verify(senderLogoStorage, never()).getLogo(anyString());
    when(emailConnectorService.isSenderLogosEnabled()).thenReturn(true);
    assertNull(service.logoUrlFor("news@brand.example", true, USER));
    assertEquals(1, queued.size(), "the domain was released");
  }

  /**
   * The reader's validators change when a resolution finds a logo and with the switch,
   * so a copy cached before the logo was resolved, or with a stale URL, is not
   * confirmed; nothing changes it while no mail server is trusted.
   */
  @Test
  void theOfferFingerprintFollowsResolutionsAndTheSwitch() {
    when(emailConnectorService.isSenderLogosEnabled()).thenReturn(true);
    int before = service.offerFingerprint();
    assertEquals(before, service.offerFingerprint(), "stable while nothing happens");
    when(senderLogoStorage.getLogo("plain.example")).thenReturn(SenderLogo.none(1L));
    assertNull(service.logoUrlFor("info@plain.example", true, USER));
    queued.remove(0).run();
    assertEquals(before, service.offerFingerprint(), "a domain found to have none changes no offer");
    when(senderLogoStorage.getLogo("brand.example")).thenReturn(LOGO);
    assertNull(service.logoUrlFor("news@brand.example", true, USER));
    queued.remove(0).run();
    int resolved = service.offerFingerprint();
    assertNotEquals(before, resolved, "a logo was found");
    when(emailConnectorService.isSenderLogosEnabled()).thenReturn(false);
    assertNotEquals(resolved, service.offerFingerprint(), "the switch");
    System.clearProperty(EmailSecurityUtils.TRUSTED_AUTHSERV_IDS_PROPERTY);
    assertEquals(0, service.offerFingerprint());
  }

  /**
   * The URL tokens' key is one for the platform: drawn by the first node that needs it,
   * stored as a global setting, and read by every other node -- a URL offered by one
   * node is served by another. An unreadable stored key is drawn anew.
   *
   * @throws Exception when the second instance cannot be built
   */
  @Test
  void theTokenKeyIsSharedByEveryNode() throws Exception {
    String token = service.token("brand.example", USER);
    String stored = settings.get(SenderLogoService.TOKEN_KEY_SETTING);
    assertEquals(32, Base64.getDecoder().decode(stored).length, "drawn once, stored");

    SenderLogoService otherNode = new SenderLogoService();
    SettingService otherSettings = mock(SettingService.class);
    keepSettingsIn(otherSettings);
    java.lang.reflect.Field field = SenderLogoService.class.getDeclaredField("settingService");
    field.setAccessible(true);
    field.set(otherNode, otherSettings);
    try {
      assertEquals(token, otherNode.token("brand.example", USER), "the other node checks the same token");
      assertEquals(stored, settings.get(SenderLogoService.TOKEN_KEY_SETTING), "and draws no key of its own");
    } finally {
      otherNode.stop();
    }

    settings.put(SenderLogoService.TOKEN_KEY_SETTING, "not base64 !");
    String redrawn = service.token("brand.example", USER);
    assertNotEquals(token, redrawn);
    assertEquals(redrawn, service.token("brand.example", USER), "the new key is kept");
  }

  /**
   * Keeps a setting service's global values in {@link #settings}, shared by every
   * instance given it, as the platform's are by every node.
   *
   * @param service the mocked setting service
   */
  private void keepSettingsIn(SettingService service) {
    lenient().when(service.get(eq(Context.GLOBAL), eq(EmailConnectorService.EMAIL_CONNECTOR_SCOPE), anyString()))
             .thenAnswer(invocation -> {
               String value = settings.get((String) invocation.getArgument(2));
               return value == null ? null : SettingValue.create(value);
             });
    lenient().doAnswer(invocation -> {
      settings.put(invocation.getArgument(2), String.valueOf(((SettingValue<?>) invocation.getArgument(3)).getValue()));
      return null;
    }).when(service).set(eq(Context.GLOBAL), eq(EmailConnectorService.EMAIL_CONNECTOR_SCOPE), anyString(), any());
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
    assertNull(service.logoUrlFor("news@brand.example", true, USER));
    service.setWarmExecutor(queued::add);
    assertNull(service.logoUrlFor("news@brand.example", true, USER));
    assertEquals(1, queued.size(), "the dropped domain was not left marked as resolving");
  }
}
