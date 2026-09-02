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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.commons.api.settings.ExoFeatureService;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.commons.file.model.FileItem;
import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.emailConnector.event.UserEmailSettingCleanupEvent;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.plugin.EmailConnectorTranslationPlugin;
import org.exoplatform.emailConnector.storage.EmailConnectorStorage;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.security.Identity;
import org.exoplatform.services.security.IdentityConstants;

import io.meeds.appcenter.service.ApplicationCenterService;
import io.meeds.social.translation.service.TranslationService;
import lombok.SneakyThrows;

/**
 * A Service to manage email connectors
 */
@Service
public class EmailConnectorService {

  public static final String        EMAIL_CONNECTOR_SCOPE_ID                     = "EMAIL_CONNECTOR_SCOPE";

  public static final Scope         EMAIL_CONNECTOR_SCOPE                        = Scope.APPLICATION.id(EMAIL_CONNECTOR_SCOPE_ID);

  public static final String        USER_EMAIL_SETTING_KEY                       = "userEmailSetting";

  public static final String        EMAIL_BOX_CACHE_SIZE_KEY                     = "emailBoxCacheSize";

  /** Administration-wide sync period key, in the same {@link #EMAIL_CONNECTOR_SCOPE}. */
  public static final String        EMAIL_BOX_SYNC_PERIOD_KEY                    = "emailBoxSyncPeriod";

  /**
   * Administration-wide size of the mailbox sync executor, in the same
   * {@link #EMAIL_CONNECTOR_SCOPE}: how many mailboxes each node synchronizes
   * at once.
   */
  public static final String        EMAIL_SYNC_THREADS_KEY                       = "emailSyncThreads";

  /** Administration-wide Trash-folder sync kill switch key. */
  public static final String        TRASH_SYNC_ENABLED_KEY                       = "trashSyncEnabled";

  /** Administration-wide Junk-folder sync kill switch key. */
  public static final String        JUNK_SYNC_ENABLED_KEY                        = "junkSyncEnabled";

  /** Administration-wide server-side drafts kill switch key. */
  public static final String        DRAFTS_SERVER_ENABLED_KEY                    = "draftsServerEnabled";

  /** Administration-wide custom-folders master-switch key. */
  public static final String        CUSTOM_FOLDERS_ENABLED_KEY                   = "customFoldersEnabled";

  private static final int          MIN_EMAIL_BOX_CACHE_SIZE                     = 1;

  private static final int          MAX_EMAIL_BOX_CACHE_SIZE                     = 5000;

  /**
   * The floor of the administration-wide sync period: below this, N connected
   * users would log in to the mail server N/5 times a minute, which is already
   * the busiest a shared 25-thread Quartz pool should be pushed to (see the
   * capacity analysis this control was designed against).
   */
  private static final int          MIN_EMAIL_BOX_SYNC_PERIOD                    = 5;

  private static final int          MAX_EMAIL_BOX_SYNC_PERIOD                    = 1440;

  private static final int          MIN_EMAIL_SYNC_THREADS                       = 1;

  /**
   * The ceiling of the sync executor: each thread holds one IMAP connection and
   * one database connection for the length of a run, on every node, so a value
   * past this is a mail-server and connection-pool problem before it is a
   * throughput one.
   */
  private static final int          MAX_EMAIL_SYNC_THREADS                       = 64;

  private static final int          DEFAULT_EMAIL_SYNC_THREADS                   = 10;

  private static final String       EMAIL_CONNECTOR_IS_MANDATORY_MESSAGE         = "Email connector is mandatory";

  private static final String       FEATURE_ACTIVE_IS_MANDATORY_MESSAGE          = "Feature active is mandatory";

  private static final String       USER_NOT_ALLOWED_FOR_EMAIL_CONNECTOR_MESSAGE =
                                                                                 "User %s is not allowed to save email connector : %s";

  private static final String       USER_NOT_ALLOWED_FOR_ACTIVATE_EMAIL_MESSAGE  =
                                                                                "User %s is not allowed to activate email feature";

  private static final String       USER_NOT_ALLOWED_FOR_CACHE_SIZE_MESSAGE      =
                                                                                "User %s is not allowed to update the email box cache size";

  private static final String       CACHE_SIZE_OUT_OF_RANGE_MESSAGE              = "emailConnector.admin.cacheSize.outOfRange";

  private static final String       SYNC_PERIOD_OUT_OF_RANGE_MESSAGE             = "emailConnector.admin.syncSettings.period.outOfRange";

  private static final String       SYNC_THREADS_OUT_OF_RANGE_MESSAGE            = "emailConnector.admin.syncThreads.outOfRange";

  private static final String       USER_NOT_ALLOWED_FOR_SYNC_SETTINGS_MESSAGE   =
                                                                                 "User %s is not allowed to update the email sync settings";

  private static final String       EMAIL_CONNECTOR_NOT_FOUND_MESSAGE            = "Email connector with id %s doesn't exist";

  private static final Log          LOG                                          =
                                        ExoLogger.getLogger(EmailConnectorService.class);

  @Autowired
  private SettingService            settingService;

  @Autowired
  private UserACL                   userAcl;

  @Autowired
  private TranslationService        translationService;

  @Autowired
  ApplicationCenterService          applicationCenterService;

  @Autowired
  private ExoFeatureService         featureService;

  @Autowired
  private EmailConnectorStorage     emailConnectorStorage;

  @Autowired
  private FileService               fileService;

  @Autowired
  private ApplicationEventPublisher eventPublisher;

  /**
   * Activate email feature.
   *
   * @param username user activating email feature
   * @param isFeatureActive A boolean flag that specifies whether the email
   *          feature is active (true) or inactive (false)
   * @throws IllegalAccessException if user is not allowed to activate email
   *           feature
   */
  public void activateEmailFeature(Boolean isFeatureActive, String username) throws IllegalAccessException {
    if (isFeatureActive == null) {
      throw new IllegalArgumentException(FEATURE_ACTIVE_IS_MANDATORY_MESSAGE);
    }
    if (!canEdit(username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_ACTIVATE_EMAIL_MESSAGE, username));
    }
    featureService.saveActiveFeature(EmailConnectorUtils.EMAIL_FEATURE, isFeatureActive);
    activateEmailApp();

  }

  /**
   * Get the number of most recent emails kept in the local cache per user. This
   * is an administration-wide setting, read at synchronization time so a change
   * takes effect on the next sync without a restart. When no value is stored (or
   * the stored value is not a valid integer) the default
   * {@link EmailConnectorUtils#DEFAULT_EMAIL_BOX_CACHE_SIZE} is returned.
   *
   * @return the configured mailbox cache size
   */
  public int getEmailBoxCacheSize() {
    SettingValue<?> settingValue = settingService.get(Context.GLOBAL, EMAIL_CONNECTOR_SCOPE, EMAIL_BOX_CACHE_SIZE_KEY);
    if (settingValue != null && settingValue.getValue() != null) {
      try {
        return Integer.parseInt(settingValue.getValue().toString());
      } catch (NumberFormatException e) {
        LOG.warn("Invalid stored email box cache size '{}', falling back to the default {}",
                 settingValue.getValue(),
                 EmailConnectorUtils.DEFAULT_EMAIL_BOX_CACHE_SIZE);
      }
    }
    return EmailConnectorUtils.DEFAULT_EMAIL_BOX_CACHE_SIZE;
  }

  /**
   * Save the administration-wide mailbox cache size (number of most recent
   * emails kept per user). Administrators only.
   *
   * @param cacheSize the number of emails to keep per user, between
   *          {@value #MIN_EMAIL_BOX_CACHE_SIZE} and
   *          {@value #MAX_EMAIL_BOX_CACHE_SIZE}
   * @param username user updating the cache size
   * @throws IllegalAccessException if the user is not allowed to update the
   *           cache size
   */
  public void saveEmailBoxCacheSize(int cacheSize, String username) throws IllegalAccessException {
    if (!canEdit(username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_CACHE_SIZE_MESSAGE, username));
    }
    if (cacheSize < MIN_EMAIL_BOX_CACHE_SIZE || cacheSize > MAX_EMAIL_BOX_CACHE_SIZE) {
      throw new IllegalArgumentException(CACHE_SIZE_OUT_OF_RANGE_MESSAGE);
    }
    settingService.set(Context.GLOBAL, EMAIL_CONNECTOR_SCOPE, EMAIL_BOX_CACHE_SIZE_KEY, SettingValue.create(String.valueOf(cacheSize)));
  }

  /**
   * Get the administration-wide mailbox sync period, in minutes: how long after a
   * mailbox's last synchronization the dispatcher considers it due again. Read by
   * the dispatcher at every tick, never captured, so a change here reaches every
   * connected mailbox at the next tick. When no value is stored, falls back to the
   * {@code email.connector.sync.user.minute.period} JVM property (default 10), the
   * same default the platform has always shipped.
   *
   * @return the configured sync period, in minutes
   */
  public int getEmailBoxSyncPeriod() {
    SettingValue<?> settingValue = settingService.get(Context.GLOBAL, EMAIL_CONNECTOR_SCOPE, EMAIL_BOX_SYNC_PERIOD_KEY);
    if (settingValue != null && settingValue.getValue() != null) {
      try {
        return Integer.parseInt(settingValue.getValue().toString());
      } catch (NumberFormatException e) {
        LOG.warn("Invalid stored email sync period '{}', falling back to the default", settingValue.getValue());
      }
    }
    return Integer.parseInt(System.getProperty("email.connector.sync.user.minute.period", "10"));
  }

  /**
   * Save the administration-wide mailbox sync period. Nothing is rescheduled: the
   * dispatcher reads the period at each tick, so the value applies at the next
   * dispatch to every connected mailbox. Administrators only.
   *
   * @param minutes the sync period, in minutes, between {@value #MIN_EMAIL_BOX_SYNC_PERIOD}
   *          and {@value #MAX_EMAIL_BOX_SYNC_PERIOD}
   * @param username user updating the sync period
   * @throws IllegalAccessException if the user is not allowed to update the sync
   *           period
   */
  public void saveEmailBoxSyncPeriod(int minutes, String username) throws IllegalAccessException {
    if (!canEdit(username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_SYNC_SETTINGS_MESSAGE, username));
    }
    if (minutes < MIN_EMAIL_BOX_SYNC_PERIOD || minutes > MAX_EMAIL_BOX_SYNC_PERIOD) {
      throw new IllegalArgumentException(SYNC_PERIOD_OUT_OF_RANGE_MESSAGE);
    }
    settingService.set(Context.GLOBAL, EMAIL_CONNECTOR_SCOPE, EMAIL_BOX_SYNC_PERIOD_KEY, SettingValue.create(String.valueOf(minutes)));
  }

  /**
   * Get the administration-wide size of the mailbox sync executor: how many
   * mailboxes each node synchronizes at once. Read by the dispatcher at every
   * tick, which resizes its pool when the value moved, so a change here takes
   * effect within a minute and without a restart. When no value is stored, falls
   * back to the {@code email.connector.sync.threads} JVM property (default
   * {@value #DEFAULT_EMAIL_SYNC_THREADS}).
   *
   * @return the executor size, in threads
   */
  public int getEmailSyncThreads() {
    SettingValue<?> settingValue = settingService.get(Context.GLOBAL, EMAIL_CONNECTOR_SCOPE, EMAIL_SYNC_THREADS_KEY);
    if (settingValue != null && settingValue.getValue() != null) {
      try {
        return Integer.parseInt(settingValue.getValue().toString());
      } catch (NumberFormatException e) {
        LOG.warn("Invalid stored email sync thread count '{}', falling back to the default", settingValue.getValue());
      }
    }
    return Integer.parseInt(System.getProperty("email.connector.sync.threads", String.valueOf(DEFAULT_EMAIL_SYNC_THREADS)));
  }

  /**
   * Save the administration-wide size of the mailbox sync executor.
   * Administrators only.
   *
   * @param threads the executor size, between {@value #MIN_EMAIL_SYNC_THREADS}
   *          and {@value #MAX_EMAIL_SYNC_THREADS}
   * @param username user updating the size
   * @throws IllegalAccessException if the user is not allowed to update the sync
   *           settings
   */
  public void saveEmailSyncThreads(int threads, String username) throws IllegalAccessException {
    if (!canEdit(username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_SYNC_SETTINGS_MESSAGE, username));
    }
    if (threads < MIN_EMAIL_SYNC_THREADS || threads > MAX_EMAIL_SYNC_THREADS) {
      throw new IllegalArgumentException(SYNC_THREADS_OUT_OF_RANGE_MESSAGE);
    }
    settingService.set(Context.GLOBAL, EMAIL_CONNECTOR_SCOPE, EMAIL_SYNC_THREADS_KEY, SettingValue.create(String.valueOf(threads)));
  }

  /**
   * Whether the Trash folder's synchronization is switched on, administration-wide.
   * Read on every sync rather than cached, so an administrator can withdraw it
   * without a restart. Falls back to the
   * {@code email.connector.trash.sync.enabled} JVM property (default {@code true})
   * when no value is stored.
   *
   * @return true when the Trash folder may be cached
   */
  public boolean isTrashSyncEnabled() {
    SettingValue<?> settingValue = settingService.get(Context.GLOBAL, EMAIL_CONNECTOR_SCOPE, TRASH_SYNC_ENABLED_KEY);
    if (settingValue != null && settingValue.getValue() != null) {
      return Boolean.parseBoolean(settingValue.getValue().toString());
    }
    return Boolean.parseBoolean(System.getProperty("email.connector.trash.sync.enabled", "true"));
  }

  /**
   * Save the administration-wide Trash-folder sync switch. Administrators only.
   *
   * @param enabled whether the Trash folder should be cached
   * @param username user updating the switch
   * @throws IllegalAccessException if the user is not allowed to update it
   */
  public void saveTrashSyncEnabled(boolean enabled, String username) throws IllegalAccessException {
    if (!canEdit(username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_SYNC_SETTINGS_MESSAGE, username));
    }
    settingService.set(Context.GLOBAL, EMAIL_CONNECTOR_SCOPE, TRASH_SYNC_ENABLED_KEY, SettingValue.create(String.valueOf(enabled)));
  }

  /**
   * Whether the Junk folder's synchronization is switched on, administration-wide —
   * see {@link #isTrashSyncEnabled()} for the shape. Falls back to the
   * {@code email.connector.junk.sync.enabled} JVM property (default {@code true}).
   *
   * @return true when the Junk folder may be cached
   */
  public boolean isJunkSyncEnabled() {
    SettingValue<?> settingValue = settingService.get(Context.GLOBAL, EMAIL_CONNECTOR_SCOPE, JUNK_SYNC_ENABLED_KEY);
    if (settingValue != null && settingValue.getValue() != null) {
      return Boolean.parseBoolean(settingValue.getValue().toString());
    }
    return Boolean.parseBoolean(System.getProperty("email.connector.junk.sync.enabled", "true"));
  }

  /**
   * Save the administration-wide Junk-folder sync switch. Administrators only.
   *
   * @param enabled whether the Junk folder should be cached
   * @param username user updating the switch
   * @throws IllegalAccessException if the user is not allowed to update it
   */
  public void saveJunkSyncEnabled(boolean enabled, String username) throws IllegalAccessException {
    if (!canEdit(username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_SYNC_SETTINGS_MESSAGE, username));
    }
    settingService.set(Context.GLOBAL, EMAIL_CONNECTOR_SCOPE, JUNK_SYNC_ENABLED_KEY, SettingValue.create(String.valueOf(enabled)));
  }

  /**
   * Whether the server-side half of drafts is switched on, administration-wide —
   * see {@link #isTrashSyncEnabled()} for the shape. Falls back to the
   * {@code email.connector.drafts.server.enabled} JVM property (default
   * {@code true}).
   *
   * @return true when drafts may be uploaded to the mail server
   */
  public boolean isServerDraftsEnabled() {
    SettingValue<?> settingValue = settingService.get(Context.GLOBAL, EMAIL_CONNECTOR_SCOPE, DRAFTS_SERVER_ENABLED_KEY);
    if (settingValue != null && settingValue.getValue() != null) {
      return Boolean.parseBoolean(settingValue.getValue().toString());
    }
    return Boolean.parseBoolean(System.getProperty("email.connector.drafts.server.enabled", "true"));
  }

  /**
   * Save the administration-wide server-side drafts switch. Administrators only.
   *
   * @param enabled whether drafts should be uploaded to the mail server
   * @param username user updating the switch
   * @throws IllegalAccessException if the user is not allowed to update it
   */
  public void saveServerDraftsEnabled(boolean enabled, String username) throws IllegalAccessException {
    if (!canEdit(username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_SYNC_SETTINGS_MESSAGE, username));
    }
    settingService.set(Context.GLOBAL, EMAIL_CONNECTOR_SCOPE, DRAFTS_SERVER_ENABLED_KEY, SettingValue.create(String.valueOf(enabled)));
  }

  /**
   * Whether custom folders are switched on at all, administration-wide — see
   * {@link #isTrashSyncEnabled()} for the shape. Unlike the Trash/Junk/drafts
   * switches, which only stop caching new mail and leave everything already
   * cached in place, this one withdraws the whole feature at once: custom
   * folders disappear from every user's folder list and "Move to…" menu and
   * from their own settings screen, and opting in/out, on-demand refresh and
   * moving mail into a custom folder are all refused ({@code EmailBoxService}'s
   * {@code checkCustomFoldersEnabled}). Nothing is deleted by turning this
   * off — the opted-in folders and their cached mail are exactly as they were
   * when it is turned back on. Falls back to the
   * {@code email.connector.customFolders.enabled} JVM property (default
   * {@code true}).
   *
   * @return true when custom folders are discovered, mirrored and offered
   */
  public boolean isCustomFoldersEnabled() {
    SettingValue<?> settingValue = settingService.get(Context.GLOBAL, EMAIL_CONNECTOR_SCOPE, CUSTOM_FOLDERS_ENABLED_KEY);
    if (settingValue != null && settingValue.getValue() != null) {
      return Boolean.parseBoolean(settingValue.getValue().toString());
    }
    return Boolean.parseBoolean(System.getProperty("email.connector.customFolders.enabled", "true"));
  }

  /**
   * Save the administration-wide custom-folders master switch. Administrators
   * only.
   *
   * @param enabled whether custom folders should be discovered, mirrored and
   *          offered
   * @param username user updating the switch
   * @throws IllegalAccessException if the user is not allowed to update it
   */
  public void saveCustomFoldersEnabled(boolean enabled, String username) throws IllegalAccessException {
    if (!canEdit(username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_SYNC_SETTINGS_MESSAGE, username));
    }
    settingService.set(Context.GLOBAL, EMAIL_CONNECTOR_SCOPE, CUSTOM_FOLDERS_ENABLED_KEY, SettingValue.create(String.valueOf(enabled)));
  }

  /**
   * Create new email connector that will be available for all users.
   *
   * @param emailConnector emailConnector to create
   * @param username user creating email connector
   * @return stored {@link EmailConnector} in datasource
   * @throws IllegalAccessException if user is not allowed to create an email
   *           connector
   */
  public EmailConnector createEmailConnector(EmailConnector emailConnector, String username) throws IllegalAccessException {
    if (emailConnector == null) {
      throw new IllegalArgumentException(EMAIL_CONNECTOR_IS_MANDATORY_MESSAGE);
    }
    if (!canEdit(username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_EMAIL_CONNECTOR_MESSAGE,
                                                     username,
                                                     emailConnector.getName()));
    }
    EmailConnector storedEmailConnector = emailConnectorStorage.createEmailConnector(emailConnector);
    activateEmailApp();
    return storedEmailConnector;

  }

  /**
   * Update an existing email connector on datasource.
   *
   * @param emailConnector dto to update on store
   * @param username user storing email connector
   * @throws IllegalAccessException if user is not allowed to update an email
   *           connector
   */
  public void updateEmailConnector(EmailConnector emailConnector, String username) throws IllegalAccessException {
    if (emailConnector == null) {
      throw new IllegalArgumentException(EMAIL_CONNECTOR_IS_MANDATORY_MESSAGE);
    }
    if (!canEdit(username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_EMAIL_CONNECTOR_MESSAGE,
                                                     username,
                                                     emailConnector.getName()));
    }
    emailConnectorStorage.updateEmailConnector(emailConnector);
  }

  /**
   * Activate an existing email connector on datasource.
   *
   * @param emailConnectorId technical identifier of email connector to be
   *          activated
   * @param isEmailConnectorActive A boolean flag that specifies whether the
   *          email connector is active (true) or inactive (false)
   * @param username user activating email connector
   * @throws IllegalAccessException if user is not allowed to activate an email
   *           connector
   */
  public void activateEmailConnector(Long emailConnectorId,
                                     boolean isEmailConnectorActive,
                                     String username) throws IllegalAccessException {
    if (emailConnectorId == null) {
      throw new IllegalArgumentException(EMAIL_CONNECTOR_IS_MANDATORY_MESSAGE);
    }
    EmailConnector storedEmailConnector = emailConnectorStorage.getEmailConnector(emailConnectorId);
    if (storedEmailConnector == null) {
      throw new IllegalArgumentException(EMAIL_CONNECTOR_IS_MANDATORY_MESSAGE);
    }
    if (!canEdit(username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_EMAIL_CONNECTOR_MESSAGE,
                                                     username,
                                                     storedEmailConnector.getName()));
    }
    emailConnectorStorage.activateEmailConnector(emailConnectorId, isEmailConnectorActive);
    activateEmailApp();
  }

  /**
   * Delete an existing email connector on datasource.
   *
   * @param emailConnectorId technical identifier of email connector to be
   *          deleted
   * @param username user deleting email connector
   * @throws IllegalAccessException if user is not allowed to delete an email
   *           connector
   */
  @Transactional
  public void deleteEmailConnector(Long emailConnectorId, String username) throws IllegalAccessException {
    if (emailConnectorId == null) {
      throw new IllegalArgumentException(EMAIL_CONNECTOR_IS_MANDATORY_MESSAGE);
    }
    EmailConnector storedEmailConnector = emailConnectorStorage.getEmailConnector(emailConnectorId);
    if (storedEmailConnector == null) {
      throw new IllegalArgumentException(EMAIL_CONNECTOR_IS_MANDATORY_MESSAGE);
    }
    if (!canEdit(username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_EMAIL_CONNECTOR_MESSAGE,
                                                     username,
                                                     storedEmailConnector.getName()));
    }
    emailConnectorStorage.deleteEmailConnector(emailConnectorId);
    activateEmailApp();
    eventPublisher.publishEvent(new UserEmailSettingCleanupEvent(emailConnectorId));
  }

  /**
   * Get an email connector by id
   *
   * @param emailConnectorId email connector to find
   * @return stored {@link EmailConnector} in datasource
   */
  public EmailConnector getEmailConnector(long emailConnectorId) {
    return emailConnectorStorage.getEmailConnector(emailConnectorId);
  }

  /**
   * Get email connectors that will be available for all users.
   *
   * @param locale used language to retrieve email connector name
   * @return list of stored {@link EmailConnector} in datasource
   */
  public List<EmailConnector> getEmailConnectors(Locale locale) {
    List<EmailConnector> emailConnectors = emailConnectorStorage.getEmailConnectors();
    emailConnectors = emailConnectors.stream().map(emailConnector -> {
      String translatedName =
                            translationService.getTranslationLabelOrDefault(EmailConnectorTranslationPlugin.EMAIL_CONNECTOR_OBJECT_TYPE,
                                                                            emailConnector.getId(),
                                                                            "name",
                                                                            locale);
      emailConnector.setName(translatedName);
      return emailConnector;
    }).toList();
    return emailConnectors;
  }

  /**
   * Get active email connectors.
   *
   * @return list of stored active {@link EmailConnector} in datasource
   */
  public List<EmailConnector> getActiveEmailConnectors() {
    return emailConnectorStorage.getActiveEmailConnectors();
  }

  /**
   * Return the {@link EmailConnector} illustration {@link InputStream}, if not
   * found, the default image {@link InputStream} will be retrieved
   *
   * @param emailConnectorId technical id of email connector
   * @return {@link InputStream} of email connector illustration
   */
  @SneakyThrows
  public InputStream getEmailConnectorImageInputStream(long emailConnectorId) {
    EmailConnector emailConnector = emailConnectorStorage.getEmailConnector(emailConnectorId);
    if (emailConnector == null) {
      throw new IllegalArgumentException(String.format(EMAIL_CONNECTOR_NOT_FOUND_MESSAGE, emailConnectorId));
    } else if (emailConnector.getImageFileId() != null) {
      FileItem fileItem = fileService.getFile(emailConnector.getImageFileId());
      if (fileItem != null && fileItem.getAsByte() != null) {
        return new ByteArrayInputStream(fileItem.getAsByte());
      }
    }
    return null;
  }

  public boolean canEdit(String username) {
    return StringUtils.isBlank(username) || userAcl.isAdministrator(getUserIdentity(username));
  }

  private Identity getUserIdentity(String username) {
    if (StringUtils.isBlank(username)) {
      return new Identity(IdentityConstants.ANONIM);
    } else {
      return userAcl.getUserIdentity(username);
    }
  }

  private void activateEmailApp() {
    applicationCenterService.getApplications(0, 0, null)
                            .getApplications()
                            .stream()
                            .filter(application -> application.getUrl().equals(EmailConnectorUtils.EMAIL_FEATURE))
                            .findFirst()
                            .ifPresent(application -> {
                              boolean hasActiveConnector = !emailConnectorStorage.getActiveEmailConnectors().isEmpty();
                              boolean featureEnabled = featureService.isActiveFeature(EmailConnectorUtils.EMAIL_FEATURE);
                              application.setActive(hasActiveConnector && featureEnabled);
                              applicationCenterService.updateApplication(application);
                            });
  }
}
