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
package org.exoplatform.emailConnector.rest;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseEntity.BodyBuilder;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.EmailManagedMode;
import org.exoplatform.emailConnector.model.EmailSyncExecutorStatus;
import org.exoplatform.emailConnector.rest.model.EmailManagedModeRequest;
import org.exoplatform.emailConnector.service.EmailConnectorService;
import org.exoplatform.emailConnector.service.EmailManagedModeService;
import org.exoplatform.emailConnector.service.EmailSyncService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/connectors")
@Tag(name = "/email-connector/rest/connectors", description = "Manages Email Connector")
public class EmailConnectorRest {

  /** What a managed-mode save naming no connector is refused with. */
  private static final String CONNECTOR_REQUIRED = "emailConnector.managed.connectorRequired";

  @Autowired
  private EmailConnectorService emailConnectorService;

  @Autowired
  private EmailSyncService      emailSyncService;

  @Autowired
  private EmailManagedModeService emailManagedModeService;

  @PatchMapping("/feature/activation")
  @Secured("administrators")
  @Operation(summary = "Activate email feature", method = "PATCH", description = "This will activate email feature")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public ResponseEntity<String> activateEmailFeature(HttpServletRequest request,
                                                     @Parameter(description = "Is feature active")
                                                     @RequestParam("active")
                                                     boolean isFeatureActive) {
    try {
      emailConnectorService.activateEmailFeature(isFeatureActive, request.getRemoteUser());
      return ResponseEntity.ok("Email feature " + (isFeatureActive ? "activated" : "deactivated"));
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @GetMapping("/cache-size")
  @Secured("administrators")
  @Operation(summary = "Gets the mailbox cache size", method = "GET", description = "This will get the number of most recent emails kept per user")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "403", description = "Forbidden") })
  public int getEmailBoxCacheSize(HttpServletRequest request) {
    return emailConnectorService.getEmailBoxCacheSize();
  }

  @PutMapping("/cache-size")
  @Secured("administrators")
  @Operation(summary = "Updates the mailbox cache size", method = "PUT", description = "This will update the number of most recent emails kept per user")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation") })
  public void updateEmailBoxCacheSize(HttpServletRequest request,
                                      @Parameter(description = "Number of most recent emails kept per user", required = true)
                                      @RequestParam("size")
                                      int size) {
    try {
      emailConnectorService.saveEmailBoxCacheSize(size, request.getRemoteUser());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  /**
   * The administration-wide sync period of the active mailboxes.
   *
   * @param request the HTTP request
   * @return the period, in minutes
   */
  @GetMapping("/sync-period")
  @Secured("administrators")
  @Operation(summary = "Gets the administration-wide sync period of the active mailboxes", method = "GET", description = "This will get the number of minutes between two automatic synchronizations of a mailbox whose owner opened it within the activity threshold")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "403", description = "Forbidden") })
  public int getEmailBoxSyncPeriod(HttpServletRequest request) {
    return emailConnectorService.getEmailBoxSyncPeriod();
  }

  /**
   * Updates the administration-wide sync period of the active mailboxes; the
   * dispatcher reads it at its next tick, and the inactive period is raised to
   * match when this one overtakes it.
   *
   * @param request the HTTP request
   * @param minutes the period, in minutes
   */
  @PutMapping("/sync-period")
  @Secured("administrators")
  @Operation(summary = "Updates the administration-wide sync period of the active mailboxes", method = "PUT", description = "This will update the number of minutes between two automatic synchronizations of an active mailbox, applied at the next dispatch; the inactive period is raised to match when it would fall below")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation") })
  public void updateEmailBoxSyncPeriod(HttpServletRequest request,
                                       @Parameter(description = "The sync period, in minutes", required = true)
                                       @RequestParam("minutes")
                                       int minutes) {
    try {
      emailConnectorService.saveEmailBoxSyncPeriod(minutes, request.getRemoteUser());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  /**
   * The administration-wide sync period of the inactive mailboxes.
   *
   * @param request the HTTP request
   * @return the period, in minutes
   */
  @GetMapping("/inactive-sync-period")
  @Secured("administrators")
  @Operation(summary = "Gets the administration-wide sync period of the inactive mailboxes", method = "GET", description = "This will get the number of minutes between two automatic synchronizations of a mailbox nobody has opened for the activity threshold")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "403", description = "Forbidden") })
  public int getEmailBoxInactiveSyncPeriod(HttpServletRequest request) {
    return emailConnectorService.getEmailBoxInactiveSyncPeriod();
  }

  /**
   * Updates the administration-wide sync period of the inactive mailboxes; the
   * dispatcher reads it at its next tick.
   *
   * @param request the HTTP request
   * @param minutes the period, in minutes, at least the active period
   */
  @PutMapping("/inactive-sync-period")
  @Secured("administrators")
  @Operation(summary = "Updates the administration-wide sync period of the inactive mailboxes", method = "PUT", description = "This will update the number of minutes between two automatic synchronizations of an inactive mailbox, applied at the next dispatch; it cannot be shorter than the active period")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation") })
  public void updateEmailBoxInactiveSyncPeriod(HttpServletRequest request,
                                               @Parameter(description = "The sync period of the inactive mailboxes, in minutes", required = true)
                                               @RequestParam("minutes")
                                               int minutes) {
    try {
      emailConnectorService.saveEmailBoxInactiveSyncPeriod(minutes, request.getRemoteUser());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  /**
   * The administration-wide activity threshold.
   *
   * @param request the HTTP request
   * @return how many days without opening the mailbox make its owner inactive
   */
  @GetMapping("/activity-threshold")
  @Secured("administrators")
  @Operation(summary = "Gets the administration-wide mailbox activity threshold", method = "GET", description = "This will get the number of days without opening the mailbox after which its owner is inactive and it follows the inactive sync period")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "403", description = "Forbidden") })
  public int getEmailBoxActivityThresholdDays(HttpServletRequest request) {
    return emailConnectorService.getEmailBoxActivityThresholdDays();
  }

  /**
   * Updates the administration-wide activity threshold; the dispatcher reads it
   * at its next tick.
   *
   * @param request the HTTP request
   * @param days the threshold, in days
   */
  @PutMapping("/activity-threshold")
  @Secured("administrators")
  @Operation(summary = "Updates the administration-wide mailbox activity threshold", method = "PUT", description = "This will update the number of days without opening the mailbox after which its owner is inactive, applied at the next dispatch")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation") })
  public void updateEmailBoxActivityThresholdDays(HttpServletRequest request,
                                                  @Parameter(description = "The activity threshold, in days", required = true)
                                                  @RequestParam("days")
                                                  int days) {
    try {
      emailConnectorService.saveEmailBoxActivityThresholdDays(days, request.getRemoteUser());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  /**
   * The administration-wide size of the mailbox sync executor.
   *
   * @param request the HTTP request
   * @return how many mailboxes each node synchronizes at once
   */
  @GetMapping("/sync-threads")
  @Secured("administrators")
  @Operation(summary = "Gets the mailbox sync executor size", method = "GET", description = "This will get the number of mailboxes each server node synchronizes at once")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "403", description = "Forbidden") })
  public int getEmailSyncThreads(HttpServletRequest request) {
    return emailConnectorService.getEmailSyncThreads();
  }

  /**
   * Updates the administration-wide size of the mailbox sync executor; the
   * dispatcher resizes its pool at its next tick.
   *
   * @param request the HTTP request
   * @param threads the executor size, in threads
   */
  @PutMapping("/sync-threads")
  @Secured("administrators")
  @Operation(summary = "Updates the mailbox sync executor size", method = "PUT", description = "This will update the number of mailboxes each server node synchronizes at once, applied at the next dispatch")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation") })
  public void updateEmailSyncThreads(HttpServletRequest request,
                                     @Parameter(description = "The executor size, in threads", required = true)
                                     @RequestParam("threads")
                                     int threads) {
    try {
      emailConnectorService.saveEmailSyncThreads(threads, request.getRemoteUser());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  /**
   * A snapshot of the mailbox sync dispatcher, for the drawer's status line.
   *
   * @param request the HTTP request
   * @return what this node runs, what the cluster holds, and the backlog
   */
  @GetMapping("/sync-status")
  @Secured("administrators")
  @Operation(summary = "Gets the mailbox sync dispatcher status", method = "GET", description = "This will get how many mailboxes are being synchronized, waiting, and due")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "403", description = "Forbidden") })
  public EmailSyncExecutorStatus getEmailSyncStatus(HttpServletRequest request) {
    return emailSyncService.getStatus();
  }

  @GetMapping("/trash-sync")
  @Secured("administrators")
  @Operation(summary = "Gets whether the Trash folder is synchronized", method = "GET", description = "This will get the administration-wide Trash folder sync switch")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "403", description = "Forbidden") })
  public boolean isTrashSyncEnabled(HttpServletRequest request) {
    return emailConnectorService.isTrashSyncEnabled();
  }

  @PatchMapping("/trash-sync")
  @Secured("administrators")
  @Operation(summary = "Updates whether the Trash folder is synchronized", method = "PATCH", description = "This will update the administration-wide Trash folder sync switch")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation") })
  public void updateTrashSyncEnabled(HttpServletRequest request,
                                     @Parameter(description = "Whether the Trash folder should be cached", required = true)
                                     @RequestParam("enabled")
                                     boolean enabled) {
    try {
      emailConnectorService.saveTrashSyncEnabled(enabled, request.getRemoteUser());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
  }

  @GetMapping("/junk-sync")
  @Secured("administrators")
  @Operation(summary = "Gets whether the Junk folder is synchronized", method = "GET", description = "This will get the administration-wide Junk folder sync switch")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "403", description = "Forbidden") })
  public boolean isJunkSyncEnabled(HttpServletRequest request) {
    return emailConnectorService.isJunkSyncEnabled();
  }

  @PatchMapping("/junk-sync")
  @Secured("administrators")
  @Operation(summary = "Updates whether the Junk folder is synchronized", method = "PATCH", description = "This will update the administration-wide Junk folder sync switch")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation") })
  public void updateJunkSyncEnabled(HttpServletRequest request,
                                    @Parameter(description = "Whether the Junk folder should be cached", required = true)
                                    @RequestParam("enabled")
                                    boolean enabled) {
    try {
      emailConnectorService.saveJunkSyncEnabled(enabled, request.getRemoteUser());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
  }

  @GetMapping("/drafts-server")
  @Secured("administrators")
  @Operation(summary = "Gets whether drafts are uploaded to the mail server", method = "GET", description = "This will get the administration-wide server-side drafts switch")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "403", description = "Forbidden") })
  public boolean isServerDraftsEnabled(HttpServletRequest request) {
    return emailConnectorService.isServerDraftsEnabled();
  }

  @PatchMapping("/drafts-server")
  @Secured("administrators")
  @Operation(summary = "Updates whether drafts are uploaded to the mail server", method = "PATCH", description = "This will update the administration-wide server-side drafts switch")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation") })
  public void updateServerDraftsEnabled(HttpServletRequest request,
                                        @Parameter(description = "Whether drafts should be uploaded to the mail server", required = true)
                                        @RequestParam("enabled")
                                        boolean enabled) {
    try {
      emailConnectorService.saveServerDraftsEnabled(enabled, request.getRemoteUser());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
  }

  @GetMapping("/custom-folders-sync")
  @Secured("administrators")
  @Operation(summary = "Gets whether custom folders are switched on", method = "GET", description = "This will get the administration-wide custom-folders master switch")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "403", description = "Forbidden") })
  public boolean isCustomFoldersEnabled(HttpServletRequest request) {
    return emailConnectorService.isCustomFoldersEnabled();
  }

  @PatchMapping("/custom-folders-sync")
  @Secured("administrators")
  @Operation(summary = "Updates whether custom folders are switched on", method = "PATCH", description = "This will update the administration-wide custom-folders master switch")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation") })
  public void updateCustomFoldersEnabled(HttpServletRequest request,
                                         @Parameter(description = "Whether custom folders should be discovered, mirrored and offered", required = true)
                                         @RequestParam("enabled")
                                         boolean enabled) {
    try {
      emailConnectorService.saveCustomFoldersEnabled(enabled, request.getRemoteUser());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
  }

  /**
   * Whether a mail sent from a shared mailbox is also filed in its owner's Sent folder,
   * administration-wide (EXO-90551).
   *
   * @param request the caller's request
   * @return the switch
   */
  @GetMapping("/shared-mailbox-sent-copy")
  @Secured("administrators")
  @Operation(summary = "Gets whether a shared mailbox send is copied into its owner's Sent", method = "GET", description = "This will get the administration-wide switch of the copy into a shared mailbox owner's Sent folder")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "403", description = "Forbidden") })
  public boolean isSharedMailboxSentCopyEnabled(HttpServletRequest request) {
    return emailConnectorService.isSharedMailboxSentCopyEnabled();
  }

  /**
   * Switches the copy of a shared mailbox send into its owner's Sent folder on or off,
   * administration-wide (EXO-90551).
   *
   * @param request the caller's request
   * @param enabled whether the copy should be filed
   */
  @PatchMapping("/shared-mailbox-sent-copy")
  @Secured("administrators")
  @Operation(summary = "Updates whether a shared mailbox send is copied into its owner's Sent", method = "PATCH", description = "This will update the administration-wide switch of the copy into a shared mailbox owner's Sent folder")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation") })
  public void updateSharedMailboxSentCopyEnabled(HttpServletRequest request,
                                                 @Parameter(description = "Whether the copy into the owner's Sent should be filed", required = true)
                                                 @RequestParam("enabled")
                                                 boolean enabled) {
    try {
      emailConnectorService.saveSharedMailboxSentCopyEnabled(enabled, request.getRemoteUser());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
  }

  @PostMapping()
  @Secured("administrators")
  @Operation(summary = "Creates email connector", method = "POST", description = "This will create email connector")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public EmailConnector createEmailConnector(HttpServletRequest request, @RequestBody
  EmailConnector emailConnector) {
    try {
      return emailConnectorService.createEmailConnector(emailConnector, request.getRemoteUser());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @PutMapping()
  @Secured("administrators")
  @Operation(summary = "Updates email connector identified by its id", method = "PUT", description = "This will update an existing email connector identified by its id")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public void updateEmailConnector(HttpServletRequest request, @RequestBody
  EmailConnector emailConnector) {
    try {
      emailConnectorService.updateEmailConnector(emailConnector, request.getRemoteUser());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @PatchMapping(path = "/{emailConnectorId}")
  @Secured("administrators")
  @Operation(summary = "Activates email connector identified by its id", method = "PATCH", description = "This will activate or deactivate an existing email connector identified by its id")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public void activateEmailConnector(HttpServletRequest request,
                                     @Parameter(description = "Email connector technical id to activate", required = true)
                                     @PathVariable("emailConnectorId")
                                     Long emailConnectorId,
                                     @Parameter(description = "Is email connector active", required = true)
                                     @RequestParam("active")
                                     boolean isEmailConnectorActive) {
    try {
      emailConnectorService.activateEmailConnector(emailConnectorId, isEmailConnectorActive, request.getRemoteUser());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @GetMapping(path = "/{emailConnectorId}/provider-config")
  @Secured("administrators")
  @Operation(summary = "Retrieves the provider configuration of an email connector", method = "GET", description = "This will return the stored provider configuration of an email connector, without any secret value")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation") })
  public Map<String, String> getProviderConfig(HttpServletRequest request,
                                               @Parameter(description = "Email connector technical id", required = true)
                                               @PathVariable("emailConnectorId")
                                               Long emailConnectorId) {
    try {
      return emailConnectorService.getProviderConfig(emailConnectorId, request.getRemoteUser());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @DeleteMapping(path = "/{emailConnectorId}")
  @Secured("administrators")
  @Operation(summary = "Deletes an existing email connector identified by its id", method = "DELETE", description = "This will delete an existing email connector identified by its id")
  @ApiResponses(value = { @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
      @ApiResponse(responseCode = "500", description = "Internal server error") })
  public void deleteEmailConnector(HttpServletRequest request,
                                   @Parameter(description = "Email connector technical id to delete", required = true)
                                   @PathVariable("emailConnectorId")
                                   Long emailConnectorId) {
    try {
      emailConnectorService.deleteEmailConnector(emailConnectorId, request.getRemoteUser());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  /**
   * Whether this deployment chooses the mail connector on its users' behalf, which one,
   * and which groups it does not reach.
   * <p>
   * Administrators only: the exclusions and the connector's name are the administration
   * screen's facts. The per-viewer {@code managedForMe} is kept in the payload for the
   * login-time attachment (EXO-89653); no user page reads this today.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @return the mode as it stands for the caller
   */
  @GetMapping("/managed")
  @Secured("administrators")
  @Operation(summary = "Reads the mail managed mode", method = "GET",
      description = "Says which connector the instance attaches everybody to, which groups it excludes, and whether "
          + "the calling user is governed by that choice. Nothing is named when managed mode is off.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled") })
  public EmailManagedMode getManagedMode(HttpServletRequest request) {
    return emailManagedModeService.getManagedMode(request.getRemoteUser());
  }

  /**
   * Records the connector the instance attaches everybody to, and the groups
   * that choice does not reach.
   * <p>
   * The connector has to be one users could actually be attached to: an unknown
   * or deactivated connector, or one whose provider asks the user for something,
   * is refused with the message code the drawer renders.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param body the connector to manage with and the excluded groups
   * @return the mode now in force, as it stands for the calling administrator
   */
  @PutMapping("/managed")
  @Secured("administrators")
  @Operation(summary = "Records the mail managed mode", method = "PUT",
      description = "Points the whole instance at one connector, minus the excluded groups. The connector must exist, "
          + "be active and be configured with a provider that asks the user for nothing: managed mode attaches every "
          + "other user to it as they log in.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden") })
  public EmailManagedMode saveManagedMode(HttpServletRequest request,
                                          @RequestBody
                                          EmailManagedModeRequest body) {
    if (body == null || body.connectorId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, CONNECTOR_REQUIRED);
    }
    try {
      emailManagedModeService.saveManagedConnector(body.connectorId(),
                                                   body.excludedGroups() == null ? List.of() : body.excludedGroups(),
                                                   request.getRemoteUser());
      return emailManagedModeService.getManagedMode(request.getRemoteUser());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  /**
   * Switches managed mode off: users choose their own connector again.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @return the mode now in force, which names no connector
   */
  @DeleteMapping("/managed")
  @Secured("administrators")
  @Operation(summary = "Switches the mail managed mode off", method = "DELETE",
      description = "Gives every user back the choice of their own mail connector.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "403", description = "Forbidden") })
  public EmailManagedMode clearManagedMode(HttpServletRequest request) {
    try {
      emailManagedModeService.clearManagedConnector(request.getRemoteUser());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    return emailManagedModeService.getManagedMode(request.getRemoteUser());
  }

  /**
   * Whether each declared provider asks its user for anything, keyed by provider
   * name — what a browser needs to decide whether its connect button shows a form
   * or connects outright.
   * <p>
   * Open to every authenticated user, unlike the connector list above and unlike
   * the provider registry: this answers about the connectors offered to the caller,
   * not about how the instance is configured.
   *
   * @return one entry per declared provider name, true when the user must supply
   *         something
   */
  @GetMapping("/connection-requirements")
  @Secured("users")
  @Operation(summary = "Tells which declared providers ask the user for credentials", method = "GET",
      description = "One entry per provider name the declared connectors use. A provider answering false connects in one click.")
  @ApiResponses(@ApiResponse(responseCode = "200", description = "Request fulfilled"))
  public Map<String, Boolean> connectionRequirements() {
    return emailConnectorService.connectionRequirements();
  }

  @GetMapping()
  @Secured("administrators")
  @Operation(summary = "Gets email connectors", method = "GET", description = "This will get email connectors")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public List<EmailConnector> getEmailConnectors(HttpServletRequest request) {
    return emailConnectorService.getEmailConnectors(request.getLocale());
  }

  @GetMapping(path = "/{emailConnectorId}/illustration")
  @Secured("users")
  @Operation(summary = "Gets an email connector illustration by email connector id", method = "GET", description = "This will get an email connector illustration by email connector id")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "500", description = "Internal server error"),
      @ApiResponse(responseCode = "400", description = "Invalid query input"),
      @ApiResponse(responseCode = "404", description = "Resource not found") })
  public ResponseEntity<InputStreamResource> getEmailConnectorIllustration(HttpServletRequest request,
                                                                           @Parameter(description = "Email connector id", required = true)
                                                                           @PathVariable("emailConnectorId")
                                                                           long emailConnectorId) {
    EmailConnector emailConnector = emailConnectorService.getEmailConnector(emailConnectorId);
    if (emailConnector == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    try {
      InputStream stream = emailConnectorService.getEmailConnectorImageInputStream(emailConnectorId);
      if (stream == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      BodyBuilder builder = ResponseEntity.ok();
      return builder.contentType(MediaType.IMAGE_PNG).body(new InputStreamResource(stream));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  /**
   * Puts a refusal's message code in the body the browser reads.
   * <p>
   * The controller methods refuse with {@code ResponseStatusException(status,
   * code)}, and Spring Boot's default error body does not carry the reason on
   * this platform - the browser received {@code {"status":400,"error":"Bad
   * Request"}} and nothing else, so the drawers fell back to "could not be
   * saved" for every rule (EXO-89652). The same shape the CalDAV add-on's {@code CaldavShareRest}
   * answers its own failures with: the status, and the code under
   * {@code message}, which is what the JS services read.
   *
   * @param refusal the refusal a controller method threw
   * @return the same status, with the code in the body
   */
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, Object>> onRefusal(ResponseStatusException refusal) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", refusal.getStatusCode().value());
    if (refusal.getReason() != null) {
      body.put("message", refusal.getReason());
    }
    return ResponseEntity.status(refusal.getStatusCode()).body(body);
  }
}
