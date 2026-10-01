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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.emailConnector.exception.SendModeMissingException;
import org.exoplatform.emailConnector.model.CalendarInvitation;
import org.exoplatform.emailConnector.model.CalendarLanding;
import org.exoplatform.emailConnector.model.InvitationLanding;
import org.exoplatform.emailConnector.model.CalendarInvitationPerson;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailDelegation;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.FolderRole;
import org.exoplatform.emailConnector.model.InvitationAnswer;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.SendIdentity;
import org.exoplatform.emailConnector.model.SendMode;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.utils.CalendarInvitationUtils;

/**
 * Calendar invitations in mail (EXO-90840): what the reader is shown, who may answer
 * from where, the REPLY that leaves, and what is remembered of it.
 */
@ExtendWith(MockitoExtension.class)
class CalendarInvitationServiceTest {

  private static final String     USER        = "john";

  private static final String     ME          = "testEmail@acme.com";

  private static final String     OWNER       = "alice@acme.com";

  private static final long       EMAIL_ID    = 12L;

  private static final String     SHARED_KEY  = "CUSTOM:7";

  @Mock
  private EmailBoxService         emailBoxService;

  @Mock
  private UserEmailSettingService userEmailSettingService;

  @Mock
  private EmailDelegationService  emailDelegationService;

  @Mock
  private SettingService          settingService;

  @Mock
  private InvitationLandingService invitationLandingService;

  @InjectMocks
  private CalendarInvitationService service;

  private Email                   email;

  /**
   * The user's mailbox address, and a received invitation from Google in their inbox.
   *
   * @throws Exception never
   */
  @BeforeEach
  void setUp() throws Exception {
    UserEmailSetting setting = new UserEmailSetting();
    setting.setEmailAddress(ME);
    lenient().when(userEmailSettingService.getUserEmailSetting(USER)).thenReturn(setting);
    email = invitationMail(MailFolder.INBOX);
    lenient().when(emailBoxService.getOwnedEmailById(EMAIL_ID, USER)).thenReturn(email);
    givenTheCalendarPart("google-weekly-request.ics");
  }

  /**
   * Clears the size cap a test may have set.
   */
  @AfterEach
  void clearTheCap() {
    System.clearProperty(CalendarInvitationService.MAX_BYTES_PROPERTY);
  }

  /**
   * A received REQUEST in the user's inbox is offered to be answered, for the address
   * the invitation lists them under, and its part is read capped.
   *
   * @throws Exception never
   */
  @Test
  void aReceivedInvitationIsOfferedToBeAnswered() throws Exception {
    CalendarInvitation invitation = service.getInvitation(EMAIL_ID, USER);

    assertTrue(invitation.isAnswerable());
    assertNull(invitation.getAnswerRefusal());
    assertEquals(ME, invitation.getAttendeeAddress());
    assertNull(invitation.getAnswer());
    assertEquals("Weekly <b>sync</b>", invitation.getSummary());
    verify(emailBoxService).readMessagePart(USER, email, "1.3", CalendarInvitationService.DEFAULT_MAX_BYTES);
  }

  /**
   * Nothing is offered where answering is not the user's to do or would confirm the
   * address to a spammer: Sent, Drafts, Junk, Trash, a draft, a scheduled mail, or an
   * event the user organises.
   *
   * @throws Exception never
   */
  @Test
  void nothingIsOfferedWhereAnsweringIsNotTheUsers() throws Exception {
    for (String folder : List.of(MailFolder.SENT, MailFolder.DRAFTS, MailFolder.JUNK, MailFolder.TRASH)) {
      email.setFolder(folder);
      assertFalse(service.getInvitation(EMAIL_ID, USER).isAnswerable(), folder);
    }
    email.setFolder(MailFolder.INBOX);
    email.setDraftLocalId("draft-1");
    assertFalse(service.getInvitation(EMAIL_ID, USER).isAnswerable(), "a draft");
    email.setDraftLocalId(null);
    email.setScheduled(true);
    assertFalse(service.getInvitation(EMAIL_ID, USER).isAnswerable(), "a scheduled mail");
    email.setScheduled(false);
    UserEmailSetting organizer = new UserEmailSetting();
    organizer.setEmailAddress("olivia@partner.example");
    when(userEmailSettingService.getUserEmailSetting(USER)).thenReturn(organizer);
    assertFalse(service.getInvitation(EMAIL_ID, USER).isAnswerable(), "the user's own event");

    email.setFolder(MailFolder.JUNK);
    when(userEmailSettingService.getUserEmailSetting(USER)).thenReturn(setting(ME));
    assertEquals(CalendarInvitationService.NOT_ANSWERABLE,
                 assertThrows(IllegalArgumentException.class,
                              () -> service.respond(EMAIL_ID, USER, InvitationAnswer.ACCEPTED)).getMessage());
    verifyNothingSent();
  }

  /**
   * A cancellation is shown as cancelled, and cannot be answered.
   *
   * @throws Exception never
   */
  @Test
  void aCancellationIsShownAndNotAnswered() throws Exception {
    givenTheCalendarPart("allday-cancel.ics");

    CalendarInvitation invitation = service.getInvitation(EMAIL_ID, USER);

    assertTrue(invitation.isCancelled());
    assertFalse(invitation.isAnswerable());
    assertEquals(CalendarInvitationService.CANCELLED,
                 assertThrows(IllegalArgumentException.class,
                              () -> service.respond(EMAIL_ID, USER, InvitationAnswer.DECLINED)).getMessage());
    verifyNothingSent();
  }

  /**
   * Somebody else's message, a message with no invitation, and one whose part is gone
   * are all "not found"; a part over the cap is too large; what is not a calendar is
   * unreadable.
   *
   * @throws Exception never
   */
  @Test
  void whatCannotBeShownSaysWhy() throws Exception {
    when(emailBoxService.getOwnedEmailById(13L, USER)).thenThrow(new IllegalAccessException("not yours"));
    assertThrows(ObjectNotFoundException.class, () -> service.getInvitation(13L, USER));
    assertThrows(ObjectNotFoundException.class, () -> service.respond(13L, USER, InvitationAnswer.ACCEPTED));

    when(emailBoxService.readMessagePart(eq(USER), any(), anyString(), anyLong())).thenReturn(null);
    assertThrows(ObjectNotFoundException.class, () -> service.getInvitation(EMAIL_ID, USER));

    when(emailBoxService.readMessagePart(eq(USER), any(), anyString(), anyLong()))
                                                                                    .thenThrow(new IllegalArgumentException(EmailBoxService.PART_TOO_LARGE));
    assertEquals(CalendarInvitationService.TOO_LARGE,
                 assertThrows(IllegalArgumentException.class, () -> service.getInvitation(EMAIL_ID, USER)).getMessage());

    doAnswer(invocation -> "<html>no calendar</html>".getBytes(StandardCharsets.UTF_8)).when(emailBoxService)
                                                                                    .readMessagePart(eq(USER), any(), anyString(), anyLong());
    assertEquals(CalendarInvitationService.UNREADABLE,
                 assertThrows(IllegalArgumentException.class, () -> service.getInvitation(EMAIL_ID, USER)).getMessage());

    email.getContent().setAttachments(List.of(attachment("application/pdf", "agenda.pdf", "2")));
    assertThrows(ObjectNotFoundException.class, () -> service.getInvitation(EMAIL_ID, USER));
  }

  /**
   * Without the iCalendar library on the server (no agenda add-on, or an incompatible
   * one), an invitation is not shown, and the mail reads as before.
   *
   * @throws Exception never
   */
  @Test
  void withoutTheLibraryNoInvitationIsShown() throws Exception {
    try (MockedStatic<CalendarInvitationUtils> library = mockStatic(CalendarInvitationUtils.class)) {
      library.when(() -> CalendarInvitationUtils.parseInvitation(any(), any(), anyInt()))
             .thenThrow(new NoClassDefFoundError("net/fortuna/ical4j/data/CalendarBuilder"));
      for (int read = 0; read < 2; read++) {
        assertEquals(CalendarInvitationService.UNSUPPORTED,
                     assertThrows(IllegalArgumentException.class, () -> service.getInvitation(EMAIL_ID, USER)).getMessage());
      }
    }
  }

  /**
   * An event this deployment's Agenda mailed is an eXo meeting: labelled, linked to in
   * Agenda by the link rebuilt from the portal's own domain, and never answered from the
   * mail -- not even when its method asks for an answer.
   *
   * @throws Exception never
   */
  @Test
  void thisDeploymentsAgendaEventIsAnsweredInAgenda() throws Exception {
    try (MockedStatic<CommonsUtils> portal = mockStatic(CommonsUtils.class)) {
      portal.when(CommonsUtils::getCurrentDomain).thenReturn("https://exo.example.test");
      for (String fixture : List.of("agenda-own-publish.ics", "agenda-own-request.ics")) {
        givenTheCalendarPart(fixture);
        CalendarInvitation invitation = service.getInvitation(EMAIL_ID, USER);
        assertTrue(invitation.isExoMeeting(), fixture);
        assertEquals("https://exo.example.test/portal/dw/agenda?eventId=42", invitation.getAgendaUrl());
        assertFalse(invitation.isAnswerable(), fixture);
        assertNull(invitation.getAnswerRefusal());
      }
      assertEquals(CalendarInvitationService.NOT_ANSWERABLE,
                   assertThrows(IllegalArgumentException.class,
                                () -> service.respond(EMAIL_ID, USER, InvitationAnswer.ACCEPTED)).getMessage());
      verifyNothingSent();
    }
  }

  /**
   * A calendar server's answer about a CalDAV copy of this deployment's event -- a random
   * UID, the event's link -- is the answer of an eXo meeting: linked to in Agenda, and
   * never answered; the CalDAV copy itself, sent as a REQUEST, is never answered from the
   * mail either. The same copy with a link on another deployment is ordinary.
   *
   * @throws Exception never
   */
  @Test
  void aCopyOfThisDeploymentsEventIsAnEXoMeetingByItsLink() throws Exception {
    try (MockedStatic<CommonsUtils> portal = mockStatic(CommonsUtils.class)) {
      portal.when(CommonsUtils::getCurrentDomain).thenReturn("http://localhost:8080");
      givenTheCalendarPart("bluemind-reply-accepted.ics");
      CalendarInvitation reply = service.getInvitation(EMAIL_ID, USER);
      assertTrue(reply.isExoMeeting());
      assertEquals("http://localhost:8080/portal/dw/agenda?eventId=168", reply.getAgendaUrl());
      assertEquals("ACCEPTED", reply.getRespondent().getPartStat());
      assertFalse(reply.isAnswerable());

      givenTheCalendarPart("caldav-copy-request.ics");
      CalendarInvitation copy = service.getInvitation(EMAIL_ID, USER);
      assertTrue(copy.isExoMeeting());
      assertFalse(copy.isAnswerable());
      assertEquals(CalendarInvitationService.NOT_ANSWERABLE,
                   assertThrows(IllegalArgumentException.class,
                                () -> service.respond(EMAIL_ID, USER, InvitationAnswer.ACCEPTED)).getMessage());

      givenTheCalendarPart("caldav-foreign-request.ics");
      CalendarInvitation foreign = service.getInvitation(EMAIL_ID, USER);
      assertFalse(foreign.isExoMeeting());
      assertNull(foreign.getAgendaUrl());
      assertTrue(foreign.isAnswerable());
      verifyNothingSent();
    }
  }

  /**
   * A REPLY, a COUNTER, a REFRESH and a DECLINECOUNTER are answers, never answered back.
   *
   * @throws Exception never
   */
  @Test
  void anAnswerIsNeverAnswered() throws Exception {
    for (String fixture : List.of("reply-no-name.ics", "counter.ics", "counter-two-attendees.ics", "refresh.ics", "declinecounter.ics")) {
      givenTheCalendarPart(fixture);
      CalendarInvitation answer = service.getInvitation(EMAIL_ID, USER);
      assertFalse(answer.isAnswerable(), fixture);
      assertEquals(CalendarInvitationService.NOT_ANSWERABLE,
                   assertThrows(IllegalArgumentException.class,
                                () -> service.respond(EMAIL_ID, USER, InvitationAnswer.ACCEPTED)).getMessage(),
                   fixture);
    }
    verifyNothingSent();
  }

  /**
   * An answer is never read as the reader's own answer: a COUNTER that lists the reader
   * -- its organiser -- as an accepting attendee, from a sender it does not name, says
   * nothing of the reader having accepted, and an answer remembered for the event is not
   * shown on it either.
   *
   * @throws Exception never
   */
  @Test
  void anAnswerIsNeverTheReadersOwnAnswer() throws Exception {
    givenTheCalendarPart("counter-organizer-listed.ics");
    email.setSender(new EmailSender("Alias", "m.alias@acme.com", null, null));
    lenient().when(settingService.get(any(), any(), anyString()))
             .thenAnswer(invocation -> SettingValue.create("{\"answer\":\"ACCEPTED\",\"sequence\":9,\"answeredAt\":1}"));

    CalendarInvitation counter = service.getInvitation(EMAIL_ID, USER);

    assertEquals("COUNTER", counter.getMethod());
    assertNull(counter.getRespondent(), "the sender is not among the attendees");
    assertNull(counter.getAnswer());
    assertFalse(counter.isAnswerable());
  }

  /**
   * A COUNTER listing every attendee is credited to the one who sent the mail, never to
   * whoever is listed first; to nobody when the sender is not among them.
   *
   * @throws Exception never
   */
  @Test
  void aCounterIsCreditedToItsSender() throws Exception {
    givenTheCalendarPart("counter-two-attendees.ics");
    email.setSender(new EmailSender("Meyer", "MEYER@acme.com", null, null));
    assertEquals("meyer@acme.com", service.getInvitation(EMAIL_ID, USER).getRespondent().getAddress());

    email.setSender(new EmailSender("Somebody", "somebody@partner.example", null, null));
    assertNull(service.getInvitation(EMAIL_ID, USER).getRespondent());
  }

  /**
   * Another eXo's event, this deployment's UID with a link elsewhere, and an event with
   * no link are ordinary invitations: never linked to this portal's Agenda, answered by
   * mail as any other.
   *
   * @throws Exception never
   */
  @Test
  void anotherDeploymentsOrAForgedAgendaEventIsAnOrdinaryInvitation() throws Exception {
    try (MockedStatic<CommonsUtils> portal = mockStatic(CommonsUtils.class)) {
      portal.when(CommonsUtils::getCurrentDomain).thenReturn("https://exo.example.test");
      for (String fixture : List.of("agenda-foreign-request.ics", "agenda-forged-request.ics", "agenda-no-url-request.ics")) {
        givenTheCalendarPart(fixture);
        CalendarInvitation invitation = service.getInvitation(EMAIL_ID, USER);
        assertFalse(invitation.isExoMeeting(), fixture);
        assertNull(invitation.getAgendaUrl(), fixture);
        assertTrue(invitation.isAnswerable(), fixture);
      }
    }
  }

  /**
   * The cap is the administrator's, a value that is not a positive number is the
   * default, and an absurd one is bounded.
   */
  @Test
  void theCapIsConfigurable() {
    assertEquals(CalendarInvitationService.DEFAULT_MAX_BYTES, CalendarInvitationService.maxBytes());
    System.setProperty(CalendarInvitationService.MAX_BYTES_PROPERTY, "1024");
    assertEquals(1024, CalendarInvitationService.maxBytes());
    System.setProperty(CalendarInvitationService.MAX_BYTES_PROPERTY, "-1");
    assertEquals(CalendarInvitationService.DEFAULT_MAX_BYTES, CalendarInvitationService.maxBytes());
    System.setProperty(CalendarInvitationService.MAX_BYTES_PROPERTY, "lots");
    assertEquals(CalendarInvitationService.DEFAULT_MAX_BYTES, CalendarInvitationService.maxBytes());
    System.setProperty(CalendarInvitationService.MAX_BYTES_PROPERTY, String.valueOf(Long.MAX_VALUE));
    assertEquals(CalendarInvitationService.MAX_CAP, CalendarInvitationService.maxBytes(), "bounded, so twice it cannot overflow");
  }

  /**
   * Accept sends the REPLY from the user's own mailbox to the organiser alone, as iMIP
   * says -- "Accepted: {title}", tied to the invitation, a text for a person and the
   * REPLY as text/calendar; method=REPLY -- then remembers the answer with the event's
   * sequence.
   *
   * @throws Exception never
   */
  @Test
  void acceptingSendsTheReplyToTheOrganiserAndRemembersIt() throws Exception {
    List<EmailBoxService.OutgoingMessageFactory> factories = captureTransmissions();

    CalendarInvitation invitation = service.respond(EMAIL_ID, USER, InvitationAnswer.ACCEPTED);

    assertEquals(InvitationAnswer.ACCEPTED, invitation.getAnswer());
    verify(emailBoxService).transmitAsUser(eq(USER), any(EmailBoxService.OutgoingMessageFactory.class));
    verify(emailBoxService, never()).transmitFromSharedMailbox(anyString(), anyLong(), any(), any());
    MimeMessage reply = factories.get(0).build(session(), new InternetAddress(ME, "Test User"));
    assertEquals(1, reply.getRecipients(Message.RecipientType.TO).length);
    assertEquals("olivia@partner.example", ((InternetAddress) reply.getRecipients(Message.RecipientType.TO)[0]).getAddress());
    assertNull(reply.getRecipients(Message.RecipientType.CC));
    assertEquals("Accepted: Weekly <b>sync</b>", reply.getSubject());
    assertEquals("<invite@partner.example>", reply.getHeader("In-Reply-To")[0]);
    assertEquals(ME, ((InternetAddress) reply.getFrom()[0]).getAddress());
    reply.saveChanges();
    assertTrue(reply.getMessageID().endsWith("@acme.com>"), "the domain it is from, not this server's host");
    Multipart alternative = (Multipart) reply.getContent();
    assertTrue(alternative.getContentType().startsWith("multipart/alternative"));
    assertTrue(((String) alternative.getBodyPart(0).getContent()).startsWith("Test User has accepted this invitation"));
    BodyPart calendar = alternative.getBodyPart(1);
    assertTrue(calendar.isMimeType("text/calendar"));
    assertTrue(calendar.getContentType().contains("method=REPLY"));
    String ics = text(calendar);
    assertTrue(ics.contains("METHOD:REPLY"));
    assertTrue(ics.contains("UID:weekly-sync@google.com"));
    assertTrue(ics.contains("SEQUENCE:2"));
    assertTrue(ics.contains("PARTSTAT=ACCEPTED"));
    assertTrue(ics.contains("mailto:" + ME));
    assertFalse(ics.contains("SENT-BY"));

    ArgumentCaptor<SettingValue<?>> stored = ArgumentCaptor.forClass(SettingValue.class);
    verify(settingService).set(eq(Context.USER.id(USER)),
                               eq(UserEmailSettingService.EMAIL_CONNECTOR_SCOPE),
                               eq(CalendarInvitationService.answerKey(ME, "weekly-sync@google.com", null)),
                               stored.capture());
    assertTrue(stored.getValue().getValue().toString().contains("\"answer\":\"ACCEPTED\""));
    assertTrue(stored.getValue().getValue().toString().contains("\"sequence\":2"));
    assertNull(invitation.getLanding(), "no add-on holds a calendar for the user");
  }

  /**
   * Once the answer left from the user's own mailbox, the event is handed to the add-on
   * holding their calendar: the user, their mailbox address, what the reader read of
   * the event, the answer and the part as received; what became of it is told back.
   *
   * @throws Exception never
   */
  @Test
  void anAnswerFromTheUsersOwnMailboxLandsInTheirCalendar() throws Exception {
    captureTransmissions();
    ArgumentCaptor<InvitationLanding> landing = ArgumentCaptor.forClass(InvitationLanding.class);
    when(invitationLandingService.land(landing.capture())).thenReturn(CalendarLanding.LANDED);

    CalendarInvitation invitation = service.respond(EMAIL_ID, USER, InvitationAnswer.TENTATIVE);

    assertEquals(CalendarLanding.LANDED, invitation.getLanding());
    assertEquals(USER, landing.getValue().username());
    assertEquals(ME, landing.getValue().attendeeAddress());
    assertEquals("weekly-sync@google.com", landing.getValue().uid());
    assertNull(landing.getValue().recurrenceId());
    assertEquals(2, landing.getValue().sequence());
    assertEquals(InvitationAnswer.TENTATIVE, landing.getValue().answer());
    assertTrue(landing.getValue().icalendar().startsWith("BEGIN:VCALENDAR"), "the part as received");
    assertTrue(landing.getValue().icalendar().contains("UID:weekly-sync@google.com"));

    when(invitationLandingService.land(any())).thenReturn(CalendarLanding.FAILED);
    assertEquals(CalendarLanding.FAILED, service.respond(EMAIL_ID, USER, InvitationAnswer.TENTATIVE).getLanding());
  }

  /**
   * An answer that did not leave lands nowhere: the calendar follows the REPLY, never
   * the other way round.
   *
   * @throws Exception never
   */
  @Test
  void anAnswerThatDidNotLeaveLandsNowhere() throws Exception {
    doThrow(new SmtpTransmitter.TransmissionException(SmtpTransmitter.Phase.SEND, new Exception("lost")))
                                                                                                        .when(emailBoxService)
                                                                                                        .transmitAsUser(eq(USER), any());
    assertThrows(IllegalStateException.class, () -> service.respond(EMAIL_ID, USER, InvitationAnswer.ACCEPTED));
    verify(invitationLandingService, never()).land(any());
  }

  /**
   * The answer given is shown again for the sequence it answered, and no longer once an
   * update raised the sequence: the organiser asks again.
   *
   * @throws Exception never
   */
  @Test
  void theAnswerGivenIsShownUntilAnUpdateAsksAgain() throws Exception {
    String key = CalendarInvitationService.answerKey(ME, "weekly-sync@google.com", null);
    when(settingService.get(Context.USER.id(USER), UserEmailSettingService.EMAIL_CONNECTOR_SCOPE, key))
                                                                                                 .thenAnswer(invocation -> SettingValue.create("{\"answer\":\"TENTATIVE\",\"sequence\":2,\"answeredAt\":1}"));
    assertEquals(InvitationAnswer.TENTATIVE, service.getInvitation(EMAIL_ID, USER).getAnswer());

    when(settingService.get(Context.USER.id(USER), UserEmailSettingService.EMAIL_CONNECTOR_SCOPE, key))
                                                                                                 .thenAnswer(invocation -> SettingValue.create("{\"answer\":\"TENTATIVE\",\"sequence\":1,\"answeredAt\":1}"));
    assertNull(service.getInvitation(EMAIL_ID, USER).getAnswer(), "an answer to sequence 1 is not one to sequence 2");
  }

  /**
   * An answer that may have left is remembered, so the reader does not invite a second
   * one; one that could not leave is not.
   *
   * @throws Exception never
   */
  @Test
  void onlyAnAnswerThatMayHaveLeftIsRemembered() throws Exception {
    doThrow(new SmtpTransmitter.TransmissionException(SmtpTransmitter.Phase.CONNECT, new Exception("down")))
                                                                                                           .when(emailBoxService)
                                                                                                           .transmitAsUser(eq(USER), any());
    assertEquals(CalendarInvitationService.SEND_FAILED,
                 assertThrows(IllegalStateException.class, () -> service.respond(EMAIL_ID, USER, InvitationAnswer.DECLINED)).getMessage());
    verify(settingService, never()).set(any(), any(), anyString(), any());

    doThrow(new SmtpTransmitter.TransmissionException(SmtpTransmitter.Phase.SEND, new Exception("lost")))
                                                                                                        .when(emailBoxService)
                                                                                                        .transmitAsUser(eq(USER), any());
    assertEquals(CalendarInvitationService.UNCONFIRMED,
                 assertThrows(IllegalStateException.class, () -> service.respond(EMAIL_ID, USER, InvitationAnswer.DECLINED)).getMessage());
    verify(settingService).set(any(), any(), anyString(), any());
  }

  /**
   * No answer is no answer.
   */
  @Test
  void anAnswerIsRequired() {
    assertEquals(CalendarInvitationService.INVALID_ANSWER,
                 assertThrows(IllegalArgumentException.class, () -> service.respond(EMAIL_ID, USER, null)).getMessage());
  }

  /**
   * In a shared mailbox the answer is the owner's: given for her address, on her behalf
   * when she allows it -- SENT-BY naming the delegate -- and as her otherwise.
   *
   * @throws Exception never
   */
  @Test
  void aSharedMailboxsInvitationIsAnsweredInItsOwnersName() throws Exception {
    EmailDelegation delegation = givenASharedMailbox();
    SendIdentity onBehalf = new SendIdentity(SendMode.ON_BEHALF, 100L, OWNER, "Alice", new Date());
    when(emailDelegationService.checkSendMode(USER, 100L, SendMode.ON_BEHALF)).thenReturn(onBehalf);
    List<EmailBoxService.OutgoingMessageFactory> factories = captureSharedTransmissions();

    CalendarInvitation invitation = service.respond(EMAIL_ID, USER, InvitationAnswer.ACCEPTED);

    assertEquals(OWNER, invitation.getAttendeeAddress());
    verify(emailBoxService).transmitFromSharedMailbox(eq(USER), eq(100L), eq(SendMode.ON_BEHALF), any());
    verify(emailBoxService, never()).transmitAsUser(anyString(), any());
    String ics = text((BodyPart) ((Multipart) factories.get(0)
                                                       .build(session(),
                                                              new InternetAddress(OWNER, "Alice"),
                                                              new InternetAddress("resolved@acme.com"))
                                                       .getContent()).getBodyPart(1));
    assertTrue(ics.contains("mailto:" + OWNER));
    assertTrue(ics.contains("SENT-BY=\"mailto:resolved@acme.com\""), "the Sender header's own address: " + ics);
    verify(settingService).set(any(), any(), eq(CalendarInvitationService.answerKey(OWNER, "weekly-sync@google.com", null)), any());
    verify(invitationLandingService, never()).land(any());
    assertNull(invitation.getLanding(), "the owner's event lands in nobody's calendar from here");

    SendIdentity as = new SendIdentity(SendMode.AS, 100L, OWNER, "Alice", new Date());
    when(emailDelegationService.checkSendMode(USER, 100L, SendMode.ON_BEHALF)).thenThrow(new SendModeMissingException(SendMode.ON_BEHALF));
    when(emailDelegationService.checkSendMode(USER, 100L, SendMode.AS)).thenReturn(as);
    service.respond(EMAIL_ID, USER, InvitationAnswer.DECLINED);
    verify(emailBoxService).transmitFromSharedMailbox(eq(USER), eq(100L), eq(SendMode.AS), any());
    String asIcs = text((BodyPart) ((Multipart) factories.get(1)
                                                         .build(session(), new InternetAddress(OWNER, "Alice"), null)
                                                         .getContent()).getBodyPart(1));
    assertFalse(asIcs.contains("SENT-BY"), "as her: nothing names the delegate");
    assertSame(delegation, emailDelegationService.delegationOf(USER, SHARED_KEY));
  }

  /**
   * A shared mailbox whose owner does not let the user send in her name shows the
   * invitation, says why no answer is offered, and refuses one.
   *
   * @throws Exception never
   */
  @Test
  void aSharedMailboxWithoutConsentIsShownAndNotAnswered() throws Exception {
    givenASharedMailbox();
    when(emailDelegationService.checkSendMode(eq(USER), eq(100L), any())).thenThrow(new SendModeMissingException(SendMode.AS));

    CalendarInvitation invitation = service.getInvitation(EMAIL_ID, USER);

    assertFalse(invitation.isAnswerable());
    assertEquals(CalendarInvitationService.SEND_NOT_ALLOWED, invitation.getAnswerRefusal());
    assertEquals(CalendarInvitationService.SEND_NOT_ALLOWED,
                 assertThrows(IllegalAccessException.class,
                              () -> service.respond(EMAIL_ID, USER, InvitationAnswer.ACCEPTED)).getMessage());
    verifyNothingSent();
  }

  /**
   * A consent withdrawn between the read and the send is seen at the send: nothing
   * leaves. Its mutant -- the second check removed -- sends with no identity, that is in
   * the delegate's own name for the owner's invitation.
   *
   * @throws Exception never
   */
  @Test
  void aConsentWithdrawnMeanwhileSendsNothing() throws Exception {
    givenASharedMailbox();
    SendIdentity onBehalf = new SendIdentity(SendMode.ON_BEHALF, 100L, OWNER, "Alice", new Date());
    when(emailDelegationService.checkSendMode(USER, 100L, SendMode.ON_BEHALF)).thenReturn(onBehalf)
                                                                            .thenThrow(new SendModeMissingException(SendMode.ON_BEHALF));
    when(emailDelegationService.checkSendMode(USER, 100L, SendMode.AS)).thenThrow(new SendModeMissingException(SendMode.AS));

    assertEquals(CalendarInvitationService.SEND_NOT_ALLOWED,
                 assertThrows(IllegalAccessException.class,
                              () -> service.respond(EMAIL_ID, USER, InvitationAnswer.ACCEPTED)).getMessage());
    verifyNothingSent();
  }

  /**
   * A consent the send itself finds withdrawn, or a share revoked, is the same refusal:
   * nothing is remembered.
   *
   * @throws Exception never
   */
  @Test
  void aConsentRefusedAtTheSendIsTheSameRefusal() throws Exception {
    givenASharedMailbox();
    when(emailDelegationService.checkSendMode(USER, 100L, SendMode.ON_BEHALF))
                                                                            .thenReturn(new SendIdentity(SendMode.ON_BEHALF, 100L, OWNER, null, new Date()));
    doThrow(new SendModeMissingException(SendMode.ON_BEHALF)).when(emailBoxService)
                                                             .transmitFromSharedMailbox(eq(USER), eq(100L), eq(SendMode.ON_BEHALF), any());
    assertEquals(CalendarInvitationService.SEND_NOT_ALLOWED,
                 assertThrows(IllegalAccessException.class,
                              () -> service.respond(EMAIL_ID, USER, InvitationAnswer.ACCEPTED)).getMessage());
    verify(settingService, never()).set(any(), any(), anyString(), any());
  }

  /**
   * A shared mailbox's Junk is no more answered from than the user's own.
   *
   * @throws Exception never
   */
  @Test
  void aSharedMailboxsJunkIsNotAnsweredFrom() throws Exception {
    givenASharedMailbox();
    lenient().when(emailDelegationService.roleFolderKey(USER, 100L, FolderRole.JUNK)).thenReturn(SHARED_KEY);

    assertFalse(service.getInvitation(EMAIL_ID, USER).isAnswerable());
    verify(emailDelegationService, never()).checkSendMode(anyString(), anyLong(), any());
  }

  /**
   * The organiser an answer goes to is one plain address: a group or a list is refused,
   * since the transport would send to every member.
   */
  @Test
  void theOrganiserIsOnePlainAddress() throws Exception {
    assertEquals("olivia@partner.example",
                 CalendarInvitationService.organizerAddress(withOrganizer("olivia@partner.example")).getAddress());
    for (String address : List.of("team: a@x.example, b@y.example;", "a@x.example, b@y.example", "", "olivia")) {
      assertEquals(CalendarInvitationService.NOT_ANSWERABLE,
                   assertThrows(IllegalArgumentException.class,
                                () -> CalendarInvitationService.organizerAddress(withOrganizer(address))).getMessage(),
                   address);
    }
    assertThrows(IllegalArgumentException.class, () -> CalendarInvitationService.organizerAddress(new CalendarInvitation()));
  }

  /**
   * The answer is remembered per attendee address -- whatever its case -- event and
   * occurrence, under a key that says nothing of them.
   */
  @Test
  void anAnswerIsRememberedPerAddressEventAndOccurrence() {
    String key = CalendarInvitationService.answerKey(ME, "uid-1", null);
    assertTrue(key.startsWith(CalendarInvitationService.ANSWER_KEY_PREFIX));
    assertFalse(key.contains("uid-1"));
    assertEquals(key, CalendarInvitationService.answerKey(ME.toUpperCase(), "uid-1", ""));
    assertNotEquals(key, CalendarInvitationService.answerKey(ME, "uid-1", "20261013T140000"));
    assertNotEquals(key, CalendarInvitationService.answerKey(OWNER, "uid-1", null));
    assertNull(CalendarInvitationService.answerKey(null, "uid-1", null));
    assertNull(CalendarInvitationService.answerKey(ME, " ", null));
  }

  /**
   * The inline text/calendar part is preferred to an attached .ics, which is used when
   * it is the only one.
   */
  @Test
  void theInlineCalendarPartIsPreferred() {
    Email mail = new Email();
    mail.setContent(new EmailContent(""));
    mail.getContent().setAttachments(List.of(attachment("application/ics", "invite.ics", "2"),
                                             attachment("text/calendar", null, "1.3")));
    assertEquals("1.3", CalendarInvitationService.calendarPart(mail).getAttachmentRemoteId());
    mail.getContent().setAttachments(List.of(attachment("application/octet-stream", "Invite.ICS", "2")));
    assertEquals("2", CalendarInvitationService.calendarPart(mail).getAttachmentRemoteId());
    mail.getContent().setAttachments(new ArrayList<>());
    assertNull(CalendarInvitationService.calendarPart(mail));
  }

  /**
   * Puts the message in a folder of a mailbox Alice shared with the user, share 100.
   *
   * @return the share
   */
  private EmailDelegation givenASharedMailbox() {
    email.setFolder(SHARED_KEY);
    EmailDelegation delegation = new EmailDelegation();
    delegation.setId(100L);
    delegation.setOwnerMailbox(OWNER);
    when(emailDelegationService.delegationOf(USER, SHARED_KEY)).thenReturn(delegation);
    return delegation;
  }

  /**
   * Records the factories the service transmits with from the user's own mailbox.
   *
   * @return the factories, in order
   * @throws Exception never
   */
  private List<EmailBoxService.OutgoingMessageFactory> captureTransmissions() throws Exception {
    List<EmailBoxService.OutgoingMessageFactory> factories = new ArrayList<>();
    doAnswer(invocation -> factories.add(invocation.getArgument(1))).when(emailBoxService).transmitAsUser(eq(USER), any());
    return factories;
  }

  /**
   * Records the factories the service transmits with from a shared mailbox.
   *
   * @return the factories, in order
   * @throws Exception never
   */
  private List<EmailBoxService.OutgoingMessageFactory> captureSharedTransmissions() throws Exception {
    List<EmailBoxService.OutgoingMessageFactory> factories = new ArrayList<>();
    doAnswer(invocation -> {
      factories.add(invocation.getArgument(3));
      return EmailBoxService.OwnerCopy.FILED;
    }).when(emailBoxService).transmitFromSharedMailbox(eq(USER), anyLong(), any(), any());
    return factories;
  }

  /**
   * Fails when anything was transmitted, from any mailbox.
   *
   * @throws Exception never
   */
  private void verifyNothingSent() throws Exception {
    verify(emailBoxService, never()).transmitAsUser(anyString(), any());
    verify(emailBoxService, never()).transmitFromSharedMailbox(anyString(), anyLong(), any(), any());
  }

  /**
   * Serves a fixture as the message's calendar part.
   *
   * @param fixture the file under invitations/
   * @throws Exception never
   */
  private void givenTheCalendarPart(String fixture) throws Exception {
    byte[] content;
    try (java.io.InputStream input = getClass().getResourceAsStream("/invitations/" + fixture)) {
      content = input.readAllBytes();
    }
    lenient().when(emailBoxService.readMessagePart(eq(USER), any(), eq("1.3"), anyLong())).thenReturn(content);
  }

  /**
   * A received message carrying an invitation in a multipart/alternative.
   *
   * @param folder its folder
   * @return the message
   */
  private static Email invitationMail(String folder) {
    Email mail = new Email();
    mail.setId(EMAIL_ID);
    mail.setUserId(USER);
    mail.setFolder(folder);
    mail.setMailRemoteId(34L);
    mail.setMailHeaderId("<invite@partner.example>");
    mail.setContent(new EmailContent("<p>Invitation</p>"));
    mail.getContent().setAttachments(List.of(attachment("text/calendar", null, "1.3")));
    return mail;
  }

  /**
   * An attachment descriptor, as the sync writes it.
   *
   * @param mimeType its type
   * @param name its file name, may be null
   * @param partPath its section path
   * @return the descriptor
   */
  private static EmailAttachment attachment(String mimeType, String name, String partPath) {
    EmailAttachment attachment = new EmailAttachment();
    attachment.setMimeType(mimeType);
    attachment.setName(name);
    attachment.setAttachmentRemoteId(partPath);
    return attachment;
  }

  /**
   * An invitation whose organiser has an address.
   *
   * @param address the address
   * @return the invitation
   */
  private static CalendarInvitation withOrganizer(String address) {
    CalendarInvitation invitation = new CalendarInvitation();
    invitation.setOrganizer(new CalendarInvitationPerson("Olivia", address, null));
    return invitation;
  }

  /**
   * A setting naming a mailbox address.
   *
   * @param address the address
   * @return the setting
   */
  private static UserEmailSetting setting(String address) {
    UserEmailSetting setting = new UserEmailSetting();
    setting.setEmailAddress(address);
    return setting;
  }

  /**
   * A session to build messages on.
   *
   * @return the session
   */
  private static Session session() {
    return Session.getInstance(new Properties());
  }

  /**
   * A part's text, its lines unfolded.
   *
   * @param part the part
   * @return its decoded text
   * @throws Exception when it cannot be read
   */
  private static String text(BodyPart part) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    part.getDataHandler().writeTo(output);
    // Unfolded (RFC 5545 §3.1): a long line is folded at 75 octets.
    return output.toString(StandardCharsets.UTF_8).replace("\r\n ", "");
  }
}
