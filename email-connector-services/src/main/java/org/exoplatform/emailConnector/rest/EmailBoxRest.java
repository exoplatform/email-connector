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
  @Operation(summary = "Gets user emails", method = "GET", description = "Gets the user's emails for a folder (INBOX by default, or SENT / ARCHIVE for the in-app folder switch)")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public EmailBox getEmailBox(HttpServletRequest request,
                              @Parameter(description = "Folder to list: INBOX, SENT or ARCHIVE")
                              @RequestParam(value = "folder", required = false, defaultValue = "INBOX")
                              String folder) {
    try {
      return emailBoxService.getEmailBox(request.getRemoteUser(), folder);
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
