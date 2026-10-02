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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.ws.frameworks.cometd.ContinuationService;

import io.meeds.social.util.JsonUtils;

/**
 * The sender logo pushes (EXO-90909): on the mailbox's channel, to the users named
 * only and only while connected, naming a domain or the user's own rows -- never a
 * logo URL -- in the frame shape the page dispatches.
 */
@ExtendWith(MockitoExtension.class)
class SenderLogoWebSocketServiceTest {

  @Mock
  private ContinuationService        continuationService;

  @InjectMocks
  private SenderLogoWebSocketService service;

  /**
   * A found logo is told to each connected user named, on the badge channel the
   * mailbox subscribes to, as {wsEventName, message: {domain}}; a user not connected
   * gets nothing, and nobody else is told.
   */
  @Test
  void aFoundLogoIsToldToTheConnectedUsersNamedOnly() {
    when(continuationService.isPresent("rita")).thenReturn(true);
    when(continuationService.isPresent("sam")).thenReturn(false);

    service.logoFound("brand.example", Set.of("rita", "sam"));

    ArgumentCaptor<String> frame = ArgumentCaptor.forClass(String.class);
    verify(continuationService).sendMessage(eq("rita"), eq("/eXo/Application/AppCenter/Badge"), frame.capture());
    verify(continuationService, never()).sendMessage(eq("sam"), anyString(), anyString());
    Map<?, ?> sent = JsonUtils.fromJsonString(frame.getValue(), Map.class);
    assertEquals(SenderLogoWebSocketService.LOGO_FOUND_EVENT, sent.get("wsEventName"));
    assertEquals(Map.of("domain", "brand.example"), sent.get("message"));
    assertFalse(frame.getValue().contains(SenderLogoService.LOGO_PATH), "never a URL");
  }

  /**
   * Rows that passed DMARC are told to their owner alone, with their ids and senders.
   */
  @Test
  void verifiedRowsAreToldToTheirOwner() {
    when(continuationService.isPresent("rita")).thenReturn(true);

    service.rowsVerified("rita", List.of(7L, 9L), List.of("news@brand.example"));

    ArgumentCaptor<String> frame = ArgumentCaptor.forClass(String.class);
    verify(continuationService).sendMessage(eq("rita"), eq(SenderLogoWebSocketService.COMETD_CHANNEL), frame.capture());
    Map<?, ?> sent = JsonUtils.fromJsonString(frame.getValue(), Map.class);
    assertEquals(SenderLogoWebSocketService.ROWS_VERIFIED_EVENT, sent.get("wsEventName"));
    Map<?, ?> message = (Map<?, ?>) sent.get("message");
    assertEquals(List.of(7, 9), message.get("ids"));
    assertEquals(List.of("news@brand.example"), message.get("addresses"));
  }

  /**
   * Nothing is sent for nothing to tell, and a transport failure is swallowed: the
   * page shows the logo at its next load.
   */
  @Test
  void nothingToTellOrATransportFailureIsQuiet() {
    service.logoFound("brand.example", Set.of());
    service.logoFound(" ", Set.of("rita"));
    service.rowsVerified("rita", List.of(), List.of());
    verifyNoInteractions(continuationService);

    when(continuationService.isPresent("rita")).thenReturn(true);
    doThrow(new IllegalStateException("not started")).when(continuationService).sendMessage(anyString(), anyString(), anyString());
    service.logoFound("brand.example", Set.of("rita"));
  }
}
