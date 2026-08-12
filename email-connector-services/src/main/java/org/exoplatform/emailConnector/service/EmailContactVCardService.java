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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.file.model.FileItem;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.component.RequestLifeCycle;
import org.exoplatform.emailConnector.carddav.ParsedVCard;
import org.exoplatform.emailConnector.carddav.VCardParser;
import org.exoplatform.emailConnector.carddav.VCardSink;
import org.exoplatform.emailConnector.model.ContactImportState;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailContact;
import org.exoplatform.emailConnector.model.EmailContactPage;
import org.exoplatform.emailConnector.model.EmailContactSource;
import org.exoplatform.emailConnector.model.SyncStatus;
import org.exoplatform.emailConnector.storage.EmailContactStorage;
import org.exoplatform.emailConnector.utils.EmailContactUtils;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.upload.UploadResource;
import org.exoplatform.upload.UploadService;

/**
 * Moves the contact store through .vcf files: exports the whole store as one
 * vCard 3.0 file, and imports the file another product exported — Gmail,
 * Outlook or iCloud shaped, which is what the parser's corpus pins.
 * <p>
 * The import's one rule, applied whole-card: it skips and reports, it never
 * merges and never revives. A card whose ANY address already resolves to a
 * contact here — a visible row or a suppressed tombstone alike — is counted
 * as already known and left alone: a bulk import must not resurrect what the
 * user deliberately removed, and must be a visible no-op when run twice.
 * Everything it does create is a plain MANUAL row, editable and deletable like
 * anything the user typed; the CardDAV bookkeeping columns stay empty.
 * <p>
 * Kept apart from {@link EmailContactService} the way the CardDAV sync is:
 * that class owns the store's rules, this one owns a file format and the
 * asynchronous run around it.
 */
@Service
public class EmailContactVCardService {

  private static final Log        LOG                       = ExoLogger.getLogger(EmailContactVCardService.class);

  /** Message code answered as a 409 while this user's previous import still runs. */
  public static final String      IMPORT_ALREADY_RUNNING    = "emailConnector.contacts.import.alreadyRunning";

  /** Message code answered as a 400 for an upload that expired or never existed. */
  public static final String      IMPORT_UPLOAD_MISSING     = "emailConnector.contacts.import.uploadMissing";

  /** Message code answered as a 400 for a file over the size cap. */
  public static final String      IMPORT_FILE_TOO_LARGE     = "emailConnector.contacts.import.fileTooLarge";

  /** Message code reported on the state when the card cap cut the run short. */
  public static final String      IMPORT_TOO_MANY_CARDS     = "emailConnector.contacts.import.tooManyCards";

  /** Message code reported on the state when the run itself broke. */
  public static final String      IMPORT_FAILED             = "emailConnector.contacts.import.failed";

  /** Message code answered as a 400 for a mail attachment holding no readable vCard. */
  public static final String      ATTACHMENT_NOT_VCARD      = "emailConnector.contacts.attachment.notVCard";

  /** Message code answered as a 400 for a vCard attachment over the size cap. */
  public static final String      ATTACHMENT_TOO_LARGE      = "emailConnector.contacts.attachment.tooLarge";
  /** Message code answered as a 400 for a file no readable card came out of. */
  public static final String      PARSE_NO_CARD             = "emailConnector.contacts.parse.noCard";

  /**
   * The most an uploaded .vcf may weigh, checked BEFORE a byte of it is parsed.
   * Twenty megabytes holds a photo-heavy export of thousands of contacts; a
   * bigger file is either not a contact export or an attempt at this server's
   * memory.
   */
  public static final long        MAX_IMPORT_FILE_BYTES     = 20L * 1024 * 1024;

  /**
   * The most cards one file may deliver. The run stops AT the cap with a
   * partial report that says so — what landed stays landed, each card being its
   * own transaction.
   */
  public static final int         MAX_IMPORT_CARDS          = 5000;

  /**
   * The most a decoded photo may weigh. Over it, the photo is dropped and the
   * contact kept — a picture too large costs the picture, which is how the
   * storage layer already reasons about pictures it cannot write.
   */
  public static final int         MAX_PHOTO_BYTES           = 1024 * 1024;

  /**
   * The most a received .vcf attachment may weigh before a byte of it is
   * parsed — the same gate the import runs, sized for its actual cargo: one
   * person, photo included, is comfortably under a megabyte, so five holds
   * every real card and refuses a file that is something else wearing a .vcf
   * name.
   */
  public static final long        MAX_ATTACHMENT_CARD_BYTES = 5L * 1024 * 1024;

  /** How many rows one export page reads — the file streams out page by page. */
  private static final int        EXPORT_PAGE_SIZE          = 200;

  /**
   * How many cards between two persisted progress states. Every card would be
   * a settings write per card times five thousand; none would leave the poll
   * staring at zeros for the whole run.
   */
  private static final int        STATE_WRITE_EVERY         = 25;

  /**
   * Who is importing right now, in this JVM — the same guard the syncs use,
   * because the stored status alone leaves a window between reading it and
   * writing it that a double-click walks straight through.
   */
  private final Set<String>       importingUsers            = ConcurrentHashMap.newKeySet();

  @Autowired
  private EmailContactService     emailContactService;

  @Autowired
  private EmailContactStorage     emailContactStorage;

  @Autowired
  private UserEmailSettingService userEmailSettingService;

  @Autowired
  private EmailBoxService         emailBoxService;

  @Autowired
  private UploadService           uploadService;

  @Autowired
  private VCardParser             vCardParser;

  /**
   * Starts importing an uploaded .vcf into the caller's store and answers
   * immediately — the run happens off the request thread, and the drawer polls
   * {@link #getImportState} until it ends.
   * <p>
   * The upload is validated here, on the request, so the user hears about a
   * missing or oversized file as the answer to their click rather than as a
   * report they must poll for.
   *
   * @param username the store owner
   * @param uploadId the upload the drawer pushed the file under
   * @return the initial state, already IN_PROGRESS
   * @throws IllegalStateException with {@link #IMPORT_ALREADY_RUNNING} while a
   *           previous import of this user still runs
   * @throws IllegalArgumentException with {@link #IMPORT_UPLOAD_MISSING} or
   *           {@link #IMPORT_FILE_TOO_LARGE}
   */
  public ContactImportState startImport(String username, String uploadId) {
    if (StringUtils.isBlank(username) || StringUtils.isBlank(uploadId)) {
      throw new IllegalArgumentException(IMPORT_UPLOAD_MISSING);
    }
    if (!importingUsers.add(username)) {
      throw new IllegalStateException(IMPORT_ALREADY_RUNNING);
    }
    try {
      UploadResource upload = uploadService.getUploadResource(uploadId);
      if (upload == null || StringUtils.isBlank(upload.getStoreLocation())) {
        throw new IllegalArgumentException(IMPORT_UPLOAD_MISSING);
      }
      // The size gate, before parsing starts: the whole point is that no code
      // below ever runs against a file bigger than this.
      if (new File(upload.getStoreLocation()).length() > MAX_IMPORT_FILE_BYTES) {
        removeUpload(uploadId);
        throw new IllegalArgumentException(IMPORT_FILE_TOO_LARGE);
      }
      ContactImportState state = new ContactImportState();
      state.setStatus(SyncStatus.IN_PROGRESS);
      state.setStartedDate(new Date().getTime());
      userEmailSettingService.setContactImportState(state, username);
      scheduleRun(() -> runImport(username, uploadId, state));
      return state;
    } catch (Exception e) {
      // The guard belongs to the run this method failed to start.
      importingUsers.remove(username);
      throw e;
    }
  }

  /**
   * The state the drawer polls: progress while the run goes, the four-number
   * report once it ended.
   *
   * @param username the store owner
   * @return the stored state, never null; a null status says no import ever ran
   */
  public ContactImportState getImportState(String username) {
    return userEmailSettingService.getContactImportState(username);
  }

  /**
   * Reads the first readable card of an uploaded .vcf as the contact it would
   * become, WITHOUT storing anything — the prefill of a form the user still
   * has to confirm, which is the whole point: an action that quietly wrote a
   * row would take the confirmation away.
   * <p>
   * Same gates as {@link #startImport} (the upload must exist, the size cap
   * holds), same mapping as the import (one card, the very fields
   * {@code toContact} files for the bulk path — photo excluded, because the
   * photo mapping stores bytes and nothing may be stored here), and the upload
   * is consumed either way: a parse is a one-shot read, not a handle.
   *
   * @param username the store owner the fields are shaped for
   * @param uploadId the upload holding the .vcf
   * @return the would-be contact, never persisted, never null
   * @throws IllegalArgumentException with {@link #IMPORT_UPLOAD_MISSING},
   *           {@link #IMPORT_FILE_TOO_LARGE} or {@link #PARSE_NO_CARD}
   */
  public EmailContact parseFirstCard(String username, String uploadId) {
    if (StringUtils.isBlank(username) || StringUtils.isBlank(uploadId)) {
      throw new IllegalArgumentException(IMPORT_UPLOAD_MISSING);
    }
    try {
      UploadResource upload = uploadService.getUploadResource(uploadId);
      if (upload == null || StringUtils.isBlank(upload.getStoreLocation())) {
        throw new IllegalArgumentException(IMPORT_UPLOAD_MISSING);
      }
      if (new File(upload.getStoreLocation()).length() > MAX_IMPORT_FILE_BYTES) {
        throw new IllegalArgumentException(IMPORT_FILE_TOO_LARGE);
      }
      FirstCardSink sink = new FirstCardSink();
      try (Reader reader = new InputStreamReader(new FileInputStream(upload.getStoreLocation()), StandardCharsets.UTF_8)) {
        vCardParser.parseAll(reader, sink);
      } catch (IOException e) {
        LOG.debug("The uploaded vCard of user {} could not be read", username, e);
        throw new IllegalArgumentException(PARSE_NO_CARD);
      }
      if (sink.card == null) {
        throw new IllegalArgumentException(PARSE_NO_CARD);
      }
      // toPrefill, not toContact: an unpersisted prefill is exactly what the mail
      // attachment path already produces, photo left behind and a lone formatted
      // name split into given/family — so the same card reads the same way
      // whether it arrived by mail or by chat.
      return toPrefill(sink.card);
    } finally {
      removeUpload(uploadId);
    }
  }

  /**
   * Writes the caller's WHOLE visible store — suppressed rows excluded, every
   * source included — as vCard 3.0 text, one card at a time onto the given
   * writer, so a five-thousand-row store never exists in memory as a file.
   * <p>
   * Rows are read through {@link EmailContactService#getContacts} on purpose:
   * that is the read that resolves a directory-linked colleague's LIVE name,
   * so the export says who the person is today, not who they were when linked.
   * A CardDAV row leaves with the UID its server gave it; nothing else gets
   * one — a UID this store invented would collide with nothing and mean less.
   *
   * @param username the store owner
   * @param out where the file goes, typically the HTTP response; flushed per
   *          page, closed by the caller
   * @throws IOException when the writer fails — the download was abandoned
   */
  public void exportContacts(String username, Writer out) throws IOException {
    Map<Long, String> vcardUids = emailContactStorage.getVcardUids(username);
    int offset = 0;
    long total;
    do {
      EmailContactPage page = emailContactService.getContacts(username, null, null, false, offset, EXPORT_PAGE_SIZE);
      List<EmailContact> rows = page == null || page.getContacts() == null ? List.of() : page.getContacts();
      total = page == null ? 0 : page.getSize();
      for (EmailContact contact : rows) {
        out.write(vCardParser.format(toCard(contact, vcardUids.get(contact.getId()))));
      }
      out.flush();
      offset += EXPORT_PAGE_SIZE;
    } while (offset < total);
  }

  /**
   * The provider-switch "download a backup, then start fresh" choice, as ONE
   * operation whose order is the whole point: the caller's entire store is
   * written onto the given writer — the very same {@link #exportContacts} the
   * 3-dots export runs, so the backup holds every row about to go, address-book
   * rows with their server UIDs included — and ONLY once every card has been
   * written and flushed without error does {@link EmailContactService#startFresh}
   * empty the store. The two live in one method precisely so no caller can
   * reorder them or run the second without the first.
   * <p>
   * A failed export deletes nothing, by construction, not by handling: the
   * export writes straight onto the HTTP response, so an abandoned download or
   * a dead connection surfaces as the {@link IOException} of a write or flush
   * — and the deletion sits AFTER that code, unreachable past a throw. There
   * is deliberately no catch here: nothing this method could do with the
   * failure beats leaving the store exactly as it was, which is what falling
   * out does.
   * <p>
   * The honest limit, stated rather than hidden: "in the user's hands" can
   * only mean "the last byte was flushed to them without error". A response
   * fully sent but discarded by the browser is beyond what any server can
   * see. That residual window is why the UI names the file a backup and
   * downloads it BEFORE the binding moves — the user watches the file land,
   * and cancelling the download cancels the wipe with it.
   * <p>
   * There is no undo past this method. The deletion is deliberate, chosen by
   * the user against the alternative of keeping everything, and their backup
   * is the recovery path — re-importable through the ordinary contacts
   * import.
   *
   * @param username the store owner
   * @param out where the backup goes, typically the HTTP response; flushed
   *          fully before anything is deleted, closed by the caller
   * @return how many contacts were deleted after the export completed
   * @throws IOException when the export could not be fully delivered — the
   *           store is untouched
   */
  public int exportContactsThenStartFresh(String username, Writer out) throws IOException {
    exportContacts(username, out);
    // Belt on braces: exportContacts flushes per page, but the deletion below
    // must only ever run after an explicit, successful flush of the LAST byte.
    out.flush();
    return emailContactService.startFresh(username);
  }

  /**
   * One contact of the caller's store as vCard 3.0 text WITHOUT its photo —
   * what the contact card's QR code encodes.
   * <p>
   * The photo's absence is the whole engineering constraint, not an oversight:
   * an embedded picture is tens of kilobytes of base64, and a QR code tops out
   * at 2953 bytes — with a photo the code is either ungenerable or an
   * unscannable wall of modules. Text fields only is what makes pointing a
   * phone at the screen actually work. The vCard UID is left out too: a phone
   * saving a scanned card has no server entry to reconcile against.
   *
   * @param username the store owner
   * @param contactId the contact to encode
   * @return the vCard text, or null when this user has no such visible contact
   */
  public String getContactVCard(String username, long contactId) {
    return getContactVCard(username, contactId, false);
  }

  /**
   * One contact of the caller's store as vCard 3.0 text, with the photo
   * optional — the shape a contact travels in as a mail attachment.
   * <p>
   * An attachment has none of the QR's byte budget, so the stored picture may
   * come along: a card somebody keeps should look like the person. It is still
   * only the picture this store OWNS — {@link #readPhoto} refuses to hand out a
   * directory colleague's platform avatar — and the vCard UID stays absent on
   * both paths: it is CardDAV bookkeeping, and whoever imports the mailed card
   * has no entry of ours to reconcile against.
   *
   * @param username the store owner
   * @param contactId the contact to encode
   * @param includePhoto whether the stored picture travels; the QR path says no
   * @return the vCard text, or null when this user has no such visible contact
   */
  public String getContactVCard(String username, long contactId, boolean includePhoto) {
    EmailContact contact = emailContactService.getContact(contactId, username);
    if (contact == null) {
      return null;
    }
    return vCardParser.format(toCard(contact, null, includePhoto));
  }

  /**
   * One contact as the vCard 3.0 text a CardDAV publish stores on the server —
   * photo included, and carrying the UID the publish minted, because on this
   * path the card IS about to become a server entry and the UID is how the
   * next sync recognises it as ours. The QR and attachment shapes keep their
   * no-UID rule; this is the one path where the bookkeeping belongs in the
   * card.
   * <p>
   * Ownership is the caller's business: the publish service has already
   * resolved the contact through {@link EmailContactService#getContact}, and
   * re-checking here would read the row twice for one refusal message.
   *
   * @param contact the caller's own contact, already ownership-checked
   * @param vcardUid the identity minted for the entry being created
   * @return the vCard text to PUT
   */
  public String getPublishVCard(EmailContact contact, String vcardUid) {
    return vCardParser.format(toCard(contact, vcardUid, true));
  }

  /**
   * The FIRST card of a received .vcf attachment, as the contact form's
   * prefill — read, never stored: keeping the person is the user's decision,
   * taken on the form this answer fills in.
   * <p>
   * First card only, on purpose: an emailed contact is one person, and a
   * multi-card file is the bulk import's job. Nothing is persisted here — no
   * row, no photo file — which is also why the card's picture is left behind:
   * the form's photo travels only as an upload id from its own cropper, and a
   * prefill must not write files for a contact that may never be saved.
   * <p>
   * The attachment is untrusted input and bounded like the import's file:
   * refused whole over {@link #MAX_ATTACHMENT_CARD_BYTES}, before parsing.
   *
   * @param username the acting user, owner of the mailbox being read
   * @param mailRemoteId the INBOX IMAP UID of the mail
   * @param attachmentId the attachment's MIME part path within the mail
   * @return the prefill, or null when the caller has no such attachment
   * @throws IllegalAccessException when the caller has no connected mailbox
   * @throws IllegalArgumentException with {@link #ATTACHMENT_TOO_LARGE} or
   *           {@link #ATTACHMENT_NOT_VCARD}
   */
  public EmailContact getAttachmentContact(String username, long mailRemoteId, String attachmentId)
                                                                                                    throws IllegalAccessException {
    EmailAttachment attachment = emailBoxService.getAttachmentByMailRemoteIdAnIdAndUserId(mailRemoteId, attachmentId, username);
    if (attachment == null || attachment.getData() == null || attachment.getData().length == 0) {
      return null;
    }
    // The size gate, before parsing starts — the same promise the import makes:
    // no code below ever runs against a file bigger than the cap.
    if (attachment.getData().length > MAX_ATTACHMENT_CARD_BYTES) {
      throw new IllegalArgumentException(ATTACHMENT_TOO_LARGE);
    }
    // parse() takes the first card of the text and only it, which is exactly
    // this method's contract; a text with no readable card answers null.
    ParsedVCard card = vCardParser.parse(new String(attachment.getData(), StandardCharsets.UTF_8));
    if (card == null) {
      throw new IllegalArgumentException(ATTACHMENT_NOT_VCARD);
    }
    return toPrefill(card);
  }

  /**
   * One stored contact as the fields a vCard carries, photo included when the
   * user's store owns one.
   *
   * @param contact the enriched store row
   * @param vcardUid the CardDAV uid to carry over, null for every other source
   * @return the card to format
   */
  private ParsedVCard toCard(EmailContact contact, String vcardUid) {
    return toCard(contact, vcardUid, true);
  }

  /**
   * One stored contact as the fields a vCard carries — the export's shape, with
   * the photo optional for the QR path.
   *
   * @param contact the enriched store row
   * @param vcardUid the CardDAV uid to carry over, null for every other source
   * @param includePhoto whether the stored picture travels; the QR path says no
   * @return the card to format
   */
  private ParsedVCard toCard(EmailContact contact, String vcardUid, boolean includePhoto) {
    List<String> emails = new ArrayList<>();
    if (StringUtils.isNotBlank(contact.getPrimaryEmail())) {
      emails.add(contact.getPrimaryEmail());
    }
    if (contact.getSecondaryEmails() != null) {
      emails.addAll(contact.getSecondaryEmails());
    }
    FileItem photo = includePhoto ? readPhoto(contact) : null;
    return new ParsedVCard(vcardUid,
                           contact.getDisplayName(),
                           contact.getGivenName(),
                           contact.getFamilyName(),
                           emails,
                           contact.getPhones() == null ? List.of() : contact.getPhones(),
                           contact.getOrganization(),
                           contact.getTitle(),
                           contact.getBirthday(),
                           contact.getPostalAddress(),
                           EmailContactUtils.noteAsText(contact.getNote()),
                           contact.getWebsite(),
                           photo == null ? null : photo.getAsByte(),
                           photo == null || photo.getFileInfo() == null ? null : photo.getFileInfo().getMimetype());
  }

  /**
   * The picture stored for a contact, when there is one to export. A directory
   * row's avatar is the platform profile's, not this store's to hand out, and
   * such rows hold no file anyway; a photo that cannot be read costs the
   * photo, never the export.
   *
   * @param contact the row being exported
   * @return the stored file, or null
   */
  private FileItem readPhoto(EmailContact contact) {
    if (contact.getPhotoFileId() == null || contact.getPhotoFileId() <= 0
        || EmailContactSource.DIRECTORY.equals(contact.getSource())) {
      return null;
    }
    try {
      return emailContactStorage.getPhotoFileItem(contact.getPhotoFileId());
    } catch (Exception e) {
      LOG.debug("The photo of contact {} could not be read for export", contact.getId(), e);
      return null;
    }
  }

  /**
   * Hands the import run to a thread of its own — behind a method so a test
   * can run it synchronously instead of racing a pool it does not own.
   *
   * @param task the run
   */
  protected void scheduleRun(Runnable task) {
    CompletableFuture.runAsync(task);
  }

  /**
   * The import run itself, off the request thread: walks the uploaded file one
   * card at a time, files each card under exactly one of the four counters,
   * and persists the state along the way for the poll. However it ends, the
   * upload is deleted, the guard released, and a final state written — a run
   * that vanished without a report is the one outcome this method may not
   * have.
   *
   * @param username the store owner
   * @param uploadId the upload holding the file
   * @param state the state started by {@link #startImport}, mutated and
   *          persisted as the run advances
   */
  protected void runImport(String username, String uploadId, ContactImportState state) {
    // Bound by hand, exactly as the startup backfills do: this thread has no
    // request, so nothing bound a persistence context to it, and every storage
    // call below would fail without one.
    RequestLifeCycle.begin(ExoContainerContext.getCurrentContainer());
    try {
      UploadResource upload = uploadService.getUploadResource(uploadId);
      if (upload == null || StringUtils.isBlank(upload.getStoreLocation())) {
        // Consumed or expired between the request and this thread: nothing to
        // read, and the report must say the run went nowhere.
        state.setMessageCode(IMPORT_FAILED);
        state.setStatus(SyncStatus.FAILURE);
        return;
      }
      try (Reader reader = new InputStreamReader(new FileInputStream(upload.getStoreLocation()), StandardCharsets.UTF_8)) {
        vCardParser.parseAll(reader, new ImportSink(username, state));
      }
      state.setStatus(SyncStatus.SUCCESS);
    } catch (Exception e) {
      state.setStatus(SyncStatus.FAILURE);
      state.setMessageCode(IMPORT_FAILED);
      LOG.warn("The vCard import of user {} failed", username, e);
    } finally {
      state.setFinishedDate(new Date().getTime());
      persistQuietly(username, state);
      removeUpload(uploadId);
      RequestLifeCycle.end();
      importingUsers.remove(username);
    }
    LOG.info("vCard import for user {}: {} imported, {} already known, {} without address, {} unreadable",
             username,
             state.getImported(),
             state.getAlreadyKnown(),
             state.getNoAddress(),
             state.getUnreadable());
  }

  /**
   * Where the streaming reader delivers each card of the uploaded file: counts
   * it, imports it when it earns a row, and pulls the brake at the card cap.
   */
  private class ImportSink implements VCardSink {

    private final String             username;

    private final ContactImportState state;

    private int                      cards;

    /**
     * Builds the sink for one run.
     *
     * @param username the store owner
     * @param state the run's state, mutated as cards land
     */
    private ImportSink(String username, ContactImportState state) {
      this.username = username;
      this.state = state;
    }

    @Override
    public boolean accept(ParsedVCard card) {
      if (atCap()) {
        return false;
      }
      importCard(username, card, state);
      return advance();
    }

    @Override
    public boolean acceptUnreadable() {
      if (atCap()) {
        return false;
      }
      state.setUnreadable(state.getUnreadable() + 1);
      return advance();
    }

    /**
     * Whether the card cap just cut the run short. Checked as the card AFTER
     * the cap arrives, so a file of exactly the cap's size is not reported
     * truncated — everything before the cap has already landed, each card in
     * its own transaction, which is what makes stopping here a partial report
     * rather than a failed run.
     *
     * @return true when this card is one past the cap
     */
    private boolean atCap() {
      if (cards >= MAX_IMPORT_CARDS) {
        state.setMessageCode(IMPORT_TOO_MANY_CARDS);
        return true;
      }
      return false;
    }

    /**
     * The per-card bookkeeping both outcomes share: the periodic persistence
     * the poll's progress numbers come from.
     *
     * @return true, to keep reading
     */
    private boolean advance() {
      cards++;
      if (cards % STATE_WRITE_EVERY == 0) {
        persistQuietly(username, state);
      }
      return true;
    }
  }

  /**
   * Files one card under exactly one of the four counters, creating the MANUAL
   * row when it earns one. Fenced per card: whatever one card's import throws,
   * it costs that card an "unreadable" tick and the next card its turn —
   * never the run.
   *
   * @param username the store owner
   * @param card the parsed card
   * @param state the run's counters, mutated in place
   */
  private void importCard(String username, ParsedVCard card, ContactImportState state) {
    try {
      List<String> addresses = normalizedAddressesOf(card);
      String uid = StringUtils.trimToNull(card.uid());
      if (addresses.isEmpty() && uid == null) {
        // Neither an address nor the identity every provider stamps on a card:
        // nothing to recognise this person by, so importing them would make
        // another copy on every re-import. Its own number in the report.
        state.setNoAddress(state.getNoAddress() + 1);
        return;
      }
      // The card's own identity is asked first, and it is what lets somebody
      // with a phone and no email address be imported at all: the address table
      // cannot speak for a person who has no address. The sync recognises the
      // same people the same way, by what the server calls them.
      if (uid != null && emailContactStorage.getContactByVcardUid(username, uid) != null) {
        state.setAlreadyKnown(state.getAlreadyKnown() + 1);
        return;
      }
      for (String address : addresses) {
        if (emailContactStorage.getContactByAddress(username, address) != null) {
          // ANY address resolving — to a visible row or a suppressed tombstone —
          // skips the whole card: no merge, no revive, and a re-import of the
          // same file is a visible no-op.
          state.setAlreadyKnown(state.getAlreadyKnown() + 1);
          return;
        }
      }
      EmailContact created = emailContactStorage.createContact(toContact(username, card, addresses));
      // Only when the card carried one, and only once the row has an id: a
      // contact is imported whether or not its provider stamped an identity, and
      // asking for one that is not there cost the whole card before this guard.
      if (uid != null && created != null && created.getId() != null) {
        emailContactStorage.setVcardUid(created.getId(), uid);
      }
      state.setImported(state.getImported() + 1);
    } catch (Exception e) {
      LOG.debug("A vCard of the import of user {} could not be filed", username, e);
      state.setUnreadable(state.getUnreadable() + 1);
    }
  }

  /**
   * The MANUAL row one card becomes. MANUAL and nothing else: editable,
   * hard-deletable, shown under "Added", untouched by the address-book
   * release — exactly what a contact the user chose to bring in should be.
   * The vCard's own UID is deliberately NOT written: that column is CardDAV
   * bookkeeping, and a file import has no server entry to keep books on.
   *
   * @param username the store owner
   * @param card the parsed card
   * @param addresses the normalized addresses, preferred first
   * @return the row to create
   */
  private EmailContact toContact(String username, ParsedVCard card, List<String> addresses) {
    EmailContact contact = cardFields(card, addresses);
    contact.setUserId(username);
    contact.setSource(EmailContactSource.MANUAL);
    contact.setSeenCount(0);
    if (card.photo() != null && card.photo().length > 0 && card.photo().length <= MAX_PHOTO_BYTES) {
      // Over the cap the photo is simply not stored: the contact is kept, per
      // the cap's contract. Under it, a photo that fails to write costs the
      // photo too — savePhotoBytes already answers null rather than throwing.
      contact.setPhotoFileId(emailContactStorage.savePhotoBytes(null, card.photo(), card.photoMimeType()));
    }
    return contact;
  }

  /**
   * The prefill a card becomes for the contact form: the same field mapping as
   * an imported row, minus everything that belongs to a persisted one — no
   * owner, no source, no photo file. When the card names the person only as one
   * formatted string, it is split into the form's given and family fields with
   * the same first-space guess the mailbox's add-sender prefill takes — a guess
   * either way, which is exactly why a form stands between this and a row.
   *
   * @param card the parsed card
   * @return the unpersisted prefill
   */
  private EmailContact toPrefill(ParsedVCard card) {
    EmailContact prefill = cardFields(card, normalizedAddressesOf(card));
    if (StringUtils.isBlank(prefill.getGivenName()) && StringUtils.isBlank(prefill.getFamilyName())
        && StringUtils.isNotBlank(prefill.getDisplayName())) {
      String[] parts = prefill.getDisplayName().trim().split("\\s+", 2);
      prefill.setGivenName(parts[0]);
      prefill.setFamilyName(parts.length > 1 ? parts[1] : null);
    }
    return prefill;
  }

  /**
   * The fields a card carries into a contact, shared by the import's MANUAL row
   * and the attachment prefill so the very same card reads the very same way on
   * both paths.
   *
   * @param card the parsed card
   * @param addresses the normalized addresses, preferred first
   * @return a contact holding only what the card said
   */
  private EmailContact cardFields(ParsedVCard card, List<String> addresses) {
    EmailContact contact = new EmailContact();
    contact.setPrimaryEmail(addresses.isEmpty() ? null : addresses.get(0));
    contact.setSecondaryEmails(addresses.size() > 1 ? addresses.subList(1, addresses.size()) : null);
    contact.setDisplayName(StringUtils.defaultIfBlank(card.formattedName(),
                                                      StringUtils.trimToNull(StringUtils.trimToEmpty(card.givenName()) + " "
                                                          + StringUtils.trimToEmpty(card.familyName()))));
    contact.setGivenName(card.givenName());
    contact.setFamilyName(card.familyName());
    contact.setPhones(card.phones() == null || card.phones().isEmpty() ? null : card.phones());
    contact.setOrganization(card.organization());
    contact.setTitle(card.title());
    // Already canonical (birthday) and capped (note) by the parser, so this path
    // stores what the sync path stores for the very same card.
    contact.setBirthday(card.birthday());
    contact.setPostalAddress(card.address());
    contact.setNote(card.note());
    contact.setWebsite(card.website());
    return contact;
  }

  /**
   * Every address of a card, normalized the way the whole store normalizes,
   * order kept (preferred first), duplicates dropped.
   *
   * @param card the parsed card
   * @return the addresses, never null
   */
  private List<String> normalizedAddressesOf(ParsedVCard card) {
    Set<String> addresses = new LinkedHashSet<>();
    if (card.emails() != null) {
      for (String email : card.emails()) {
        String normalized = EmailContactUtils.normalizeAddress(email);
        if (normalized != null) {
          addresses.add(normalized);
        }
      }
    }
    return new ArrayList<>(addresses);
  }

  /**
   * Persists the state without letting a settings store having a bad moment
   * fail the run — a progress write the poll misses is a stale number for two
   * seconds, while an exception here would cost the rest of the file.
   *
   * @param username the store owner
   * @param state the state to persist
   */
  private void persistQuietly(String username, ContactImportState state) {
    try {
      userEmailSettingService.setContactImportState(state, username);
    } catch (Exception e) {
      LOG.debug("The import state of user {} could not be persisted", username, e);
    }
  }

  /**
   * Deletes the upload once the run is done with it, however the run went — an
   * uploaded address book left lying in the upload store is somebody's
   * contacts on disk for nothing.
   *
   * @param uploadId the upload to drop
   */
  private void removeUpload(String uploadId) {
    try {
      uploadService.removeUploadResource(uploadId);
    } catch (Exception e) {
      LOG.debug("Upload {} could not be removed after the vCard import", uploadId, e);
    }
  }

  /**
   * The sink of {@link #parseFirstCard}: keeps the first readable card and
   * stops the reader there — an unreadable prefix card costs itself, not the
   * parse, same accounting as the import's.
   */
  private static class FirstCardSink implements VCardSink {

    private ParsedVCard card;

    /**
     * Keeps the first readable card and stops the read: one card is all this
     * sink is for.
     *
     * @param card the parsed card, never null
     * @return false, to stop reading
     */
    @Override
    public boolean accept(ParsedVCard card) {
      this.card = card;
      return false;
    }
  }
}
