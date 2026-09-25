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
package org.exoplatform.emailConnector.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.exception.ServerRuleConflictException;
import org.exoplatform.emailConnector.exception.ServerRuleUnavailableException;
import org.exoplatform.emailConnector.exception.ServerRuleUnsupportedException;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.EmailFolder;
import org.exoplatform.emailConnector.model.HopRef;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.ReconcileReport;
import org.exoplatform.emailConnector.model.ServerRule;
import org.exoplatform.emailConnector.model.ServerRuleCapabilities;
import org.exoplatform.emailConnector.model.ServerRuleSet;
import org.exoplatform.emailConnector.model.ServerRulesSettings;
import org.exoplatform.emailConnector.model.ServerRulesState;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.service.acl.MailboxAclSession;
import org.exoplatform.emailConnector.service.rules.ServerRuleEngine;
import org.exoplatform.emailConnector.service.rules.ServerRuleEngineRegistry;
import org.exoplatform.emailConnector.service.rules.sieve.ExoSieveScript;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import io.meeds.social.util.JsonUtils;

/**
 * The server rules of the user's own mailbox: the filters the mail server runs at
 * delivery, authored in eXo and written through the connector's
 * {@link ServerRuleEngine}. eXo keeps no copy: every read asks the server.
 * <p>
 * Every verb acts on the caller's own mailbox only -- a request made from a shared
 * mailbox is refused, as no server path lets eXo write rules as that mailbox's owner --
 * and as the caller, through {@link EmailDelegationService#openOwnSession}. A rule's
 * folders are resolved here, from the caller's own mirrored folders, never taken as
 * text from the request, and only the actions a user may author are accepted: the
 * keyword eXo's own rules pick up at sync is written by reconciliation, never by a user.
 * <p>
 * The script the rules are written into is shared with the automatic reply: after a
 * write its hash is stored under the one {@link ExoSieveScript#HASH_SETTING_KEY} entry
 * both features compare with, so an edit made outside eXo reads "modified" in both.
 */
@Service
public class EmailServerRuleService {

  private static final Log         LOG                 = ExoLogger.getLogger(EmailServerRuleService.class);

  /** The deployment's switch for server rules, on by default. */
  public static final String       ENABLED_PROPERTY    = "email.connector.filters.server.enabled";

  /** The user setting holding the one-time consent that eXo writes rules on the server. */
  public static final String       CONSENT_SETTING_KEY = "emailServerRulesConsent";

  /** The feature is switched off. */
  public static final String       DISABLED            = "emailConnector.rules.disabled";

  /** Asked from a mailbox that is not the caller's own. */
  public static final String       OWN_MAILBOX_ONLY    = "emailConnector.rules.ownMailboxOnly";

  /** The caller has no connected mailbox. */
  public static final String       NOT_CONNECTED       = "emailConnector.rules.notConnected";

  /** The caller may not use their connector. */
  public static final String       NOT_ALLOWED         = "emailConnector.rules.notAllowed";

  /** The first write came without the consent the form asks for. */
  public static final String       CONSENT_REQUIRED    = "emailConnector.rules.consentRequired";

  /** A folder the sync has not resolved on the server yet. */
  public static final String       FOLDER_UNRESOLVED   = "emailConnector.rules.folder.unresolved";

  /** A folder the user does not mirror. */
  public static final String       FOLDER_NOT_MIRRORED = "emailConnector.folder.notMirrored";

  @Autowired
  private UserEmailSettingService  userEmailSettingService;

  @Autowired
  private EmailConnectorService    emailConnectorService;

  @Autowired
  private EmailDelegationService   emailDelegationService;

  @Autowired
  private EmailFolderService       emailFolderService;

  @Autowired
  private EmailBoxService          emailBoxService;

  @Autowired
  private ServerRuleEngineRegistry serverRuleEngineRegistry;

  @Autowired
  private SettingService           settingService;

  private Clock                    clock               = Clock.systemUTC();

  /**
   * Replaces the clock.
   *
   * @param newClock the clock
   */
  void setClock(Clock newClock) {
    this.clock = newClock;
  }

  /**
   * What the caller's mail server can do with rules, per form element, from a live probe.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @return the capabilities
   * @throws ObjectNotFoundException when the feature is off, or no mailbox is connected
   * @throws IllegalAccessException when the request comes from someone else's mailbox, or
   *           the caller may not use their connector
   * @throws ServerRuleUnavailableException when the server cannot be used
   */
  public ServerRuleCapabilities getCapabilities(String username,
                                                Long delegationId) throws ObjectNotFoundException,
                                                                   IllegalAccessException,
                                                                   ServerRuleUnavailableException {
    ServerRuleEngine engine = engineOf(username, delegationId);
    try (MailboxAclSession session = emailDelegationService.openOwnSession(username)) {
      return engine.probe(session);
    }
  }

  /**
   * The server group of the caller's filters, read live: what the engine can do, the
   * rules eXo manages there and where they stand.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @return the group; no rule and the engine's reason when it cannot hold rules
   * @throws ObjectNotFoundException when the feature is off, or no mailbox is connected
   * @throws IllegalAccessException when the request comes from someone else's mailbox, or
   *           the caller may not use their connector
   * @throws ServerRuleUnavailableException when the server cannot be used
   */
  public ServerRulesSettings getServerRules(String username,
                                            Long delegationId) throws ObjectNotFoundException,
                                                               IllegalAccessException,
                                                               ServerRuleUnavailableException {
    ServerRuleEngine engine = engineOf(username, delegationId);
    try (MailboxAclSession session = emailDelegationService.openOwnSession(username)) {
      ServerRuleCapabilities capabilities = engine.probe(session);
      if (!capabilities.supported()) {
        return settings(username, capabilities, engine, ServerRuleSet.none());
      }
      try {
        return settings(username, capabilities, engine, compared(engine.listRules(session), storedHash(username)));
      } catch (ServerRuleUnsupportedException e) {
        LOG.debug("Engine {} lists no server rules: {}", engine.getName(), e.getMessage());
        return settings(username, capabilities, engine, ServerRuleSet.none());
      }
    }
  }

  /**
   * Creates or replaces one of the caller's server rules, and publishes it. The first
   * write needs the caller's consent, recorded once.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @param ref the rule's reference to replace, or null to create one
   * @param rule the rule as the form sent it, folders as eXo keys
   * @param republish true to overwrite eXo's script although it changed outside eXo
   * @param consent true when the caller agreed, in this request, that eXo manages rules
   *          on their mail server
   * @return the group after the write, capabilities not re-read (null)
   * @throws ObjectNotFoundException when the feature is off, or no mailbox is connected
   * @throws IllegalAccessException when the request comes from someone else's mailbox
   * @throws IllegalArgumentException with a message code when a value is invalid, a
   *           folder is not one of the caller's mirrored folders, or consent is missing
   * @throws ServerRuleUnavailableException when the server cannot be used
   * @throws ServerRuleConflictException when another client's script is in the way, or
   *           eXo's script changed outside eXo; nothing was written
   * @throws ServerRuleUnsupportedException when this connector cannot hold the rule
   */
  public ServerRulesSettings saveRule(String username,
                                      Long delegationId,
                                      String ref,
                                      ServerRule rule,
                                      boolean republish,
                                      boolean consent) throws ObjectNotFoundException,
                                                       IllegalAccessException,
                                                       ServerRuleUnavailableException,
                                                       ServerRuleConflictException,
                                                       ServerRuleUnsupportedException {
    ServerRuleEngine engine = engineOf(username, delegationId);
    if (rule == null) {
      throw new IllegalArgumentException(ServerRule.INVALID);
    }
    if (ref != null && !ServerRule.isValidRef(ref)) {
      throw new IllegalArgumentException(ServerRule.INVALID);
    }
    ServerRule resolved = new ServerRule(ref,
                                         rule.name(),
                                         rule.enabled(),
                                         rule.matchAll(),
                                         rule.conditions(),
                                         resolveActions(username, rule.actions()),
                                         rule.stop()).validated();
    requireConsent(username, consent);
    try (MailboxAclSession session = emailDelegationService.openOwnSession(username)) {
      ServerRuleSet written = engine.saveRule(session, resolved, republish ? null : storedHash(username));
      recordWrite(username, written);
      LOG.info("Server rule {} by user {} on connector {}",
               ref == null ? "created" : "'" + ref + "' saved",
               username,
               session.connector().getId());
      return settings(username, null, engine, written);
    }
  }

  /**
   * Deletes one of the caller's server rules, and publishes.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @param ref the rule's reference
   * @param republish true to overwrite eXo's script although it changed outside eXo
   * @return the group after the write, capabilities not re-read (null)
   * @throws ObjectNotFoundException when the feature is off, no mailbox is connected, or
   *           no such rule is eXo's
   * @throws IllegalAccessException when the request comes from someone else's mailbox
   * @throws ServerRuleUnavailableException when the server cannot be used
   * @throws ServerRuleConflictException when eXo's script changed outside eXo, or another
   *           client's script is in the way; nothing was written
   * @throws ServerRuleUnsupportedException when this connector cannot hold rules
   */
  public ServerRulesSettings deleteRule(String username,
                                        Long delegationId,
                                        String ref,
                                        boolean republish) throws ObjectNotFoundException,
                                                           IllegalAccessException,
                                                           ServerRuleUnavailableException,
                                                           ServerRuleConflictException,
                                                           ServerRuleUnsupportedException {
    ServerRuleEngine engine = engineOf(username, delegationId);
    if (!ServerRule.isValidRef(ref)) {
      throw new ObjectNotFoundException(ServerRule.INVALID);
    }
    try (MailboxAclSession session = emailDelegationService.openOwnSession(username)) {
      ServerRuleSet written = engine.deleteRule(session, ref, republish ? null : storedHash(username));
      recordWrite(username, written);
      LOG.info("Server rule {} deleted by user {} on connector {}", ref, username, session.connector().getId());
      return settings(username, null, engine, written);
    }
  }

  /**
   * Writes eXo's rules again, as the server holds them, and makes the server run them:
   * "Re-activate" when another client activated its own script, "Re-publish" (with
   * {@code republish}) when eXo's script was edited outside eXo.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @param republish true to overwrite eXo's script although it changed outside eXo
   * @return the group after the write, capabilities not re-read (null)
   * @throws ObjectNotFoundException when the feature is off, or no mailbox is connected
   * @throws IllegalAccessException when the request comes from someone else's mailbox
   * @throws ServerRuleUnavailableException when the server cannot be used
   * @throws ServerRuleConflictException when another client's script is in the way, or
   *           eXo's script changed outside eXo; nothing was written
   * @throws ServerRuleUnsupportedException when this connector cannot hold rules
   */
  public ServerRulesSettings publishRules(String username,
                                          Long delegationId,
                                          boolean republish) throws ObjectNotFoundException,
                                                             IllegalAccessException,
                                                             ServerRuleUnavailableException,
                                                             ServerRuleConflictException,
                                                             ServerRuleUnsupportedException {
    ServerRuleEngine engine = engineOf(username, delegationId);
    try (MailboxAclSession session = emailDelegationService.openOwnSession(username)) {
      ServerRuleSet written = engine.publishRules(session, republish ? null : storedHash(username));
      recordWrite(username, written);
      LOG.info("Server rules re-published by user {} on connector {}", username, session.connector().getId());
      return settings(username, null, engine, written);
    }
  }

  /**
   * Makes the caller's mail server hold exactly the given hops -- the server halves of
   * the caller's eXo rules that also run at delivery -- and records the script's hash
   * like every other write. The hops are built by eXo from its own rules, never taken
   * from a request, so they are the one place a keyword is written.
   *
   * @param username the caller, from the request's session
   * @param hops every hop the caller's eXo rules need
   * @param republish true to overwrite eXo's script although it changed outside eXo
   * @param consent true when the caller agreed, in this request, that eXo manages rules
   *          on their mail server
   * @param publishing true when this write adds or changes a hop, which needs the
   *          consent; a write that only removes one does not
   * @return what was written, and the rules afterwards
   * @throws ObjectNotFoundException when the feature is off, or no mailbox is connected
   * @throws IllegalAccessException when the caller may not use their connector
   * @throws IllegalArgumentException {@value #CONSENT_REQUIRED} without the consent
   * @throws ServerRuleUnavailableException when the server cannot be used
   * @throws ServerRuleConflictException when another client's script is in the way, or
   *           eXo's script changed outside eXo; nothing was written
   * @throws ServerRuleUnsupportedException when this connector cannot hold a hop
   */
  public ReconcileReport reconcileHops(String username,
                                       List<HopRef> hops,
                                       boolean republish,
                                       boolean consent,
                                       boolean publishing) throws ObjectNotFoundException,
                                                           IllegalAccessException,
                                                           ServerRuleUnavailableException,
                                                           ServerRuleConflictException,
                                                           ServerRuleUnsupportedException {
    ServerRuleEngine engine = engineOf(username, null);
    if (publishing) {
      requireConsent(username, consent);
    }
    try (MailboxAclSession session = emailDelegationService.openOwnSession(username)) {
      ReconcileReport report = engine.reconcile(session, hops == null ? List.of() : hops, republish ? null : storedHash(username));
      recordWrite(username, report.rules());
      if (report.changed()) {
        LOG.info("Server hops of user {} reconciled on connector {}: published {}, removed {}",
                 username,
                 session.connector().getId(),
                 report.published(),
                 report.removed());
      }
      return report;
    }
  }

  /**
   * The actions of a rule the user authored, their folders resolved against the user's
   * own mirrored folders: a move to one of their custom folders or to Archive, a move to
   * their Junk or Trash. The keyword action is refused: only reconciliation writes it.
   *
   * @param username the caller
   * @param actions the actions as sent
   * @return the actions with the folder paths the engine writes
   * @throws IllegalArgumentException with a message code for an action the user may not
   *           author, or a folder that is not theirs, not mirrored or not resolved yet
   */
  List<ServerRule.Action> resolveActions(String username, List<ServerRule.Action> actions) {
    List<ServerRule.Action> resolved = new ArrayList<>();
    for (ServerRule.Action action : actions == null ? List.<ServerRule.Action>of() : actions) {
      String type = action == null || action.type() == null ? null : action.type().trim().toUpperCase(Locale.ROOT);
      if (type == null || ServerRule.TAG.equals(type)) {
        throw new IllegalArgumentException(ServerRule.INVALID_ACTION);
      }
      switch (type) {
      case ServerRule.MOVE_TO_FOLDER -> {
        String key = StringUtils.trimToNull(action.folderKey());
        resolved.add(new ServerRule.Action(type, key, folderPath(username, key), null));
      }
      case ServerRule.MARK_JUNK -> resolved.add(new ServerRule.Action(type, MailFolder.JUNK, builtIn(username, MailFolder.JUNK), null));
      case ServerRule.DELETE -> resolved.add(new ServerRule.Action(type, MailFolder.TRASH, builtIn(username, MailFolder.TRASH), null));
      default -> resolved.add(new ServerRule.Action(type, null, null, action.keyword()));
      }
    }
    return resolved;
  }

  /**
   * The server name of the folder a move targets: one of the user's own custom folders
   * they mirror, or Archive.
   *
   * @param username the caller
   * @param key the folder's eXo key
   * @return its full name on the server
   * @throws IllegalArgumentException {@code emailConnector.folder.unknown} for no such
   *           folder of the user's own, {@value #FOLDER_NOT_MIRRORED} for one they do not
   *           mirror, {@value #FOLDER_UNRESOLVED} for Archive before the sync found it
   */
  private String folderPath(String username, String key) {
    if (MailFolder.ARCHIVE.equals(key)) {
      return builtIn(username, key);
    }
    if (!MailFolder.isCustom(key)) {
      throw new IllegalArgumentException(EmailFolderService.UNKNOWN_FOLDER_MESSAGE);
    }
    EmailFolder folder = emailFolderService.getFolderByKey(username, key);
    if (folder.isMissing() || folder.getDelegationId() != null || StringUtils.isBlank(folder.getRemoteName())) {
      // A folder of a mailbox shared with the user is somebody else's: never a target.
      throw new IllegalArgumentException(EmailFolderService.UNKNOWN_FOLDER_MESSAGE);
    }
    if (!folder.isSyncEnabled()) {
      throw new IllegalArgumentException(FOLDER_NOT_MIRRORED);
    }
    return folder.getRemoteName();
  }

  /**
   * The server name of one of the user's built-in folders, as the sync resolved it.
   *
   * @param username the caller
   * @param key the folder's eXo key
   * @return its full name on the server
   * @throws IllegalArgumentException {@value #FOLDER_UNRESOLVED} when the sync has not
   *           resolved it
   */
  private String builtIn(String username, String key) {
    String name = emailBoxService.getRememberedFolderName(username, key);
    if (name == null) {
      throw new IllegalArgumentException(FOLDER_UNRESOLVED);
    }
    return name;
  }

  /**
   * Refuses a write the caller has not agreed to: the first one records the consent the
   * form asked for, every later one finds it recorded.
   *
   * @param username the caller
   * @param consent whether this request carries the caller's agreement
   * @throws IllegalArgumentException {@value #CONSENT_REQUIRED} without either
   */
  private void requireConsent(String username, boolean consent) {
    if (hasConsented(username)) {
      return;
    }
    if (!consent) {
      throw new IllegalArgumentException(CONSENT_REQUIRED);
    }
    settingService.set(Context.USER.id(username),
                       UserEmailSettingService.EMAIL_CONNECTOR_SCOPE,
                       CONSENT_SETTING_KEY,
                       SettingValue.create(String.valueOf(clock.millis())));
  }

  /**
   * Whether the caller already agreed that eXo manages rules on their mail server.
   *
   * @param username the caller
   * @return true once recorded
   */
  boolean hasConsented(String username) {
    SettingValue<?> value = settingService.get(Context.USER.id(username),
                                               UserEmailSettingService.EMAIL_CONNECTOR_SCOPE,
                                               CONSENT_SETTING_KEY);
    return value != null && value.getValue() != null;
  }

  /**
   * A rule set eXo can read as its own is {@link ServerRulesState#MODIFIED} when the
   * script the server holds is not the one eXo last wrote.
   *
   * @param read what the engine read
   * @param storedHash the hash eXo stored, possibly null
   * @return the read, its state corrected
   */
  static ServerRuleSet compared(ServerRuleSet read, String storedHash) {
    boolean eXosOwn = read.state() == ServerRulesState.OWN || read.state() == ServerRulesState.INACTIVE;
    if (eXosOwn && storedHash != null && read.scriptHash() != null && !storedHash.equals(read.scriptHash())) {
      return read.withState(ServerRulesState.MODIFIED);
    }
    return read;
  }

  /**
   * After the server accepted a write: the script's hash, in the entry the automatic
   * reply compares with too.
   *
   * @param username the caller
   * @param written what the server holds after the write
   */
  private void recordWrite(String username, ServerRuleSet written) {
    if (written.scriptHash() != null) {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("hash", written.scriptHash());
      value.put("lastWriteDate", clock.millis());
      settingService.set(Context.USER.id(username),
                         UserEmailSettingService.EMAIL_CONNECTOR_SCOPE,
                         ExoSieveScript.HASH_SETTING_KEY,
                         SettingValue.create(JsonUtils.toJsonString(value)));
    }
  }

  /**
   * The hash of eXo's script as eXo last wrote it, whichever feature wrote it.
   *
   * @param username the caller
   * @return the hash, or null when none or unreadable
   */
  @SuppressWarnings("unchecked")
  String storedHash(String username) {
    SettingValue<?> value = settingService.get(Context.USER.id(username),
                                               UserEmailSettingService.EMAIL_CONNECTOR_SCOPE,
                                               ExoSieveScript.HASH_SETTING_KEY);
    if (value == null || value.getValue() == null) {
      return null;
    }
    try {
      Map<String, Object> stored = JsonUtils.fromJsonString(value.getValue().toString(), Map.class);
      Object hash = stored == null ? null : stored.get("hash");
      return hash == null ? null : hash.toString();
    } catch (RuntimeException e) {
      LOG.debug("The stored Sieve script hash of user {} could not be read", username, e);
      return null;
    }
  }

  /**
   * The group's answer.
   *
   * @param username the caller
   * @param capabilities the probe's answer, or null when not re-read
   * @param engine the engine
   * @param rules what the server holds
   * @return the group
   */
  private ServerRulesSettings settings(String username,
                                       ServerRuleCapabilities capabilities,
                                       ServerRuleEngine engine,
                                       ServerRuleSet rules) {
    return new ServerRulesSettings(capabilities,
                                   engine.getName(),
                                   rules.rules(),
                                   rules.state(),
                                   rules.foreignScriptName(),
                                   hasConsented(username));
  }

  /**
   * The engine of the caller's own connector, after every check that comes first: the
   * feature is on, the request is about the caller's own mailbox, a mailbox is
   * connected, and the caller may still use its connector.
   *
   * @param username the caller
   * @param delegationId the share the request was made from; any value is refused
   * @return the engine
   * @throws ObjectNotFoundException when the feature is off, or no mailbox is connected
   * @throws IllegalAccessException when the request comes from someone else's mailbox, or
   *           the caller may not use the connector
   */
  private ServerRuleEngine engineOf(String username, Long delegationId) throws ObjectNotFoundException, IllegalAccessException {
    if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true").trim())) {
      throw new ObjectNotFoundException(DISABLED);
    }
    if (delegationId != null) {
      throw new IllegalAccessException(OWN_MAILBOX_ONLY);
    }
    UserEmailSetting setting = userEmailSettingService.getUserEmailSetting(username);
    if (setting == null || StringUtils.isBlank(setting.getEmailConnectorId()) || StringUtils.isBlank(setting.getEmailAddress())) {
      throw new ObjectNotFoundException(NOT_CONNECTED);
    }
    long connectorId = Long.parseLong(setting.getEmailConnectorId());
    EmailConnector connector = emailConnectorService.getEmailConnector(connectorId);
    if (connector == null) {
      throw new ObjectNotFoundException(NOT_CONNECTED);
    }
    if (!userEmailSettingService.canConnect(connectorId, username)) {
      throw new IllegalAccessException(NOT_ALLOWED);
    }
    return serverRuleEngineRegistry.engineFor(connector);
  }
}
