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
import org.exoplatform.emailConnector.model.ServerRule;
import org.exoplatform.emailConnector.model.ServerRuleCapabilities;
import org.exoplatform.emailConnector.model.ServerRulesSettings;
import org.exoplatform.emailConnector.service.EmailServerRuleService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The user's mail filters. This resource holds the server group: the rules the mail
 * server runs at delivery, written on the caller's own mailbox through the connector's
 * rules engine, and what that engine can do. Every answer is a live read of the mail
 * server; eXo keeps no copy of a server rule.
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
   * A write of the server group, with every refusal it may answer.
   */
  @FunctionalInterface
  private interface Write {

    /**
     * Runs the write.
     *
     * @return the group after it
     * @throws ObjectNotFoundException when the feature is off, no mailbox, or no rule
     * @throws IllegalAccessException when the request is not about the caller's mailbox
     * @throws ServerRuleUnavailableException when the server cannot be used
     * @throws ServerRuleConflictException when eXo declined to write
     * @throws ServerRuleUnsupportedException when the connector cannot hold the rule
     */
    ServerRulesSettings run() throws ObjectNotFoundException,
                              IllegalAccessException,
                              ServerRuleUnavailableException,
                              ServerRuleConflictException,
                              ServerRuleUnsupportedException;
  }
}
