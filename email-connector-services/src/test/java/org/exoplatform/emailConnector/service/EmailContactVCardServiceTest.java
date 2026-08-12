/**
 * Copyright (C) 2025 eXo Platform SAS
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.commons.file.model.FileItem;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.component.RequestLifeCycle;
import org.exoplatform.emailConnector.carddav.EzVCardParser;
import org.exoplatform.emailConnector.carddav.VCardParser;
import org.exoplatform.emailConnector.model.ContactImportState;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailContact;
import org.exoplatform.emailConnector.model.EmailContactPage;
import org.exoplatform.emailConnector.model.EmailContactSource;
import org.exoplatform.emailConnector.model.SyncStatus;
import org.exoplatform.emailConnector.storage.EmailContactStorage;
import org.exoplatform.upload.UploadResource;
import org.exoplatform.upload.UploadService;

/**
 * The import's skip-and-report contract and the export's whole-store promise.
 * Every rule here decides what happens to somebody's real address book, so
 * each is pinned on its own — and the parser is the real one, because the
 * rules are about what real files do.
 */
@ExtendWith(MockitoExtension.class)
public class EmailContactVCardServiceTest {

  private static final String                    USERNAME  = "alice";

  private static final String                    UPLOAD_ID = "upload-1";

  @Mock
  private EmailContactService                    emailContactService;

  @Mock
  private EmailContactStorage                    emailContactStorage;

  @Mock
  private UserEmailSettingService                userEmailSettingService;

  @Mock
  private UploadService                          uploadService;

  @Mock
  private EmailBoxService                        emailBoxService;

  @Spy
  private VCardParser                            vCardParser = new EzVCardParser();

  @Spy
  @InjectMocks
  private EmailContactVCardService               service;

  @TempDir
  private Path                                   tempDir;

  // The import thread binds a persistence context by hand; a unit test has no
  // container to bind one from, so the lifecycle statics are stubbed away.
  private MockedStatic<RequestLifeCycle>         requestLifeCycle;

  private MockedStatic<ExoContainerContext>      containerContext;

  /**
   * Runs the scheduled import synchronously and silences the container
   * lifecycle, so every test reads its report right after the call.
   */
  @BeforeEach
  void setUp() {
    requestLifeCycle = mockStatic(RequestLifeCycle.class);
    containerContext = mockStatic(ExoContainerContext.class);
    lenient().doAnswer(invocation -> {
      ((Runnable) invocation.getArgument(0)).run();
      return null;
    }).when(service).scheduleRun(any(Runnable.class));
  }

  /**
   * Releases the static stubs.
   */
  @AfterEach
  void tearDown() {
    requestLifeCycle.close();
    containerContext.close();
  }

  @Test
  void aFreshFileImportsEveryCardAsAManualRow() throws Exception {
    givenUpload("""
        BEGIN:VCARD
        VERSION:3.0
        FN:Jane Doe
        N:Doe;Jane;;;
        EMAIL;TYPE=INTERNET,PREF:Jane@Example.com
        TEL:+33612345678
        ORG:Acme
        END:VCARD
        BEGIN:VCARD
        VERSION:3.0
        FN:John Roe
        EMAIL:john@example.com
        END:VCARD""");
    when(emailContactStorage.getContactByAddress(eq(USERNAME), anyString())).thenReturn(null);

    service.startImport(USERNAME, UPLOAD_ID);

    ArgumentCaptor<EmailContact> created = ArgumentCaptor.forClass(EmailContact.class);
    verify(emailContactStorage, times(2)).createContact(created.capture());
    EmailContact first = created.getAllValues().get(0);
    assertEquals(EmailContactSource.MANUAL, first.getSource());
    assertEquals("jane@example.com", first.getPrimaryEmail());
    assertEquals("Jane Doe", first.getDisplayName());
    assertEquals(List.of("+33612345678"), first.getPhones());
    ContactImportState state = lastPersistedState();
    assertEquals(SyncStatus.SUCCESS, state.getStatus());
    assertEquals(2, state.getImported());
    assertEquals(0, state.getAlreadyKnown());
    assertNull(state.getMessageCode());
    verify(uploadService).removeUploadResource(UPLOAD_ID);
  }

  @Test
  void aCardKnownByItsSecondaryAddressIsSkippedWhole() throws Exception {
    // The person is already here, filed under their other address: importing
    // the card would make them two rows, which is the thing to prevent.
    givenUpload("""
        BEGIN:VCARD
        VERSION:3.0
        FN:Jane Doe
        EMAIL;TYPE=PREF:jane.new@example.com
        EMAIL:jane.known@example.com
        END:VCARD""");
    when(emailContactStorage.getContactByAddress(USERNAME, "jane.new@example.com")).thenReturn(null);
    when(emailContactStorage.getContactByAddress(USERNAME, "jane.known@example.com")).thenReturn(visibleContact(5L));

    service.startImport(USERNAME, UPLOAD_ID);

    verify(emailContactStorage, never()).createContact(any());
    assertEquals(1, lastPersistedState().getAlreadyKnown());
  }

  @Test
  void aSuppressedTombstoneIsNeitherRevivedNorDuplicated() throws Exception {
    // The user deleted this person once. A bulk import must respect that: the
    // card counts as already known and the tombstone stays exactly as it is.
    givenUpload("""
        BEGIN:VCARD
        VERSION:3.0
        FN:Removed Person
        EMAIL:removed@example.com
        END:VCARD""");
    EmailContact tombstone = visibleContact(9L);
    tombstone.setSuppressed(true);
    when(emailContactStorage.getContactByAddress(USERNAME, "removed@example.com")).thenReturn(tombstone);

    service.startImport(USERNAME, UPLOAD_ID);

    verify(emailContactStorage, never()).createContact(any());
    verify(emailContactStorage, never()).updateContact(any());
    ContactImportState state = lastPersistedState();
    assertEquals(1, state.getAlreadyKnown());
    assertEquals(0, state.getImported());
  }

  @Test
  void reImportingTheSameFileIsAVisibleNoOp() throws Exception {
    String vcf = """
        BEGIN:VCARD
        VERSION:3.0
        FN:Jane Doe
        EMAIL:jane@example.com
        END:VCARD
        BEGIN:VCARD
        VERSION:3.0
        FN:John Roe
        EMAIL:john@example.com
        END:VCARD""";
    // A store that actually remembers what the first run created, which is
    // exactly what makes the second run find everything already known.
    Set<String> known = new HashSet<>();
    lenient().when(emailContactStorage.getContactByAddress(eq(USERNAME), anyString()))
             .thenAnswer(invocation -> known.contains(invocation.getArgument(1)) ? visibleContact(1L) : null);
    lenient().when(emailContactStorage.createContact(any())).thenAnswer(invocation -> {
      EmailContact contact = invocation.getArgument(0);
      known.add(contact.getPrimaryEmail());
      return contact;
    });

    givenUpload(vcf);
    service.startImport(USERNAME, UPLOAD_ID);
    assertEquals(2, lastPersistedState().getImported());

    givenUpload(vcf);
    service.startImport(USERNAME, UPLOAD_ID);

    verify(emailContactStorage, times(2)).createContact(any());
    ContactImportState second = lastPersistedState();
    assertEquals(0, second.getImported());
    assertEquals(2, second.getAlreadyKnown());
  }

  @Test
  void aPhotoOverTheCapCostsThePhotoNotTheContact() throws Exception {
    byte[] oversized = new byte[EmailContactVCardService.MAX_PHOTO_BYTES + 1];
    givenUpload("BEGIN:VCARD\nVERSION:3.0\nFN:Jane Doe\nEMAIL:jane@example.com\nPHOTO;ENCODING=b;TYPE=JPEG:"
        + Base64.getEncoder().encodeToString(oversized) + "\nEND:VCARD");
    when(emailContactStorage.getContactByAddress(eq(USERNAME), anyString())).thenReturn(null);

    service.startImport(USERNAME, UPLOAD_ID);

    ArgumentCaptor<EmailContact> created = ArgumentCaptor.forClass(EmailContact.class);
    verify(emailContactStorage).createContact(created.capture());
    assertNull(created.getValue().getPhotoFileId());
    verify(emailContactStorage, never()).savePhotoBytes(any(), any(), any());
    assertEquals(1, lastPersistedState().getImported());
  }

  @Test
  void aPhotoUnderTheCapIsStoredWithItsContact() throws Exception {
    givenUpload("""
        BEGIN:VCARD
        VERSION:3.0
        FN:Jane Doe
        EMAIL:jane@example.com
        PHOTO;ENCODING=b;TYPE=JPEG:/9j/4AAQSkZJRg==
        END:VCARD""");
    when(emailContactStorage.getContactByAddress(eq(USERNAME), anyString())).thenReturn(null);
    when(emailContactStorage.savePhotoBytes(eq(null), any(), eq("image/jpeg"))).thenReturn(42L);

    service.startImport(USERNAME, UPLOAD_ID);

    ArgumentCaptor<EmailContact> created = ArgumentCaptor.forClass(EmailContact.class);
    verify(emailContactStorage).createContact(created.capture());
    assertEquals(42L, created.getValue().getPhotoFileId());
  }

  @Test
  void theCardCapStopsTheRunWithAPartialReport() throws Exception {
    StringBuilder vcf = new StringBuilder();
    for (int i = 0; i <= EmailContactVCardService.MAX_IMPORT_CARDS; i++) {
      vcf.append("BEGIN:VCARD\nVERSION:3.0\nFN:Person ").append(i)
         .append("\nEMAIL:person").append(i).append("@example.com\nEND:VCARD\n");
    }
    givenUpload(vcf.toString());
    when(emailContactStorage.getContactByAddress(eq(USERNAME), anyString())).thenReturn(null);

    service.startImport(USERNAME, UPLOAD_ID);

    verify(emailContactStorage, times(EmailContactVCardService.MAX_IMPORT_CARDS)).createContact(any());
    ContactImportState state = lastPersistedState();
    assertEquals(SyncStatus.SUCCESS, state.getStatus());
    assertEquals(EmailContactVCardService.MAX_IMPORT_CARDS, state.getImported());
    assertEquals(EmailContactVCardService.IMPORT_TOO_MANY_CARDS, state.getMessageCode());
  }

  @Test
  void aFileOfExactlyTheCapIsNotReportedTruncated() throws Exception {
    StringBuilder vcf = new StringBuilder();
    for (int i = 0; i < EmailContactVCardService.MAX_IMPORT_CARDS; i++) {
      vcf.append("BEGIN:VCARD\nVERSION:3.0\nFN:Person ").append(i)
         .append("\nEMAIL:person").append(i).append("@example.com\nEND:VCARD\n");
    }
    givenUpload(vcf.toString());
    when(emailContactStorage.getContactByAddress(eq(USERNAME), anyString())).thenReturn(null);

    service.startImport(USERNAME, UPLOAD_ID);

    ContactImportState state = lastPersistedState();
    assertEquals(EmailContactVCardService.MAX_IMPORT_CARDS, state.getImported());
    assertNull(state.getMessageCode());
  }

  @Test
  void aSecondImportWhileOneRunsIsRefused() throws Exception {
    givenUpload("BEGIN:VCARD\nVERSION:3.0\nFN:One\nEMAIL:one@example.com\nEND:VCARD");
    // The first run is scheduled but never executed, exactly the window a
    // double-click hits in production.
    doNothing().when(service).scheduleRun(any());
    service.startImport(USERNAME, UPLOAD_ID);

    IllegalStateException refusal = assertThrows(IllegalStateException.class,
                                                 () -> service.startImport(USERNAME, UPLOAD_ID));

    assertEquals(EmailContactVCardService.IMPORT_ALREADY_RUNNING, refusal.getMessage());
  }

  @Test
  void aFileOverTheSizeCapIsRefusedBeforeParsing() throws Exception {
    Path file = tempDir.resolve("huge.vcf");
    try (RandomAccessFile huge = new RandomAccessFile(file.toFile(), "rw")) {
      huge.setLength(EmailContactVCardService.MAX_IMPORT_FILE_BYTES + 1);
    }
    UploadResource upload = new UploadResource(UPLOAD_ID);
    upload.setStoreLocation(file.toString());
    when(uploadService.getUploadResource(UPLOAD_ID)).thenReturn(upload);

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> service.startImport(USERNAME, UPLOAD_ID));

    assertEquals(EmailContactVCardService.IMPORT_FILE_TOO_LARGE, refusal.getMessage());
    // Not one byte of it was parsed, the upload is gone, and the guard is
    // released — the user can retry with a sane file at once.
    verify(vCardParser, never()).parseAll(any(), any());
    verify(uploadService).removeUploadResource(UPLOAD_ID);
    givenUpload("BEGIN:VCARD\nVERSION:3.0\nFN:One\nEMAIL:one@example.com\nEND:VCARD");
    when(emailContactStorage.getContactByAddress(eq(USERNAME), anyString())).thenReturn(null);
    service.startImport(USERNAME, UPLOAD_ID);
    assertEquals(1, lastPersistedState().getImported());
  }

  @Test
  void aMissingUploadIsRefusedAndReleasesTheGuard() throws Exception {
    when(uploadService.getUploadResource(UPLOAD_ID)).thenReturn(null);

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> service.startImport(USERNAME, UPLOAD_ID));

    assertEquals(EmailContactVCardService.IMPORT_UPLOAD_MISSING, refusal.getMessage());
    givenUpload("BEGIN:VCARD\nVERSION:3.0\nFN:One\nEMAIL:one@example.com\nEND:VCARD");
    when(emailContactStorage.getContactByAddress(eq(USERNAME), anyString())).thenReturn(null);
    service.startImport(USERNAME, UPLOAD_ID);
    assertEquals(1, lastPersistedState().getImported());
  }

  @Test
  void aRunThatBreaksStillReportsAndCleansUp() throws Exception {
    // The upload vanished between the request thread and the run: the report
    // must say FAILURE rather than leave the poll on IN_PROGRESS for ever.
    UploadResource upload = new UploadResource(UPLOAD_ID);
    upload.setStoreLocation(tempDir.resolve("gone.vcf").toString());
    when(uploadService.getUploadResource(UPLOAD_ID)).thenReturn(upload);

    service.startImport(USERNAME, UPLOAD_ID);

    ContactImportState state = lastPersistedState();
    assertEquals(SyncStatus.FAILURE, state.getStatus());
    assertEquals(EmailContactVCardService.IMPORT_FAILED, state.getMessageCode());
    verify(uploadService).removeUploadResource(UPLOAD_ID);
    // The guard is released even by a broken run.
    givenUpload("BEGIN:VCARD\nVERSION:3.0\nFN:One\nEMAIL:one@example.com\nEND:VCARD");
    when(emailContactStorage.getContactByAddress(eq(USERNAME), anyString())).thenReturn(null);
    service.startImport(USERNAME, UPLOAD_ID);
    assertEquals(1, lastPersistedState().getImported());
  }

  @Test
  void aCardWithoutAnAddressIsItsOwnNumberInTheReport() throws Exception {
    givenUpload("""
        BEGIN:VCARD
        VERSION:3.0
        FN:Phone Only
        TEL:+33199999999
        END:VCARD
        BEGIN:VCARD
        VERSION:3.0
        FN:Jane Doe
        EMAIL:jane@example.com
        END:VCARD""");
    when(emailContactStorage.getContactByAddress(eq(USERNAME), anyString())).thenReturn(null);

    service.startImport(USERNAME, UPLOAD_ID);

    ContactImportState state = lastPersistedState();
    assertEquals(1, state.getNoAddress());
    assertEquals(1, state.getImported());
    verify(emailContactStorage, times(1)).createContact(any());
  }

  @Test
  void theExportWritesTheWholeStoreWithLiveNamesAndServerUids() throws Exception {
    EmailContact manual = visibleContact(1L);
    manual.setDisplayName("Jane Doe");
    manual.setSecondaryEmails(List.of("jane.work@example.com"));
    manual.setPhones(List.of("+33612345678"));
    EmailContact carddav = visibleContact(2L);
    carddav.setSource(EmailContactSource.CARDDAV);
    carddav.setPrimaryEmail("book@example.com");
    carddav.setDisplayName("Book Person");
    EmailContact directory = visibleContact(3L);
    directory.setSource(EmailContactSource.DIRECTORY);
    directory.setPrimaryEmail("colleague@example.com");
    // What getContacts answers IS the live-resolved name: the export reads
    // through the same enrichment the drawer does.
    directory.setDisplayName("Renamed Colleague");
    when(emailContactService.getContacts(USERNAME, null, null, false, 0, 200))
        .thenReturn(new EmailContactPage(List.of(manual, carddav, directory), Map.of(), 3, 0, 200));
    when(emailContactStorage.getVcardUids(USERNAME)).thenReturn(Map.of(2L, "server-uid-2"));
    StringWriter out = new StringWriter();

    service.exportContacts(USERNAME, out);

    String vcf = out.toString();
    assertEquals(3, vcf.split("BEGIN:VCARD", -1).length - 1);
    assertTrue(vcf.contains("UID:server-uid-2"));
    // The manual and directory rows leave WITHOUT a uid: one this store
    // invented would mean nothing to any importer.
    assertEquals(1, vcf.split("UID:", -1).length - 1);
    assertTrue(vcf.contains("Renamed Colleague"));
    assertTrue(vcf.contains("jane.work@example.com"));
    assertTrue(vcf.contains("VERSION:3.0"));
  }

  @Test
  void theExportCarriesTheStoredPhoto() throws Exception {
    EmailContact withPhoto = visibleContact(1L);
    withPhoto.setPhotoFileId(42L);
    when(emailContactService.getContacts(USERNAME, null, null, false, 0, 200))
        .thenReturn(new EmailContactPage(List.of(withPhoto), Map.of(), 1, 0, 200));
    when(emailContactStorage.getVcardUids(USERNAME)).thenReturn(Map.of());
    FileItem photo = new FileItem(42L, "emailContactPhoto", "image/jpeg", "emailConnector", 4L, null, null, false,
                                  new java.io.ByteArrayInputStream(new byte[] { 1, 2, 3, 4 }));
    when(emailContactStorage.getPhotoFileItem(42L)).thenReturn(photo);
    StringWriter out = new StringWriter();

    service.exportContacts(USERNAME, out);

    assertTrue(out.toString().contains("PHOTO"));
  }

  @Test
  void theExportPagesThroughABigStore() throws Exception {
    EmailContact pageOneRow = visibleContact(1L);
    EmailContact pageTwoRow = visibleContact(2L);
    pageTwoRow.setPrimaryEmail("second@example.com");
    when(emailContactService.getContacts(USERNAME, null, null, false, 0, 200))
        .thenReturn(new EmailContactPage(java.util.Collections.nCopies(200, pageOneRow), Map.of(), 201, 0, 200));
    when(emailContactService.getContacts(USERNAME, null, null, false, 200, 200))
        .thenReturn(new EmailContactPage(List.of(pageTwoRow), Map.of(), 201, 200, 200));
    when(emailContactStorage.getVcardUids(USERNAME)).thenReturn(Map.of());
    StringWriter out = new StringWriter();

    service.exportContacts(USERNAME, out);

    verify(emailContactService).getContacts(USERNAME, null, null, false, 200, 200);
    assertEquals(201, out.toString().split("BEGIN:VCARD", -1).length - 1);
  }

  // -------------------------------------------------------------------------
  // Export-then-start-fresh -- the provider switch's destructive half. The
  // ordering IS the feature: no deletion until the backup left, a failed
  // backup deletes nothing, and there is no undo past a delivered one.
  // -------------------------------------------------------------------------

  @Test
  void nothingIsDeletedUntilTheWholeBackupWasWritten() throws Exception {
    EmailContact contact = visibleContact(1L);
    when(emailContactService.getContacts(USERNAME, null, null, false, 0, 200))
        .thenReturn(new EmailContactPage(List.of(contact), Map.of(), 1, 0, 200));
    when(emailContactStorage.getVcardUids(USERNAME)).thenReturn(Map.of());
    StringWriter out = new StringWriter();
    // Captured AT THE MOMENT of deletion, not after the call returns: the
    // assertion is about the order inside the operation, and only what the
    // writer already held when startFresh ran can prove it.
    StringBuilder backupWhenDeleted = new StringBuilder();
    when(emailContactService.startFresh(USERNAME)).thenAnswer(invocation -> {
      backupWhenDeleted.append(out);
      return 1;
    });

    int deleted = service.exportContactsThenStartFresh(USERNAME, out);

    assertEquals(1, deleted);
    verify(emailContactService).startFresh(USERNAME);
    assertTrue(backupWhenDeleted.toString().contains("BEGIN:VCARD"),
               "the row being deleted was already in the user's backup when the deletion ran");
    assertEquals(out.toString(), backupWhenDeleted.toString(), "not one byte was written after the deletion started");
  }

  @Test
  void aFailedBackupDeletesNothing() {
    EmailContact contact = visibleContact(1L);
    when(emailContactService.getContacts(USERNAME, null, null, false, 0, 200))
        .thenReturn(new EmailContactPage(List.of(contact), Map.of(), 1, 0, 200));
    when(emailContactStorage.getVcardUids(USERNAME)).thenReturn(Map.of());
    // The writer is the HTTP response: an abandoned download or a dead
    // connection is exactly a write that throws.
    java.io.Writer abandoned = new java.io.Writer() {
      /** Fails like a connection the browser closed mid-download. */
      @Override
      public void write(char[] cbuf, int off, int len) throws java.io.IOException {
        throw new java.io.IOException("connection reset by peer");
      }

      /** Never reached before the write failed. */
      @Override
      public void flush() {
      }

      /** Closed by the caller, not this test. */
      @Override
      public void close() {
      }
    };

    assertThrows(java.io.IOException.class, () -> service.exportContactsThenStartFresh(USERNAME, abandoned));

    // The whole point of the ordering: a backup that never reached the user
    // leaves the store exactly as it was.
    verify(emailContactService, never()).startFresh(anyString());
  }

  @Test
  void aStoreThatCannotBeReadDeletesNothingEither() {
    // Not only the writer: any failure inside the export -- a page read
    // included -- must leave the store untouched, because the backup the user
    // was promised does not exist.
    when(emailContactStorage.getVcardUids(USERNAME)).thenReturn(Map.of());
    when(emailContactService.getContacts(USERNAME, null, null, false, 0, 200))
        .thenThrow(new RuntimeException("database gone"));

    assertThrows(RuntimeException.class, () -> service.exportContactsThenStartFresh(USERNAME, new StringWriter()));

    verify(emailContactService, never()).startFresh(anyString());
  }

  @Test
  void parsingAnswersTheFirstCardsFieldsAndStoresNothing() throws Exception {
    // Two cards on purpose: the parse is defined as "the FIRST card", and the
    // second one must not leak into the answer nor into the store.
    givenUpload("""
        BEGIN:VCARD
        VERSION:3.0
        FN:Jane Doe
        N:Doe;Jane;;;
        EMAIL;TYPE=INTERNET,PREF:Jane@Example.com
        TEL:+33612345678
        ORG:Acme
        END:VCARD
        BEGIN:VCARD
        VERSION:3.0
        FN:John Roe
        EMAIL:john@example.com
        END:VCARD""");

    EmailContact parsed = service.parseFirstCard(USERNAME, UPLOAD_ID);

    assertEquals("Jane Doe", parsed.getDisplayName());
    assertEquals("jane@example.com", parsed.getPrimaryEmail());
    assertEquals(List.of("+33612345678"), parsed.getPhones());
    assertEquals("Acme", parsed.getOrganization());
    // No source and no id: a prefill is not a row. The source is decided by the
    // save that follows, exactly as it is on the mail attachment's prefill —
    // both paths go through toPrefill, so the same card reads the same way
    // whether it arrived by chat or by mail.
    assertNull(parsed.getSource());
    assertNull(parsed.getId());
    // Nothing persisted: the whole point is a form the user still confirms.
    verify(emailContactStorage, never()).createContact(any());
    verify(emailContactStorage, never()).savePhotoBytes(any(), any(), any());
    // The upload is consumed: a parse is a one-shot read, not a handle.
    verify(uploadService).removeUploadResource(UPLOAD_ID);
  }

  @Test
  void parsingLeavesThePhotoAlone() throws Exception {
    // Mapping a photo STORES its bytes, and a parse may store nothing — so the
    // photo is deliberately absent from the answer, not resized, not inlined.
    givenUpload("BEGIN:VCARD\nVERSION:3.0\nFN:Jane Doe\nEMAIL:jane@example.com\nPHOTO;ENCODING=b;TYPE=JPEG:"
        + Base64.getEncoder().encodeToString(new byte[] { 1, 2, 3, 4 }) + "\nEND:VCARD");

    EmailContact parsed = service.parseFirstCard(USERNAME, UPLOAD_ID);

    assertNull(parsed.getPhotoFileId());
    verify(emailContactStorage, never()).savePhotoBytes(any(), any(), any());
  }

  @Test
  void parsingRefusesAMissingUpload() {
    when(uploadService.getUploadResource(UPLOAD_ID)).thenReturn(null);

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> service.parseFirstCard(USERNAME, UPLOAD_ID));

    assertEquals(EmailContactVCardService.IMPORT_UPLOAD_MISSING, refusal.getMessage());
  }

  @Test
  void parsingRefusesAFileWithNoReadableCard() throws Exception {
    givenUpload("this is not a vCard at all");

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> service.parseFirstCard(USERNAME, UPLOAD_ID));

    assertEquals(EmailContactVCardService.PARSE_NO_CARD, refusal.getMessage());
    // Consumed even when refused: the caller retries with a new upload.
    verify(uploadService).removeUploadResource(UPLOAD_ID);
  }

  @Test
  void parsingRefusesAFileOverTheSizeCapBeforeParsing() throws Exception {
    Path file = tempDir.resolve("huge-parse.vcf");
    try (RandomAccessFile huge = new RandomAccessFile(file.toFile(), "rw")) {
      huge.setLength(EmailContactVCardService.MAX_IMPORT_FILE_BYTES + 1);
    }
    UploadResource upload = new UploadResource(UPLOAD_ID);
    upload.setStoreLocation(file.toString());
    when(uploadService.getUploadResource(UPLOAD_ID)).thenReturn(upload);

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> service.parseFirstCard(USERNAME, UPLOAD_ID));

    assertEquals(EmailContactVCardService.IMPORT_FILE_TOO_LARGE, refusal.getMessage());
    verify(vCardParser, never()).parseAll(any(), any());
    verify(uploadService).removeUploadResource(UPLOAD_ID);
  }

  /**
   * Stages a .vcf as the upload the service will read.
   *
   * @param vcf the file content
   * @throws Exception when the temp file cannot be written
   */
  private void givenUpload(String vcf) throws Exception {
    Path file = Files.createTempFile(tempDir, "import", ".vcf");
    Files.write(file, vcf.getBytes(StandardCharsets.UTF_8));
    UploadResource upload = new UploadResource(UPLOAD_ID);
    upload.setStoreLocation(file.toString());
    when(uploadService.getUploadResource(UPLOAD_ID)).thenReturn(upload);
  }

  /**
   * The state the run last persisted — with the synchronous scheduler, the
   * final report.
   *
   * @return the last persisted state
   */
  private ContactImportState lastPersistedState() {
    ArgumentCaptor<ContactImportState> captor = ArgumentCaptor.forClass(ContactImportState.class);
    verify(userEmailSettingService, atLeastOnce()).setContactImportState(captor.capture(), eq(USERNAME));
    return captor.getValue();
  }

  /**
   * A plain visible collected contact of the acting user.
   *
   * @param id the row id
   * @return the contact
   */
  private EmailContact visibleContact(long id) {
    EmailContact contact = new EmailContact();
    contact.setId(id);
    contact.setUserId(USERNAME);
    contact.setSource(EmailContactSource.COLLECTED);
    contact.setPrimaryEmail("someone" + id + "@example.com");
    return contact;
  }

  @Test
  void theQrCardCarriesTheTextFieldsAndNeverThePhoto() {
    // The photo's absence is the QR's whole engineering constraint: with an
    // embedded picture the code is either ungenerable or unscannable.
    EmailContact contact = visibleContact(1L);
    contact.setDisplayName("Jane Doe");
    contact.setPhotoFileId(42L);
    contact.setPhones(List.of("+33612345678"));
    when(emailContactService.getContact(1L, USERNAME)).thenReturn(contact);

    String vcard = service.getContactVCard(USERNAME, 1L);

    assertTrue(vcard.contains("FN:Jane Doe"));
    assertTrue(vcard.contains("TEL"));
    assertTrue(!vcard.contains("PHOTO"));
    // The stored picture is not even read: the QR path must stay cheap and
    // must not fail over a file it has no use for.
    verify(emailContactStorage, never()).getPhotoFileItem(any());
    // No UID either: a phone saving a scanned card has no server to reconcile with.
    assertTrue(!vcard.contains("UID"));
  }

  @Test
  void theQrCardOfSomebodyElsesContactIsNothing() {
    when(emailContactService.getContact(9L, USERNAME)).thenReturn(null);

    assertNull(service.getContactVCard(USERNAME, 9L));
  }

  @Test
  void aPersonWithAPhoneAndNoAddressIsImportedOnTheirCardIdentity() throws Exception {
    // The sync keeps such people -- a contact is a person, addresses are
    // attributes -- and the import used to refuse them for want of anything to
    // recognise them by. The card's own UID is that something.
    givenUpload("""
        BEGIN:VCARD
        VERSION:3.0
        UID:urn:uuid:phone-only
        FN:Bruno Sanchez
        TEL;TYPE=CELL:+33612340001
        END:VCARD""");
    EmailContact created = visibleContact(31L);
    when(emailContactStorage.createContact(any())).thenReturn(created);

    service.startImport(USERNAME, UPLOAD_ID);

    ContactImportState state = lastPersistedState();
    assertEquals(1, state.getImported());
    assertEquals(0, state.getNoAddress());
    verify(emailContactStorage).setVcardUid(31L, "urn:uuid:phone-only");
  }

  @Test
  void reimportingThatPersonRecognisesThem() throws Exception {
    givenUpload("""
        BEGIN:VCARD
        VERSION:3.0
        UID:urn:uuid:phone-only
        FN:Bruno Sanchez
        TEL;TYPE=CELL:+33612340001
        END:VCARD""");
    when(emailContactStorage.getContactByVcardUid(USERNAME, "urn:uuid:phone-only")).thenReturn(visibleContact(31L));

    service.startImport(USERNAME, UPLOAD_ID);

    ContactImportState state = lastPersistedState();
    assertEquals(0, state.getImported());
    assertEquals(1, state.getAlreadyKnown());
    verify(emailContactStorage, never()).createContact(any());
  }

  @Test
  void theCardFieldsLandOnTheImportedRow() throws Exception {
    // Birthday, address, note and website used to be lost twice — dropped on
    // read AND unwritable on export. This pins the read half of the fix.
    givenUpload("""
        BEGIN:VCARD
        VERSION:3.0
        FN:Jane Doe
        EMAIL:jane@example.com
        BDAY:1985-04-12
        ADR:;;12 rue de la Paix;Paris;;75002;France
        NOTE:Met at FOSDEM.\\nPrefers email.
        URL:https://janedoe.example
        END:VCARD""");
    when(emailContactStorage.getContactByAddress(eq(USERNAME), anyString())).thenReturn(null);

    service.startImport(USERNAME, UPLOAD_ID);

    ArgumentCaptor<EmailContact> created = ArgumentCaptor.forClass(EmailContact.class);
    verify(emailContactStorage).createContact(created.capture());
    EmailContact row = created.getValue();
    assertEquals("1985-04-12", row.getBirthday());
    assertEquals("12 rue de la Paix", row.getPostalAddress().street());
    assertEquals("Paris", row.getPostalAddress().city());
    assertEquals("75002", row.getPostalAddress().postalCode());
    assertEquals("France", row.getPostalAddress().country());
    assertEquals("Met at FOSDEM.\nPrefers email.", row.getNote());
    assertEquals("https://janedoe.example", row.getWebsite());
  }

  @Test
  void theExportWritesTheCardFieldsBack() throws Exception {
    // The write half: what the store holds must leave in the vCard's own slots
    // — a structured ADR, not one long street line.
    EmailContact contact = visibleContact(1L);
    contact.setBirthday("--04-12");
    contact.setPostalAddress(new org.exoplatform.emailConnector.model.PostalAddress("12 rue de la Paix",
                                                                                    "Paris",
                                                                                    null,
                                                                                    "75002",
                                                                                    "France"));
    contact.setNote("Met at FOSDEM.");
    contact.setWebsite("https://janedoe.example");
    when(emailContactService.getContacts(USERNAME, null, null, false, 0, 200))
        .thenReturn(new EmailContactPage(List.of(contact), Map.of(), 1, 0, 200));
    when(emailContactStorage.getVcardUids(USERNAME)).thenReturn(Map.of());
    StringWriter out = new StringWriter();

    service.exportContacts(USERNAME, out);

    String vcf = out.toString();
    assertTrue(vcf.contains("BDAY:--04-12"));
    assertTrue(vcf.contains("ADR:;;12 rue de la Paix;Paris;;75002;France"));
    assertTrue(vcf.contains("NOTE:Met at FOSDEM."));
    assertTrue(vcf.contains("URL:https://janedoe.example"));
  }

  @Test
  void theAttachmentCardCarriesTheStoredPhotoTheQrMustDrop() throws Exception {
    // Same contact, two shapes: the QR path drops the picture for the byte
    // budget a scannable code has, the attachment path has no such budget and
    // ships it. The UID stays absent on both — CardDAV bookkeeping is not the
    // recipient's to reconcile.
    EmailContact contact = visibleContact(1L);
    contact.setDisplayName("Jane Doe");
    contact.setPhotoFileId(42L);
    when(emailContactService.getContact(1L, USERNAME)).thenReturn(contact);
    FileItem photo = new FileItem(42L, "emailContactPhoto", "image/jpeg", "emailConnector", 4L, null, null, false,
                                  new java.io.ByteArrayInputStream(new byte[] { 1, 2, 3, 4 }));
    when(emailContactStorage.getPhotoFileItem(42L)).thenReturn(photo);

    String vcard = service.getContactVCard(USERNAME, 1L, true);

    assertTrue(vcard.contains("FN:Jane Doe"));
    assertTrue(vcard.contains("PHOTO"));
    assertTrue(!vcard.contains("UID"));
  }

  @Test
  void theAttachmentCardOfAColleagueNeverShipsThePlatformAvatar() {
    // A directory row's picture IS the colleague's profile avatar, resolved
    // live — not this store's to mail out, photo=true or not.
    EmailContact directory = visibleContact(1L);
    directory.setSource(EmailContactSource.DIRECTORY);
    directory.setDisplayName("Colleague");
    directory.setPhotoFileId(42L);
    when(emailContactService.getContact(1L, USERNAME)).thenReturn(directory);

    String vcard = service.getContactVCard(USERNAME, 1L, true);

    assertTrue(!vcard.contains("PHOTO"));
    verify(emailContactStorage, never()).getPhotoFileItem(any());
  }

  @Test
  void aVCardAttachmentPrefillsTheFirstCardOnly() throws Exception {
    // One mail, one person: the second card of the file belongs to the bulk
    // import, and nothing of either card is persisted by the read.
    String vcf = """
        BEGIN:VCARD
        VERSION:3.0
        FN:Jane Doe
        N:Doe;Jane;;;
        EMAIL;TYPE=INTERNET,PREF:Jane@Example.com
        TEL:+33612345678
        ORG:Acme
        PHOTO;ENCODING=b;TYPE=JPEG:/9j/4AAQSkZJRg==
        END:VCARD
        BEGIN:VCARD
        VERSION:3.0
        FN:John Roe
        EMAIL:john@example.com
        END:VCARD""";
    when(emailBoxService.getAttachmentByMailRemoteIdAnIdAndUserId(7L, "2", USERNAME))
        .thenReturn(new EmailAttachment(null, 7L, "2", "jane.vcf", "text/vcard", vcf.getBytes(StandardCharsets.UTF_8)));

    EmailContact prefill = service.getAttachmentContact(USERNAME, 7L, "2");

    assertEquals("jane@example.com", prefill.getPrimaryEmail());
    assertEquals("Jane", prefill.getGivenName());
    assertEquals("Doe", prefill.getFamilyName());
    assertEquals(List.of("+33612345678"), prefill.getPhones());
    assertEquals("Acme", prefill.getOrganization());
    // Unpersisted through and through: no row, no owner, no source, and the
    // card's photo left behind — the form owns the picture flow.
    assertNull(prefill.getId());
    assertNull(prefill.getUserId());
    assertNull(prefill.getSource());
    assertNull(prefill.getPhotoFileId());
    verify(emailContactStorage, never()).createContact(any());
    verify(emailContactStorage, never()).savePhotoBytes(any(), any(), any());
  }

  @Test
  void aPrefillCarriesEveryPhoneWithItsType() throws Exception {
    // The confirm step's raw material: every number of the card, types kept,
    // so nothing for the form to lose before the user even presses Save.
    String vcf = """
        BEGIN:VCARD
        VERSION:3.0
        FN:Jane Doe
        EMAIL;TYPE=INTERNET:jane@example.com
        TEL;TYPE=CELL:+33 6 12 34 56 78
        TEL;TYPE=WORK:+33 1 23 45 67 89
        TEL:+33 2 11 22 33 44
        END:VCARD""";
    when(emailBoxService.getAttachmentByMailRemoteIdAnIdAndUserId(7L, "2", USERNAME))
        .thenReturn(new EmailAttachment(null, 7L, "2", "jane.vcf", "text/vcard", vcf.getBytes(StandardCharsets.UTF_8)));

    EmailContact prefill = service.getAttachmentContact(USERNAME, 7L, "2");

    assertEquals(List.of("cell,+33 6 12 34 56 78", "work,+33 1 23 45 67 89", "+33 2 11 22 33 44"),
                 prefill.getPhones());
  }

  @Test
  void aCardNamingThePersonInOneStringIsSplitForTheForm() throws Exception {
    // The same first-space guess the mailbox's add-sender prefill takes: a
    // guess either way, which is why a form stands between this and a row.
    String vcf = """
        BEGIN:VCARD
        VERSION:3.0
        FN:Jane van Doe
        EMAIL:jane@example.com
        END:VCARD""";
    when(emailBoxService.getAttachmentByMailRemoteIdAnIdAndUserId(7L, "2", USERNAME))
        .thenReturn(new EmailAttachment(null, 7L, "2", "jane.vcf", "text/vcard", vcf.getBytes(StandardCharsets.UTF_8)));

    EmailContact prefill = service.getAttachmentContact(USERNAME, 7L, "2");

    assertEquals("Jane", prefill.getGivenName());
    assertEquals("van Doe", prefill.getFamilyName());
  }

  @Test
  void anAttachmentThatIsNoVCardIsACleanRefusal() throws Exception {
    when(emailBoxService.getAttachmentByMailRemoteIdAnIdAndUserId(7L, "2", USERNAME))
        .thenReturn(new EmailAttachment(null, 7L, "2", "contact.vcf", "text/vcard",
                                        "Nothing card-shaped in here".getBytes(StandardCharsets.UTF_8)));

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> service.getAttachmentContact(USERNAME, 7L, "2"));

    assertEquals(EmailContactVCardService.ATTACHMENT_NOT_VCARD, refusal.getMessage());
  }

  @Test
  void anOversizedAttachmentIsRefusedBeforeParsing() throws Exception {
    byte[] oversized = new byte[(int) EmailContactVCardService.MAX_ATTACHMENT_CARD_BYTES + 1];
    when(emailBoxService.getAttachmentByMailRemoteIdAnIdAndUserId(7L, "2", USERNAME))
        .thenReturn(new EmailAttachment(null, 7L, "2", "huge.vcf", "text/vcard", oversized));

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> service.getAttachmentContact(USERNAME, 7L, "2"));

    assertEquals(EmailContactVCardService.ATTACHMENT_TOO_LARGE, refusal.getMessage());
    // Not one byte of it met the parser — the cap's whole point.
    verify(vCardParser, never()).parse(anyString());
  }

  @Test
  void somebodyElsesMailIsNobodysContactCard() throws Exception {
    // The mailbox reads only the caller's own INBOX and answers everything
    // else — another user's UID included — as this exception, which the REST
    // layer turns into the 404 the never-403 rule wants.
    when(emailBoxService.getAttachmentByMailRemoteIdAnIdAndUserId(7L, "2", USERNAME))
        .thenThrow(new IllegalStateException("Error when connecting store for user alice"));

    assertThrows(IllegalStateException.class, () -> service.getAttachmentContact(USERNAME, 7L, "2"));
  }

  @Test
  void aUserWithoutAMailboxCannotReadAttachments() throws Exception {
    when(emailBoxService.getAttachmentByMailRemoteIdAnIdAndUserId(7L, "2", USERNAME))
        .thenThrow(new IllegalAccessException("no mailbox"));

    assertThrows(IllegalAccessException.class, () -> service.getAttachmentContact(USERNAME, 7L, "2"));
  }

  @Test
  void anEmptyAttachmentIsNotFound() throws Exception {
    when(emailBoxService.getAttachmentByMailRemoteIdAnIdAndUserId(7L, "2", USERNAME))
        .thenReturn(new EmailAttachment(null, 7L, "2", "empty.vcf", "text/vcard", new byte[0]));

    assertNull(service.getAttachmentContact(USERNAME, 7L, "2"));
  }

  @Test
  void aCardWithNeitherAddressNorIdentityIsStillSkipped() throws Exception {
    givenUpload("""
        BEGIN:VCARD
        VERSION:3.0
        FN:Nobody At All
        TEL;TYPE=CELL:+33612340009
        END:VCARD""");

    service.startImport(USERNAME, UPLOAD_ID);

    ContactImportState state = lastPersistedState();
    assertEquals(0, state.getImported());
    assertEquals(1, state.getNoAddress());
  }

}
