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

import java.util.List;
import java.util.Locale;
import java.util.Properties;

import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Store;

import org.apache.commons.lang3.StringUtils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.commons.api.settings.ExoFeatureService;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.emailConnector.entity.UserEmailSettingEntity;
import org.exoplatform.emailConnector.event.EmailBoxCleanupEvent;
import org.exoplatform.emailConnector.event.EmailBoxSyncEvent;
import org.exoplatform.emailConnector.model.ContactSyncState;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.plugin.EmailConnectorTranslationPlugin;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.web.security.codec.CodecInitializer;
import org.exoplatform.web.security.security.TokenServiceInitializationException;

import io.meeds.social.translation.service.TranslationService;
import io.meeds.social.util.JsonUtils;

/**
 * A Service to manage user email setting
 */
@Service
public class UserEmailSettingService {

  public static final String        EMAIL_CONNECTOR_SCOPE_ID                           = "EMAIL_CONNECTOR_SCOPE";

  public static final Scope         EMAIL_CONNECTOR_SCOPE                              =
                                                          Scope.APPLICATION.id(EMAIL_CONNECTOR_SCOPE_ID);

  public static final String        USER_EMAIL_SETTING_KEY                             = "userEmailSetting";

  /**
   * The address-book sync's own key, deliberately NOT a field of
   * {@link #USER_EMAIL_SETTING_KEY}: the mailbox sync rewrites that whole document
   * on every status update, so sharing it would let the two syncs overwrite each
   * other's fields.
   */
  public static final String        CONTACT_SYNC_STATE_KEY                             = "emailContactSyncState";

  private static final String       USER_NOT_ALLOWED_FOR_CONNECT_EMAIL_SETTING_MESSAGE =
                                                                                       "User %s is not allowed to connect email setting";

  private static final Log          LOG                                                =
                                        ExoLogger.getLogger(UserEmailSettingService.class);

  @Autowired
  private SettingService            settingService;

  @Autowired
  private CodecInitializer          codecInitializer;

  @Autowired
  private TranslationService        translationService;

  @Autowired
  private ExoFeatureService         featureService;

  @Autowired
  private EmailConnectorService     emailConnectorService;

  @Autowired
  private ApplicationEventPublisher eventPublisher;

  /**
   * Connect user email setting.
   *
   * @param userEmailSetting userEmailSetting to connect
   * @param username user connecting the user email setting
   * @param broadcast broadcast event
   * @throws IllegalAccessException if user is not allowed to connect email
   *           setting
   */
  @Transactional
  public void connectUserEmailSetting(UserEmailSetting userEmailSetting,
                                      String username,
                                      boolean broadcast) throws IllegalAccessException {
    if (!canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_CONNECT_EMAIL_SETTING_MESSAGE, username));
    }
    Store store = null;
    try {
      store = connect(userEmailSetting);
      setUserEmailSetting(userEmailSetting, username, broadcast);
      eventPublisher.publishEvent(new EmailBoxSyncEvent(username));
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
  public void setUserEmailSetting(UserEmailSetting userEmailSetting, String username, boolean broadcast) {
    userEmailSetting.setEmailPassword(encodePassword(userEmailSetting.getEmailPassword()));
    UserEmailSettingEntity userEmailSettingEntity = new UserEmailSettingEntity(userEmailSetting.getEmailConnectorId(),
                                                                               userEmailSetting.getEmailAddress(),
                                                                               userEmailSetting.getEmailPassword(),
                                                                               userEmailSetting.getEmailBoxUserSyncPeriod(),
                                                                               userEmailSetting.getEmailSyncStatus(),
                                                                               userEmailSetting.getEmailSyncFailedAttemps(),
                                                                               userEmailSetting.getLastEmailSyncStartDate());
    userEmailSettingEntity.setNotifyAllCategories(userEmailSetting.getNotifyAllCategories());
    userEmailSettingEntity.setNotifyCategories(userEmailSetting.getNotifyCategories());
    userEmailSettingEntity.setDefaultCategoryView(userEmailSetting.getDefaultCategoryView());
    // The address-book binding travels with the mailbox one because it IS the same
    // account on every provider that serves both. Its password is encoded by the
    // same codec as the mail password, and for the same reason: it must never sit
    // in the settings store in the clear.
    userEmailSettingEntity.setCarddavEnabled(userEmailSetting.getCarddavEnabled());
    userEmailSettingEntity.setCarddavUsername(StringUtils.trimToNull(userEmailSetting.getCarddavUsername()));
    userEmailSettingEntity.setCarddavPassword(encodePassword(userEmailSetting.getCarddavPassword()));
    settingService.set(Context.USER.id(username),
                       EMAIL_CONNECTOR_SCOPE,
                       USER_EMAIL_SETTING_KEY,
                       SettingValue.create(JsonUtils.toJsonString(userEmailSettingEntity)));
    if (broadcast) {
      eventPublisher.publishEvent(new EmailBoxCleanupEvent(username));
    }
  }

  /**
   * Update only the new-mail notification / default-view preferences of a user's email setting,
   * without reconnecting the mailbox. A no-op when the user has no connected mailbox.
   *
   * @param username the user whose preferences are updated
   * @param notifyAllCategories notify for every new email (null/true) or only for the selected
   *          categories (false)
   * @param notifyCategories the Inbox category ids to notify about when notifyAllCategories is false
   * @param defaultCategoryView the Inbox category the mailbox drawer opens positioned to — in practice the
   *          Important category's id when the user's "open on Important" toggle is on, null when it is off
   */
  public void updateEmailPreferences(String username,
                                     Boolean notifyAllCategories,
                                     List<Long> notifyCategories,
                                     Long defaultCategoryView) {
    UserEmailSetting userEmailSetting = getUserEmailSetting(username);
    if (StringUtils.isBlank(userEmailSetting.getEmailConnectorId())) {
      return;
    }
    userEmailSetting.setNotifyAllCategories(notifyAllCategories);
    userEmailSetting.setNotifyCategories(notifyCategories);
    userEmailSetting.setDefaultCategoryView(defaultCategoryView);
    setUserEmailSetting(userEmailSetting, username, false);
  }

  /**
   * Turns the address-book sync on or off for one user, and stores the account it
   * should use.
   * <p>
   * A blank password means "leave the stored one alone", so the settings screen
   * never has to send a secret back to the server just to toggle a switch — and a
   * blank username means "the same account as the mailbox", which is the truth on
   * every provider that serves mail and contacts from one login.
   *
   * @param username the mailbox owner
   * @param enabled whether the address book should sync
   * @param carddavUsername the CardDAV account, or null/blank to reuse the mailbox's
   * @param carddavPassword the CardDAV password, or null/blank to leave it unchanged
   */
  public void updateAddressBookBinding(String username, Boolean enabled, String carddavUsername, String carddavPassword) {
    UserEmailSetting userEmailSetting = getUserEmailSetting(username);
    if (StringUtils.isBlank(userEmailSetting.getEmailConnectorId())) {
      return;
    }
    userEmailSetting.setCarddavEnabled(enabled);
    userEmailSetting.setCarddavUsername(StringUtils.trimToNull(carddavUsername));
    if (StringUtils.isNotBlank(carddavPassword)) {
      userEmailSetting.setCarddavPassword(carddavPassword);
    }
    setUserEmailSetting(userEmailSetting, username, false);
  }

  /**
   * Get user email setting by email connector id
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
      if (storedUserEmailSetting.getEmailConnectorId() != null) {
        userEmailSetting = storedUserEmailSetting;
        userEmailSetting.setEmailPassword(decodePassword(userEmailSetting.getEmailPassword()));
        userEmailSetting.setCarddavPassword(decodePassword(userEmailSetting.getCarddavPassword()));
        EmailConnector emailConnector =
                                      emailConnectorService.getEmailConnector(Long.parseLong(userEmailSetting.getEmailConnectorId()));
        if (emailConnector != null) {
          userEmailSetting.setCarddavAvailable(StringUtils.isNotBlank(emailConnector.getCarddavUrl()));
          userEmailSetting.setEmailConnectorImageUrl(emailConnector.getImageUrl());
          userEmailSetting.setEmailConnectorIcon(emailConnector.getIcon());
          userEmailSetting.setEmailConnectorName(emailConnector.getName());
          userEmailSetting.setEmailConnectorWebmailUrl(emailConnector.getWebmailUrl());
          userEmailSetting.setConnected(emailConnector.isActive());
        }
      }
    }
    return userEmailSetting;
  }

  /**
   * Delete user email setting.
   *
   * @param username user deleting user email setting
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void deleteUserEmailSetting(String username) {
    settingService.remove(Context.USER.id(username), EMAIL_CONNECTOR_SCOPE, USER_EMAIL_SETTING_KEY);
    eventPublisher.publishEvent(new EmailBoxCleanupEvent(username));
  }

  /**
   * Get users by email connector id.
   *
   * @param emailConnectorId email connector id
   * @return list of users with emailConnectorId configured in their user email
   *         setting
   */
  public List<String> getUserEmailSettingsByEmailConnectorId(long emailConnectorId) {
    List<Context> contexts =
                           settingService.getContextsByTypeAndScopeAndSettingName(Context.USER.getName(),
                                                                                  Scope.APPLICATION.getName(),
                                                                                  EmailConnectorService.EMAIL_CONNECTOR_SCOPE_ID,
                                                                                  EmailConnectorService.USER_EMAIL_SETTING_KEY,
                                                                                  0,
                                                                                  Integer.MAX_VALUE);
    List<String> users = contexts.stream().filter(context -> {
      UserEmailSetting userEmailSetting = getUserEmailSetting(context.getId());
      return userEmailSetting.getEmailConnectorId().equals(String.valueOf(emailConnectorId));
    }).map(Context::getId).toList();
    return users;
  }

  /**
   * Connect to user mail box.
   *
   * @param userEmailSetting userEmailSetting used to connect
   * @return store user box connected store
   */
  public Store connect(UserEmailSetting userEmailSetting) throws MessagingException {
    EmailConnector emailConnector =
                                  emailConnectorService.getEmailConnector(Long.parseLong(userEmailSetting.getEmailConnectorId()));
    return connect(userEmailSetting, emailConnector);
  }

  /**
   * Connect to the user's mail box against an already-resolved connector preset. Pure
   * IMAP — no database read happens here, which is what lets the sync's parallel
   * body-prefetch workers call it from bare threads (no request lifecycle, no
   * EntityManager) after the sync thread resolved the connector once for all of them.
   *
   * @param userEmailSetting userEmailSetting used to connect
   * @param emailConnector the connector preset holding the IMAP endpoint
   * @return store user box connected store
   */
  public Store connect(UserEmailSetting userEmailSetting, EmailConnector emailConnector) throws MessagingException {
    Properties props = new Properties();
    props.setProperty("mail.imaps.ssl.enable", "true");
    props.setProperty("mail.store.protocol", "imaps");
    props.setProperty("mail.imaps.port", emailConnector.getImapPort());
    // Timeouts on the imaps store so a slow or stuck fetch can never hang the sync
    // forever — a hung sync stays IN_PROGRESS and blocks every subsequent one. The
    // property prefix must match the protocol (imaps), not imap, or it is ignored.
    props.setProperty("mail.imaps.connectiontimeout", "15000");
    props.setProperty("mail.imaps.timeout", "30000");
    props.setProperty("mail.imaps.writetimeout", "30000");
    // Read a message body in one go. The default fetch size is 16KB, so every message is
    // pulled in 16KB slices, each its own round-trip to the server -- an HTML newsletter of a
    // few hundred KB costs a dozen of them, and the sync spends most of its time waiting on
    // the network rather than on the mailbox.
    props.setProperty("mail.imaps.fetchsize", "1048576");
    // getInstance (not getDefaultInstance) so these props actually apply rather than
    // silently reusing the first-ever session's properties.
    Session session = Session.getInstance(props);
    Store store = session.getStore();
    store.connect(emailConnector.getImapUrl(),
                  Integer.parseInt(emailConnector.getImapPort()),
                  userEmailSetting.getEmailAddress(),
                  userEmailSetting.getEmailPassword());
    return store;
  }

  /**
   * Get user email connectors.
   *
   * @param locale used language to retrieve email connectors names
   * @param username user getting email connectors
   * @return list of stored {@link EmailConnector} in datasource
   */
  public List<EmailConnector> getUserEmailConnectors(Locale locale, String username) {
    List<EmailConnector> activeEmailConnectors = emailConnectorService.getActiveEmailConnectors();
    activeEmailConnectors = activeEmailConnectors.stream().map(emailConnector -> {
      String translatedName =
                            translationService.getTranslationLabelOrDefault(EmailConnectorTranslationPlugin.EMAIL_CONNECTOR_OBJECT_TYPE,
                                                                            emailConnector.getId(),
                                                                            "name",
                                                                            locale);
      emailConnector.setName(translatedName);
      emailConnector.setUserConnected(isEmailConnectorUserConnected(emailConnector.getId(), getUserEmailSetting(username)));
      emailConnector.setCanConnect(canConnect(emailConnector.getId(), username));
      return emailConnector;
    }).toList();
    return activeEmailConnectors;
  }

  public boolean canConnect(long emailConnectorId, String username) {
    UserEmailSetting userEmailSetting = getUserEmailSetting(username);
    if (!featureService.isActiveFeature(EmailConnectorUtils.EMAIL_FEATURE)) {
      return false;
    }
    if (emailConnectorService.getEmailConnector(emailConnectorId) == null) {
      return false;
    }
    if (!emailConnectorService.getEmailConnector(emailConnectorId).isActive()) {
      return false;
    }
    if (userEmailSetting.getEmailConnectorId() != null
        && emailConnectorService.getEmailConnector(Long.parseLong(userEmailSetting.getEmailConnectorId())) != null
        && emailConnectorService.getEmailConnector(Long.parseLong(userEmailSetting.getEmailConnectorId())).isActive()
        && !isEmailConnectorUserConnected(emailConnectorId, userEmailSetting)) {
      return false;
    }
    return true;
  }

  private boolean isEmailConnectorUserConnected(long emailConnectorId, UserEmailSetting userEmailSetting) {
    return String.valueOf(emailConnectorId).equals(userEmailSetting.getEmailConnectorId());
  }

  /**
   * Where this user's address-book sync got to, or a blank state when it has never
   * run. Never null, so callers do not branch on absence.
   *
   * @param username the mailbox owner
   * @return the stored state, or a fresh empty one
   */
  public ContactSyncState getContactSyncState(String username) {
    SettingValue<?> value = settingService.get(Context.USER.id(username), EMAIL_CONNECTOR_SCOPE, CONTACT_SYNC_STATE_KEY);
    if (value == null || value.getValue() == null) {
      return new ContactSyncState();
    }
    try {
      return JsonUtils.fromJsonString(value.getValue().toString(), ContactSyncState.class);
    } catch (Exception e) {
      // Unreadable state is not worth failing over: the sync treats it as never
      // having run, which costs one full pass and fixes itself.
      LOG.warn("The stored contact sync state of user {} could not be read, starting from scratch", username, e);
      return new ContactSyncState();
    }
  }

  /**
   * Stores where the address-book sync got to.
   *
   * @param state the state to store
   * @param username the mailbox owner
   */
  public void setContactSyncState(ContactSyncState state, String username) {
    settingService.set(Context.USER.id(username),
                       EMAIL_CONNECTOR_SCOPE,
                       CONTACT_SYNC_STATE_KEY,
                       SettingValue.create(JsonUtils.toJsonString(state)));
  }

  /**
   * Forgets everything the address-book sync knew about this user, so the next run
   * discovers and reads the whole address book again. What "reset the address
   * book" means, and what disconnecting a mailbox implies.
   *
   * @param username the mailbox owner
   */
  public void clearContactSyncState(String username) {
    settingService.remove(Context.USER.id(username), EMAIL_CONNECTOR_SCOPE, CONTACT_SYNC_STATE_KEY);
  }

  private String decodePassword(String password) {
    // Nothing to decode is not an error. The mail password always exists, so this
    // never came up until a second, optional password arrived: every user who has
    // never bound an address book stores a null one, and handing that to the codec
    // failed the whole settings read.
    if (StringUtils.isBlank(password)) {
      return null;
    }
    try {
      return codecInitializer.getCodec().decode(password);
    } catch (TokenServiceInitializationException e) {
      LOG.warn("Error when decoding password", e);
      return null;
    }
  }

  private String encodePassword(String password) {
    if (StringUtils.isBlank(password)) {
      return null;
    }
    try {
      return codecInitializer.getCodec().encode(password);
    } catch (TokenServiceInitializationException e) {
      LOG.warn("Error when encoding password", e);
      return null;
    }
  }
}
