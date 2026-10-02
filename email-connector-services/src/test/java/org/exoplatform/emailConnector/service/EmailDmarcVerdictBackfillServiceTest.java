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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.emailConnector.utils.EmailSecurityUtils;

import io.meeds.common.ContainerTransactional;

/**
 * The DMARC verdict fill of mail cached before it was recorded (EXO-90909): gated on a
 * trusted mail server and the logo switch, bounded per user per minute and per run,
 * the user's own folders of received mail only, and the verdict computed from the
 * header alone as the sync computes it.
 */
@ExtendWith(MockitoExtension.class)
class EmailDmarcVerdictBackfillServiceTest {

  private static final String         USER      = "rita";

  private static final String         PASS      = "mx.example.com; dmarc=pass header.from=brand.example";

  @Mock
  private EmailConnectorService       emailConnectorService;

  @Mock
  private EmailBoxStorage             emailBoxStorage;

  @Mock
  private SenderLogoWebSocketService  senderLogoWebSocketService;

  @InjectMocks
  private EmailDmarcVerdictBackfillService service;

  private final List<Runnable>        queued    = new ArrayList<>();

  private final AtomicLong            now       = new AtomicLong(1_000_000L);

  private final List<List<Email>>     readRows  = new ArrayList<>();

  /**
   * Holds the runs, sets the clock, names a trusted mail server and switches the logos
   * on.
   */
  @BeforeEach
  void setUp() {
    service.setExecutor(queued::add);
    service.setClock(now::get);
    System.setProperty(EmailSecurityUtils.TRUSTED_AUTHSERV_IDS_PROPERTY, "mx.example.com");
    lenient().when(emailConnectorService.isSenderLogosEnabled()).thenReturn(true);
  }

  /**
   * Forgets the trusted mail server, and stops the pool.
   */
  @AfterEach
  void tearDown() {
    System.clearProperty(EmailSecurityUtils.TRUSTED_AUTHSERV_IDS_PROPERTY);
    service.stop();
  }

  /**
   * Nothing is scheduled, read or written while no mail server is named as trusted,
   * nor while the logos are switched off; and a run queued before the trust was unset
   * reads nothing.
   */
  @Test
  void nothingRunsWithoutATrustedMailServerOrTheSwitch() {
    when(emailConnectorService.isSenderLogosEnabled()).thenReturn(false);
    assertFalse(service.schedule(USER, MailFolder.INBOX, this::answer));
    // Switched on again: only the missing trust stops what follows.
    lenient().when(emailConnectorService.isSenderLogosEnabled()).thenReturn(true);

    System.clearProperty(EmailSecurityUtils.TRUSTED_AUTHSERV_IDS_PROPERTY);
    assertFalse(service.schedule(USER, MailFolder.INBOX, this::answer));
    assertEquals(0, service.fill(USER, MailFolder.INBOX, this::answer));
    assertEquals(List.of(), queued);
    verifyNoInteractions(emailBoxStorage, senderLogoWebSocketService);
    assertEquals(List.of(), readRows);
  }

  /**
   * The user's own folders of received mail are covered; Sent, Drafts and the all-mail
   * store are not, nor a blank user.
   */
  @Test
  void onlyFoldersOfReceivedMailAreCovered() {
    int user = 0;
    for (String folder : List.of(MailFolder.INBOX, MailFolder.ARCHIVE, MailFolder.TRASH, MailFolder.JUNK, "CUSTOM:3")) {
      assertTrue(service.schedule("user" + user++, folder, this::answer), folder);
    }
    for (String folder : List.of(MailFolder.SENT, MailFolder.DRAFTS, MailFolder.ALL_MAIL, "SCHEDULED")) {
      assertFalse(service.schedule("user" + user++, folder, this::answer), folder);
    }
    assertFalse(service.schedule(" ", MailFolder.INBOX, this::answer));
    assertFalse(service.schedule(USER, MailFolder.INBOX, null));
    assertEquals(5, queued.size());
  }

  /**
   * One run per user per minute, whatever the folders opened; another user is not held
   * back; a run dropped by a full queue is not queued.
   */
  @Test
  void oneRunPerUserPerMinute() {
    assertTrue(service.schedule(USER, MailFolder.INBOX, this::answer));
    now.addAndGet(EmailDmarcVerdictBackfillService.USER_INTERVAL_MS - 1);
    assertFalse(service.schedule(USER, MailFolder.ARCHIVE, this::answer));
    assertTrue(service.schedule("sam", MailFolder.INBOX, this::answer));
    now.addAndGet(1);
    assertTrue(service.schedule(USER, MailFolder.ARCHIVE, this::answer));
    assertEquals(3, queued.size());

    service.setExecutor(runnable -> {
      throw new RejectedExecutionException("full");
    });
    now.addAndGet(EmailDmarcVerdictBackfillService.USER_INTERVAL_MS);
    assertFalse(service.schedule(USER, MailFolder.INBOX, this::answer));
  }

  /**
   * A run reads the newest rows without a verdict, a screen at most, and the headers of
   * those rows alone; a pass from the trusted server is recorded as passed and pushed to
   * the user, a pass written by another server, a failure and a message the server no
   * longer holds are recorded as not passed.
   */
  @Test
  void aRunRecordsEachRowsVerdictFromItsHeader() {
    Email passed = row(1L, 11L, "news@brand.example");
    Email untrusted = row(2L, 12L, "news@brand.example");
    Email failed = row(3L, 13L, "alerts@bank.example");
    Email gone = row(4L, 14L, "news@brand.example");
    when(emailBoxStorage.getEmailsWithoutDmarcVerdict(USER, MailFolder.INBOX, EmailDmarcVerdictBackfillService.ROWS_PER_RUN))
        .thenReturn(List.of(passed, untrusted, failed, gone));
    when(emailBoxStorage.setDmarcVerdict(eq(USER), any(), anyBoolean())).thenAnswer(invocation -> ((Collection<?>) invocation.getArgument(1)).size());
    AuthenticationResultsReader reader = rows -> {
      readRows.add(rows);
      return Map.of(11L, new String[] { PASS },
                    12L, new String[] { "evil.example; dmarc=pass header.from=brand.example" },
                    13L, new String[] { "mx.example.com; dmarc=fail header.from=bank.example" });
    };

    assertEquals(4, service.fill(USER, MailFolder.INBOX, reader));

    assertEquals(List.of(List.of(passed, untrusted, failed, gone)), readRows);
    verify(emailBoxStorage).setDmarcVerdict(USER, List.of(1L), true);
    verify(emailBoxStorage).setDmarcVerdict(USER, List.of(2L, 3L, 4L), false);
    verify(senderLogoWebSocketService).rowsVerified(eq(USER), eq(List.of(1L)), eq(new java.util.LinkedHashSet<>(List.of("news@brand.example"))));
  }

  /**
   * A mail server that cannot be read records nothing and pushes nothing, and the folder
   * is tried again at a later opening.
   */
  @Test
  void anUnreadableServerRecordsNothing() {
    when(emailBoxStorage.getEmailsWithoutDmarcVerdict(USER, MailFolder.INBOX, EmailDmarcVerdictBackfillService.ROWS_PER_RUN))
        .thenReturn(List.of(row(1L, 11L, "news@brand.example")));

    assertEquals(0, service.fill(USER, MailFolder.INBOX, rows -> {
      throw new IllegalStateException("down");
    }));

    verify(emailBoxStorage, never()).setDmarcVerdict(anyString(), any(), anyBoolean());
    verifyNoInteractions(senderLogoWebSocketService);
    assertTrue(service.schedule(USER, MailFolder.INBOX, this::answer), "not remembered as complete");
  }

  /**
   * A folder with nothing left to fill, or whose last run took fewer rows than a screen,
   * is not looked at again; a full screen leaves it to the next run.
   */
  @Test
  void aCompleteFolderIsNotLookedAtAgain() {
    when(emailBoxStorage.getEmailsWithoutDmarcVerdict(eq(USER), eq(MailFolder.INBOX), anyInt())).thenReturn(List.of());
    service.fill(USER, MailFolder.INBOX, this::answer);
    assertFalse(service.schedule(USER, MailFolder.INBOX, this::answer));
    assertEquals(List.of(), readRows, "nothing to read");

    when(emailBoxStorage.getEmailsWithoutDmarcVerdict(eq(USER), eq(MailFolder.ARCHIVE), anyInt()))
        .thenReturn(rows(EmailDmarcVerdictBackfillService.ROWS_PER_RUN));
    service.fill(USER, MailFolder.ARCHIVE, this::answer);
    assertTrue(service.schedule(USER, MailFolder.ARCHIVE, this::answer), "a full screen: more may be left");

    when(emailBoxStorage.getEmailsWithoutDmarcVerdict(eq(USER), eq(MailFolder.JUNK), anyInt()))
        .thenReturn(rows(EmailDmarcVerdictBackfillService.ROWS_PER_RUN - 1));
    service.fill(USER, MailFolder.JUNK, this::answer);
    now.addAndGet(EmailDmarcVerdictBackfillService.USER_INTERVAL_MS);
    assertFalse(service.schedule(USER, MailFolder.JUNK, this::answer), "the last rows were taken");
  }

  /**
   * The run binds the container on the pool's thread: removing the annotation leaves
   * the storage and the credentials without one, which no call made by hand shows.
   *
   * @throws Exception never
   */
  @Test
  void theRunBindsTheContainer() throws Exception {
    assertTrue(EmailDmarcVerdictBackfillService.class.getMethod("run", String.class, String.class, AuthenticationResultsReader.class)
                                                     .isAnnotationPresent(ContainerTransactional.class));
  }

  /**
   * A reader answering a pass for every row it is given.
   *
   * @param rows the rows
   * @return a pass by UID
   */
  private Map<Long, String[]> answer(List<Email> rows) {
    readRows.add(rows);
    Map<Long, String[]> headers = new java.util.HashMap<>();
    rows.forEach(row -> headers.put(row.getMailRemoteId(), new String[] { PASS }));
    return headers;
  }

  /**
   * Rows from the brand.
   *
   * @param count how many
   * @return the rows
   */
  private static List<Email> rows(int count) {
    return IntStream.range(0, count).mapToObj(i -> row(i, 100L + i, "news@brand.example")).toList();
  }

  /**
   * A row as the fill reads it.
   *
   * @param id its id
   * @param uid its UID
   * @param address its sender's address
   * @return the row
   */
  private static Email row(long id, long uid, String address) {
    Email email = new Email();
    email.setId(id);
    email.setMailRemoteId(uid);
    email.setSender(new EmailSender(null, address, null, null));
    return email;
  }
}
