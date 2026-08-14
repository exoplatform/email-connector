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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.util.CollectionUtils;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailBox;
import org.exoplatform.emailConnector.model.EmailCategory;
import org.exoplatform.emailConnector.model.EmailOutgoingAttachment;
import org.exoplatform.emailConnector.model.EmailSearchResultPage;
import org.exoplatform.emailConnector.model.ForwardedAttachments;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.ThreadAiSummary;
import org.exoplatform.emailConnector.service.EmailBoxService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/email-box")
@Tag(name = "/email-connector/rest/email-box", description = "Manages Email Box")
public class EmailBoxRest {

  @Autowired
  private EmailBoxService emailBoxService;

  @GetMapping()
  @Secured("users")
  @Operation(summary = "Gets user emails", method = "GET", description = "Gets the user's emails for a folder (INBOX by default, or SENT / ARCHIVE for the in-app folder switch), optionally restricted to the starred ones")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public EmailBox getEmailBox(HttpServletRequest request,
                              @Parameter(description = "Folder to list: INBOX, SENT or ARCHIVE")
                              @RequestParam(value = "folder", required = false, defaultValue = "INBOX")
                              String folder,
                              @Parameter(description = "When true, only the starred emails (IMAP \\Flagged) are returned")
                              @RequestParam(value = "starred", required = false, defaultValue = "false")
                              boolean starred) {
    try {
      return emailBoxService.getEmailBox(request.getRemoteUser(), folder, starred);
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @PostMapping("/synchronization")
  @Secured("users")
  @Operation(summary = "Synchronizes email box", method = "POST", description = "This will synchronize email box")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public ResponseEntity<String> synchronizeUserEmails(HttpServletRequest request) {
    try {
      emailBoxService.synchronize(request.getRemoteUser());
      return ResponseEntity.ok().build();
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  @PostMapping("/reset")
  @Secured("users")
  @Operation(summary = "Resets and re-synchronizes the email box", method = "POST",
             description = "Clears the locally-cached emails and runs a full re-synchronization from the server")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "409", description = "A synchronization is already in progress"), })
  public ResponseEntity<String> resetUserEmailBox(HttpServletRequest request) {
    try {
      emailBoxService.resetAndResynchronize(request.getRemoteUser());
      return ResponseEntity.ok().build();
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    }
  }

  @GetMapping("/favorites/{emailId}")
  @Secured("users")
  @Operation(summary = "Gets a favorited email by its technical id", method = "GET",
             description = "Resolves one entry of the global Favorites drawer. Favorites are stored against the email's technical id, unlike the rest of this API which addresses messages by their IMAP UID, so this is the one read that takes that id. Answers 404 for an email that is not the caller's, so a favorite id never reveals whether it exists.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
      @ApiResponse(responseCode = "404", description = "Not found"), })
  public ResponseEntity<Email> getFavoriteEmailById(HttpServletRequest request,
                                                    @Parameter(description = "Technical id of the favorited email", required = true)
                                                    @PathVariable("emailId")
                                                    long emailId) {
    try {
      Email email = emailBoxService.getOwnedEmailById(emailId, request.getRemoteUser());
      if (email == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      return ResponseEntity.ok().cacheControl(CacheControl.noCache().cachePrivate()).body(email);
    } catch (IllegalAccessException e) {
      // Somebody else's mail is reported as missing rather than forbidden: the
      // drawer drops an entry it cannot resolve, and "forbidden" would confirm
      // that the id exists.
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  @GetMapping("/search/cached")
  @Secured("users")
  @Operation(summary = "Searches the locally cached mail", method = "GET",
             description = "Filters the messages this add-on already holds locally, over their subject, sender and body. Answers immediately, without touching the mail server, which is what the platform's unified search needs: it queries every connector at once and shows the page when the slowest answers. Use /search to reach the whole mailbox.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request: no search text"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"), })
  public EmailSearchResultPage searchCachedEmails(HttpServletRequest request,
                                                  @Parameter(description = "Text searched over subject, sender and body", required = true)
                                                  @RequestParam("q")
                                                  String query,
                                                  @Parameter(description = "When true, only the messages the user favorited are returned. The unified search's Favorites filter sends it.")
                                                  @RequestParam(value = "favorites", required = false, defaultValue = "false")
                                                  boolean favorites,
                                                  @Parameter(description = "How many hits to return, newest first")
                                                  @RequestParam(value = "limit", required = false, defaultValue = "5")
                                                  int limit) {
    try {
      return emailBoxService.searchCachedEmails(request.getRemoteUser(), query, favorites, limit);
    } catch (IllegalAccessException e) {
      // No mailbox connected is the normal state for most users: the unified search
      // asks every connector, so this is not an error, it is an empty section.
      return new EmailSearchResultPage(List.of(), 0);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @GetMapping("/search")
  @Secured("users")
  @Operation(summary = "Searches the mailbox on the server", method = "GET",
             description = "Runs an IMAP SEARCH over the remote folder (INBOX by default), so it finds mail anywhere in the mailbox, not just the locally-cached window. Returns the newest hits (uid, folder, subject, sender, date, read flag, cached flag) plus the total match count. At least one criterion (query, from, to, unread or sinceDays) is required.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request: unknown folder or no search criterion"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
      @ApiResponse(responseCode = "500", description = "The mailbox could not be reached or searched"), })
  public EmailSearchResultPage searchEmails(HttpServletRequest request,
                                            @Parameter(description = "Free text matched against subject or sender")
                                            @RequestParam(value = "query", required = false)
                                            String query,
                                            @Parameter(description = "Text matched against the sender only")
                                            @RequestParam(value = "from", required = false)
                                            String from,
                                            @Parameter(description = "Text matched against the To or Cc recipients only — how a person is pinned in the SENT folder, where the sender is always the user")
                                            @RequestParam(value = "to", required = false)
                                            String to,
                                            @Parameter(description = "Restrict to unread messages")
                                            @RequestParam(value = "unread", required = false, defaultValue = "false")
                                            boolean unread,
                                            @Parameter(description = "When true, only the messages carrying the IMAP \\Flagged flag match")
                                            @RequestParam(value = "favorites", required = false, defaultValue = "false")
                                            boolean favorites,
                                            @Parameter(description = "Restrict to messages received in the last N days")
                                            @RequestParam(value = "sinceDays", required = false)
                                            Integer sinceDays,
                                            @Parameter(description = "Folder to search: INBOX, SENT or ARCHIVE")
                                            @RequestParam(value = "folder", required = false, defaultValue = "INBOX")
                                            String folder,
                                            @Parameter(description = "Maximum number of hits to return (newest first)")
                                            @RequestParam(value = "limit", required = false, defaultValue = "20")
                                            int limit) {
    try {
      return emailBoxService.searchEmails(request.getRemoteUser(), query, from, to, unread, favorites, sinceDays, folder, limit);
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  @PostMapping("/search/{mailRemoteId}")
  @Secured("users")
  @Operation(summary = "Fetches a searched message into the local cache", method = "POST",
             description = "Opens a search hit that lives outside the locally-cached window: fetches that one message from the server on demand, caches it through the regular pipeline (threading and categories work unchanged) and returns it in full. Already-cached messages are returned without touching the server. Returns 409 while a synchronization is running; retry in a few seconds.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request: unknown folder"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
      @ApiResponse(responseCode = "404", description = "The message no longer exists on the server"),
      @ApiResponse(responseCode = "409", description = "A synchronization is running; retry shortly"),
      @ApiResponse(responseCode = "500", description = "The mailbox could not be reached"), })
  public ResponseEntity<Email> fetchSearchedEmail(HttpServletRequest request,
                                                  @Parameter(description = "The message's IMAP UID in the folder", required = true)
                                                  @PathVariable("mailRemoteId")
                                                  long mailRemoteId,
                                                  @Parameter(description = "Folder the search hit came from: INBOX, SENT or ARCHIVE")
                                                  @RequestParam(value = "folder", required = false, defaultValue = "INBOX")
                                                  String folder) {
    try {
      Email email = emailBoxService.fetchSearchedEmail(mailRemoteId, folder, request.getRemoteUser());
      if (email == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      return ResponseEntity.ok(email);
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (IllegalStateException e) {
      // The sync-in-progress refusal is a retryable conflict, not a server error.
      if ("emailConnector.search.syncInProgress".equals(e.getMessage())) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
      }
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  @GetMapping("/{mailRemoteId}")
  @Secured("users")
  @Operation(summary = "Gets remote email by id", method = "GET", description = "This will get remote email by id")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public ResponseEntity<Email> getRemoteEmailById(HttpServletRequest request,
                                                  @Parameter(description = "Email id", required = true)
                                                  @PathVariable("mailRemoteId")
                                                  long mailRemoteId,
                                                  @Parameter(description = "Folder the message is in: INBOX, SENT or ARCHIVE")
                                                  @RequestParam(value = "folder", required = false, defaultValue = "INBOX")
                                                  String folder,
                                                  @RequestHeader(value = "If-None-Match", required = false)
                                                  String ifNoneMatch) {
    try {
      // UIDs are per-folder, so the folder is part of the message identity / eTag.
      String eTag = "\"" + Objects.hash(mailRemoteId, folder, request.getRemoteUser()) + "\"";
      if (ifNoneMatch != null && ifNoneMatch.replace("W/", "").equals(eTag)) {
        emailBoxService.broadcastOpenEmail(request.getRemoteUser());
        return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(eTag).build();
      }
      Email email = emailBoxService.getEmailByMailRemoteIdAndUserId(mailRemoteId,
                                                                    request.getRemoteUser(),
                                                                    folder,
                                                                    true,
                                                                    true,
                                                                    true,
                                                                    true);
      if (email == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      return ResponseEntity.ok().eTag(eTag).cacheControl(CacheControl.noCache().cachePrivate()).body(email);
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  @GetMapping("/thread/{threadId}")
  @Secured("users")
  @Operation(summary = "Gets a conversation across folders", method = "GET", description = "This will get all cached messages of a conversation (INBOX, SENT, ARCHIVE) by thread id")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation") })
  public List<Email> getThread(HttpServletRequest request,
                               @Parameter(description = "Conversation thread id", required = true)
                               @PathVariable("threadId")
                               String threadId) {
    try {
      return emailBoxService.getThread(threadId, request.getRemoteUser());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
  }

  @GetMapping("/thread/{threadId}/complete")
  @Secured("users")
  @Operation(summary = "Completes a conversation from the archive", method = "GET", description = "Fetches a conversation's archived messages (Gmail All Mail) on demand and returns the whole thread. Slower than /thread/{threadId} as it may hit IMAP; call it in the background after rendering the cached thread.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation") })
  public List<Email> completeThread(HttpServletRequest request,
                                    @Parameter(description = "Conversation thread id", required = true)
                                    @PathVariable("threadId")
                                    String threadId) {
    try {
      return emailBoxService.completeThread(threadId, request.getRemoteUser());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
  }

  @GetMapping("/thread/{threadId}/ai-summary")
  @Secured("users")
  @Operation(summary = "Gets a conversation's stored summary", method = "GET",
             description = "Returns the summary stored for a conversation, with a 'stale' flag saying whether the conversation has gained a message since it was written. 404 when no summary has been stored: this add-on does not produce them, so a deployment with no producer installed answers 404 for every conversation, which is the expected silence rather than an error.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
      @ApiResponse(responseCode = "404", description = "No summary stored for this conversation") })
  public ThreadAiSummary getThreadAiSummary(HttpServletRequest request,
                                            @Parameter(description = "Conversation thread id", required = true)
                                            @PathVariable("threadId")
                                            String threadId) {
    try {
      ThreadAiSummary summary = emailBoxService.getThreadAiSummary(threadId, request.getRemoteUser());
      if (summary == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      return summary;
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
  }

  @PostMapping("/thread/{threadId}/ai-summary/refresh")
  @Secured("users")
  @Operation(summary = "Asks for a conversation to be summarised", method = "POST",
             description = "Broadcasts a request to summarise the conversation and returns immediately. 202 and not 200 on purpose: the request has been accepted, and nothing here can promise a summary will be written — the producer lives elsewhere, and a deployment without one is supported. Poll the GET endpoint for the result.")
  @ApiResponses(value = { @ApiResponse(responseCode = "202", description = "Request accepted"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation") })
  public ResponseEntity<String> refreshThreadAiSummary(HttpServletRequest request,
                                                       @Parameter(description = "Conversation thread id", required = true)
                                                       @PathVariable("threadId")
                                                       String threadId) {
    try {
      emailBoxService.requestThreadAiSummary(threadId, request.getRemoteUser());
      return ResponseEntity.accepted().build();
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
  }

  @PostMapping("/webmail/broadcast")
  @Secured("users")
  @Operation(summary = "Broadcasts access webmail", method = "POST", description = "This will broadcast access webmail")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public void broadcastOpenWebmail(HttpServletRequest request) {
    try {
      emailBoxService.broadcastAccessWebmail(request.getRemoteUser());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  @PatchMapping()
  @Secured("users")
  @Operation(summary = "Updates emails read status", method = "PATCH", description = "This will update emails read status")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public void updateEmailReadStatus(HttpServletRequest request,
                                    @Parameter(description = "Email remote ids", required = true)
                                    @RequestBody
                                    List<Long> mailRemoteIds,
                                    @RequestParam("readStatus")
                                    boolean readStatus) {
    try {
      if (mailRemoteIds == null || mailRemoteIds.isEmpty()) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      emailBoxService.updateEmailReadStatus(mailRemoteIds, request.getRemoteUser(), readStatus, true);
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  @PatchMapping("/starred")
  @Secured("users")
  @Operation(summary = "Stars or unstars emails", method = "PATCH", description = "Sets or clears the IMAP \\Flagged flag ('star') of the given emails, locally and on the mail server, so the star shows in every mail client. Returns the number of emails whose remote update failed (their local change is reverted).")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public Map<String, Integer> updateEmailStarredStatus(HttpServletRequest request,
                                                       @Parameter(description = "Email remote ids", required = true)
                                                       @RequestBody
                                                       List<Long> mailRemoteIds,
                                                       @RequestParam("starred")
                                                       boolean starred) {
    try {
      if (mailRemoteIds == null || mailRemoteIds.isEmpty()) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      int failedUpdates = emailBoxService.updateEmailStarredStatus(mailRemoteIds, request.getRemoteUser(), starred, true);
      Map<String, Integer> response = new HashMap<>();
      response.put("failedUpdates", failedUpdates);
      return response;
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  @DeleteMapping()
  @Secured("users")
  @Operation(summary = "Deletes email", method = "DELETE", description = "This will delete email")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public Map<String, Integer> deleteEmail(HttpServletRequest request,
                                          @Parameter(description = "Email remote ids", required = true)
                                          @RequestBody
                                          List<Long> mailRemoteIds) {

    try {
      if (mailRemoteIds == null || mailRemoteIds.isEmpty()) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      int failedEmailDeletions = emailBoxService.deleteEmail(mailRemoteIds, request.getRemoteUser());
      Map<String, Integer> response = new HashMap<>();
      response.put("failedDeletions", failedEmailDeletions);
      return response;
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  @DeleteMapping("/archive")
  @Secured("users")
  @Operation(summary = "Archives email", method = "DELETE", description = "This will archive email")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public Map<String, Integer> archiveEmail(HttpServletRequest request,
                                           @Parameter(description = "Email remote ids", required = true)
                                           @RequestBody
                                           List<Long> mailRemoteIds) {
    try {
      if (mailRemoteIds == null || mailRemoteIds.isEmpty()) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      int failedEmailArchives = emailBoxService.archiveEmail(mailRemoteIds, request.getRemoteUser());
      Map<String, Integer> response = new HashMap<>();
      response.put("failedArchives", failedEmailArchives);
      return response;
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  /**
   * Puts trashed messages back into the inbox.
   * <p>
   * Its own endpoint rather than a flag on the delete: restore and delete are opposite
   * operations on the same rows, and a boolean deciding which one runs is one typo away
   * from destroying what the user asked to keep. The verb is POST because a restore is
   * a move, not a removal.
   *
   * @param request the caller's request, for the acting user
   * @param mailRemoteIds the IMAP UIDs, within the Trash folder, to put back
   * @return {@code failedRestores}: how many could not be restored
   */
  @PostMapping("/trash/restore")
  @Secured("users")
  @Operation(summary = "Restores trashed emails", method = "POST", description = "Moves the given messages out of the Trash folder and back into the inbox")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
      @ApiResponse(responseCode = "404", description = "Not found"), })
  public Map<String, Integer> restoreEmail(HttpServletRequest request,
                                           @Parameter(description = "Email remote ids", required = true)
                                           @RequestBody
                                           List<Long> mailRemoteIds) {
    try {
      if (mailRemoteIds == null || mailRemoteIds.isEmpty()) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      int failedRestores = emailBoxService.restoreEmail(mailRemoteIds, request.getRemoteUser());
      Map<String, Integer> response = new HashMap<>();
      response.put("failedRestores", failedRestores);
      return response;
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  /**
   * Removes trashed messages from the mail server for good.
   * <p>
   * Named messages only — the body is the list to destroy, and an empty one is a 404
   * rather than "everything". There is deliberately no endpoint that empties the Trash:
   * see {@code EmailBoxService#purgeEmail}.
   *
   * @param request the caller's request, for the acting user
   * @param mailRemoteIds the IMAP UIDs, within the Trash folder, to destroy
   * @return {@code failedPurges}: how many could not be removed
   */
  @DeleteMapping("/trash")
  @Secured("users")
  @Operation(summary = "Permanently deletes trashed emails", method = "DELETE", description = "Removes the given messages from the Trash folder on the mail server, with no copy kept anywhere")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
      @ApiResponse(responseCode = "404", description = "Not found"), })
  public Map<String, Integer> purgeEmail(HttpServletRequest request,
                                         @Parameter(description = "Email remote ids", required = true)
                                         @RequestBody
                                         List<Long> mailRemoteIds) {
    try {
      if (mailRemoteIds == null || mailRemoteIds.isEmpty()) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      int failedPurges = emailBoxService.purgeEmail(mailRemoteIds, request.getRemoteUser());
      Map<String, Integer> response = new HashMap<>();
      response.put("failedPurges", failedPurges);
      return response;
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  @GetMapping("/categories")
  @Secured("users")
  @Operation(summary = "Lists the categories used on the user's emails", method = "GET", description = "Returns the categories currently applied to the user's emails, resolved to their localized name")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation") })
  public List<EmailCategory> getEmailCategories(HttpServletRequest request) {
    try {
      return emailBoxService.getEmailCategories(request.getRemoteUser(), request.getLocale());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
  }

  @GetMapping("/categories/available")
  @Secured("users")
  @Operation(summary = "Lists the assignable email categories", method = "GET", description = "Returns the add-on's own email categories a user can assign (Important / Invitation / Notification / To review), whether or not already used")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled") })
  public List<EmailCategory> getAvailableEmailCategories(HttpServletRequest request) {
    return emailBoxService.getAvailableEmailCategories(request.getRemoteUser(), request.getLocale());
  }

  @PostMapping("/categories/{categoryId}")
  @Secured("users")
  @Operation(summary = "Tags emails with a category", method = "POST", description = "Links the given emails (by IMAP id) to an existing category; use it to categorize a whole conversation by passing its message ids")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Unknown category"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation") })
  public Map<String, Integer> linkEmailsToCategory(HttpServletRequest request,
                                                   @Parameter(description = "Category id", required = true)
                                                   @PathVariable("categoryId")
                                                   long categoryId,
                                                   @Parameter(description = "Email remote ids", required = true)
                                                   @RequestBody
                                                   List<Long> mailRemoteIds) {
    try {
      int linked = emailBoxService.linkEmailsToCategory(mailRemoteIds, categoryId, request.getRemoteUser());
      Map<String, Integer> response = new HashMap<>();
      response.put("linked", linked);
      return response;
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @DeleteMapping("/categories/{categoryId}")
  @Secured("users")
  @Operation(summary = "Removes a category from emails", method = "DELETE", description = "Unlinks the given emails (by IMAP id) from a category")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation") })
  public Map<String, Integer> unlinkEmailsFromCategory(HttpServletRequest request,
                                                       @Parameter(description = "Category id", required = true)
                                                       @PathVariable("categoryId")
                                                       long categoryId,
                                                       @Parameter(description = "Email remote ids", required = true)
                                                       @RequestBody
                                                       List<Long> mailRemoteIds) {
    try {
      int unlinked = emailBoxService.unlinkEmailsFromCategory(mailRemoteIds, categoryId, request.getRemoteUser());
      Map<String, Integer> response = new HashMap<>();
      response.put("unlinked", unlinked);
      return response;
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
  }

  @PostMapping("/send")
  @Secured("users")
  @Operation(summary = "Sends email", method = "POST", description = "This will send email")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public void sendEmail(HttpServletRequest request,
                        @Parameter(description = "Email to be sent", required = true)
                        @RequestBody
                        Email email) {
    try {
      if (email == null || email.getTo() == null || email.getTo().isEmpty()) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      emailBoxService.sendEmail(email, request.getRemoteUser());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  @PostMapping("/drafts")
  @Secured("users")
  @Operation(summary = "Saves a draft", method = "POST",
             description = "Saves the composed draft locally, and — when 'push' is set and the account has a Drafts folder — appends it to the mail server's Drafts folder as well. A blank draftLocalId starts a new draft; the id in the answer is the handle to keep saving, resuming and discarding it by. The answer also carries the draft's state, which tells the composer whether the words made it to the server or live only here.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
      @ApiResponse(responseCode = "404", description = "No draft under that local id (it has been sent or discarded)"), })
  public Email saveDraft(HttpServletRequest request,
                         @Parameter(description = "The composed draft", required = true)
                         @RequestBody
                         Email draft,
                         @Parameter(description = "When true, also upload the draft to the mail server's Drafts folder")
                         @RequestParam(value = "push", required = false, defaultValue = "false")
                         boolean push) {
    try {
      if (draft == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
      }
      Email saved = emailBoxService.saveDraft(draft, request.getRemoteUser(), push);
      if (saved == null) {
        // A save under a local id that no longer names anything: the draft was sent or
        // discarded while this request was in flight. Answering 404 rather than
        // re-creating it is what stops a draft of an already-sent mail coming back.
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      return saved;
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @PostMapping("/drafts/{draftLocalId}/send")
  @Secured("users")
  @Operation(summary = "Sends a draft", method = "POST",
             description = "Sends the draft, in this order: the text the composer is showing is written to the draft's row, the mail is transmitted, the copy on the mail server is removed, and the local row is removed. A refused send changes nothing — the draft is still there, in both places. A send that succeeded but whose cleanup did not still removes the local row, deliberately: a draft of an already-sent mail is a worse outcome than a stray copy in a Drafts folder.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "No local id, or a send of this draft is already in flight"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
      @ApiResponse(responseCode = "404", description = "No draft under that local id"),
      @ApiResponse(responseCode = "500", description = "The mail server refused the message"), })
  public void sendDraft(HttpServletRequest request,
                        @Parameter(description = "The draft's local id", required = true)
                        @PathVariable("draftLocalId")
                        String draftLocalId,
                        @Parameter(description = "The draft as the composer is showing it", required = true)
                        @RequestBody
                        Email draft) {
    try {
      if (draft == null || CollectionUtils.isEmpty(draft.getTo())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
      }
      // The path is what names the draft; a body claiming a different id would be two
      // answers to one question, and the addressable one wins.
      draft.setDraftLocalId(draftLocalId);
      emailBoxService.sendDraft(draft, request.getRemoteUser());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (ObjectNotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  @DeleteMapping("/drafts/{draftLocalId}")
  @Secured("users")
  @Operation(summary = "Discards a draft", method = "DELETE",
             description = "Removes the draft: the copy on the mail server first, then the local row. Answers 404 for an id the caller has no draft under, so a draft id never reveals whether it exists, and 500 when the server copy could not be removed — in which case the local row is deliberately kept, so the two never disagree about whether the draft still exists.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "The copy on the mail server could not be removed"), })
  public ResponseEntity<String> deleteDraft(HttpServletRequest request,
                                            @Parameter(description = "The draft's local id", required = true)
                                            @PathVariable("draftLocalId")
                                            String draftLocalId) {
    try {
      if (!emailBoxService.deleteDraft(draftLocalId, request.getRemoteUser())) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      return ResponseEntity.ok().build();
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  @PostMapping("/drafts/{draftLocalId}/attachments")
  @Secured("users")
  @Operation(summary = "Attaches an uploaded file to a draft", method = "POST",
             description = "Copies a commons upload into the platform's file store and records it on the draft, so the file survives the browser session, the tab and a server restart - which a temporary upload does not. Answers the draft as it now stands, attachments included, with its revision stepped: attaching is an edit, and a draft that did not notice one would accept a file and never send it. Answers 404 for an id the caller has no draft under, and 400 when the upload is gone or the draft would go over the size a message may carry. The draft is not pushed to the mail server by this call - the next save does that, carrying the file with it; a draft whose files cannot all be read is never pushed at all, since a copy up there without them would look complete and would not be.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "The upload is gone, or the draft would be too large to send"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
      @ApiResponse(responseCode = "404", description = "Not found"), })
  public Email addDraftAttachment(HttpServletRequest request,
                                  @Parameter(description = "The draft's local id", required = true)
                                  @PathVariable("draftLocalId")
                                  String draftLocalId,
                                  @RequestBody
                                  EmailOutgoingAttachment attachment) {
    try {
      Email draft = emailBoxService.addDraftAttachment(draftLocalId, request.getRemoteUser(), attachment);
      if (draft == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      return draft;
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  @PostMapping("/drafts/{draftLocalId}/attachments/forwarded")
  @Secured("users")
  @Operation(summary = "Carries a forwarded message's files onto the draft that forwards it", method = "POST",
             description = "Copies the files of the message being forwarded into the platform's file store and records them on the draft, so a forward arrives carrying what the original carried. The caller names the message, never its parts: which files are taken is read from the cached rows of a message that is the caller's own, in the folder they name. Answers the draft as it now stands - attachments included, revision stepped, since attaching is an edit - together with the names of the files that were NOT attached, because they would take the draft over the size a message may carry or because they could not be read. Nothing fails for one file: the rest are still attached, and the answer is what the forward will and will not carry. Answers 404 for an id the caller has no draft under, and for a message they have none of in that folder.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "No draft was named"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
      @ApiResponse(responseCode = "404", description = "Not found"), })
  public ForwardedAttachments addForwardedAttachments(HttpServletRequest request,
                                                      @Parameter(description = "The draft's local id", required = true)
                                                      @PathVariable("draftLocalId")
                                                      String draftLocalId,
                                                      @Parameter(description = "The IMAP UID of the message being forwarded",
                                                                 required = true)
                                                      @RequestParam("mailRemoteId")
                                                      long mailRemoteId,
                                                      @Parameter(description = "The folder that message is listed in; blank means INBOX")
                                                      @RequestParam(name = "folder", required = false)
                                                      String folder) {
    try {
      ForwardedAttachments forwarded = emailBoxService.addForwardedAttachments(draftLocalId,
                                                                              request.getRemoteUser(),
                                                                              mailRemoteId,
                                                                              folder);
      if (forwarded == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      return forwarded;
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  @DeleteMapping("/drafts/{draftLocalId}/attachments/{attachmentId}")
  @Secured("users")
  @Operation(summary = "Removes a file from a draft", method = "DELETE",
             description = "Removes the attachment row and records its stored file as unreferenced, for a later sweep to free. Answers the draft as it now stands, with its revision stepped for the same reason attaching steps it. Answers 404 both for a draft the caller does not have and for an attachment that is not on it, so neither id can be probed for existence.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
      @ApiResponse(responseCode = "404", description = "Not found"), })
  public Email removeDraftAttachment(HttpServletRequest request,
                                     @Parameter(description = "The draft's local id", required = true)
                                     @PathVariable("draftLocalId")
                                     String draftLocalId,
                                     @Parameter(description = "The attachment's own id", required = true)
                                     @PathVariable("attachmentId")
                                     long attachmentId) {
    try {
      Email draft = emailBoxService.removeDraftAttachment(draftLocalId, request.getRemoteUser(), attachmentId);
      if (draft == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      return draft;
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  @GetMapping("/drafts/{draftLocalId}/attachments/{attachmentId}")
  @Secured("users")
  @Operation(summary = "Downloads a file attached to a draft", method = "GET",
             description = "Reads the bytes back from the platform's file store. Deliberately a separate address from /attachments/{mailRemoteId}/{attachmentId}, which cannot reach a draft's file at all: that one addresses a message by its IMAP UID, and an unpushed draft has none - its MAIL_REMOTE_ID is null, which is the column that lookup joins on.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
      @ApiResponse(responseCode = "404", description = "Not found"), })
  public ResponseEntity<byte[]> getDraftAttachment(HttpServletRequest request,
                                                   @Parameter(description = "The draft's local id", required = true)
                                                   @PathVariable("draftLocalId")
                                                   String draftLocalId,
                                                   @Parameter(description = "The attachment's own id", required = true)
                                                   @PathVariable("attachmentId")
                                                   long attachmentId) {
    try {
      EmailAttachment attachment = emailBoxService.getDraftAttachment(draftLocalId, request.getRemoteUser(), attachmentId);
      if (attachment == null || attachment.getData() == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      String filename = StringUtils.defaultIfBlank(attachment.getName(), "attachment");
      String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
      // No ETag, unlike the received-attachment download. That one is safe to cache
      // because the bytes behind a (folder, UID, part path) never change; a draft's
      // attachment id is reused by nothing, but the draft around it is edited
      // constantly, and a cached answer is not worth the reasoning.
      return ResponseEntity.ok()
                           .contentType(MediaType.parseMediaType(StringUtils.defaultIfBlank(attachment.getMimeType(),
                                                                                            "application/octet-stream")))
                           .header(HttpHeaders.CONTENT_DISPOSITION,
                                   "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + encodedFilename)
                           .body(attachment.getData());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  @GetMapping("/attachments/{mailRemoteId}/{attachmentId}")
  @Secured("users")
  @Operation(summary = "Gets attachment by mail remote id and attachment id", method = "GET",
             description = "This will get attachment by mail remote id and attachment id. The folder is part of the address, not a filter: IMAP UIDs are numbered per folder, so the same id names a different message in INBOX and in SENT. It defaults to INBOX, which is what every caller written before the mailbox held other folders meant.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public ResponseEntity<byte[]> getAttachmentByMailRemoteIdAnId(HttpServletRequest request,
                                                                @Parameter(description = "Email id", required = true)
                                                                @PathVariable("mailRemoteId")
                                                                long mailRemoteId,
                                                                @Parameter(description = "Attachment id", required = true)
                                                                @PathVariable("attachmentId")
                                                                String attachmentId,
                                                                @Parameter(description = "The folder the message is listed in (INBOX, SENT, ARCHIVE, ALL_MAIL, DRAFTS); INBOX when omitted")
                                                                @RequestParam(value = "folder", required = false,
                                                                              defaultValue = MailFolder.INBOX)
                                                                String folder,
                                                                @RequestHeader(value = "If-None-Match", required = false)
                                                                String ifNoneMatch) {
    try {
      // The folder joins the ETag because it joins the identity: without it, the same
      // tag would be minted for two different files and a browser that had cached one
      // would answer the other from its own cache, never asking us.
      String eTag = "\"" + Objects.hash(mailRemoteId, attachmentId, folder, request.getRemoteUser()) + "\"";
      if (ifNoneMatch != null && ifNoneMatch.replace("W/", "").equals(eTag)) {
        return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(eTag).build();
      }
      EmailAttachment emailAttachment = emailBoxService.getAttachmentByMailRemoteIdAnIdAndUserId(mailRemoteId,
                                                                                                 attachmentId,
                                                                                                 request.getRemoteUser(),
                                                                                                 folder);
      if (emailAttachment == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      byte[] data = emailAttachment.getData();
      String filename = emailAttachment.getName();
      String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
      return ResponseEntity.ok()
                           .eTag(eTag)
                           .contentType(MediaType.parseMediaType(emailAttachment.getMimeType()))
                           .header(HttpHeaders.CONTENT_DISPOSITION,
                                   "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + encodedFilename)
                           .body(data);
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }
}
