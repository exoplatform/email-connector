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
package org.exoplatform.emailConnector.rest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.exception.ServerRuleConflictException;
import org.exoplatform.emailConnector.exception.ServerRuleUnavailableException;
import org.exoplatform.emailConnector.exception.ServerRuleUnsupportedException;
import org.exoplatform.emailConnector.model.EmailFilter;
import org.exoplatform.emailConnector.model.EmailFilterMatch;
import org.exoplatform.emailConnector.model.FilterApplyReport;
import org.exoplatform.emailConnector.model.FilterPreview;
import org.exoplatform.emailConnector.model.ServerRule;
import org.exoplatform.emailConnector.model.ServerRuleCapabilities;
import org.exoplatform.emailConnector.model.ServerRulesSettings;
import org.exoplatform.emailConnector.service.EmailFilterService;
import org.exoplatform.emailConnector.service.EmailServerRuleService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The user's mail filters, both groups of the filters drawer under one resource.
 * <ul>
 * <li>The server group, under {@code /server}: the rules the mail server runs at
 * delivery, written on the caller's own mailbox through the connector's rules engine.
 * Every answer is a live read of the mail server; eXo keeps no copy of a server rule, so
 * they are addressed by the engine's reference, never by an eXo id.</li>
 * <li>The eXo group, at the root: the rules eXo runs itself after each sync, stored in
 * eXo and addressed by id -- their log, the preview, the one-off pass, and the mail's
 * Automations panel under {@code /mail} and {@code /matches}. A rule that also runs at
 * delivery writes its server half first; a refusal of the server stores nothing.</li>
 * </ul>
 * The eXo group's listing does not read the server: a server that cannot be reached
 * answers 502 on {@code /server} and never hides the rules eXo holds. Every verb acts on
 * the caller's own mailbox; one asked from a shared mailbox is refused (403).
 */
@RestController
@RequestMapping("/email-box/filters")
@Tag(name = "/email-connector/rest/email-box/filters", description = "Manages the user's mail filters")
public class EmailFilterRest {

  private static final String    DELEGATION_DESCRIPTION = "The share the request is made from; refused, rules are a setting of the "
      + "caller's own mailbox";

  private static final String    REPUBLISH_DESCRIPTION  = "Overwrite eXo's own script although it changed outside eXo";

  private static final String    CONFLICT_DESCRIPTION   = "{message, scriptName}: another script is active and the server cannot "
      + "include it (emailConnector.absence.serverConflict), another client's automatic reply would break eXo's "
      + "(.managedElsewhere), or eXo's script changed outside eXo (.modifiedOutside, re-send with republish=true)";

  private static final String    UNAVAILABLE_DESCRIPTION = "The mail server could not be used (emailConnector.absence.serverUnreachable, "
      + ".serverRefused, .tlsHostName, .authenticationFailed, .serverUnsupported, .serverNotConfigured)";

  @Autowired
  private EmailServerRuleService emailServerRuleService;

  @Autowired
  private EmailFilterService     emailFilterService;

  private static final String    FILTER_BAD_REQUEST      = "An invalid value (emailConnector.rules.name.invalid, .condition.invalid, "
      + ".action.invalid, emailConnector.filters.kind.invalid, .scope.invalid, .agent.invalid), a folder or category that is not "
      + "the caller's (emailConnector.folder.unknown, .notMirrored, emailConnector.filters.category.unknown), too many rules "
      + "(emailConnector.filters.tooMany), no consent yet for a rule with a server half (emailConnector.rules.consentRequired), "
      + "or a server half this server cannot run (emailConnector.rules.unsupported.*)";

  private static final String    FORBIDDEN_DESCRIPTION   = "Asked from someone else's mailbox (emailConnector.rules.ownMailboxOnly), "
      + "or the connector may not be used";

  /**
   * What the caller's mail server can do with filters, per form element.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param delegationId the share the request is made from; refused
   * @return the capabilities
   */
  @GetMapping("/capabilities")
  @Secured("users")
  @Operation(summary = "Reads what the caller's mail server can do with filters", method = "GET",
      description = "A live probe, as the caller, of the rules engine their connector is configured with "
          + "(email.connector.rulesEngine[.<connectorId>], default none): supported, reasonCode, readsForeignRules, "
          + "publishConflict (another client's script is active), vocabularySource, and per form element "
          + "{supported, reasonKey}. On Sieve the elements follow the SIEVE capability line the server re-issues after "
          + "STARTTLS. Own mailbox only: with delegationId the answer is 403.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "403", description = "Asked from someone else's mailbox (emailConnector.rules.ownMailboxOnly), or the connector may not be used"),
      @ApiResponse(responseCode = "404", description = "The feature is off, or no mailbox is connected"),
      @ApiResponse(responseCode = "502", description = UNAVAILABLE_DESCRIPTION) })
  public ServerRuleCapabilities getCapabilities(HttpServletRequest request,
                                                @Parameter(description = DELEGATION_DESCRIPTION)
                                                @RequestParam(name = "delegationId", required = false)
                                                Long delegationId) {
    try {
      return emailServerRuleService.getCapabilities(request.getRemoteUser(), delegationId);
    } catch (ObjectNotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
    } catch (ServerRuleUnavailableException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
    }
  }

  /**
   * The server group of the caller's filters.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param delegationId the share the request is made from; refused
   * @return the group
   */
  @GetMapping("/server")
  @Secured("users")
  @Operation(summary = "Reads the filters the caller's mail server runs at delivery", method = "GET",
      description = "A live read: the capabilities, the engine, the rules eXo manages on the server in the order it applies "
          + "them, their state -- OWN, INACTIVE (the server runs another client's script instead; foreignScriptName names it), "
          + "MODIFIED (eXo's script changed outside eXo), UNREADABLE (eXo's script cannot be read as eXo's: repaired or deleted in the mail client, never written over) or NONE -- the script of another client the server also runs, and "
          + "whether the caller already agreed that eXo manages rules on their server. On Sieve only eXo's own rules are "
          + "listed. Own mailbox only.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "403", description = "Asked from someone else's mailbox (emailConnector.rules.ownMailboxOnly), or the connector may not be used"),
      @ApiResponse(responseCode = "404", description = "The feature is off, or no mailbox is connected"),
      @ApiResponse(responseCode = "502", description = UNAVAILABLE_DESCRIPTION) })
  public ServerRulesSettings getServerRules(HttpServletRequest request,
                                            @Parameter(description = DELEGATION_DESCRIPTION)
                                            @RequestParam(name = "delegationId", required = false)
                                            Long delegationId) {
    try {
      return emailServerRuleService.getServerRules(request.getRemoteUser(), delegationId);
    } catch (ObjectNotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
    } catch (ServerRuleUnavailableException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
    }
  }

  /**
   * Creates a server rule.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param delegationId the share the request is made from; refused
   * @param republish true to overwrite eXo's own script although it changed outside eXo
   * @param consent true when the caller agreed, in this request, that eXo manages rules
   *          on their mail server
   * @param rule the rule
   * @return the group after the write, or the conflict with the script it is about
   */
  @PostMapping("/server")
  @Secured("users")
  @Operation(summary = "Creates a filter the caller's mail server runs at delivery", method = "POST",
      description = "Conditions FROM, TO, CC, ANY_RECIPIENT (CONTAINS, NOT_CONTAINS, EQUALS, STARTS_WITH, ENDS_WITH, "
          + "MATCHES_DOMAIN), SUBJECT and HEADER (the text operators), MESSAGE_SIZE (GT, LT, in kilobytes), IS_LIST and "
          + "IS_AUTOMATED (IS_TRUE, IS_FALSE); actions MOVE_TO_FOLDER (folderKey: one of the caller's mirrored CUSTOM:<id> "
          + "folders, or ARCHIVE), MARK_JUNK, DELETE (moves to Trash), MARK_READ, STAR; stop. Nothing else: no forward, "
          + "reply, discard or rejection. The first write needs consent=true, recorded once. Nothing another client wrote "
          + "is ever replaced: when it would be, the answer is 409. Own mailbox only.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Written; the group after the write, capabilities not re-read"),
      @ApiResponse(responseCode = "400", description = "An invalid value (emailConnector.rules.invalid, .name.invalid, .condition.invalid, .action.invalid), a folder that is not one of the caller's mirrored folders (emailConnector.folder.unknown, .notMirrored, emailConnector.rules.folder.unresolved), no consent yet (emailConnector.rules.consentRequired), or an element this server cannot run (emailConnector.rules.unsupported.*)"),
      @ApiResponse(responseCode = "403", description = "Asked from someone else's mailbox (emailConnector.rules.ownMailboxOnly), or the connector may not be used"),
      @ApiResponse(responseCode = "404", description = "The feature is off, or no mailbox is connected"),
      @ApiResponse(responseCode = "409", description = CONFLICT_DESCRIPTION),
      @ApiResponse(responseCode = "502", description = UNAVAILABLE_DESCRIPTION) })
  public ResponseEntity<Object> createServerRule(HttpServletRequest request,
                                                 @Parameter(description = DELEGATION_DESCRIPTION)
                                                 @RequestParam(name = "delegationId", required = false)
                                                 Long delegationId,
                                                 @Parameter(description = REPUBLISH_DESCRIPTION)
                                                 @RequestParam(name = "republish", required = false, defaultValue = "false")
                                                 boolean republish,
                                                 @Parameter(description = "The caller agrees that eXo manages rules on their mail server")
                                                 @RequestParam(name = "consent", required = false, defaultValue = "false")
                                                 boolean consent,
                                                 @RequestBody
                                                 ServerRule rule) {
    return write(() -> emailServerRuleService.saveRule(request.getRemoteUser(), delegationId, null, rule, republish, consent));
  }

  /**
   * Replaces a server rule.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param ref the rule's reference
   * @param delegationId the share the request is made from; refused
   * @param republish true to overwrite eXo's own script although it changed outside eXo
   * @param consent true when the caller agreed, in this request, that eXo manages rules
   *          on their mail server
   * @param rule the rule
   * @return the group after the write, or the conflict with the script it is about
   */
  @PutMapping("/server/{ref}")
  @Secured("users")
  @Operation(summary = "Replaces a filter the caller's mail server runs at delivery", method = "PUT",
      description = "The rule keeps its place in the order. The vocabulary, consent and conflicts are those of the creation. "
          + "An unknown reference is 404 (emailConnector.rules.notFound).")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Written; the group after the write, capabilities not re-read"),
      @ApiResponse(responseCode = "400", description = "An invalid value, folder or consent, as for the creation"),
      @ApiResponse(responseCode = "403", description = "Asked from someone else's mailbox (emailConnector.rules.ownMailboxOnly), or the connector may not be used"),
      @ApiResponse(responseCode = "404", description = "The feature is off, or no mailbox is connected"),
      @ApiResponse(responseCode = "409", description = CONFLICT_DESCRIPTION),
      @ApiResponse(responseCode = "502", description = UNAVAILABLE_DESCRIPTION) })
  public ResponseEntity<Object> updateServerRule(HttpServletRequest request,
                                                 @Parameter(description = "The rule's reference")
                                                 @PathVariable("ref")
                                                 String ref,
                                                 @Parameter(description = DELEGATION_DESCRIPTION)
                                                 @RequestParam(name = "delegationId", required = false)
                                                 Long delegationId,
                                                 @Parameter(description = REPUBLISH_DESCRIPTION)
                                                 @RequestParam(name = "republish", required = false, defaultValue = "false")
                                                 boolean republish,
                                                 @Parameter(description = "The caller agrees that eXo manages rules on their mail server")
                                                 @RequestParam(name = "consent", required = false, defaultValue = "false")
                                                 boolean consent,
                                                 @RequestBody
                                                 ServerRule rule) {
    return write(() -> emailServerRuleService.saveRule(request.getRemoteUser(), delegationId, ref, rule, republish, consent));
  }

  /**
   * Deletes a server rule.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param ref the rule's reference
   * @param delegationId the share the request is made from; refused
   * @param republish true to overwrite eXo's own script although it changed outside eXo
   * @return the group after the write, or the conflict with the script it is about
   */
  @DeleteMapping("/server/{ref}")
  @Secured("users")
  @Operation(summary = "Deletes a filter the caller's mail server runs at delivery", method = "DELETE",
      description = "Everything else eXo's script holds -- the other rules, the automatic reply -- is written back as it was. "
          + "A hop -- the server half of a filter eXo runs -- is not deleted here: deleting that filter removes it.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Deleted; the group after the write, capabilities not re-read"),
      @ApiResponse(responseCode = "400", description = "The reference names a hop (emailConnector.rules.action.invalid)"),
      @ApiResponse(responseCode = "403", description = "Asked from someone else's mailbox (emailConnector.rules.ownMailboxOnly), or the connector may not be used"),
      @ApiResponse(responseCode = "404", description = "The feature is off, no mailbox is connected, or no such rule (emailConnector.rules.notFound)"),
      @ApiResponse(responseCode = "409", description = CONFLICT_DESCRIPTION),
      @ApiResponse(responseCode = "502", description = UNAVAILABLE_DESCRIPTION) })
  public ResponseEntity<Object> deleteServerRule(HttpServletRequest request,
                                                 @Parameter(description = "The rule's reference")
                                                 @PathVariable("ref")
                                                 String ref,
                                                 @Parameter(description = DELEGATION_DESCRIPTION)
                                                 @RequestParam(name = "delegationId", required = false)
                                                 Long delegationId,
                                                 @Parameter(description = REPUBLISH_DESCRIPTION)
                                                 @RequestParam(name = "republish", required = false, defaultValue = "false")
                                                 boolean republish) {
    return write(() -> emailServerRuleService.deleteRule(request.getRemoteUser(), delegationId, ref, republish));
  }

  /**
   * Writes eXo's rules again and makes the server run them.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param delegationId the share the request is made from; refused
   * @param republish true to overwrite eXo's own script although it changed outside eXo
   * @return the group after the write, or the conflict with the script it is about
   */
  @PostMapping("/server/publish")
  @Secured("users")
  @Operation(summary = "Re-activates, or re-publishes, the filters eXo manages on the caller's mail server", method = "POST",
      description = "Re-activate: the server runs another client's script instead of eXo's, eXo makes it run eXo's again "
          + "through the one-active-script policy. Re-publish (republish=true): eXo's script was edited outside eXo, eXo "
          + "writes it back from its own header.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Written; the group after the write, capabilities not re-read"),
      @ApiResponse(responseCode = "403", description = "Asked from someone else's mailbox (emailConnector.rules.ownMailboxOnly), or the connector may not be used"),
      @ApiResponse(responseCode = "404", description = "The feature is off, or no mailbox is connected"),
      @ApiResponse(responseCode = "409", description = CONFLICT_DESCRIPTION),
      @ApiResponse(responseCode = "502", description = UNAVAILABLE_DESCRIPTION) })
  public ResponseEntity<Object> publishServerRules(HttpServletRequest request,
                                                   @Parameter(description = DELEGATION_DESCRIPTION)
                                                   @RequestParam(name = "delegationId", required = false)
                                                   Long delegationId,
                                                   @Parameter(description = REPUBLISH_DESCRIPTION)
                                                   @RequestParam(name = "republish", required = false, defaultValue = "false")
                                                   boolean republish) {
    return write(() -> emailServerRuleService.publishRules(request.getRemoteUser(), delegationId, republish));
  }

  /**
   * The eXo group of the caller's filters.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param delegationId the share the request is made from; refused
   * @return the rules, in the order they run
   */
  @GetMapping
  @Secured("users")
  @Operation(summary = "Reads the filters eXo runs after each sync of the caller's inbox", method = "GET",
      description = "The eXo group, in the order the rules run: kind EXO (conditions evaluated by eXo) or HOP (also runs at "
          + "delivery: the server sets tagKeyword on the mails it matches, under the server rule serverRuleRef = hop-<id>), "
          + "the counters, and lastError when the sync switched a rule off. Never reads the mail server: the server group "
          + "is GET /server. Own mailbox only.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "403", description = FORBIDDEN_DESCRIPTION),
      @ApiResponse(responseCode = "404", description = "The feature is off (emailConnector.filters.disabled), or no mailbox is connected") })
  public List<EmailFilter> getFilters(HttpServletRequest request,
                                      @Parameter(description = DELEGATION_DESCRIPTION)
                                      @RequestParam(name = "delegationId", required = false)
                                      Long delegationId) {
    return read(() -> emailFilterService.getFilters(request.getRemoteUser(), delegationId));
  }

  /**
   * Creates an eXo rule.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param delegationId the share the request is made from; refused
   * @param consent true when the caller agreed that eXo manages rules on their server
   * @param republish true to overwrite eXo's own script although it changed outside eXo
   * @param filter the rule
   * @return the rule as stored, or the conflict with the script it is about
   */
  @PostMapping
  @Secured("users")
  @Operation(summary = "Creates a filter eXo runs after each sync of the caller's inbox", method = "POST",
      description = "Conditions: the server's vocabulary (FROM, TO, CC, ANY_RECIPIENT, SUBJECT, HEADER, IS_LIST, IS_AUTOMATED) "
          + "plus, for kind EXO, BODY and SUBJECT_OR_BODY (text operators) and HAS_ATTACHMENT (IS_TRUE, IS_FALSE); MESSAGE_SIZE "
          + "for kind HOP only. Actions, each at most once, one filing at most: AGENT {agentNameId, instruction, outputs within "
          + "NOTE, CATEGORY, STAR, MARK_READ, DRAFT_REPLY, NOTIFY}, MOVE_TO_FOLDER {folderKey: a mirrored CUSTOM:<id> or "
          + "ARCHIVE}, ADD_CATEGORY {categoryId}, MARK_READ, STAR, MARK_JUNK, DELETE (to Trash), NOTIFY. With AGENT every other "
          + "action waits for the assistant. Kind HOP publishes its server half first -- a keyword-only server rule under "
          + "hop-<id> -- and stores nothing when the server refuses; the first one needs consent=true. Own mailbox only.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Created"),
      @ApiResponse(responseCode = "400", description = FILTER_BAD_REQUEST),
      @ApiResponse(responseCode = "403", description = FORBIDDEN_DESCRIPTION),
      @ApiResponse(responseCode = "404", description = "The feature is off, or no mailbox is connected"),
      @ApiResponse(responseCode = "409", description = CONFLICT_DESCRIPTION),
      @ApiResponse(responseCode = "502", description = UNAVAILABLE_DESCRIPTION) })
  public ResponseEntity<Object> createFilter(HttpServletRequest request,
                                             @Parameter(description = DELEGATION_DESCRIPTION)
                                             @RequestParam(name = "delegationId", required = false)
                                             Long delegationId,
                                             @Parameter(description = "The caller agrees that eXo manages rules on their mail server")
                                             @RequestParam(name = "consent", required = false, defaultValue = "false")
                                             boolean consent,
                                             @Parameter(description = REPUBLISH_DESCRIPTION)
                                             @RequestParam(name = "republish", required = false, defaultValue = "false")
                                             boolean republish,
                                             @RequestBody
                                             EmailFilter filter) {
    return write(() -> emailFilterService.createFilter(request.getRemoteUser(), delegationId, filter, consent, republish));
  }

  /**
   * Saves a filter of the drawer's one list, wherever it runs: eXo decides.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param delegationId the share the request is made from; refused
   * @param ref the server filter it replaces, by its reference; none otherwise
   * @param id the eXo filter it replaces, by its id; none otherwise
   * @param consent true when the caller agreed that eXo manages rules on their server
   * @param republish true to overwrite eXo's own script although it changed outside eXo
   * @param filter the filter; its kind is ignored
   * @return the filter as saved, or the conflict with the script it is about
   */
  @PostMapping("/routed")
  @Secured("users")
  @Operation(summary = "Saves a mail filter and decides where it runs", method = "POST",
      description = "The filter drawer's one entry point. Kind SERVER when the mail server can run every condition and every "
          + "action (the capabilities' answer): the server runs it at delivery and eXo keeps no copy. Kind HOP when the server can "
          + "test every condition and set eXo's keyword but not run every action (ADD_CATEGORY, NOTIFY, AGENT, or a flag it "
          + "cannot set): the server marks the mail at delivery, eXo applies every action after its sync. Kind EXO otherwise: a "
          + "condition only eXo reads (BODY, SUBJECT_OR_BODY, HAS_ATTACHMENT), or no rules on the server. With ref (a server "
          + "filter) or id (an eXo filter), the filter it was is replaced, moving between the server and eXo server first: the "
          + "server never holds both, and a refusal changes nothing. Answers the eXo filter as stored, or for a server filter its "
          + "kind and content, with its reference when it had one. Own mailbox only.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Saved"),
      @ApiResponse(responseCode = "400", description = FILTER_BAD_REQUEST),
      @ApiResponse(responseCode = "403", description = FORBIDDEN_DESCRIPTION),
      @ApiResponse(responseCode = "404", description = "A feature the filter needs is off, no mailbox is connected, or no such filter"),
      @ApiResponse(responseCode = "409", description = CONFLICT_DESCRIPTION),
      @ApiResponse(responseCode = "502", description = UNAVAILABLE_DESCRIPTION) })
  public ResponseEntity<Object> saveRoutedFilter(HttpServletRequest request,
                                                 @Parameter(description = DELEGATION_DESCRIPTION)
                                                 @RequestParam(name = "delegationId", required = false)
                                                 Long delegationId,
                                                 @Parameter(description = "The server filter it replaces")
                                                 @RequestParam(name = "ref", required = false)
                                                 String ref,
                                                 @Parameter(description = "The eXo filter it replaces")
                                                 @RequestParam(name = "id", required = false)
                                                 Long id,
                                                 @Parameter(description = "The caller agrees that eXo manages rules on their mail server")
                                                 @RequestParam(name = "consent", required = false, defaultValue = "false")
                                                 boolean consent,
                                                 @Parameter(description = REPUBLISH_DESCRIPTION)
                                                 @RequestParam(name = "republish", required = false, defaultValue = "false")
                                                 boolean republish,
                                                 @RequestBody
                                                 EmailFilter filter) {
    return write(() -> emailFilterService.saveRouted(request.getRemoteUser(), delegationId, filter, ref, id, consent, republish));
  }

  /**
   * Replaces an eXo rule.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param id the rule
   * @param delegationId the share the request is made from; refused
   * @param consent true when the caller agreed that eXo manages rules on their server
   * @param republish true to overwrite eXo's own script although it changed outside eXo
   * @param filter the rule
   * @return the rule as stored, or the conflict with the script it is about
   */
  @PutMapping("/{id:[0-9]+}")
  @Secured("users")
  @Operation(summary = "Replaces a filter eXo runs after each sync of the caller's inbox", method = "PUT",
      description = "The rule keeps its place. A rule that has or had a server half writes the server first -- publishing, "
          + "changing or removing the hop -- and stores nothing when the server refuses. Vocabulary as for the creation.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Replaced"),
      @ApiResponse(responseCode = "400", description = FILTER_BAD_REQUEST),
      @ApiResponse(responseCode = "403", description = FORBIDDEN_DESCRIPTION),
      @ApiResponse(responseCode = "404", description = "The feature is off, no mailbox is connected, or no such rule (emailConnector.filters.notFound)"),
      @ApiResponse(responseCode = "409", description = CONFLICT_DESCRIPTION),
      @ApiResponse(responseCode = "502", description = UNAVAILABLE_DESCRIPTION) })
  public ResponseEntity<Object> updateFilter(HttpServletRequest request,
                                             @Parameter(description = "The rule's id")
                                             @PathVariable("id")
                                             long id,
                                             @Parameter(description = DELEGATION_DESCRIPTION)
                                             @RequestParam(name = "delegationId", required = false)
                                             Long delegationId,
                                             @Parameter(description = "The caller agrees that eXo manages rules on their mail server")
                                             @RequestParam(name = "consent", required = false, defaultValue = "false")
                                             boolean consent,
                                             @Parameter(description = REPUBLISH_DESCRIPTION)
                                             @RequestParam(name = "republish", required = false, defaultValue = "false")
                                             boolean republish,
                                             @RequestBody
                                             EmailFilter filter) {
    return write(() -> emailFilterService.updateFilter(request.getRemoteUser(), delegationId, id, filter, consent, republish));
  }

  /**
   * Deletes an eXo rule.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param id the rule
   * @param delegationId the share the request is made from; refused
   * @param republish true to overwrite eXo's own script although it changed outside eXo
   * @return 204, or the conflict with the script it is about
   */
  @DeleteMapping("/{id:[0-9]+}")
  @Secured("users")
  @Operation(summary = "Deletes a filter eXo runs after each sync of the caller's inbox", method = "DELETE",
      description = "A rule with a server half removes it from the server first. Its log stays, as long as the retention.")
  @ApiResponses(value = { @ApiResponse(responseCode = "204", description = "Deleted"),
      @ApiResponse(responseCode = "403", description = FORBIDDEN_DESCRIPTION),
      @ApiResponse(responseCode = "404", description = "The feature is off, no mailbox is connected, or no such rule"),
      @ApiResponse(responseCode = "409", description = CONFLICT_DESCRIPTION),
      @ApiResponse(responseCode = "502", description = UNAVAILABLE_DESCRIPTION) })
  public ResponseEntity<Object> deleteFilter(HttpServletRequest request,
                                             @Parameter(description = "The rule's id")
                                             @PathVariable("id")
                                             long id,
                                             @Parameter(description = DELEGATION_DESCRIPTION)
                                             @RequestParam(name = "delegationId", required = false)
                                             Long delegationId,
                                             @Parameter(description = REPUBLISH_DESCRIPTION)
                                             @RequestParam(name = "republish", required = false, defaultValue = "false")
                                             boolean republish) {
    ResponseEntity<Object> answer = write(() -> {
      emailFilterService.deleteFilter(request.getRemoteUser(), delegationId, id, republish);
      return null;
    });
    return answer.getStatusCode().is2xxSuccessful() ? ResponseEntity.noContent().build() : answer;
  }

  /**
   * Orders the eXo rules.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param delegationId the share the request is made from; refused
   * @param orderedIds every rule's id, once, in the new order
   * @return the rules, in their new order
   */
  @PutMapping("/order")
  @Secured("users")
  @Operation(summary = "Orders the filters eXo runs after each sync of the caller's inbox", method = "PUT",
      description = "The body is every rule's id, once, in the new order; anything else is 400 "
          + "(emailConnector.filters.order.invalid). Server rules are ordered on the server.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Ordered"),
      @ApiResponse(responseCode = "400", description = "Not a permutation of the caller's rules"),
      @ApiResponse(responseCode = "403", description = FORBIDDEN_DESCRIPTION),
      @ApiResponse(responseCode = "404", description = "The feature is off, or no mailbox is connected") })
  public List<EmailFilter> reorderFilters(HttpServletRequest request,
                                          @Parameter(description = DELEGATION_DESCRIPTION)
                                          @RequestParam(name = "delegationId", required = false)
                                          Long delegationId,
                                          @RequestBody
                                          List<Long> orderedIds) {
    return read(() -> emailFilterService.reorder(request.getRemoteUser(), delegationId, orderedIds));
  }

  /**
   * Previews a rule over the mail eXo keeps of the caller's inbox.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param delegationId the share the request is made from; refused
   * @param draft the rule's kind, match mode and conditions
   * @return the count and a sample
   */
  @PostMapping("/preview")
  @Secured("users")
  @Operation(summary = "Previews a filter over the mail eXo keeps of the caller's inbox", method = "POST",
      description = "Evaluates the draft's conditions over the cached inbox -- the latest mails, not the whole mailbox -- and "
          + "answers {total, scanned, sample (the ten newest), notPreviewable (HEADER, MESSAGE_SIZE: not evaluated on a cached "
          + "mail), approximate (true for kind HOP or SERVER: the server compares, not eXo)}. Nothing is stored.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "An invalid condition or kind"),
      @ApiResponse(responseCode = "403", description = FORBIDDEN_DESCRIPTION),
      @ApiResponse(responseCode = "404", description = "The feature is off, or no mailbox is connected") })
  public FilterPreview previewFilter(HttpServletRequest request,
                                     @Parameter(description = DELEGATION_DESCRIPTION)
                                     @RequestParam(name = "delegationId", required = false)
                                     Long delegationId,
                                     @RequestBody
                                     EmailFilter draft) {
    return read(() -> emailFilterService.preview(request.getRemoteUser(), delegationId, draft));
  }

  /**
   * Runs an eXo rule once over the mail eXo keeps of the caller's inbox.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param id the rule
   * @param delegationId the share the request is made from; refused
   * @param withAgent true to queue the assistant on the newest matches too
   * @return what the pass did
   */
  @PostMapping("/{id:[0-9]+}/apply")
  @Secured("users")
  @Operation(summary = "Applies a filter to the mail already in the caller's inbox", method = "POST",
      description = "Once, as the caller, over the cached inbox: the rule's actions on every mail it matches and has not "
          + "handled yet, each logged and undoable from the mail. The assistant only with withAgent=true, on the newest "
          + "matches (exo.email.filters.agent.retroactiveMax, default 50) and within the caps. A header or size condition "
          + "cannot be decided on a cached mail: those mails are counted notApplicable.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "{scanned, matched, alreadyHandled, queued, notApplicable}"),
      @ApiResponse(responseCode = "400", description = "The rule is switched off (emailConnector.filters.notEnabled) or unreadable"),
      @ApiResponse(responseCode = "403", description = FORBIDDEN_DESCRIPTION),
      @ApiResponse(responseCode = "404", description = "The feature is off, no mailbox is connected, or no such rule") })
  public FilterApplyReport applyFilter(HttpServletRequest request,
                                       @Parameter(description = "The rule's id")
                                       @PathVariable("id")
                                       long id,
                                       @Parameter(description = DELEGATION_DESCRIPTION)
                                       @RequestParam(name = "delegationId", required = false)
                                       Long delegationId,
                                       @Parameter(description = "Queue the assistant on the newest matches too")
                                       @RequestParam(name = "withAgent", required = false, defaultValue = "false")
                                       boolean withAgent) {
    return read(() -> emailFilterService.applyOnce(request.getRemoteUser(), delegationId, id, withAgent));
  }

  /**
   * Publishes an eXo rule's server half again.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param id the rule
   * @param delegationId the share the request is made from; refused
   * @param consent true when the caller agreed that eXo manages rules on their server
   * @param republish true to overwrite eXo's own script although it changed outside eXo
   * @return the rule, or the conflict with the script it is about
   */
  @PostMapping("/{id:[0-9]+}/republish")
  @Secured("users")
  @Operation(summary = "Publishes the server half of a filter again", method = "POST",
      description = "For a rule of kind HOP whose server half was removed or changed on the server, which eXo never repairs "
          + "by itself: every hop of the caller's is written again, the rest of eXo's script kept.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Published"),
      @ApiResponse(responseCode = "400", description = "Not an enabled rule with a server half (emailConnector.filters.notHop), no consent, or unsupported"),
      @ApiResponse(responseCode = "403", description = FORBIDDEN_DESCRIPTION),
      @ApiResponse(responseCode = "404", description = "The feature is off, no mailbox is connected, or no such rule"),
      @ApiResponse(responseCode = "409", description = CONFLICT_DESCRIPTION),
      @ApiResponse(responseCode = "502", description = UNAVAILABLE_DESCRIPTION) })
  public ResponseEntity<Object> republishFilter(HttpServletRequest request,
                                                @Parameter(description = "The rule's id")
                                                @PathVariable("id")
                                                long id,
                                                @Parameter(description = DELEGATION_DESCRIPTION)
                                                @RequestParam(name = "delegationId", required = false)
                                                Long delegationId,
                                                @Parameter(description = "The caller agrees that eXo manages rules on their mail server")
                                                @RequestParam(name = "consent", required = false, defaultValue = "false")
                                                boolean consent,
                                                @Parameter(description = REPUBLISH_DESCRIPTION)
                                                @RequestParam(name = "republish", required = false, defaultValue = "false")
                                                boolean republish) {
    return write(() -> emailFilterService.republishHop(request.getRemoteUser(), delegationId, id, consent, republish));
  }

  /**
   * The newest matches of an eXo rule.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param id the rule
   * @param delegationId the share the request is made from; refused
   * @param limit how many, at most 100
   * @return the matches, newest first
   */
  @GetMapping("/{id:[0-9]+}/log")
  @Secured("users")
  @Operation(summary = "Reads what a filter did lately", method = "GET",
      description = "The newest matches of the rule, at most 100: the mail, what was done, the assistant's status.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "403", description = FORBIDDEN_DESCRIPTION),
      @ApiResponse(responseCode = "404", description = "The feature is off, no mailbox is connected, or no such rule") })
  public List<EmailFilterMatch> getFilterLog(HttpServletRequest request,
                                             @Parameter(description = "The rule's id")
                                             @PathVariable("id")
                                             long id,
                                             @Parameter(description = DELEGATION_DESCRIPTION)
                                             @RequestParam(name = "delegationId", required = false)
                                             Long delegationId,
                                             @Parameter(description = "How many, at most 100")
                                             @RequestParam(name = "limit", required = false, defaultValue = "100")
                                             int limit) {
    return read(() -> emailFilterService.getLog(request.getRemoteUser(), delegationId, id, limit));
  }

  /**
   * What the caller's rules did to one of their mails.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param emailId the cached mail's id
   * @param delegationId the share the request is made from; refused
   * @return the matches, newest first
   */
  @GetMapping("/mail/{emailId:[0-9]+}")
  @Secured("users")
  @Operation(summary = "Reads what the caller's filters did to one of their mails", method = "GET",
      description = "The mail's Automations panel: each eXo rule that matched it, what it did and whether it can be undone, "
          + "the assistant's status and answer. By the cached mail's id -- a Message-ID is not URL-safe -- and found again by "
          + "Message-ID, so a mail moved or re-cached keeps its history. What a server rule did at delivery is never here.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "403", description = FORBIDDEN_DESCRIPTION + ", or the mail is not the caller's"),
      @ApiResponse(responseCode = "404", description = "The feature is off, no mailbox is connected, or no such mail") })
  public List<EmailFilterMatch> getMailMatches(HttpServletRequest request,
                                               @Parameter(description = "The cached mail's id")
                                               @PathVariable("emailId")
                                               long emailId,
                                               @Parameter(description = DELEGATION_DESCRIPTION)
                                               @RequestParam(name = "delegationId", required = false)
                                               Long delegationId) {
    return read(() -> emailFilterService.getMatchesOfMail(request.getRemoteUser(), delegationId, emailId));
  }

  /**
   * Undoes what a match did.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param id the match
   * @param delegationId the share the request is made from; refused
   * @param action the action to undo, or none for every one
   * @return the match after the undo
   */
  @PostMapping("/matches/{id:[0-9]+}/undo")
  @Secured("users")
  @Operation(summary = "Undoes what a filter did to a mail", method = "POST",
      description = "One action (action=MOVE_TO_FOLDER, ADD_CATEGORY, MARK_READ, STAR, MARK_JUNK, DELETE) or, without it, every "
          + "one that can be undone: the mail moved back, the category taken off, the read mark or star withdrawn, the mail "
          + "brought back from Junk or Trash. Nothing is undone twice.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Undone"),
      @ApiResponse(responseCode = "400", description = "The mail is not yet where eXo can see it (emailConnector.filters.undo.notYet), the action cannot be undone (.undo.unsupported), or the mailbox's own code"),
      @ApiResponse(responseCode = "403", description = FORBIDDEN_DESCRIPTION),
      @ApiResponse(responseCode = "404", description = "The feature is off, no mailbox is connected, or no such match") })
  public EmailFilterMatch undoMatch(HttpServletRequest request,
                                    @Parameter(description = "The match's id")
                                    @PathVariable("id")
                                    long id,
                                    @Parameter(description = DELEGATION_DESCRIPTION)
                                    @RequestParam(name = "delegationId", required = false)
                                    Long delegationId,
                                    @Parameter(description = "The action to undo; every one when absent")
                                    @RequestParam(name = "action", required = false)
                                    String action) {
    return read(() -> emailFilterService.undo(request.getRemoteUser(), delegationId, id, action));
  }

  /**
   * Queues the assistant again on a match.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param id the match
   * @param delegationId the share the request is made from; refused
   * @return the match, queued
   */
  @PostMapping("/matches/{id:[0-9]+}/retry")
  @Secured("users")
  @Operation(summary = "Runs a filter's assistant again on a mail", method = "POST",
      description = "For a match whose assistant is done, failed or was skipped: queued again. Its other actions, when they "
          + "ran, do not run again.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Queued"),
      @ApiResponse(responseCode = "400", description = "No assistant on this match, or it is still due (emailConnector.filters.agent.invalid)"),
      @ApiResponse(responseCode = "403", description = FORBIDDEN_DESCRIPTION),
      @ApiResponse(responseCode = "404", description = "The feature is off, no mailbox is connected, or no such match") })
  public EmailFilterMatch retryMatch(HttpServletRequest request,
                                     @Parameter(description = "The match's id")
                                     @PathVariable("id")
                                     long id,
                                     @Parameter(description = DELEGATION_DESCRIPTION)
                                     @RequestParam(name = "delegationId", required = false)
                                     Long delegationId) {
    return read(() -> emailFilterService.retry(request.getRemoteUser(), delegationId, id));
  }

  /**
   * Runs a read, or a write that cannot meet a conflict, and maps its refusals to
   * statuses.
   *
   * @param <T> the answer's type
   * @param call the call
   * @return its answer
   */
  private static <T> T read(Read<T> call) {
    try {
      return call.run();
    } catch (ObjectNotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  /**
   * A read of the eXo group, with every refusal it may answer.
   *
   * @param <T> the answer's type
   */
  @FunctionalInterface
  private interface Read<T> {

    /**
     * Runs the read.
     *
     * @return its answer
     * @throws ObjectNotFoundException when the feature is off, no mailbox, or no such
     *           rule, match or mail
     * @throws IllegalAccessException when the request is not about the caller's mailbox
     */
    T run() throws ObjectNotFoundException, IllegalAccessException;
  }

  /**
   * Runs a write and maps its refusals to statuses.
   *
   * @param write the write
   * @return 200 with the group, or 409 with the conflict
   */
  private static ResponseEntity<Object> write(Write write) {
    try {
      return ResponseEntity.ok(write.run());
    } catch (ServerRuleConflictException e) {
      Map<String, String> body = new LinkedHashMap<>();
      body.put("message", e.getMessage());
      body.put("scriptName", e.getScriptName());
      return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    } catch (ObjectNotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
    } catch (IllegalArgumentException | ServerRuleUnsupportedException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (ServerRuleUnavailableException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
    }
  }

  /**
   * A write of either group, with every refusal it may answer.
   */
  @FunctionalInterface
  private interface Write {

    /**
     * Runs the write.
     *
     * @return the group, or the rule, after it
     * @throws ObjectNotFoundException when the feature is off, no mailbox, or no rule
     * @throws IllegalAccessException when the request is not about the caller's mailbox
     * @throws ServerRuleUnavailableException when the server cannot be used
     * @throws ServerRuleConflictException when eXo declined to write
     * @throws ServerRuleUnsupportedException when the connector cannot hold the rule
     */
    Object run() throws ObjectNotFoundException,
                              IllegalAccessException,
                              ServerRuleUnavailableException,
                              ServerRuleConflictException,
                              ServerRuleUnsupportedException;
  }
}
