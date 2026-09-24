/**
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.mail.Authenticator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.services.connector.credentials.ConnectorCredentialsChannel;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsException;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsContext;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsService;
import org.exoplatform.services.connector.credentials.HttpConnectorCredentials;
import org.exoplatform.services.connector.credentials.MailConnectorCredentials;

import lombok.SneakyThrows;

/**
 * What the resolver puts in the context it asks every question in.
 * <p>
 * These are the only tests that can see it. A caller mocking the resolver observes
 * the arguments it passed, never the channel the resolver chose for it — and for
 * CardDAV the channel is not even a parameter, it is the resolver's own decision.
 * Swapping HTTP for IMAP there resolved the wrong material with every caller-side
 * test still green, which is why this class exists.
 */
@ExtendWith(MockitoExtension.class)
public class EmailCredentialsResolverTest {

  private static final Long              CONNECTOR_ID  = 42L;

  private static final String            PROVIDER_NAME = "personal";

  private static final String            USERNAME      = "john";

  @Mock
  private ConnectorCredentialsService    connectorCredentialsService;

  @InjectMocks
  private EmailCredentialsResolver        resolver;

  @Test
  @SneakyThrows
  void aMailboxAsksOnTheImapChannel() {
    Authenticator produced = new Authenticator() {
    };
    when(connectorCredentialsService.produce(any())).thenReturn(new MailConnectorCredentials(ConnectorCredentialsChannel.IMAP,
                                                                                            produced,
                                                                                            null));

    Authenticator answered = resolver.authenticator(CONNECTOR_ID,
                                                    PROVIDER_NAME,
                                                    USERNAME,
                                                    ConnectorCredentialsChannel.IMAP);

    assertSame(produced, answered, "the authenticator handed back must be the produced one");
    assertContext(ConnectorCredentialsChannel.IMAP);
  }

  @Test
  @SneakyThrows
  void anAddressBookAsksOnTheHttpChannel() {
    // The channel the resolver decides on its own, and the only thing that tells an
    // address book conversation from a mailbox one: same connector row, same stored
    // setting, same connector kind — HTTP material instead of mail material.
    when(connectorCredentialsService.produce(any())).thenReturn(new HttpConnectorCredentials("Bearer produced", null));

    String answered = resolver.authorization(CONNECTOR_ID, PROVIDER_NAME, USERNAME);

    assertEquals("Bearer produced", answered, "the header handed back must be the produced one");
    assertContext(ConnectorCredentialsChannel.HTTP);
  }

  @Test
  @SneakyThrows
  void theAddressBookAccountAsksOnTheHttpChannelToo() {
    // Same channel as the header: naming whose address book it is and authenticating
    // the conversation are the same conversation, and a provider that serves this
    // connector's mail channels but not HTTP has no address book to name an account
    // in. Asked on SMTP instead — the sender address's channel, the nearest wrong
    // answer — a mail-only provider would name an account for a book it cannot talk
    // to, and the sync would fail one request later with an unrelated message.
    when(connectorCredentialsService.resolveTargetIdentity(any())).thenReturn("technical@dav.example");

    assertEquals("technical@dav.example", resolver.targetAccount(CONNECTOR_ID, PROVIDER_NAME, USERNAME));

    assertContext(capturedTargetIdentityContext(), ConnectorCredentialsChannel.HTTP);
  }

  @Test
  @SneakyThrows
  void theSenderAddressComesFromTheProviderNotFromTheSetting() {
    when(connectorCredentialsService.resolveTargetIdentity(any())).thenReturn("technical@dav.example");

    assertEquals("technical@dav.example", resolver.senderAddress(CONNECTOR_ID, PROVIDER_NAME, USERNAME));

    assertContext(capturedTargetIdentityContext(), ConnectorCredentialsChannel.SMTP);
  }

  /**
   * The four fields that decide which provider answers and with what material, on the
   * context that was actually handed to the resolution service.
   *
   * @param expectedChannel the channel this conversation must have asked on
   */
  @SneakyThrows
  private void assertContext(ConnectorCredentialsChannel expectedChannel) {
    ArgumentCaptor<ConnectorCredentialsContext> asked = ArgumentCaptor.forClass(ConnectorCredentialsContext.class);
    verify(connectorCredentialsService).produce(asked.capture());
    assertContext(asked.getValue(), expectedChannel);
  }

  /**
   * The context handed to the resolution service when the question was about the
   * target identity rather than about material.
   *
   * @return the captured context
   */
  @SneakyThrows
  private ConnectorCredentialsContext capturedTargetIdentityContext() {
    ArgumentCaptor<ConnectorCredentialsContext> asked = ArgumentCaptor.forClass(ConnectorCredentialsContext.class);
    verify(connectorCredentialsService).resolveTargetIdentity(asked.capture());
    return asked.getValue();
  }

  /**
   * The four fields that decide which provider answers and with what material.
   *
   * @param context the context that was actually handed to the resolution service
   * @param expectedChannel the channel this conversation must have asked on
   */
  private void assertContext(ConnectorCredentialsContext context, ConnectorCredentialsChannel expectedChannel) {
    assertEquals(expectedChannel, context.getChannel(), "the channel decides which material type comes back");
    // The kind stays "email" whatever the channel: the credentials come from this
    // connector's own stored setting, which is what the kind names. Spelled out
    // rather than read from the constant — asserting the constant against itself
    // is a tautology, and it let a renamed kind through when it was tried.
    assertEquals("email", context.getConnectorKind());
    assertEquals(CONNECTOR_ID, context.getConnectorId());
    assertEquals(PROVIDER_NAME, context.getConnectorCredentialsProviderName());
    assertEquals(USERNAME, context.getUsername());
  }


  /**
   * Whether a connector asks its user for anything is a property of its provider,
   * and the people who need the answer are the users themselves - the connect
   * drawer has to know whether to show a form. They cannot read the provider
   * registry, whose endpoint is reserved to administrators, so the single bit they
   * are entitled to is relayed here.
   */
  @Test
  public void tellsWhetherTheConfiguredProviderAsksTheUserForAnything() throws Exception {
    when(connectorCredentialsService.requiresUserAction("personal")).thenReturn(true);
    when(connectorCredentialsService.requiresUserAction("bluemind-sudo")).thenReturn(false);

    assertTrue(resolver.requiresUserAction("personal"));
    assertFalse(resolver.requiresUserAction("bluemind-sudo"));
  }

  /**
   * A preset naming no provider - every row predating the registry - asks. Silence
   * must never become a silent connection.
   */
  @Test
  public void asksTheUserWhenNoProviderIsNamed() throws Exception {
    assertTrue(resolver.requiresUserAction(null));
    assertTrue(resolver.requiresUserAction("  "));
    verify(connectorCredentialsService, never()).requiresUserAction(any());
  }

  /** An unknown provider is refused, exactly as every other resolution is. */
  @Test
  public void refusesToAnswerForAnUnknownProvider() throws Exception {
    when(connectorCredentialsService.requiresUserAction("nobody"))
        .thenThrow(new ConnectorCredentialsException("no such provider"));

    assertThrows(ConnectorCredentialsException.class, () -> resolver.requiresUserAction("nobody"));
  }

  /** EXO-89649. Only the server refusing the credentials counts, wherever JavaMail put it. */
  @Test
  void recognisesAnAuthenticationFailureOnEitherChain() {
    javax.mail.AuthenticationFailedException refused = new javax.mail.AuthenticationFailedException("AUTHENTICATIONFAILED");
    javax.mail.MessagingException wrappedAsNext = new javax.mail.SendFailedException("Sending failed", refused);
    RuntimeException wrappedAsCause = new IllegalStateException("Error when connecting store", refused);

    org.junit.jupiter.api.Assertions.assertTrue(EmailCredentialsResolver.isAuthenticationFailure(refused));
    org.junit.jupiter.api.Assertions.assertTrue(EmailCredentialsResolver.isAuthenticationFailure(wrappedAsNext));
    org.junit.jupiter.api.Assertions.assertTrue(EmailCredentialsResolver.isAuthenticationFailure(wrappedAsCause));
    org.junit.jupiter.api.Assertions.assertFalse(EmailCredentialsResolver.isAuthenticationFailure(new javax.mail.MessagingException("Connection timed out")));
    org.junit.jupiter.api.Assertions.assertFalse(EmailCredentialsResolver.isAuthenticationFailure(null));
  }

  /** EXO-89649. The invalidation reaches the provider with the very context production used. */
  @Test
  void invalidatesOnTheChannelTheMaterialWasRefusedOn() {
    resolver.invalidate(CONNECTOR_ID, PROVIDER_NAME, USERNAME, ConnectorCredentialsChannel.SMTP);

    org.mockito.ArgumentCaptor<org.exoplatform.services.connector.credentials.ConnectorCredentialsContext> context =
        org.mockito.ArgumentCaptor.forClass(org.exoplatform.services.connector.credentials.ConnectorCredentialsContext.class);
    org.mockito.Mockito.verify(connectorCredentialsService).invalidate(context.capture());
    assertEquals(CONNECTOR_ID, context.getValue().getConnectorId());
    assertEquals(PROVIDER_NAME, context.getValue().getConnectorCredentialsProviderName());
    assertEquals(USERNAME, context.getValue().getUsername());
    assertEquals(ConnectorCredentialsChannel.SMTP, context.getValue().getChannel());
  }

  /** EXO-89649. A retry is worth it only for a provider that produces its material itself. */
  @Test
  @SneakyThrows
  void retriesOnlyForAProviderThatProducesItsOwnMaterial() {
    when(connectorCredentialsService.requiresUserAction("bluemind-sudo")).thenReturn(false);
    when(connectorCredentialsService.requiresUserAction("personal")).thenReturn(true);
    when(connectorCredentialsService.requiresUserAction("unknown")).thenThrow(new org.exoplatform.services.connector.credentials.ConnectorCredentialsException("none"));

    org.junit.jupiter.api.Assertions.assertTrue(resolver.retriesAfterRefusal("bluemind-sudo"));
    org.junit.jupiter.api.Assertions.assertFalse(resolver.retriesAfterRefusal("personal"));
    org.junit.jupiter.api.Assertions.assertFalse(resolver.retriesAfterRefusal("unknown"));
  }

  /** An invalidation that fails is swallowed - its callers run it inside failure handling. */
  @Test
  void anInvalidationThatFailsNeverThrows() {
    org.mockito.Mockito.doThrow(new IllegalStateException("provider broke")).when(connectorCredentialsService).invalidate(any());

    org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> resolver.invalidate(CONNECTOR_ID, PROVIDER_NAME, USERNAME, ConnectorCredentialsChannel.SMTP));
  }
}
