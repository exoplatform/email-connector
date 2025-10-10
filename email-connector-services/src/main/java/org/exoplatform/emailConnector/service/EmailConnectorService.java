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
import java.util.Properties;

import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Store;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.api.settings.ExoFeatureService;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.commons.file.model.FileItem;
import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.emailConnector.entity.UserEmailSettingEntity;
import org.exoplatform.emailConnector.job.EmailBoxSyncJob;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.plugin.EmailConnectorTranslationPlugin;
import org.exoplatform.emailConnector.storage.EmailConnectorStorage;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.scheduler.JobInfo;
import org.exoplatform.services.scheduler.JobSchedulerService;
import org.exoplatform.services.scheduler.PeriodInfo;
import org.exoplatform.services.security.Identity;
import org.exoplatform.services.security.IdentityConstants;
import org.exoplatform.web.security.codec.CodecInitializer;
import org.exoplatform.web.security.security.TokenServiceInitializationException;

import io.meeds.appcenter.service.ApplicationCenterService;
import io.meeds.social.translation.service.TranslationService;
import io.meeds.social.util.JsonUtils;
import jakarta.annotation.PostConstruct;
import lombok.SneakyThrows;

/**
 * A Service to manage email connectors
 */
@Service
public class EmailConnectorService {

  private static final String   EMAIL_CONNECTOR_IS_MANDATORY_MESSAGE               = "Email connector is mandatory";

  private static final String   FEATURE_ACTIVE_IS_MANDATORY_MESSAGE                = "Feature active is mandatory";

  private static final String   USER_NOT_ALLOWED_FOR_EMAIL_CONNECTOR_MESSAGE       =
                                                                             "User %s is not allowed to save email connector : %s";

  private static final String   USER_NOT_ALLOWED_FOR_ACTIVATE_EMAIL_MESSAGE        =
                                                                            "User %s is not allowed to activate email feature";

  private static final String   USER_NOT_ALLOWED_FOR_CONNECT_EMAIL_SETTING_MESSAGE =
                                                                                   "User %s is not allowed to connect email setting";

  private static final String   EMAIL_CONNECTOR_NOT_FOUND_MESSAGE                  = "Email connector with id %s doesn't exist";

  private static final String   EMAIL_CONNECTOR_SCOPE_ID                           = "EMAIL_CONNECTOR_SCOPE";

  private static final Scope    EMAIL_CONNECTOR_SCOPE                              =
                                                      Scope.APPLICATION.id(EMAIL_CONNECTOR_SCOPE_ID);

  private static final String   USER_EMAIL_SETTING_KEY                             = "userEmailSetting";

  private static final Log      LOG                                                =
                                    ExoLogger.getLogger(EmailConnectorService.class);

  @Autowired
  private UserACL               userAcl;

  @Autowired
  private EmailConnectorStorage emailConnectorStorage;

  @Autowired
  private FileService           fileService;

  @Autowired
  private TranslationService    translationService;

  @Autowired
  private SettingService        settingService;

  @Autowired
  ApplicationCenterService      applicationCenterService;

  @Autowired
  private CodecInitializer      codecInitializer;

  @Autowired
  private ExoFeatureService     featureService;

  @Autowired
  private JobSchedulerService   jobSchedulerService;

  @PostConstruct
  public void initEmailBoxSyncJob() throws Exception {
    List<Context> contexts = settingService.getContextsByTypeAndScopeAndSettingName(Context.USER.getName(),
                                                                                    Scope.APPLICATION.getName(),
                                                                                    EMAIL_CONNECTOR_SCOPE_ID,
                                                                                    USER_EMAIL_SETTING_KEY,
                                                                                    0,
                                                                                    Integer.MAX_VALUE);
    for (Context context : contexts) {
      UserEmailSetting userEmailSetting = getUserEmailSetting(context.getId());
      scheduleEmailBoxUserSyncJob(userEmailSetting, context.getId());
    }
  }

  /**
   * Activate email feature.
   *
   * @param username user activating email feature
   * @param isFeatureActive A boolean flag that specifies whether the email
   *          feature is active (true) or inactive (false)
   * @throws IllegalAccessException if user is not allowed to activate email
   *           feature
   */
  public void activateEmailFeature(String isFeatureActive, String username) throws IllegalAccessException {
    if (isFeatureActive == null) {
      throw new IllegalArgumentException(FEATURE_ACTIVE_IS_MANDATORY_MESSAGE);
    }
    if (!canEdit(username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_ACTIVATE_EMAIL_MESSAGE, username));
    }
    boolean isFeatureActiveBool = Boolean.parseBoolean(isFeatureActive);
    featureService.saveActiveFeature(EmailConnectorUtils.EMAIL_FEATURE, isFeatureActiveBool);
    activateEmailApp();

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
                                     String isEmailConnectorActive,
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
   * Get active email connectors that will be available for all users.
   *
   * @param locale used language to retrieve email connector name
   * @param username user getting active email connectors
   * @return list of stored {@link EmailConnector} in datasource
   */
  public List<EmailConnector> getActiveEmailConnectors(Locale locale, String username) {
    List<EmailConnector> activeEmailConnectors = emailConnectorStorage.getActiveEmailConnectors();
    activeEmailConnectors = activeEmailConnectors.stream().map(emailConnector -> {
      String translatedName =
                            translationService.getTranslationLabelOrDefault(EmailConnectorTranslationPlugin.EMAIL_CONNECTOR_OBJECT_TYPE,
                                                                            emailConnector.getId(),
                                                                            "name",
                                                                            locale);
      emailConnector.setName(translatedName);
      emailConnector.setUserConnected(isEmailConnectorUserConnected(emailConnector.getId(), username));
      emailConnector.setCanConnect(canConnect(emailConnector.getId(), username));
      return emailConnector;
    }).toList();
    return activeEmailConnectors;
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

  /**
   * Connect user email setting.
   *
   * @param userEmailSetting userEmailSetting to connect
   * @param username user connecting the user email setting
   * @throws IllegalAccessException if user is not allowed to connect email
   *           setting
   */
  public void connectUserEmailSetting(UserEmailSetting userEmailSetting, String username) throws IllegalAccessException {
    if (!canConnect(userEmailSetting)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_CONNECT_EMAIL_SETTING_MESSAGE, username));
    }
    Store store = null;
    try {
      store = connect(userEmailSetting);
      setUserEmailSetting(userEmailSetting, username);
      try {
        scheduleEmailBoxUserSyncJob(userEmailSetting, username);
      } catch (Exception e) {
        LOG.warn("Error when scheduling email box user sync job", e);
      }
    } catch (Exception e) {
      LOG.error("Error when connecting store for user {}", username, e);
      throw new IllegalStateException(String.format("Error when connecting store for user %s", username));
    } finally {
      try {
        if (store != null && store.isConnected()) {
          store.close();
        }
      } catch (MessagingException messagingException) {
        LOG.warn("Error when closing store", messagingException);
      }
    }
  }

  /**
   * Set user email setting.
   *
   * @param userEmailSetting userEmailSetting to set
   * @param username user setting the user email setting
   */
  public void setUserEmailSetting(UserEmailSetting userEmailSetting, String username) {
    userEmailSetting.setEmailPassword(encodePassword(userEmailSetting.getEmailPassword()));
    UserEmailSettingEntity userEmailSettingEntity = new UserEmailSettingEntity(userEmailSetting.getEmailConnectorId(),
                                                                               userEmailSetting.getEmailAddress(),
                                                                               userEmailSetting.getEmailPassword(),
                                                                               userEmailSetting.getEmailBoxUserSyncPeriod(),
                                                                               userEmailSetting.getEmailSyncStatus(),
                                                                               userEmailSetting.getEmailSyncFailedAttemps(),
                                                                               userEmailSetting.getLastEmailSyncStartDate());
    settingService.set(Context.USER.id(username),
                       EMAIL_CONNECTOR_SCOPE,
                       USER_EMAIL_SETTING_KEY,
                       SettingValue.create(JsonUtils.toJsonString(userEmailSettingEntity)));
  }

  /**
   * Get user email setting.
   *
   * @param username user getting user email setting
   * @return stored {@link UserEmailSetting} in datasource
   */
  public UserEmailSetting getUserEmailSetting(String username) {
    UserEmailSetting userEmailSetting = new UserEmailSetting();
    SettingValue<?> userEmailSettingValue = settingService.get(Context.USER.id(username),
                                                               EMAIL_CONNECTOR_SCOPE,
                                                               USER_EMAIL_SETTING_KEY);
    if (userEmailSettingValue != null) {
      UserEmailSetting storedUserEmailSetting = JsonUtils.fromJsonString(userEmailSettingValue.getValue().toString(),
                                                                         UserEmailSetting.class);
      userEmailSetting = storedUserEmailSetting;
      userEmailSetting.setEmailPassword(decodePassword(userEmailSetting.getEmailPassword()));
      EmailConnector emailConnector = getEmailConnector(Long.parseLong(userEmailSetting.getEmailConnectorId()));
      if (emailConnector != null) {
        userEmailSetting.setEmailConnectorImageUrl(emailConnector.getImageUrl());
        userEmailSetting.setEmailConnectorIcon(emailConnector.getIcon());
        userEmailSetting.setEmailConnectorName(emailConnector.getName());
        userEmailSetting.setConnected(emailConnector.isActive());
      }
    }
    return userEmailSetting;
  }

  /**
   * Delete user email setting.
   *
   * @param username user deleting user email setting
   */
  public void deleteUserEmailSetting(String username) {
    settingService.remove(Context.USER.id(username), EMAIL_CONNECTOR_SCOPE, USER_EMAIL_SETTING_KEY);
  }

  /**
   * Check if user has edit rights.
   *
   * @param username user to check edit rights
   * @return boolean value if user can edit or not
   */
  public boolean canEdit(String username) {
    return StringUtils.isBlank(username) || userAcl.isAdministrator(getUserIdentity(username));
  }

  /**
   * Connect to user mail box.
   *
   * @param userEmailSetting userEmailSetting used to connect
   * @return store user box connected store
   */
  public Store connect(UserEmailSetting userEmailSetting) throws MessagingException {
    EmailConnector emailConnector = getEmailConnector(Long.parseLong(userEmailSetting.getEmailConnectorId()));
    Properties props = new Properties();
    props.setProperty("mail.imaps.ssl.enable", "true");
    props.setProperty("mail.store.protocol", "imaps");
    props.setProperty("mail.imaps.port", emailConnector.getPort());
    props.put("mail.imap.connectiontimeout", "10000");
    // Connect to the server
    Session session = Session.getDefaultInstance(props);
    Store store = session.getStore();
    store.connect(emailConnector.getImapUrl(),
                  Integer.parseInt(emailConnector.getPort()),
                  userEmailSetting.getEmailAddress(),
                  userEmailSetting.getEmailPassword());
    return store;
  }

  public boolean canConnect(UserEmailSetting userEmailSetting) {
    return featureService.isActiveFeature(EmailConnectorUtils.EMAIL_FEATURE) && userEmailSetting.getEmailConnectorId() != null
        && getEmailConnector(Long.parseLong(userEmailSetting.getEmailConnectorId())) != null
        && getEmailConnector(Long.parseLong(userEmailSetting.getEmailConnectorId())).isActive();
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

  private boolean canConnect(Long emailConnectorId, String username) {
    return getUserEmailSetting(username).getEmailConnectorId() == null
        || getEmailConnector(Long.parseLong(getUserEmailSetting(username).getEmailConnectorId())) == null
        || !getEmailConnector(Long.parseLong(getUserEmailSetting(username).getEmailConnectorId())).isActive()
        || isEmailConnectorUserConnected(emailConnectorId, username);
  }

  private String decodePassword(String password) {
    try {
      return codecInitializer.getCodec().decode(password);
    } catch (TokenServiceInitializationException e) {
      LOG.warn("Error when decoding password", e);
      return null;
    }
  }

  private String encodePassword(String password) {
    try {
      return codecInitializer.getCodec().encode(password);
    } catch (TokenServiceInitializationException e) {
      LOG.warn("Error when encoding password", e);
      return null;
    }
  }

  private Identity getUserIdentity(String username) {
    if (StringUtils.isBlank(username)) {
      return new Identity(IdentityConstants.ANONIM);
    } else {
      return userAcl.getUserIdentity(username);
    }
  }

  private boolean isEmailConnectorUserConnected(Long emailConnectorId, String username) {
    return getUserEmailSetting(username) != null
        && String.valueOf(emailConnectorId).equals(getUserEmailSetting(username).getEmailConnectorId());
  }

  private void scheduleEmailBoxUserSyncJob(UserEmailSetting userEmailSetting, String username) throws Exception {
    String emailBoxSyncJobName = username + EmailConnectorUtils.EMAIL_BOX_SYNC_JOB_NAME;
    JobInfo emailBoxSyncJobInfo = new JobInfo(emailBoxSyncJobName, EmailConnectorUtils.EMAIL_FEATURE, EmailBoxSyncJob.class);
    // Remove next email box sync job for the user
    jobSchedulerService.removeJob(emailBoxSyncJobInfo);
    PeriodInfo periodInfo =
                          new PeriodInfo(null, null, 0, EmailConnectorUtils.getEmailBoxUserSyncPeriod(userEmailSetting) * 60000);
    jobSchedulerService.addPeriodJob(emailBoxSyncJobInfo, periodInfo);
    LOG.info("Email box sync for user: {} scheduled periodically every {} minutes",
             username,
             EmailConnectorUtils.getEmailBoxUserSyncPeriod(userEmailSetting));
  }
}
