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

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;


import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.api.notification.NotificationContext;
import org.exoplatform.commons.api.notification.model.PluginKey;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.notification.impl.NotificationContextImpl;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.component.RequestLifeCycle;
import org.exoplatform.emailConnector.exception.DelegationRevokedException;
import org.exoplatform.emailConnector.model.EmailFolder;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.MailImportState;
import org.exoplatform.emailConnector.model.SyncStatus;
import org.exoplatform.emailConnector.notification.plugin.MailImportFinishedNotificationPlugin;
import org.exoplatform.emailConnector.utils.MailArchiveReader;
import org.exoplatform.emailConnector.utils.NotificationConstants;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.upload.UploadResource;
import org.exoplatform.upload.UploadService;

import io.meeds.social.util.JsonUtils;

import jakarta.annotation.PreDestroy;

/**
 * Imports mail from files into a folder of the user's mailbox (EXO-90846): one or
 * several {@code .eml}, a {@code .zip} of them, or an {@code .mbox} exported by
 * Thunderbird, Apple Mail or Gmail. Each mail is added on the user's mail server by
 * IMAP APPEND, so it appears in every mail application, dated by its own {@code Date}
 * header so it sorts where it belongs.
 * <p>
 * The shape is the contact import's ({@link EmailContactVCardService}): the files come
 * through the platform's upload service, the request validates and answers at once, the
 * run happens off the request thread on the node that received the uploads (an upload
 * lives on that node's disk), and its state is stored in the user's settings for the
 * drawer to poll from any node. However the run ends, the uploads are deleted, a final
 * state is stored, and the user is notified with the counts.
 * <p>
 * One import per user at a time: a JVM guard, as the contact import has, and the stored
 * state, which a run on another node keeps fresh while it goes -- a state that stopped
 * being written for {@link #STALE_AFTER_MS} belongs to a node that died, and is reported
 * as interrupted rather than holding the user's imports forever. No database claim: the
 * run cannot move to another node anyway, and a double start on two nodes in the same
 * instant costs duplicates only for mails without a Message-ID, every other one being
 * skipped by the folder's Message-ID check.
 * <p>
 * The mails' read state: a source that says (an mbox's {@code Status} or
 * {@code X-Mozilla-Status}, Gmail's {@code X-Gmail-Labels}) is followed; a source that
 * says nothing -- a {@code .eml} -- is imported as read. Imported mail is mail the user
 * already received elsewhere: imported unread into the inbox it would fire the new-mail
 * notification and swell the unread count for an archive.
 */
@Service
public class EmailImportService {

  /** Message code answered as a 409 while this user's previous import still runs. */
  public static final String       IMPORT_ALREADY_RUNNING  = "emailConnector.import.alreadyRunning";

  /** Message code answered as a 400 for an upload that expired or never existed. */
  public static final String       IMPORT_UPLOAD_MISSING   = "emailConnector.import.uploadMissing";

  /** Message code answered as a 400 for more files than {@link #MAX_IMPORT_FILES}. */
  public static final String       IMPORT_TOO_MANY_FILES   = "emailConnector.import.tooManyFiles";

  /** Message code answered as a 400 for files weighing more than {@link #MAX_IMPORT_TOTAL_BYTES}. */
  public static final String       IMPORT_TOO_LARGE        = "emailConnector.import.tooLarge";

  /** Message code reported for a folder the user does not have (any more). */
  public static final String       IMPORT_FOLDER_MISSING   = "emailConnector.import.folderMissing";

  /** Message code reported when the run stopped at {@link #MAX_IMPORT_MAILS}. */
  public static final String       IMPORT_TOO_MANY_MAILS   = "emailConnector.import.tooManyMails";

  /** Message code reported when the mail server refused too many mails in a row. */
  public static final String       IMPORT_SERVER_REFUSING  = "emailConnector.import.serverRefusing";

  /** Message code reported when the share the folder belongs to ended or its right went. */
  public static final String       IMPORT_ACCESS_LOST      = "emailConnector.import.accessLost";

  /** Message code reported when the run itself broke. */
  public static final String       IMPORT_FAILED           = "emailConnector.import.failed";

  /** Message code reported for a run whose node stopped writing its state. */
  public static final String       IMPORT_INTERRUPTED      = "emailConnector.import.interrupted";

  /** The most files one import takes. */
  public static final int          MAX_IMPORT_FILES        = 50;

  /**
   * The most one import weighs, all files together, checked before a byte is read: a
   * year of a busy mailbox, and what one upload round trip reasonably carries.
   */
  public static final long         MAX_IMPORT_TOTAL_BYTES  = 200L * 1024 * 1024;

  /** The most mails one import reads; the run stops there with a partial report. */
  public static final int          MAX_IMPORT_MAILS        = 5000;

  /** The most one mail may weigh: the common mail-server limit. */
  public static final int          MAX_MAIL_BYTES          = 25 * 1024 * 1024;

  /** The most one mail's header block may weigh; real ones are a few kilobytes. */
  public static final int          MAX_HEADER_BYTES        = 256 * 1024;

  /** The most entries a zip may hold, directories included. */
  public static final int          MAX_ZIP_ENTRIES         = MAX_IMPORT_MAILS;

  /** The most a zip entry may inflate, as a multiple of what it weighs. */
  public static final int          MAX_ZIP_RATIO           = 100;

  /** The most a zip may inflate to, all entries together. */
  public static final long         MAX_ZIP_TOTAL_BYTES     = 1024L * 1024 * 1024;

  /** How long a run may go without writing its state before it is taken as dead. */
  public static final long         STALE_AFTER_MS          = 10L * 60 * 1000;

  /** The settings key of the state, apart from every other document for the usual reason. */
  static final String              MAIL_IMPORT_STATE_KEY   = "emailMailImportState";

  // How many mails between two stored states: progress for the poll, a heartbeat for
  // the other nodes, and not one settings write per mail.
  static final int                 STATE_WRITE_EVERY       = 20;

  // How many refusals by the mail server in a row stop the run: a full quota or a
  // withdrawn right refuses every mail after it, and trying them all reports nothing new.
  static final int                 SERVER_REFUSALS_TO_STOP = 20;

  // The prefix of a run's directory in the temporary directory.
  static final String              WORK_DIR_PREFIX         = "email-import-";

  private static final Log         LOG                     = ExoLogger.getLogger(EmailImportService.class);

  // A run's directory: this server's user alone may list, read and write it.
  private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rwx------");

  private static final AtomicInteger THREADS             = new AtomicInteger();

  private final Set<String>        importingUsers          = ConcurrentHashMap.newKeySet();

  // A thread per run, as the contact import's runAsync gives it, never a queue: a run
  // waiting in a queue writes no state, and would read as dead to the other nodes. The
  // per-user guard bounds the threads to the users importing at once.
  private final ExecutorService    importExecutor          = Executors.newCachedThreadPool(runnable -> {
                                                             Thread thread = new Thread(runnable,
                                                                                        "email-import-"
                                                                                            + THREADS.incrementAndGet());
                                                             thread.setDaemon(true);
                                                             return thread;
                                                           });

  @Autowired
  private EmailBoxService          emailBoxService;

  @Autowired
  private EmailFolderService       emailFolderService;

  @Autowired
  private SettingService           settingService;

  @Autowired
  private UploadService            uploadService;

  /**
   * Stops the import threads with the application.
   */
  @PreDestroy
  public void stop() {
    importExecutor.shutdownNow();
  }

  /**
   * Starts importing uploaded files into a folder, and answers at once with the state
   * the drawer then polls. Everything that can be refused on the request is refused
   * here, so the user hears it as the answer to their click: the folder and the caller's
   * right to write in it, a run already going, a missing upload, too many files, too
   * many bytes.
   * <p>
   * A refused request leaves the uploads alone: the platform does not bind an upload id
   * to the user who made it, so a request naming somebody else's must not be able to
   * delete it. The drawer deletes its own on a refusal, and the platform those of an
   * ended session. An accepted one takes the files out of the upload service into a
   * directory of the run's own, deleted when the run ends, so that a session ending
   * meanwhile (the platform then deletes its uploads) does not cut the run short.
   *
   * @param username the mailbox owner, or the delegate importing into a shared folder
   * @param folder the folder key
   * @param uploadIds the uploads holding the files
   * @return the initial state, IN_PROGRESS
   * @throws IllegalAccessException when the caller may not use their mailbox, or lacks
   *           the insert right on that shared folder
   * @throws IllegalArgumentException with a message code for a request that cannot run
   * @throws IllegalStateException {@link #IMPORT_ALREADY_RUNNING} while an import of this
   *           user runs
   */
  public MailImportState startImport(String username, String folder, List<String> uploadIds) throws IllegalAccessException {
    List<String> uploads = uploadIds == null ? List.of() : uploadIds.stream().filter(StringUtils::isNotBlank).distinct().toList();
    String folderKey = StringUtils.defaultIfBlank(folder, MailFolder.INBOX);
    if (StringUtils.isBlank(username) || uploads.isEmpty()) {
      throw new IllegalArgumentException(IMPORT_UPLOAD_MISSING);
    }
    if (uploads.size() > MAX_IMPORT_FILES) {
      throw new IllegalArgumentException(IMPORT_TOO_MANY_FILES);
    }
    emailBoxService.checkImportTarget(username, folderKey);
    if (!importingUsers.add(username)) {
      throw new IllegalStateException(IMPORT_ALREADY_RUNNING);
    }
    Path workDir = null;
    try {
      if (isRunningElsewhere(getStoredState(username))) {
        throw new IllegalStateException(IMPORT_ALREADY_RUNNING);
      }
      List<File> files = new ArrayList<>();
      long totalBytes = 0;
      for (String uploadId : uploads) {
        UploadResource upload = uploadService.getUploadResource(uploadId);
        if (upload == null || StringUtils.isBlank(upload.getStoreLocation()) || !new File(upload.getStoreLocation()).isFile()) {
          throw new IllegalArgumentException(IMPORT_UPLOAD_MISSING);
        }
        File file = new File(upload.getStoreLocation());
        totalBytes += file.length();
        files.add(file);
      }
      if (totalBytes > MAX_IMPORT_TOTAL_BYTES) {
        throw new IllegalArgumentException(IMPORT_TOO_LARGE);
      }
      workDir = takeUploads(uploads, files);
      MailImportState state = newState(folderKey);
      state.setStatus(SyncStatus.IN_PROGRESS);
      state.setTotalBytes(totalBytes);
      state.setStartedDate(System.currentTimeMillis());
      state.setUpdatedDate(state.getStartedDate());
      storeState(username, state);
      // The answer is a copy: the run mutates its state on another thread while this
      // one is being serialised.
      MailImportState answer = JsonUtils.fromJsonString(JsonUtils.toJsonString(state), MailImportState.class);
      Path runDir = workDir;
      scheduleRun(() -> runImport(username, folderKey, runDir, state));
      return answer;
    } catch (RuntimeException e) {
      importingUsers.remove(username);
      // Taken, then not run (the state not stored, the threads shut down): the files go
      // with the run that will not happen.
      deleteQuietly(workDir);
      throw e;
    }
  }

  /**
   * The state the drawer polls: progress while a run goes, its report once it ended,
   * and the limits in every case. A run whose node stopped writing it for
   * {@link #STALE_AFTER_MS} is answered as interrupted.
   *
   * @param username the user
   * @return the state, never null; a null status says no import ever ran
   */
  public MailImportState getImportState(String username) {
    MailImportState state = getStoredState(username);
    if (state.getStatus() == SyncStatus.IN_PROGRESS && !importingUsers.contains(username) && isStale(state)) {
      state.setStatus(SyncStatus.FAILURE);
      state.setMessageCode(IMPORT_INTERRUPTED);
    }
    withLimits(state);
    return state;
  }

  /**
   * Hands a run to the import threads -- behind a method so a test can run it on its own
   * thread.
   *
   * @param task the run
   */
  protected void scheduleRun(Runnable task) {
    importExecutor.execute(task);
  }

  /**
   * The run itself, off the request thread: opens the folder (re-checking the caller's
   * right to write there), reads each file into it mail by mail, and stores the state as
   * it goes -- first as it starts, so the state is fresh whatever came before. However
   * it ends, the run's files are deleted, the guard released, a final state stored and
   * the user notified.
   *
   * @param username the user
   * @param folderKey the folder key
   * @param workDir the run's directory, holding the files in the order given, deleted
   *          at the end
   * @param state the state started by {@link #startImport}
   */
  protected void runImport(String username, String folderKey, Path workDir, MailImportState state) {
    RequestLifeCycle.begin(ExoContainerContext.getCurrentContainer());
    storeQuietly(username, state);
    try (MailImportTarget target = emailBoxService.openImportTarget(username, folderKey)) {
      if (target == null) {
        state.setStatus(SyncStatus.FAILURE);
        state.setMessageCode(IMPORT_FOLDER_MISSING);
        return;
      }
      MailImportRun run = new MailImportRun(username, target, state, () -> storeQuietly(username, state));
      MailArchiveReader reader = new MailArchiveReader(MAX_MAIL_BYTES,
                                                       MAX_HEADER_BYTES,
                                                       MAX_ZIP_ENTRIES,
                                                       MAX_ZIP_RATIO,
                                                       MAX_ZIP_TOTAL_BYTES);
      long doneBytes = 0;
      for (File file : runFiles(workDir)) {
        if (run.isStopped()) {
          break;
        }
        String cutShort = reader.read(file, run);
        if (cutShort != null) {
          state.setMessageCode(cutShort);
        }
        doneBytes += file.length();
        state.setProcessedBytes(doneBytes);
        storeQuietly(username, state);
      }
      if (run.isConnectionLost()) {
        state.setStatus(SyncStatus.FAILURE);
        state.setMessageCode(IMPORT_FAILED);
      } else {
        state.setStatus(SyncStatus.SUCCESS);
      }
    } catch (IllegalAccessException | DelegationRevokedException e) {
      LOG.debug("The mail import of user {} into folder {} was refused", username, folderKey, e);
      state.setStatus(SyncStatus.FAILURE);
      state.setMessageCode(IMPORT_ACCESS_LOST);
    } catch (Exception e) {
      LOG.warn("The mail import of user {} into folder {} failed", username, folderKey, e);
      state.setStatus(SyncStatus.FAILURE);
      state.setMessageCode(IMPORT_FAILED);
    } finally {
      state.setFinishedDate(System.currentTimeMillis());
      storeQuietly(username, state);
      deleteQuietly(workDir);
      RequestLifeCycle.end();
      importingUsers.remove(username);
      notifyFinished(username, state);
    }
    LOG.info("Mail import of user {} into folder {}: {} added, {} skipped, {} refused{}",
             username,
             folderKey,
             state.getAdded(),
             state.getSkipped(),
             state.getRefused(),
             state.getMessageCode() == null ? "" : " (" + state.getMessageCode() + ")");
  }

  /**
   * Tells the user how the import ended, with the counts: web and mail, through the
   * notification plugin. A notification that cannot be made costs the notification,
   * never the run's report, which the drawer still shows.
   *
   * @param username the user
   * @param state the final state
   */
  private void notifyFinished(String username, MailImportState state) {
    try {
      NotificationContext ctx = NotificationContextImpl.cloneInstance()
                                                       .append(MailImportFinishedNotificationPlugin.RECEIVER, username)
                                                       .append(MailImportFinishedNotificationPlugin.FOLDER, state.getFolder())
                                                       .append(MailImportFinishedNotificationPlugin.FOLDER_NAME,
                                                               folderName(username, state.getFolder()))
                                                       .append(MailImportFinishedNotificationPlugin.STATUS,
                                                               String.valueOf(state.getStatus()))
                                                       .append(MailImportFinishedNotificationPlugin.ADDED,
                                                               String.valueOf(state.getAdded()))
                                                       .append(MailImportFinishedNotificationPlugin.SKIPPED,
                                                               String.valueOf(state.getSkipped()))
                                                       .append(MailImportFinishedNotificationPlugin.REFUSED,
                                                               String.valueOf(state.getRefused()))
                                                       .append(MailImportFinishedNotificationPlugin.MESSAGE_CODE,
                                                               StringUtils.defaultString(state.getMessageCode()));
      ctx.getNotificationExecutor()
         .with(ctx.makeCommand(PluginKey.key(NotificationConstants.MAIL_IMPORT_FINISHED_NOTIFICATION_PLUGIN)))
         .execute(ctx);
    } catch (RuntimeException | LinkageError e) {
      LOG.warn("User {} could not be notified about the end of a mail import", username, e);
    }
  }

  /**
   * The name a folder of the user's own is shown under, for the notification; empty for a
   * built-in, which the notification names in the receiver's language.
   *
   * @param username the user
   * @param folderKey the folder key
   * @return the folder's display name, or empty
   */
  private String folderName(String username, String folderKey) {
    if (!MailFolder.isCustom(folderKey)) {
      return "";
    }
    try {
      EmailFolder folder = emailFolderService.getFolderByKey(username, folderKey);
      return folder == null ? "" : StringUtils.defaultString(folder.getDisplayName());
    } catch (RuntimeException e) {
      LOG.debug("Folder {} of user {} has no name to show", folderKey, username, e);
      return "";
    }
  }

  /**
   * Whether a stored state is a run that another node still drives: in progress, and
   * written within {@link #STALE_AFTER_MS}.
   *
   * @param state the stored state
   * @return true while such a run goes
   */
  private static boolean isRunningElsewhere(MailImportState state) {
    return state.getStatus() == SyncStatus.IN_PROGRESS && !isStale(state);
  }

  /**
   * Whether an in-progress state stopped being written long enough ago for its run to be
   * dead.
   *
   * @param state the stored state
   * @return true when it is stale
   */
  private static boolean isStale(MailImportState state) {
    Long updated = state.getUpdatedDate() != null ? state.getUpdatedDate() : state.getStartedDate();
    return updated == null || System.currentTimeMillis() - updated > STALE_AFTER_MS;
  }

  /**
   * A fresh state for a folder, its limits filled in.
   *
   * @param folderKey the folder key
   * @return the state
   */
  private static MailImportState newState(String folderKey) {
    MailImportState state = new MailImportState();
    state.setFolder(folderKey);
    return withLimits(state);
  }

  /**
   * Fills the limits the drawer tells the user about.
   *
   * @param state the state
   * @return the same state
   */
  private static MailImportState withLimits(MailImportState state) {
    state.setMaxFiles(MAX_IMPORT_FILES);
    state.setMaxTotalBytes(MAX_IMPORT_TOTAL_BYTES);
    state.setMaxMails(MAX_IMPORT_MAILS);
    state.setMaxMailBytes(MAX_MAIL_BYTES);
    return state;
  }

  /**
   * The stored state of a user's import.
   *
   * @param username the user
   * @return the state, or a fresh empty one
   */
  private MailImportState getStoredState(String username) {
    SettingValue<?> value = settingService.get(Context.USER.id(username),
                                               UserEmailSettingService.EMAIL_CONNECTOR_SCOPE,
                                               MAIL_IMPORT_STATE_KEY);
    if (value == null || value.getValue() == null) {
      return new MailImportState();
    }
    try {
      return JsonUtils.fromJsonString(value.getValue().toString(), MailImportState.class);
    } catch (Exception e) {
      LOG.warn("The stored mail import state of user {} could not be read, starting from scratch", username, e);
      return new MailImportState();
    }
  }

  /**
   * Stores a user's import state, stamped with the time it was written.
   *
   * @param username the user
   * @param state the state
   */
  private void storeState(String username, MailImportState state) {
    state.setUpdatedDate(System.currentTimeMillis());
    settingService.set(Context.USER.id(username),
                       UserEmailSettingService.EMAIL_CONNECTOR_SCOPE,
                       MAIL_IMPORT_STATE_KEY,
                       SettingValue.create(JsonUtils.toJsonString(state)));
  }

  /**
   * {@link #storeState}, a failure logged rather than thrown: a progress write that fails
   * must not end the run.
   *
   * @param username the user
   * @param state the state
   */
  private void storeQuietly(String username, MailImportState state) {
    try {
      storeState(username, state);
    } catch (RuntimeException e) {
      LOG.warn("The mail import state of user {} could not be stored", username, e);
    }
  }

  /**
   * Takes the files of accepted uploads out of the upload service, into a directory of
   * the run's own, named by their order: the run reads them there whatever happens to
   * the uploads, and deletes them when it ends.
   *
   * @param uploadIds the upload ids, in the order given
   * @param files their files, in the same order
   * @return the run's directory
   * @throws UncheckedIOException when the files cannot be moved; the directory is then
   *           deleted again
   */
  private Path takeUploads(List<String> uploadIds, List<File> files) {
    Path workDir = null;
    try {
      workDir = createPrivateDirectory();
      for (int i = 0; i < files.size(); i++) {
        Files.move(files.get(i).toPath(), workDir.resolve(String.format("%03d", i)));
      }
    } catch (IOException e) {
      deleteQuietly(workDir);
      throw new UncheckedIOException("The uploaded files could not be taken for an import", e);
    }
    for (String uploadId : uploadIds) {
      try {
        uploadService.removeUploadResource(uploadId);
      } catch (RuntimeException e) {
        LOG.debug("Upload {} could not be released", uploadId, e);
      }
    }
    return workDir;
  }

  /**
   * Creates a run's directory in the temporary directory, readable, writable and
   * traversable by this server's user alone: the temporary directory is shared with
   * every user of the machine, and the files are users' mail. Owner-only from its
   * creation on a POSIX file system; on another one, made so right after, the
   * directory being empty meanwhile.
   *
   * @return the directory
   * @throws IOException when it cannot be created, or made private
   */
  static Path createPrivateDirectory() throws IOException {
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      return Files.createTempDirectory(WORK_DIR_PREFIX, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
    }
    Path directory = Files.createTempDirectory(WORK_DIR_PREFIX); // NOSONAR made owner-only below, empty until then
    File file = directory.toFile();
    boolean ownerOnly = file.setReadable(false, false) && file.setReadable(true, true)
        && file.setWritable(false, false) && file.setWritable(true, true)
        && file.setExecutable(false, false) && file.setExecutable(true, true);
    if (!ownerOnly) {
      Files.delete(directory);
      throw new IOException("The import directory could not be made private");
    }
    return directory;
  }

  /**
   * The files of a run, in the order they were given.
   *
   * @param workDir the run's directory
   * @return the files
   * @throws IOException when the directory cannot be listed
   */
  private static List<File> runFiles(Path workDir) throws IOException {
    try (Stream<Path> paths = Files.list(workDir)) {
      return paths.sorted().map(Path::toFile).toList();
    }
  }

  /**
   * Deletes a run's directory and its files, best-effort.
   *
   * @param workDir the directory, possibly null
   */
  private static void deleteQuietly(Path workDir) {
    if (workDir == null) {
      return;
    }
    try (Stream<Path> paths = Files.walk(workDir)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    } catch (IOException | RuntimeException e) {
      LOG.warn("The import directory {} could not be deleted", workDir, e);
    }
  }
}
