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

import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailCategory;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailBox;
import org.exoplatform.emailConnector.model.EmailSearchResultPage;
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
  @Operation(summary = "Lists the assignable email categories", method = "GET", description = "Returns the add-on's own email categories a user can assign (Important / Invitation / Notification), whether or not already used")
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
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"), })
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
      return emailBoxService.saveDraft(draft, request.getRemoteUser(), push);
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @DeleteMapping("/drafts/{draftLocalId}")
  @Secured("users")
  @Operation(summary = "Discards a draft", method = "DELETE",
             description = "Removes the locally-stored draft. Answers 404 for an id the caller has no draft under, so a draft id never reveals whether it exists.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
      @ApiResponse(responseCode = "404", description = "Not found"), })
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
    }
  }

  @GetMapping("/attachments/{mailRemoteId}/{attachmentId}")
  @Secured("users")
  @Operation(summary = "Gets attachment by mail remote id and attachment id", method = "GET", description = "This will get attachment by mail remote id and attachment id")
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
                                                                @RequestHeader(value = "If-None-Match", required = false)
                                                                String ifNoneMatch) {
    try {
      String eTag = "\"" + Objects.hash(mailRemoteId, attachmentId, request.getRemoteUser()) + "\"";
      if (ifNoneMatch != null && ifNoneMatch.replace("W/", "").equals(eTag)) {
        return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(eTag).build();
      }
      EmailAttachment emailAttachment = emailBoxService.getAttachmentByMailRemoteIdAnIdAndUserId(mailRemoteId,
                                                                                                 attachmentId,
                                                                                                 request.getRemoteUser());
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
