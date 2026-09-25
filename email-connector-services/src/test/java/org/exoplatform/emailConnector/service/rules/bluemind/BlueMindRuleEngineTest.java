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
package org.exoplatform.emailConnector.service.rules.bluemind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.mail.PasswordAuthentication;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import org.exoplatform.emailConnector.exception.ServerRuleUnavailableException;
import org.exoplatform.emailConnector.exception.ServerRuleUnsupportedException;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.ForwardingSetting;
import org.exoplatform.emailConnector.model.ForwardingState;
import org.exoplatform.emailConnector.model.ServerRuleCapabilities;
import org.exoplatform.emailConnector.model.ServerVacation;
import org.exoplatform.emailConnector.model.VacationSetting;
import org.exoplatform.emailConnector.model.VacationState;
import org.exoplatform.emailConnector.provider.EmailCredentialsResolver;
import org.exoplatform.emailConnector.service.acl.MailboxAclSession;
import org.exoplatform.emailConnector.service.bluemind.BlueMindEndpoint;
import org.exoplatform.emailConnector.service.bluemind.BlueMindForwarding;
import org.exoplatform.emailConnector.service.bluemind.BlueMindMailboxTransport;
import org.exoplatform.emailConnector.service.bluemind.BlueMindSession;
import org.exoplatform.emailConnector.service.bluemind.BlueMindTransportException.Kind;
import org.exoplatform.emailConnector.service.bluemind.BlueMindVacation;
import org.exoplatform.emailConnector.service.bluemind.FakeBlueMindTransport;
import org.exoplatform.emailConnector.service.bluemind.FakeBlueMindTransport.Call;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsChannel;

/**
 * The BlueMind engine through its port, over an in-memory BlueMind (see
 * {@link FakeBlueMindTransport} for what is captured and what is derived, to verify):
 * nothing without an implementation of the port, the probe, the adopted reply, the
 * instants of the window, the {@code _vacation}-only write, and the failures as codes.
 */
public class BlueMindRuleEngineTest {

  private static final long        CONNECTOR_ID = 92L;

  private static final String      CORE_URL     = "https://webmail.demo3.livecollab.fr/api";

  private static final String      LOGIN        = "anais.francois@demo3.livecollab.fr";

  private static final ZoneId      PARIS        = ZoneId.of("Europe/Paris");

  private FakeBlueMindTransport    bluemind;

  private EmailCredentialsResolver resolver;

  private BlueMindRuleEngine       engine;

  private MailboxAclSession        session;

  private final AtomicInteger      resolved     = new AtomicInteger();

  private final AtomicReference<PasswordAuthentication> credentials =
                                                                 new AtomicReference<>(new PasswordAuthentication(LOGIN, "secret"));

  /**
   * Points the preset's core API at the test account's, and builds the caller's session
   * with the account's login and password as its mail credentials, counting each
   * resolution.
   */
  @BeforeEach
  public void setUp() {
    System.setProperty(BlueMindEndpoint.CORE_URL_PROPERTY + "." + CONNECTOR_ID, CORE_URL);
    bluemind = new FakeBlueMindTransport();
    resolver = mock(EmailCredentialsResolver.class);
    engine = new BlueMindRuleEngine(bluemind, resolver);
    EmailConnector preset = new EmailConnector();
    preset.setId(CONNECTOR_ID);
    preset.setAuthProviderName("personal");
    preset.setImapUrl("imap.demo3.livecollab.fr");
    session = new MailboxAclSession(preset, "anais", LOGIN, null, null, () -> {
      resolved.incrementAndGet();
      return credentials.get();
    });
  }

  /**
   * Clears the property.
   */
  @AfterEach
  public void tearDown() {
    System.clearProperty(BlueMindEndpoint.CORE_URL_PROPERTY + "." + CONNECTOR_ID);
  }

  /**
   * The engine is the one a preset selects with {@code bluemind}.
   */
  @Test
  public void testTheName() {
    assertEquals("bluemind", engine.getName());
  }

  /**
   * Without an implementation of the port -- the product ships none until the
   * {@code bluemind-commons} adapter exists -- the probe answers unsupported with the
   * transport-missing reason, never "supported", reads nothing and refuses a write, and
   * no credential is resolved.
   *
   * @throws Exception on failure
   */
  @Test
  public void testWithoutATransportNothingIsSupported() throws Exception {
    BlueMindRuleEngine none = new BlueMindRuleEngine(null, resolver);
    ServerRuleCapabilities capabilities = none.probe(session);
    assertFalse(capabilities.supported());
    assertEquals(BlueMindRuleEngine.TRANSPORT_MISSING, capabilities.reasonCode());
    assertFalse(capabilities.isSupported(ServerRuleCapabilities.VACATION));
    assertEquals(VacationState.NONE, none.readVacation(session, PARIS).state());
    assertEquals(VacationState.NONE, none.readVacation(session).state());
    assertEquals(ServerRuleUnsupportedException.VACATION_UNSUPPORTED,
                 assertThrows(ServerRuleUnsupportedException.class,
                              () -> none.writeVacation(session, reply(true, null, null), 7, null)).getMessage());
    assertEquals(0, resolved.get());
    verify(resolver, never()).invalidate(any(), anyString(), anyString(), any());
  }

  /**
   * The transport is an optional bean: the Spring constructor takes none, and the field is
   * injected only when a bean exists.
   *
   * @throws Exception on failure
   */
  @Test
  public void testTheTransportIsAnOptionalBean() throws Exception {
    Field field = BlueMindRuleEngine.class.getDeclaredField("transport");
    assertFalse(field.getAnnotation(Autowired.class).required());
    assertEquals(BlueMindMailboxTransport.class, field.getType());
    assertTrue(BlueMindRuleEngine.class.getConstructor(EmailCredentialsResolver.class).isAnnotationPresent(Autowired.class));
    BlueMindRuleEngine springBuilt = new BlueMindRuleEngine(resolver);
    assertEquals(BlueMindRuleEngine.TRANSPORT_MISSING, springBuilt.probe(session).reasonCode());
  }

  /**
   * The probe logs in at the configured core API with the session's login, reads the
   * reply, and closes the session: a plain-text reply with a window, read whoever set it,
   * the forward read only; no HTML, no forward write, no rules.
   *
   * @throws Exception on failure
   */
  @Test
  public void testTheProbe() throws Exception {
    ServerRuleCapabilities capabilities = engine.probe(session);
    assertTrue(capabilities.supported());
    assertTrue(capabilities.isSupported(ServerRuleCapabilities.VACATION));
    assertTrue(capabilities.isSupported(ServerRuleCapabilities.VACATION_DATE_WINDOW));
    assertTrue(capabilities.isSupported(ServerRuleCapabilities.READS_FOREIGN_VACATION));
    assertTrue(capabilities.isSupported(ServerRuleCapabilities.FORWARDING_READ));
    assertFalse(capabilities.isSupported(ServerRuleCapabilities.VACATION_HTML));
    assertFalse(capabilities.isSupported(ServerRuleCapabilities.FORWARDING_WRITE));
    assertFalse(capabilities.isSupported(ServerRuleCapabilities.MOVE_TO_FOLDER));
    assertFalse(capabilities.publishConflict());
    assertEquals(ServerRuleCapabilities.VocabularySource.FIXED, capabilities.vocabularySource());
    assertEquals(List.of("login", "getVacation", "logout"), names());
    Call login = bluemind.calls("login").get(0);
    assertEquals(CORE_URL, login.apiRoot());
    assertEquals(LOGIN, login.login());
  }

  /**
   * A server without the {@code _vacation} endpoint answers the probe unsupported; one
   * that refuses the account fails it.
   *
   * @throws Exception on failure
   */
  @Test
  public void testAProbeOnAServerWithoutTheEndpoint() throws Exception {
    bluemind.fail("getVacation", Kind.NOT_FOUND);
    ServerRuleCapabilities capabilities = engine.probe(session);
    assertFalse(capabilities.supported());
    assertEquals(ServerRuleUnavailableException.SERVER_UNSUPPORTED, capabilities.reasonCode());
    bluemind.fail("getVacation", Kind.REFUSED);
    assertEquals(ServerRuleUnavailableException.SERVER_REFUSED,
                 assertThrows(ServerRuleUnavailableException.class, () -> engine.probe(session)).getMessage());
  }

  /**
   * A reply set in the webmail is adopted: eXo's own state, the server as its source, its
   * days in the caller's zone -- which moves them -- and its text with LF line breaks.
   * Without the caller's zone the BlueMind account's own applies, else UTC.
   *
   * @throws Exception on failure
   */
  @Test
  public void testAReplySetInTheWebmailIsAdopted() throws Exception {
    bluemind.setHeld(FakeBlueMindTransport.WEBMAIL);
    ServerVacation paris = engine.readVacation(session, PARIS);
    assertEquals(VacationState.OWN, paris.state());
    assertNull(paris.foreignScriptName());
    assertNull(paris.scriptHash());
    VacationSetting reply = paris.vacation();
    assertTrue(reply.isEnabled());
    assertEquals("2026-10-01", reply.getStart());
    assertEquals("2026-10-15", reply.getEnd());
    assertEquals("Europe/Paris", reply.getTimeZone());
    assertEquals("Absent", reply.getSubject());
    assertEquals("Je suis absente.\nRetour le 16.", reply.getText());
    assertEquals(VacationSetting.Source.SERVER, reply.getSource());

    assertEquals("2026-10-16", engine.readVacation(session, ZoneId.of("Asia/Tokyo")).vacation().getEnd());

    bluemind.answerLoginAs(FakeBlueMindTransport.USER_UID, FakeBlueMindTransport.DOMAIN_UID, "Asia/Tokyo");
    VacationSetting account = engine.readVacation(session).vacation();
    assertEquals("Asia/Tokyo", account.getTimeZone());
    assertEquals("2026-10-16", account.getEnd());

    bluemind.answerLoginAs(FakeBlueMindTransport.USER_UID, FakeBlueMindTransport.DOMAIN_UID, "Not/AZone");
    assertEquals("Z", engine.readVacation(session).vacation().getTimeZone());
  }

  /**
   * A mailbox where nothing was ever set holds no reply.
   *
   * @throws Exception on failure
   */
  @Test
  public void testNothingSetReadsNone() throws Exception {
    ServerVacation read = engine.readVacation(session, PARIS);
    assertEquals(VacationState.NONE, read.state());
    assertNull(read.vacation());
  }

  /**
   * The window is sent as the instant of 00:00 on the first day and of 23:59:59.999 on
   * the last day, in the user's zone, each at its own offset: a window across the end of
   * summer time gets two. An edited text is sent as plain text, the HTML body empty.
   *
   * @throws Exception on failure
   */
  @Test
  public void testTheWindowIsSentAsTheUsersDayBoundaries() throws Exception {
    ServerVacation written = engine.writeVacation(session, reply(true, "2026-10-20", "2026-11-03"), 7, null);
    BlueMindVacation posted = lastPosted();
    assertEquals(Instant.parse("2026-10-19T22:00:00Z").toEpochMilli(), posted.start());
    assertEquals(Instant.parse("2026-11-03T22:59:59.999Z").toEpochMilli(), posted.end());
    assertTrue(posted.enabled());
    assertEquals("Away", posted.subject());
    assertEquals("Back on Monday.\nThanks.", posted.text());
    assertNull(posted.textHtml());
    assertEquals(VacationState.OWN, written.state());
    assertEquals("2026-10-20", written.vacation().getStart());
    assertEquals("2026-11-03", written.vacation().getEnd());

    engine.writeVacation(session, reply(true, null, null), 7, null);
    posted = lastPosted();
    assertNull(posted.start());
    assertNull(posted.end());
  }

  /**
   * A day that did not change keeps the instant the server holds, so saving a reply set
   * in the webmail as it was read -- or switching it off -- never moves its window; a day
   * that changed gets the user's day boundary.
   *
   * @throws Exception on failure
   */
  @Test
  public void testAnUnchangedDayKeepsTheStoredInstant() throws Exception {
    bluemind.setHeld(FakeBlueMindTransport.WEBMAIL);
    engine.writeVacation(session, reply(false, "2026-10-01", "2026-10-15"), 7, null);
    BlueMindVacation posted = lastPosted();
    assertEquals(1790843820000L, posted.start());
    assertEquals(1792080000000L, posted.end());
    assertFalse(posted.enabled());

    engine.writeVacation(session, reply(true, "2026-10-01", "2026-10-20"), 7, null);
    posted = lastPosted();
    assertEquals(1790843820000L, posted.start());
    assertEquals(Instant.parse("2026-10-20T21:59:59.999Z").toEpochMilli(), posted.end());

    engine.writeVacation(session, reply(true, "2026-10-02", "2026-10-20"), 7, null);
    posted = lastPosted();
    assertEquals(Instant.parse("2026-10-01T22:00:00Z").toEpochMilli(), posted.start());
  }

  /**
   * A reply written in the webmail keeps its HTML body and its line breaks when eXo
   * switches it off or saves it with its text unchanged; a text edited in eXo is sent as
   * plain text, the HTML body empty.
   *
   * @throws Exception on failure
   */
  @Test
  public void testAnUnchangedTextKeepsTheWebmailsBody() throws Exception {
    bluemind.setHeld(FakeBlueMindTransport.WEBMAIL);
    VacationSetting read = engine.readVacation(session, PARIS).vacation();
    read.setEnabled(false);
    engine.writeVacation(session, read, 7, null);
    assertEquals("Je suis absente.\r\nRetour le 16.", lastPosted().text());
    assertEquals("<p>Je suis absente.</p>", lastPosted().textHtml());

    read.setText("Je suis absente.\nRetour le 19.");
    engine.writeVacation(session, read, 7, null);
    assertEquals("Je suis absente.\nRetour le 19.", lastPosted().text());
    assertNull(lastPosted().textHtml());
  }

  /**
   * The forward is read through {@code _forwarding} of the login's own mailbox: its
   * destinations and whether a copy is kept; only reads are issued, and the session is
   * closed.
   *
   * @throws Exception on failure
   */
  @Test
  public void testTheForwardIsReadAndNothingWritten() throws Exception {
    bluemind.setForwarding(new BlueMindForwarding(true, false, Set.of("z@demo3.livecollab.fr", "a@demo3.livecollab.fr", " ")));
    ForwardingSetting forwarding = engine.readForwarding(session);
    assertEquals(ForwardingState.SERVER_FORWARD, forwarding.state());
    assertEquals(List.of("a@demo3.livecollab.fr", "z@demo3.livecollab.fr"), forwarding.destinations());
    assertFalse(forwarding.keepCopy());
    assertNull(forwarding.scriptName());
    assertEquals(List.of("login", "getForwarding", "logout"), names());
    assertEquals(FakeBlueMindTransport.USER_UID, bluemind.calls("getForwarding").get(0).session().userUid());
  }

  /**
   * A forward switched off, or on without a destination, forwards nothing.
   *
   * @throws Exception on failure
   */
  @Test
  public void testAForwardOffForwardsNothing() throws Exception {
    bluemind.setForwarding(new BlueMindForwarding(false, true, Set.of("a@demo3.livecollab.fr")));
    assertEquals(ForwardingSetting.none(), engine.readForwarding(session));
    bluemind.setForwarding(new BlueMindForwarding(true, true, Set.of()));
    assertEquals(ForwardingSetting.none(), engine.readForwarding(session));
    assertTrue(bluemind.posted().isEmpty());
  }

  /**
   * A failed read is the engine's code, never the server's text.
   */
  @Test
  public void testAFailedForwardReadIsUnavailable() {
    bluemind.fail("getForwarding", Kind.UNREACHABLE);
    assertEquals(ServerRuleUnavailableException.SERVER_UNREACHABLE,
                 assertThrows(ServerRuleUnavailableException.class, () -> engine.readForwarding(session)).getMessage());
  }

  /**
   * Without an implementation of the port the forward is unknown: no credential is
   * resolved and nothing is called.
   *
   * @throws Exception on failure
   */
  @Test
  public void testNoTransportReadsNoForward() throws Exception {
    BlueMindRuleEngine dormant = new BlueMindRuleEngine(null, resolver);
    assertEquals(ForwardingSetting.unknown(), dormant.readForwarding(session));
    assertEquals(0, resolved.get());
  }

  /**
   * Switching on, editing and switching off read and write the reply and nothing else,
   * every mailbox call carrying the session the login answered -- the login's own
   * mailbox -- and every session is closed; the forward is left as it was.
   *
   * @throws Exception on failure
   */
  @Test
  public void testOnlyTheReplyOfTheLoginsMailboxIsWritten() throws Exception {
    engine.writeVacation(session, reply(true, "2026-10-01", "2026-10-15"), 7, null);
    engine.writeVacation(session, reply(true, "2026-10-01", "2026-10-16"), 7, null);
    engine.writeVacation(session, reply(false, "2026-10-01", "2026-10-16"), 7, null);
    assertEquals(Set.of("login", "getVacation", "setVacation", "logout"), Set.copyOf(names()));
    assertEquals(3, bluemind.calls("setVacation").size());
    assertEquals(3, bluemind.calls("login").size());
    assertEquals(3, bluemind.calls("logout").size());
    assertSame(FakeBlueMindTransport.FORWARD, bluemind.forwarding());
    for (Call call : bluemind.calls()) {
      if (!"login".equals(call.name())) {
        BlueMindSession carried = call.session();
        assertEquals(FakeBlueMindTransport.USER_UID, carried.userUid());
        assertEquals(FakeBlueMindTransport.DOMAIN_UID, carried.domainUid());
        assertEquals(CORE_URL, carried.apiRoot());
      }
    }
  }

  /**
   * A login the transport cannot use -- its answer names no account -- addresses
   * nothing: no mailbox call is made, and the caller is told the server could not be used.
   *
   * @throws Exception on failure
   */
  @Test
  public void testALoginNamingNoAccountAddressesNothing() throws Exception {
    bluemind.fail("login", Kind.PROTOCOL);
    assertEquals(ServerRuleUnavailableException.SERVER_UNREACHABLE,
                 assertThrows(ServerRuleUnavailableException.class,
                              () -> engine.writeVacation(session, reply(true, null, null), 7, null)).getMessage());
    assertEquals(List.of("login"), names());
  }

  /**
   * The port's failures as the codes a user can act on.
   *
   * @throws Exception on failure
   */
  @Test
  public void testFailuresAsCodes() throws Exception {
    assertEquals(ServerRuleUnavailableException.AUTHENTICATION, readFailure(Kind.AUTHENTICATION));
    assertEquals(ServerRuleUnavailableException.SERVER_REFUSED, readFailure(Kind.REFUSED));
    assertEquals(ServerRuleUnavailableException.SERVER_UNSUPPORTED, readFailure(Kind.NOT_FOUND));
    assertEquals(ServerRuleUnavailableException.SERVER_UNREACHABLE, readFailure(Kind.UNREACHABLE));
    assertEquals(ServerRuleUnavailableException.SERVER_UNREACHABLE, readFailure(Kind.PROTOCOL));
    assertEquals(bluemind.calls("login").size(), bluemind.calls("logout").size());
  }

  /**
   * A refused login is told to the credentials provider; the login is tried once more
   * when the provider may produce fresh material, and not when it would hand the same
   * password back.
   *
   * @throws Exception on failure
   */
  @Test
  public void testARefusedLoginIsRetriedOnceWhenTheProviderAllows() throws Exception {
    bluemind.fail("login", Kind.AUTHENTICATION);
    when(resolver.retriesAfterRefusal("personal")).thenReturn(false);
    assertEquals(ServerRuleUnavailableException.AUTHENTICATION,
                 assertThrows(ServerRuleUnavailableException.class, () -> engine.readVacation(session, PARIS)).getMessage());
    assertEquals(1, bluemind.calls("login").size());
    verify(resolver).invalidate(CONNECTOR_ID, "personal", "anais", ConnectorCredentialsChannel.IMAP);

    when(resolver.retriesAfterRefusal("personal")).thenReturn(true);
    assertThrows(ServerRuleUnavailableException.class, () -> engine.readVacation(session, PARIS));
    assertEquals(3, bluemind.calls("login").size());
  }

  /**
   * A core URL with no BlueMind login behind it reads as not configured.
   *
   * @throws Exception on failure
   */
  @Test
  public void testALoginNotFoundIsNotConfigured() throws Exception {
    bluemind.fail("login", Kind.NOT_FOUND);
    assertEquals(ServerRuleUnavailableException.NOT_CONFIGURED,
                 assertThrows(ServerRuleUnavailableException.class, () -> engine.probe(session)).getMessage());
  }

  /**
   * No login and password from the provider, or no core API configured, fail before any
   * call on the port.
   *
   * @throws Exception on failure
   */
  @Test
  public void testNothingIsSentWithoutCredentialsOrEndpoint() throws Exception {
    credentials.set(null);
    assertEquals(ServerRuleUnavailableException.AUTHENTICATION,
                 assertThrows(ServerRuleUnavailableException.class, () -> engine.probe(session)).getMessage());
    System.clearProperty(BlueMindEndpoint.CORE_URL_PROPERTY + "." + CONNECTOR_ID);
    assertEquals(ServerRuleUnavailableException.NOT_CONFIGURED,
                 assertThrows(ServerRuleUnavailableException.class, () -> engine.probe(session)).getMessage());
    assertEquals(List.of(), bluemind.calls());
    verify(resolver, never()).invalidate(any(), anyString(), anyString(), any());
  }

  /**
   * A window without a zone is refused before anything is sent.
   */
  @Test
  public void testAWindowNeedsAZone() {
    VacationSetting reply = reply(true, "2026-10-01", null);
    reply.setTimeZone(null);
    assertEquals("emailConnector.absence.timeZone.invalid",
                 assertThrows(IllegalArgumentException.class, () -> engine.writeVacation(session, reply, 7, null)).getMessage());
    assertEquals(List.of(), bluemind.calls());
  }

  /**
   * The code a read fails with when {@code getVacation} fails with a kind.
   *
   * @param kind the kind
   * @return the code
   */
  private String readFailure(Kind kind) {
    bluemind.fail("getVacation", kind);
    return assertThrows(ServerRuleUnavailableException.class, () -> engine.readVacation(session, PARIS)).getMessage();
  }

  /**
   * The names of the port's calls, in order.
   *
   * @return the names
   */
  private List<String> names() {
    return bluemind.calls().stream().map(Call::name).toList();
  }

  /**
   * The last reply written.
   *
   * @return the reply
   */
  private BlueMindVacation lastPosted() {
    List<BlueMindVacation> posted = bluemind.posted();
    return posted.get(posted.size() - 1);
  }

  /**
   * A reply in Paris.
   *
   * @param enabled whether it is on
   * @param start the first day, or null
   * @param end the last day, or null
   * @return the reply
   */
  private static VacationSetting reply(boolean enabled, String start, String end) {
    return new VacationSetting(enabled, start, end, "Europe/Paris", "Away", "Back on Monday.\nThanks.", 0, null);
  }
}
